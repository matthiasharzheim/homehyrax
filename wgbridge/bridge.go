// Package wgbridge is the networking core of HomeHyrax.
//
// WireGuard (wireguard-go + gVisor netstack) and Tailscale (tsnet) run
// entirely inside the app process, without Android's VpnService. The app
// therefore does not occupy the system VPN slot and works alongside any other
// VPN. The app's WebViews talk to a local HTTP proxy (127.0.0.1, random port,
// per-launch password) that decides per destination whether to go through a
// tunnel or directly.
//
// The API is gomobile-compatible on purpose (only string/int/bool/error).
// Tests share global state (tunnels, routes, proxy) and must not run in parallel.
package wgbridge

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type tunnel struct {
	id     string
	raw    string
	cfg    *wgConfig
	dev    *device.Device
	tnet   *netstack.Net
	errLog *errLogger
}

var (
	mu      sync.Mutex
	tunnels = map[string]*tunnel{}
	routes  = map[string]string{} // host (klein, ohne Port) -> Tunnel-ID

	// Stopp-Zaehler je ID (StopAll zaehlt fuer alle): ein Start, der waehrend
	// eines Stopps lief, darf seinen Tunnel danach nicht mehr eintragen.
	stopGen    = map[string]uint64{}
	stopAllGen uint64
)

// genLocked: aktueller Stopp-Stand der ID (mu muss gehalten sein).
func genLocked(id string) uint64 { return stopAllGen + stopGen[id] }

// errLogger merkt sich den letzten Fehler des Tunnels fuer die Anzeige.
type errLogger struct {
	mu   sync.Mutex
	last string
}

func (l *errLogger) errorf(format string, args ...any) {
	l.mu.Lock()
	l.last = strings.TrimSpace(strings.ReplaceAll(fmt.Sprintf(format, args...), "\n", " "))
	l.mu.Unlock()
}

// ValidateConfig prueft eine wg-quick-Konfiguration ohne etwas zu starten.
// Leerer String = gueltig, sonst die Fehlermeldung.
func ValidateConfig(config string) string {
	if _, err := parseConfig(config); err != nil {
		return err.Error()
	}
	return ""
}

// startMu: immer nur ein Tunnelstart zur Zeit. Die Arbeit (DNS bis 5 s,
// Geraet hochfahren) laeuft ohne mu, damit der Proxy weiter waehlen kann.
var startMu sync.Mutex

// freshSec: so lange gilt ein Handshake als aktuell (WireGuard verwirft
// Schluessel nach 180 s ohne neuen Handshake).
const freshSec = 180

// StartTunnel startet (oder behaelt) den Tunnel mit dieser ID. Laeuft er
// bereits mit identischer Konfiguration, bleibt er; ohne aktuellen Handshake
// wird nur der Endpoint neu aufgeloest (DynDNS-Adresse kann sich geaendert
// haben). Eine geaenderte Konfiguration ersetzt den alten Tunnel erst, wenn
// der neue steht - bei einem Fehler laeuft der alte weiter.
func StartTunnel(id, config string) error {
	startMu.Lock()
	defer startMu.Unlock()
	mu.Lock()
	old := tunnels[id]
	gen := genLocked(id)
	mu.Unlock()
	if old != nil && old.raw == config {
		if age := handshakeAge(id); age < 0 || age > freshSec {
			if eps, err := old.cfg.endpointsUAPI(); err == nil {
				_ = old.dev.IpcSet(eps)
			}
		}
		return nil
	}
	cfg, err := parseConfig(config)
	if err != nil {
		return err
	}
	uapi, err := cfg.uapi()
	if err != nil {
		return err
	}
	tunDev, tnet, err := netstack.CreateNetTUN(cfg.addresses, cfg.dns, cfg.mtu)
	if err != nil {
		return err
	}
	el := &errLogger{}
	logger := &device.Logger{Verbosef: device.DiscardLogf, Errorf: el.errorf}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), logger)
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		return err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return err
	}
	mu.Lock()
	if genLocked(id) != gen {
		// waehrend des Starts gestoppt: nicht wieder eintragen
		mu.Unlock()
		dev.Close()
		return errors.New("tunnel not active")
	}
	prev := tunnels[id]
	tunnels[id] = &tunnel{id: id, raw: config, cfg: cfg, dev: dev, tnet: tnet, errLog: el}
	mu.Unlock()
	if prev != nil {
		prev.dev.Close()
		transport.CloseIdleConnections()
	}
	return nil
}

// StopTunnel beendet einen Tunnel (WireGuard oder Tailscale; unbekannte ID =
// no-op). Kehrt erst zurueck, wenn der Tailscale-Server geschlossen ist. Zum
// Loeschen einer Tailscale-Verbindung samt Ordner RemoveTailscale verwenden.
func StopTunnel(id string) {
	// Nur austragen unter mu; das Schliessen dauert (Tailscale bis Sekunden)
	// und darf den Proxy nicht blockieren.
	mu.Lock()
	stopGen[id]++
	t, ok := tunnels[id]
	delete(tunnels, id)
	ts, isTS := tsTunnels[id]
	delete(tsTunnels, id)
	mu.Unlock()
	if ok {
		t.dev.Close()
	}
	if isTS {
		ts.srv.Close()
	}
	transport.CloseIdleConnections() // keine Keep-Alive-Verbindung in einen gestoppten Tunnel wiederverwenden
}

// StopAll beendet alle Tunnel.
func StopAll() {
	mu.Lock()
	stopAllGen++
	var wg []*tunnel
	for id, t := range tunnels {
		wg = append(wg, t)
		delete(tunnels, id)
	}
	var ts []*tsTunnel
	for id, t := range tsTunnels {
		ts = append(ts, t)
		delete(tsTunnels, id)
	}
	mu.Unlock()
	for _, t := range wg {
		t.dev.Close()
	}
	for _, t := range ts {
		t.srv.Close()
	}
	transport.CloseIdleConnections()
}

// IsRunning meldet, ob der Tunnel laeuft (Tailscale: gestartet, auch wenn noch nicht angemeldet).
func IsRunning(id string) bool {
	mu.Lock()
	defer mu.Unlock()
	_, ok := tunnels[id]
	_, ok2 := tsTunnels[id]
	return ok || ok2
}

// WaitHandshake wartet bis timeoutMs auf einen erfolgreichen Handshake mit
// dem Server. true = Tunnel steht. false = Server nicht erreichbar (falscher
// Schluessel, Port zu, DynDNS veraltet ...), Details via LastError.
// Bei Tailscale: warten, bis das Geraet angemeldet und verbunden ist
// (Anmeldung noetig -> sofort false).
func WaitHandshake(id string, timeoutMs int) bool {
	deadline := time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	mu.Lock()
	ts, isTS := tsTunnels[id]
	mu.Unlock()
	if isTS {
		for {
			st := ts.status()
			switch {
			case st.State == "Running":
				return true
			// Anmeldung im Browser noetig (URL liegt vor) bzw. Freigabe fehlt:
			// Warten bringt nichts. NeedsLogin ohne URL ist nur der kurze Startzustand.
			case (st.State == "NeedsLogin" && st.AuthURL != "") || st.State == "NeedsMachineAuth":
				return false
			case time.Now().After(deadline):
				return false
			}
			time.Sleep(200 * time.Millisecond)
		}
	}
	// Nur ein aktueller Handshake zaehlt: ein alter sagt nichts darueber, ob
	// der Server JETZT erreichbar ist (Netzwechsel, Router neu gestartet).
	nudged := false
	for {
		age := handshakeAge(id)
		if age >= 0 && age <= freshSec {
			return true
		}
		if !nudged {
			nudge(id) // ohne Verkehr startet WireGuard keinen Handshake
			nudged = true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// nudge schickt ein einzelnes UDP-Paket (Port 9, "discard") in den Tunnel,
// damit WireGuard einen neuen Handshake startet.
func nudge(id string) {
	mu.Lock()
	t, ok := tunnels[id]
	mu.Unlock()
	if !ok {
		return
	}
	target, ok := t.cfg.nudgeTarget()
	if !ok {
		return
	}
	c, err := t.tnet.DialUDPAddrPort(netip.AddrPort{}, netip.AddrPortFrom(target, 9))
	if err != nil {
		return
	}
	c.Write([]byte{0})
	c.Close()
}

// HandshakeAgeSec: Sekunden seit dem letzten Handshake, -1 = noch keiner.
func HandshakeAgeSec(id string) int {
	return handshakeAge(id)
}

func handshakeAge(id string) int {
	mu.Lock()
	t, ok := tunnels[id]
	mu.Unlock()
	if !ok {
		return -1
	}
	s, err := t.dev.IpcGet()
	if err != nil {
		return -1
	}
	newest := int64(0)
	for _, line := range strings.Split(s, "\n") {
		if v, ok := strings.CutPrefix(line, "last_handshake_time_sec="); ok {
			var n int64
			for _, c := range v {
				n = n*10 + int64(c-'0')
			}
			if n > newest {
				newest = n
			}
		}
	}
	if newest == 0 {
		return -1
	}
	return int(time.Now().Unix() - newest)
}

// LastError liefert die letzte Fehlermeldung des Tunnels (leer = keine).
func LastError(id string) string {
	mu.Lock()
	t, ok := tunnels[id]
	ts, isTS := tsTunnels[id]
	mu.Unlock()
	if isTS {
		st := ts.status()
		switch st.State {
		case "NeedsLogin":
			return "Tailscale: login required"
		case "NeedsMachineAuth":
			return "Tailscale: device needs approval in the admin console"
		}
		return st.Error
	}
	if !ok {
		return ""
	}
	t.errLog.mu.Lock()
	defer t.errLog.mu.Unlock()
	return t.errLog.last
}

// NetworkChanged nach WLAN/Mobilfunk-Wechsel aufrufen: bindet die
// UDP-Sockets neu, der Tunnel laeuft ohne Unterbrechung weiter.
func NetworkChanged() {
	mu.Lock()
	list := make([]*tunnel, 0, len(tunnels))
	for _, t := range tunnels {
		list = append(list, t)
	}
	mu.Unlock()
	// ausserhalb von mu: BindUpdate oeffnet Sockets neu und wartet auf die Empfaenger
	for _, t := range list {
		_ = t.dev.BindUpdate()
	}
	tsNetworkChanged()
}

// SetRoute: Anfragen an host (Name oder IP, ohne Port) laufen durch den
// Tunnel tunnelID. Leere tunnelID entfernt die Route (= direkt).
func SetRoute(host, tunnelID string) {
	mu.Lock()
	host = normHost(host)
	changed := routes[host] != tunnelID
	if tunnelID == "" {
		delete(routes, host)
	} else {
		routes[host] = tunnelID
	}
	mu.Unlock()
	if changed {
		// Keep-Alive-Verbindungen liefen sonst weiter ueber den alten Weg
		transport.CloseIdleConnections()
	}
}

// ClearRoutes entfernt alle Routen.
func ClearRoutes() {
	mu.Lock()
	routes = map[string]string{}
	mu.Unlock()
	transport.CloseIdleConnections()
}

func normHost(h string) string {
	return strings.ToLower(strings.Trim(strings.TrimSpace(h), "[]"))
}

// dialFunc waehlt durch einen Tunnel.
type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

// tunnelFor entscheidet: explizite Route fuer den Host, sonst eine IP, die in
// den (nicht-Default-)AllowedIPs eines laufenden WireGuard-Tunnels liegt
// (siehe coveringLocked). nil = direkt. Tailscale-Tunnel werden nur ueber
// explizite Routen genutzt. noDNS = WireGuard-Tunnel ohne DNS-Server:
// Namen muss der Aufrufer selbst aufloesen.
func tunnelFor(host string) (dial dialFunc, noDNS bool, err error) {
	mu.Lock()
	defer mu.Unlock()
	host = normHost(host)
	if id, ok := routes[host]; ok {
		if t, ok := tunnels[id]; ok {
			return t.tnet.DialContext, len(t.cfg.dns) == 0, nil
		}
		if ts, ok := tsTunnels[id]; ok {
			return ts.dial, false, nil
		}
		return nil, false, errors.New("tunnel not active")
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		if t := coveringLocked(ip.Unmap()); t != nil {
			return t.tnet.DialContext, false, nil
		}
	}
	return nil, false, nil
}

// OverlappingTunnels meldet laufende WireGuard-Tunnel, deren AllowedIPs sich
// ueberschneiden (ohne /0). Dann entscheidet fuer IP-Ziele ohne Route das
// laengste Praefix bzw. die kleinste ID, nicht die Kachel - die App sollte
// warnen. Rueckgabe: betroffene IDs sortiert, kommagetrennt; leer = keine.
func OverlappingTunnels() string {
	mu.Lock()
	defer mu.Unlock()
	ids := make([]string, 0, len(tunnels))
	for id := range tunnels {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	hit := map[string]bool{}
	for i, a := range ids {
		for _, b := range ids[i+1:] {
			if tunnels[a].cfg.overlaps(tunnels[b].cfg) {
				hit[a], hit[b] = true, true
			}
		}
	}
	var out []string
	for _, id := range ids {
		if hit[id] {
			out = append(out, id)
		}
	}
	return strings.Join(out, ",")
}

// coveringLocked: der Tunnel, dessen AllowedIPs ip am genauesten abdecken
// (laengstes Praefix). Gleichstand -> kleinste ID, damit die Wahl nicht von
// der Map-Reihenfolge abhaengt. mu muss gehalten sein.
func coveringLocked(ip netip.Addr) *tunnel {
	ids := make([]string, 0, len(tunnels))
	for id := range tunnels {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	var best *tunnel
	bestBits := -1
	for _, id := range ids {
		if b := tunnels[id].cfg.coverBits(ip); b > bestBits {
			best, bestBits = tunnels[id], b
		}
	}
	return best
}

var directDialer = &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}

// dialRouted ist der Wegweiser des Proxys.
func dialRouted(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	dial, noDNS, err := tunnelFor(host)
	if err != nil {
		return nil, err
	}
	if dial == nil {
		return directDialer.DialContext(ctx, "tcp", addr)
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	_, perr := netip.ParseAddr(host)
	isName := perr != nil
	if isName && noDNS {
		// Kein DNS im Tunnel: netstack koennte den Namen gar nicht fragen
		// ("cannot marshal DNS message"), also gleich ueber das normale Netz.
		ip, lerr := lookupSystem(ctx, host)
		if lerr != nil {
			return nil, lerr
		}
		return dial(ctx, "tcp", net.JoinHostPort(ip.String(), port))
	}
	c, err := dial(ctx, "tcp", addr)
	if err == nil {
		return c, nil
	}
	// Hostname, den das DNS im Tunnel nicht kennt: ueber das normale Netz
	// aufloesen und die IP durch den Tunnel waehlen. Nur dann - bei
	// Verbindungsfehlern ginge der Name sonst unnoetig an das DNS des
	// Mobilfunk-/WLAN-Netzes.
	if isName && nameNotFound(err) {
		if ip, lerr := lookupSystem(ctx, host); lerr == nil {
			return dial(ctx, "tcp", net.JoinHostPort(ip.String(), port))
		}
	}
	return nil, err
}

// systemLookup ist der Resolver des normalen Netzes (in Tests ersetzbar).
var systemLookup = net.DefaultResolver.LookupNetIP

// lookupSystem loest host ueber das normale Netz auf, IPv4 bevorzugt.
func lookupSystem(ctx context.Context, host string) (netip.Addr, error) {
	ips, err := systemLookup(ctx, "ip", host)
	if err != nil {
		return netip.Addr{}, err
	}
	if len(ips) == 0 {
		return netip.Addr{}, &net.DNSError{Err: "no such host", Name: host, IsNotFound: true}
	}
	for _, ip := range ips {
		if ip.Unmap().Is4() {
			return ip.Unmap(), nil
		}
	}
	return ips[0], nil
}

// nameNotFound: Waehlen scheiterte, weil das DNS im Tunnel den Namen nicht
// kennt (NXDOMAIN bzw. keine Adresse) - und nicht, weil das Ziel oder der
// DNS-Server nicht antwortet.
func nameNotFound(err error) bool {
	var de *net.DNSError
	if errors.As(err, &de) {
		return de.IsNotFound
	}
	// netstack: Antwort ohne passende Adresse
	return strings.Contains(err.Error(), "missing address") || strings.Contains(err.Error(), "no suitable address found")
}
