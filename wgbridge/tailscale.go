package wgbridge

// Tailscale als zweite Verbindungsart (tsnet, ebenfalls komplett im
// App-Prozess, ohne Android-VpnService). Gedacht fuer Anschluesse ohne
// eigene oeffentliche IPv4 (DS-Lite/CGNAT) oder Router ohne WireGuard-Server:
// im Heimnetz laeuft ein Tailscale-Geraet als Subnetz-Router, die App nimmt
// dessen Routen an (RouteAll) und erreicht so die 192.168.x-Adressen.
//
// Datenschutz: Log-Uploads an Tailscale sind abgeschaltet (logtail.Disable,
// NoLogsNoSupport). Der Koordinationsserver ist waehlbar (Headscale).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/logtail"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

func init() {
	logtail.Disable()
	envknob.SetNoLogsNoSupport()
	netmon.RegisterInterfaceGetter(interfaces)
}

type tsTunnel struct {
	id  string
	raw string // Konfiguration (JSON), fuer "unveraendert?"-Vergleich
	srv *tsnet.Server

	mu       sync.Mutex
	lastErr  string
	log      []string                  // letzte Zeilen des Tailscale-Protokolls (nur im RAM, fuer "Details")
	lanMap   map[netip.Addr]netip.Addr // LAN-IP eines Geraets -> seine Tailscale-IP (siehe dial)
	lanMapAt time.Time
}

const tsLogLines = 80

// logf sammelt das Tailscale-Protokoll im Speicher (kein Upload), damit die
// App bei Problemen zeigen kann, woran es haengt.
func (t *tsTunnel) logf(format string, args ...any) {
	line := time.Now().Format("15:04:05 ") + strings.TrimSpace(fmt.Sprintf(format, args...))
	t.mu.Lock()
	t.log = append(t.log, line)
	if len(t.log) > tsLogLines {
		t.log = t.log[len(t.log)-tsLogLines:]
	}
	t.mu.Unlock()
}

// TailscaleLog liefert die letzten Protokollzeilen (neueste unten).
func TailscaleLog(id string) string {
	mu.Lock()
	t, ok := tsTunnels[id]
	mu.Unlock()
	if !ok {
		return ""
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	return strings.Join(t.log, "\n")
}

var tsTunnels = map[string]*tsTunnel{} // geschuetzt durch mu (bridge.go)

// tsConfig ist die in der App gespeicherte Tailscale-Konfiguration.
type tsConfig struct {
	ControlURL string `json:"control,omitempty"` // leer = Tailscale, sonst z. B. Headscale
	AuthKey    string `json:"authkey,omitempty"` // optional, sonst Anmeldung im Browser
}

// tsStartMu: immer nur ein Tailscale-Start (bzw. RemoveTailscale) zur Zeit.
// Sonst liefen bei gleichzeitigen Starts derselben ID zwei Server auf
// demselben Zustandsordner, und einer wuerde nie geschlossen. StopTunnel
// nimmt die Sperre bewusst nicht (soll nie warten); dafuer sorgt der
// Stopp-Zaehler (genLocked), dass ein gestoppter Start nicht zurueckkommt.
var tsStartMu sync.Mutex

// testHookTSStarted wird (nur in Tests) nach srv.Start aufgerufen, bevor der
// Tunnel eingetragen wird.
var testHookTSStarted func(id string)

// StartTailscale startet (oder behaelt) die Tailscale-Verbindung mit dieser
// ID. dir = eigener Zustandsordner (Schluessel, Anmeldung), config = JSON
// {"control":"...","authkey":"..."}. Kehrt sofort zurueck; ob eine Anmeldung
// noetig ist, liefert TailscaleStatus.
func StartTailscale(id, dir, config, hostname string) (err error) {
	// Panik beim Start als Fehler melden statt die App zu beenden
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("Tailscale start error: %v", r)
		}
	}()
	var cfg tsConfig
	if strings.TrimSpace(config) != "" {
		if err := json.Unmarshal([]byte(config), &cfg); err != nil {
			return errors.New("Tailscale settings invalid")
		}
	}
	tsStartMu.Lock()
	defer tsStartMu.Unlock()
	mu.Lock()
	old, ok := tsTunnels[id]
	if ok && old.raw == config {
		mu.Unlock()
		return nil
	}
	if ok {
		// neue Konfiguration: der alte Server muss vorher zu (gleicher Ordner)
		delete(tsTunnels, id)
	}
	gen := genLocked(id)
	mu.Unlock()
	if ok {
		old.srv.Close()
	}

	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	androidDirs(dir)
	t := &tsTunnel{id: id, raw: config}
	t.srv = &tsnet.Server{
		Dir:        dir,
		Hostname:   hostname,
		ControlURL: strings.TrimSpace(cfg.ControlURL),
		AuthKey:    strings.TrimSpace(cfg.AuthKey),
		UserLogf:   t.logf,
		Logf:       t.logf,
	}
	if err := t.srv.Start(); err != nil {
		t.srv.Close()
		return errors.New("Tailscale does not start: " + err.Error())
	}
	if testHookTSStarted != nil {
		testHookTSStarted(id)
	}
	mu.Lock()
	if genLocked(id) != gen {
		// waehrend des Starts gestoppt: nicht wieder eintragen
		mu.Unlock()
		t.srv.Close()
		return errors.New("tunnel not active")
	}
	tsTunnels[id] = t
	mu.Unlock()

	// Subnetz-Routen der anderen Geraete annehmen (sonst nur 100.x erreichbar)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		lc, err := t.srv.LocalClient()
		if err != nil {
			t.setErr(err.Error())
			return
		}
		if _, err := lc.EditPrefs(ctx, &ipn.MaskedPrefs{
			Prefs:       ipn.Prefs{RouteAll: true},
			RouteAllSet: true,
		}); err != nil {
			t.setErr(err.Error())
		}
	}()
	return nil
}

// RemoveTailscale beendet die Tailscale-Verbindung (wartet auch einen gerade
// laufenden Start ab) und loescht danach ihren Zustandsordner dir. Die App ruft
// das beim Loeschen einer Tailscale-Verbindung auf, statt den Ordner selbst zu
// loeschen: ein noch offener Server schriebe sonst Zustand/Logs neu hinein.
func RemoveTailscale(id, dir string) error {
	tsStartMu.Lock()
	defer tsStartMu.Unlock()
	StopTunnel(id)
	if dir == "" {
		return nil
	}
	return os.RemoveAll(dir)
}

func (t *tsTunnel) setErr(s string) {
	t.mu.Lock()
	t.lastErr = s
	t.mu.Unlock()
}

func (t *tsTunnel) err() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.lastErr
}

// tsStatus ist die Antwort von TailscaleStatus (als JSON an die App).
type tsStatus struct {
	State   string `json:"state"`             // NoState, NeedsLogin, NeedsMachineAuth, Starting, Running, Stopped
	AuthURL string `json:"authURL,omitempty"` // im Browser oeffnen, wenn NeedsLogin
	IP      string `json:"ip,omitempty"`      // eigene 100.x-Adresse
	User    string `json:"user,omitempty"`    // angemeldetes Konto
	Routes  string `json:"routes,omitempty"`  // erreichbare Heimnetze (Subnetz-Routen), kommagetrennt
	Error   string `json:"error,omitempty"`
}

// TailscaleStatus liefert den Zustand als JSON (siehe tsStatus). Unbekannte
// ID -> {"state":"Stopped"}.
func TailscaleStatus(id string) string {
	st := tsStatus{State: "Stopped"}
	mu.Lock()
	t, ok := tsTunnels[id]
	mu.Unlock()
	if ok {
		st = t.status()
	}
	b, _ := json.Marshal(st)
	return string(b)
}

func (t *tsTunnel) status() tsStatus {
	st := tsStatus{State: "NoState", Error: t.err()}
	lc, err := t.srv.LocalClient()
	if err != nil {
		st.Error = err.Error()
		return st
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	s, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		st.Error = err.Error()
		return st
	}
	st.State = s.BackendState
	st.AuthURL = s.AuthURL
	if len(s.TailscaleIPs) > 0 {
		st.IP = s.TailscaleIPs[0].String()
	}
	if s.Self != nil {
		if u, ok := s.User[s.Self.UserID]; ok {
			st.User = u.LoginName
		}
	}
	if st.State == "Running" {
		if full, err := lc.Status(ctx); err == nil {
			var r []string
			seen := map[string]bool{}
			for _, p := range full.Peer {
				if p.PrimaryRoutes == nil {
					continue
				}
				for _, pfx := range p.PrimaryRoutes.All() {
					if pfx.Bits() > 0 && !seen[pfx.String()] {
						seen[pfx.String()] = true
						r = append(r, pfx.String())
					}
				}
			}
			st.Routes = strings.Join(r, ", ")
		}
	}
	return st
}

// TailscaleLogin fordert eine (neue) Anmelde-Adresse an; sie erscheint kurz
// danach in TailscaleStatus.authURL.
func TailscaleLogin(id string) error {
	mu.Lock()
	t, ok := tsTunnels[id]
	mu.Unlock()
	if !ok {
		return errors.New("Tailscale not started")
	}
	lc, err := t.srv.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return lc.StartLoginInteractive(ctx)
}

// TailscaleLogout meldet das Geraet ab (Schluessel wird ungueltig).
func TailscaleLogout(id string) {
	mu.Lock()
	t, ok := tsTunnels[id]
	mu.Unlock()
	if !ok {
		return
	}
	if lc, err := t.srv.LocalClient(); err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = lc.Logout(ctx)
	}
}

// ---- Netzwerk-Schnittstellen (Android erlaubt Go kein net.Interfaces) ----

var (
	ifMu   sync.Mutex
	ifList []netmon.Interface
)

// SetInterfaces meldet die Netzwerk-Schnittstellen des Handys (aus
// java.net.NetworkInterface). Eine Zeile pro Schnittstelle:
// name TAB index TAB mtu TAB flags(u=up,l=loopback,m=multicast) TAB adressen (a/prefix, kommagetrennt)
func SetInterfaces(spec string) {
	var list []netmon.Interface
	for _, line := range strings.Split(spec, "\n") {
		f := strings.Split(strings.TrimSpace(line), "\t")
		if len(f) < 4 || f[0] == "" {
			continue
		}
		idx, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		var flags net.Flags
		if strings.Contains(f[3], "u") {
			flags |= net.FlagUp | net.FlagRunning
		}
		if strings.Contains(f[3], "l") {
			flags |= net.FlagLoopback
		}
		if strings.Contains(f[3], "m") {
			flags |= net.FlagMulticast
		}
		var addrs []net.Addr
		if len(f) >= 5 {
			for _, a := range strings.Split(f[4], ",") {
				if p, err := netip.ParsePrefix(strings.TrimSpace(a)); err == nil {
					addrs = append(addrs, &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())})
				}
			}
		}
		list = append(list, netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: f[0], Flags: flags},
			AltAddrs:  addrs,
		})
	}
	ifMu.Lock()
	ifList = list
	ifMu.Unlock()
}

// SetDefaultInterface meldet die Schnittstelle der Standardroute (wlan0,
// rmnet_data0 ...); leer = kein Netz.
func SetDefaultInterface(name string) {
	setDefaultInterface(name) // nur auf Android wirksam (tailscale_android.go)
}

// interfaces liefert Tailscale die per SetInterfaces gemeldeten Schnittstellen.
func interfaces() ([]netmon.Interface, error) {
	ifMu.Lock()
	defer ifMu.Unlock()
	if ifList == nil {
		// noch nichts gemeldet (Tests, Desktop): Standardweg
		ifs, err := net.Interfaces()
		if err != nil {
			return nil, err
		}
		out := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			out[i] = netmon.Interface{Interface: &ifs[i]}
		}
		return out, nil
	}
	return append([]netmon.Interface(nil), ifList...), nil
}

// tsNetworkChanged: Netzwechsel an Tailscale melden (neu verbinden).
func tsNetworkChanged() {
	mu.Lock()
	var list []*tsTunnel
	for _, t := range tsTunnels {
		list = append(list, t)
	}
	mu.Unlock()
	for _, t := range list {
		if nm := t.srv.Sys().NetMon.Get(); nm != nil {
			nm.InjectEvent()
		}
	}
}

// androidDirs: Tailscale sucht Log-/Temp-Ordner in den ueblichen Linux-Orten
// (/var/lib/tailscale, $HOME/.cache, $TMPDIR). Unter Android gibt es fuer Apps
// keinen davon -> panic "no safe place found to store log state" beim Start.
// Deshalb alles in den App-Ordner lenken (nur wenn nicht gesetzt).
func androidDirs(dir string) {
	logs := filepath.Join(dir, "logs")
	tmp := filepath.Join(dir, "tmp")
	_ = os.MkdirAll(logs, 0o700)
	_ = os.MkdirAll(tmp, 0o700)
	_ = os.Setenv("TS_LOGS_DIR", logs)
	if fi, err := os.Stat(os.TempDir()); err != nil || !fi.IsDir() || !writable(os.TempDir()) {
		_ = os.Setenv("TMPDIR", tmp)
	}
	if os.Getenv("HOME") == "" {
		_ = os.Setenv("HOME", dir)
	}
	if os.Getenv("XDG_CACHE_HOME") == "" {
		_ = os.Setenv("XDG_CACHE_HOME", filepath.Join(dir, "cache"))
	}
}

func writable(d string) bool {
	f, err := os.CreateTemp(d, ".w")
	if err != nil {
		return false
	}
	f.Close()
	os.Remove(f.Name())
	return true
}

// TailscaleProbe prueft Schritt fuer Schritt, warum ein Ziel (host:port) ueber
// Tailscale (nicht) erreichbar ist - fuer "Verbindung testen" in der App.
func TailscaleProbe(id, hostport string, timeoutMs int) string {
	mu.Lock()
	t, ok := tsTunnels[id]
	mu.Unlock()
	if !ok {
		return "Tailscale not started"
	}
	st := t.status()
	if st.State != "Running" {
		return "Tailscale not connected (" + st.State + ")"
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	ipp, via, err := t.srv.Sys().Dialer.Get().UserDialPlan(ctx, "tcp", hostport)
	if err != nil {
		return "address cannot be resolved: " + err.Error()
	}
	var b strings.Builder
	if !via {
		fmt.Fprintf(&b, "%s is not in any shared home network (routes: %s) – NOT via Tailscale", ipp.Addr(), st.Routes)
		return b.String()
	}
	fmt.Fprintf(&b, "%s: route via Tailscale", ipp)
	if lc, err := t.srv.LocalClient(); err == nil {
		if full, err := lc.Status(ctx); err == nil {
			for _, p := range full.Peer {
				if p.PrimaryRoutes == nil {
					continue
				}
				for _, pfx := range p.PrimaryRoutes.All() {
					if pfx.Contains(ipp.Addr()) {
						con := "Relay (DERP " + p.Relay + ")"
						if p.CurAddr != "" {
							con = "direct " + p.CurAddr
						}
						fmt.Fprintf(&b, " via %s (%s, %s)", p.HostName, map[bool]string{true: "online", false: "OFFLINE"}[p.Online], con)
						// Tunnel bis zum Router selbst pruefen (TSMP-Ping geht durch WireGuard bis in dessen Tailscale):
						// klappt der, der LAN-Zugriff aber nicht, leitet der Router nicht ins Heimnetz weiter.
						if len(p.TailscaleIPs) > 0 {
							// eigenes Zeitbudget: der erste Ping nach dem Start geht oft erst ueber ein Relay
							pctx, pcancel := context.WithTimeout(context.Background(), 8*time.Second)
							pr, perr := lc.Ping(pctx, p.TailscaleIPs[0], tailcfg.PingTSMP)
							pcancel()
							switch {
							case perr != nil:
								fmt.Fprintf(&b, " · tunnel to %s: NO reply (%v)", p.HostName, perr)
							case pr.Err != "":
								fmt.Fprintf(&b, " · tunnel to %s: NO reply (%s)", p.HostName, pr.Err)
							default:
								fmt.Fprintf(&b, " · tunnel to %s: OK %.0f ms", p.HostName, pr.LatencySeconds*1000)
							}
							// welche Adressen meldet das Geraet? (fuer "ist das Geraet selbst")
							var own []string
							for lan, tsip := range t.lanToTailscale(context.Background()) {
								if tsip == p.TailscaleIPs[0] {
									own = append(own, lan.String())
								}
							}
							fmt.Fprintf(&b, " · %s reports addresses: %s", p.HostName, strings.Join(own, ", "))
						}
					}
				}
			}
		}
	}
	if r := t.rewrite(context.Background(), hostport); r != hostport {
		fmt.Fprintf(&b, " · is the device itself -> %s", r)
	}
	start := time.Now()
	dctx, dcancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer dcancel()
	c, err := t.dial(dctx, "tcp", hostport)
	if err != nil {
		fmt.Fprintf(&b, " · connection FAILED after %d ms: %v", time.Since(start).Milliseconds(), err)
		return b.String()
	}
	c.Close()
	fmt.Fprintf(&b, " · connection OK in %d ms", time.Since(start).Milliseconds())
	return b.String()
}

// dial waehlt durch Tailscale. Sonderfall Subnetz-Router selbst (ein Dienst
// laeuft AUF dem Geraet, das zugleich Subnetz-Router ist, z. B. ein NAS):
// Anfragen an die eigene LAN-Adresse des Routers leitet Tailscale im
// Userspace-Modus nicht an sich selbst weiter -> Timeout. Die LAN-Adressen
// eines Geraets stehen in seinen Endpunkten; dann direkt seine Tailscale-IP
// waehlen (siehe lanMapFrom).
func (t *tsTunnel) dial(ctx context.Context, network, addr string) (net.Conn, error) {
	return t.srv.Dial(ctx, network, t.rewrite(ctx, addr))
}

// rewrite: LAN-Adresse eines Tailscale-Geraets -> dessen Tailscale-IP (gleicher Port).
func (t *tsTunnel) rewrite(ctx context.Context, addr string) string {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	ip, err := netip.ParseAddr(host)
	if err != nil {
		return addr
	}
	if ts, ok := t.lanToTailscale(ctx)[ip.Unmap()]; ok {
		return net.JoinHostPort(ts.String(), port)
	}
	return addr
}

// lanToTailscale: LAN-Adresse eines Subnetz-Routers -> seine Tailscale-IP
// (siehe lanMapFrom), 30 s gecacht.
func (t *tsTunnel) lanToTailscale(ctx context.Context) map[netip.Addr]netip.Addr {
	t.mu.Lock()
	if t.lanMap != nil && time.Since(t.lanMapAt) < 30*time.Second {
		m := t.lanMap
		t.mu.Unlock()
		return m
	}
	t.mu.Unlock()
	peers, ok := t.netmapPeers(ctx)
	m := lanMapFrom(peers)
	if !ok {
		return m // keine Netzwerkkarte bekommen: nicht cachen, naechstes Mal erneut
	}
	t.mu.Lock()
	t.lanMap, t.lanMapAt = m, time.Now()
	t.mu.Unlock()
	return m
}

// tsPeer: die fuer die Umleitung noetigen Angaben eines Tailscale-Geraets.
type tsPeer struct {
	tsIP      netip.Addr
	routes    []netip.Prefix // vom Geraet bereitgestellte Subnetz-Routen (PrimaryRoutes)
	endpoints []netip.Addr   // gemeldete Adressen (u. a. seine LAN-Adressen)
}

// netmapPeers liest die Geraete aus der Netzwerkkarte (NetMap). Die Endpunkte
// stehen nur dort, nicht im Status. ok = Karte erhalten.
func (t *tsTunnel) netmapPeers(ctx context.Context) (peers []tsPeer, ok bool) {
	lc, err := t.srv.LocalClient()
	if err != nil {
		return nil, false
	}
	wctx, cancel := context.WithTimeout(ctx, 4*time.Second)
	defer cancel()
	w, err := lc.WatchIPNBus(wctx, ipn.NotifyInitialNetMap)
	if err != nil {
		return nil, false
	}
	defer w.Close()
	for {
		n, err := w.Next()
		if err != nil {
			return nil, false
		}
		if n.NetMap == nil {
			continue
		}
		for _, p := range n.NetMap.Peers {
			addrs := p.Addresses()
			if addrs.Len() == 0 {
				continue
			}
			tp := tsPeer{tsIP: addrs.At(0).Addr(), routes: p.PrimaryRoutes().AsSlice()}
			for _, ep := range p.Endpoints().All() {
				tp.endpoints = append(tp.endpoints, ep.Addr())
			}
			peers = append(peers, tp)
		}
		return peers, true
	}
}

// lanMapFrom: LAN-Adresse -> Tailscale-IP des Geraets, das diese Adresse als
// Endpunkt meldet UND ihr Netz als Subnetz-Router bereitstellt. Nur dann ist
// es das Geraet im Heimnetz: ein Laptop in einem fremden Netz mit gleichem
// Adressbereich (z. B. 192.168.178.x) meldet dieselbe Adresse, stellt das Netz
// aber nicht bereit. Passen mehrere Geraete, ist das Ziel mehrdeutig -> keine
// Umleitung.
func lanMapFrom(peers []tsPeer) map[netip.Addr]netip.Addr {
	m := map[netip.Addr]netip.Addr{}
	ambiguous := map[netip.Addr]bool{}
	for _, p := range peers {
		for _, ep := range p.endpoints {
			a := ep.Unmap()
			if a.IsLoopback() || a.IsUnspecified() || !inRoutes(p.routes, a) {
				continue
			}
			if old, ok := m[a]; ok && old != p.tsIP {
				ambiguous[a] = true
			}
			m[a] = p.tsIP
		}
	}
	for a := range ambiguous {
		delete(m, a)
	}
	return m
}

// inRoutes: liegt a in einer der Routen (ohne Default-Route /0)?
func inRoutes(routes []netip.Prefix, a netip.Addr) bool {
	for _, r := range routes {
		if r.Bits() > 0 && r.Contains(a) {
			return true
		}
	}
	return false
}
