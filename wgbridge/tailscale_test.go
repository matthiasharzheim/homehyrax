package wgbridge

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/logpolicy"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

// TestTailscaleThroughProxy: echter Koordinationsserver (testcontrol) + DERP
// im selben Prozess, ein "Heimgeraet" mit HTTP-Server auf seiner 100.x-IP.
// Die App-Seite (StartTailscale) meldet sich an, der Proxy leitet per Route
// durch Tailscale.
func TestTailscaleThroughProxy(t *testing.T) {
	if testing.Short() {
		t.Skip("langsam")
	}
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })

	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	control := &testcontrol.Server{DERPMap: derpMap, DNSConfig: &tailcfg.DNSConfig{Proxied: true},
		MagicDNSDomain: "tail-scale.ts.net", Logf: logger.Discard}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)
	controlURL := control.HTTPTestServer.URL

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	// Heimgeraet
	home := &tsnet.Server{Dir: filepath.Join(t.TempDir(), "home"), ControlURL: controlURL, Hostname: "home",
		Store: new(mem.Store), Ephemeral: true, Logf: logger.Discard}
	t.Cleanup(func() { home.Close() })
	st, err := home.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	homeIP := st.TailscaleIPs[0].String()
	ln, err := home.Listen("tcp", ":80")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "hallo per tailscale %s", r.URL.Path)
	}))

	// App-Seite
	cfg := fmt.Sprintf(`{"control":%q}`, controlURL)
	if err := StartTailscale("ts1", filepath.Join(t.TempDir(), "app"), cfg, "phone"); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(StopAll)
	if !WaitHandshake("ts1", 30000) {
		t.Fatalf("nicht verbunden: %s / %s", TailscaleStatus("ts1"), LastError("ts1"))
	}
	var s tsStatus
	if err := json.Unmarshal([]byte(TailscaleStatus("ts1")), &s); err != nil || s.State != "Running" || s.IP == "" {
		t.Fatalf("status %+v %v", s, err)
	}
	// Subnetz-Routen annehmen ist gesetzt
	lc, _ := tsTunnels["ts1"].srv.LocalClient()
	deadline := time.Now().Add(10 * time.Second)
	for {
		p, err := lc.GetPrefs(ctx)
		if err == nil && p.RouteAll {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("RouteAll nicht gesetzt")
		}
		time.Sleep(100 * time.Millisecond)
	}

	pp, err := StartProxy("geheim")
	if err != nil {
		t.Fatal(err)
	}
	pu := &url.URL{Scheme: "http", User: url.UserPassword(ProxyUser, "geheim"), Host: fmt.Sprintf("127.0.0.1:%d", pp)}
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu)}, Timeout: 20 * time.Second}

	SetRoute(homeIP, "ts1")
	t.Cleanup(ClearRoutes)
	var body string
	for i := 0; i < 20; i++ { // erster Pfad ueber DERP braucht u. U. einen Moment
		resp, err := client.Get("http://" + homeIP + "/index.html")
		if err == nil {
			b, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			body = string(b)
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if body != "hallo per tailscale /index.html" {
		t.Fatalf("got %q", body)
	}

	// Stoppen: Route zeigt ins Leere -> Fehler vom Proxy statt still direkt
	StopTunnel("ts1")
	if IsRunning("ts1") {
		t.Fatal("laeuft noch")
	}
	resp, err := client.Get("http://" + homeIP + "/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadGateway || resp.Header.Get(ErrorHeader) == "" {
		t.Fatalf("erwartet 502 vom Proxy bei gestopptem Tunnel, got %d", resp.StatusCode)
	}
}

// newTestControl startet Koordinationsserver + DERP im Prozess und liefert die Adresse.
func newTestControl(t *testing.T) (*testcontrol.Server, string) {
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	control := &testcontrol.Server{DERPMap: derpMap, Logf: logger.Discard}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)
	return control, control.HTTPTestServer.URL
}

// TestTailscaleConcurrentStart: zwei gleichzeitige Starts derselben ID ergeben genau
// einen Server; ein Stopp waehrend des Starts bringt den Tunnel nicht zurueck.
func TestTailscaleConcurrentStart(t *testing.T) {
	if testing.Short() {
		t.Skip("langsam")
	}
	_, controlURL := newTestControl(t)
	cfg := fmt.Sprintf(`{"control":%q}`, controlURL)
	t.Cleanup(StopAll)
	t.Cleanup(func() { testHookTSStarted = nil })

	var started atomic.Int32
	testHookTSStarted = func(string) {
		started.Add(1)
		time.Sleep(200 * time.Millisecond) // Fenster fuer den zweiten Start
	}
	dir := filepath.Join(t.TempDir(), "app")
	var wg sync.WaitGroup
	errs := make([]error, 2)
	for i := range errs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs[i] = StartTailscale("ts5", dir, cfg, "phone")
		}()
	}
	wg.Wait()
	if errs[0] != nil || errs[1] != nil {
		t.Fatalf("Start: %v / %v", errs[0], errs[1])
	}
	if n := started.Load(); n != 1 {
		t.Fatalf("%d Server gestartet, erwartet 1", n)
	}
	if !IsRunning("ts5") {
		t.Fatal("laeuft nicht")
	}

	// Stopp waehrend des Starts
	testHookTSStarted = func(id string) { StopTunnel(id) }
	if err := StartTailscale("ts6", filepath.Join(t.TempDir(), "app6"), cfg, "phone"); err == nil {
		t.Fatal("Start nach Stopp meldet Erfolg")
	}
	if IsRunning("ts6") {
		t.Fatal("gestoppter Tunnel ist wieder da")
	}

	// Entfernen: Server zu, Ordner weg
	testHookTSStarted = nil
	if err := RemoveTailscale("ts5", dir); err != nil {
		t.Fatal(err)
	}
	if IsRunning("ts5") {
		t.Fatal("laeuft nach RemoveTailscale noch")
	}
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("Ordner noch da: %v", err)
	}
}

// Umleitung LAN-Adresse -> Tailscale-IP nur fuer Subnetz-Router des Ziels.
func TestLanMapFrom(t *testing.T) {
	a := netip.MustParseAddr
	lan := []netip.Prefix{netip.MustParsePrefix("192.168.178.0/24")}
	router := tsPeer{tsIP: a("100.64.0.1"), routes: lan,
		endpoints: []netip.Addr{a("192.168.178.2"), a("203.0.113.7"), a("127.0.0.1")}}
	// Laptop in einem fremden Netz mit gleichem Adressbereich, ohne Routen
	laptop := tsPeer{tsIP: a("100.64.0.2"), endpoints: []netip.Addr{a("192.168.178.20"), a("192.168.178.2")}}

	m := lanMapFrom([]tsPeer{router, laptop})
	if got := m[a("192.168.178.2")]; got != router.tsIP {
		t.Fatalf("Router: got %v", got)
	}
	if got, ok := m[a("192.168.178.20")]; ok {
		t.Fatalf("Laptop ohne Route darf nicht umgeleitet werden -> %v", got)
	}
	if _, ok := m[a("203.0.113.7")]; ok {
		t.Fatal("oeffentliche Adresse ausserhalb der Route umgeleitet")
	}
	if len(m) != 1 {
		t.Fatalf("map %v", m)
	}

	// zwei Router fuer dasselbe Netz melden dieselbe Adresse -> mehrdeutig, keine Umleitung
	other := tsPeer{tsIP: a("100.64.0.3"), routes: lan, endpoints: []netip.Addr{a("192.168.178.2")}}
	if m := lanMapFrom([]tsPeer{router, other}); len(m) != 0 {
		t.Fatalf("mehrdeutig, erwartet leer: %v", m)
	}
	// Default-Route (Exit-Node) zaehlt nicht als Subnetz-Route
	exit := tsPeer{tsIP: a("100.64.0.4"), routes: []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")},
		endpoints: []netip.Addr{a("10.0.0.5")}}
	if m := lanMapFrom([]tsPeer{exit}); len(m) != 0 {
		t.Fatalf("Exit-Node umgeleitet: %v", m)
	}
}

// TestTailscaleInteractiveLogin: Koordinationsserver verlangt Anmeldung ->
// TailscaleStatus liefert NeedsLogin + AuthURL, WaitHandshake bricht sofort ab;
// nach der Anmeldung (CompleteAuth) wird der Zustand Running.
func TestTailscaleInteractiveLogin(t *testing.T) {
	if testing.Short() {
		t.Skip("langsam")
	}
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	control := &testcontrol.Server{DERPMap: derpMap, RequireAuth: true, Logf: logger.Discard}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)

	cfg := fmt.Sprintf(`{"control":%q}`, control.HTTPTestServer.URL)
	if err := StartTailscale("ts2", filepath.Join(t.TempDir(), "app"), cfg, "phone"); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(StopAll)

	var s tsStatus
	deadline := time.Now().Add(15 * time.Second)
	for {
		json.Unmarshal([]byte(TailscaleStatus("ts2")), &s)
		if s.AuthURL != "" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("keine Anmelde-Adresse: %+v\n%s", s, TailscaleLog("ts2"))
		}
		time.Sleep(100 * time.Millisecond)
	}
	if s.State != "NeedsLogin" {
		t.Fatalf("state %q", s.State)
	}
	start := time.Now()
	if WaitHandshake("ts2", 10000) {
		t.Fatal("ohne Anmeldung verbunden?")
	}
	if time.Since(start) > 2*time.Second {
		t.Fatal("WaitHandshake haette bei NeedsLogin+URL sofort abbrechen muessen")
	}
	if TailscaleLog("ts2") == "" {
		t.Fatal("kein Protokoll")
	}
	if !control.CompleteAuth(s.AuthURL) {
		t.Fatal("CompleteAuth")
	}
	// so pollt auch die App nach der Anmeldung (NeedsLogin haengt kurz nach)
	deadline = time.Now().Add(20 * time.Second)
	for {
		json.Unmarshal([]byte(TailscaleStatus("ts2")), &s)
		if s.State == "Running" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("nach Anmeldung nicht verbunden: %+v", s)
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// TestTailscaleAndroidEnv: wie auf Android - kein HOME, kein Cache-Ordner, TMPDIR nicht
// beschreibbar, Arbeitsverzeichnis "/". Ohne androidDirs: panic "no safe place found to
// store log state".
func TestTailscaleAndroidEnv(t *testing.T) {
	if testing.Short() {
		t.Skip("langsam")
	}
	dir := filepath.Join(t.TempDir(), "app") // vor TMPDIR-Aenderung
	for _, k := range []string{"HOME", "XDG_CACHE_HOME", "TS_LOGS_DIR", "STATE_DIRECTORY"} {
		t.Setenv(k, "")
		os.Unsetenv(k)
	}
	t.Setenv("TMPDIR", "/nicht/da")
	wd, _ := os.Getwd()
	os.Chdir("/")
	t.Cleanup(func() { os.Chdir(wd) })

	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	control := &testcontrol.Server{DERPMap: derpMap, Logf: logger.Discard}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)

	cfg := fmt.Sprintf(`{"control":%q}`, control.HTTPTestServer.URL)
	if err := StartTailscale("ts3", dir, cfg, "phone"); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(StopAll)
	if !WaitHandshake("ts3", 20000) {
		t.Fatalf("nicht verbunden: %s", TailscaleStatus("ts3"))
	}
	// Tailscale muss seine Log-/Temp-Ordner im App-Ordner finden (Android hat sonst keinen)
	if got := logpolicy.LogsDir(logger.Discard); !strings.HasPrefix(got, dir) {
		t.Fatalf("LogsDir = %q, erwartet im App-Ordner %q", got, dir)
	}
	// TMPDIR gilt nur auf Unix/Android (Windows nimmt TMP/TEMP)
	if runtime.GOOS != "windows" && !strings.HasPrefix(os.TempDir(), dir) {
		t.Fatalf("TempDir = %q", os.TempDir())
	}
}

// hostIPv4 liefert eine nutzbare private IPv4 dieses Rechners (als "Heimnetz" im Test).
// Nicht Link-lokal (169.254.x, getrennte Adapter) und nicht 100.x (laufendes Tailscale).
func hostIPv4(t *testing.T) netip.Addr {
	ifs, _ := net.InterfaceAddrs()
	for _, a := range ifs {
		p, err := netip.ParsePrefix(a.String())
		if err != nil || !p.Addr().Is4() || !p.Addr().IsPrivate() {
			continue
		}
		if ln, err := net.Listen("tcp", net.JoinHostPort(p.Addr().String(), "0")); err == nil {
			ln.Close()
			return p.Addr()
		}
	}
	t.Skip("keine private IPv4")
	return netip.Addr{}
}

// TestTailscaleSubnetRoute: wie ein NAS als Subnetz-Router - ein Tailscale-Geraet gibt
// ein LAN-Netz frei, die App ruft eine Adresse in diesem Netz auf (Route muss nicht nur
// sichtbar sein, sondern auch gewaehlt werden).
func TestTailscaleSubnetRoute(t *testing.T) {
	if testing.Short() {
		t.Skip("langsam")
	}
	lanIP := hostIPv4(t)
	lan := netip.PrefixFrom(lanIP, 24).Masked()
	ln, err := net.Listen("tcp", net.JoinHostPort(lanIP.String(), "0"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "dienst im lan")
	}))
	target := ln.Addr().String()

	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })
	derpMap := integration.RunDERPAndSTUN(t, logger.Discard, "127.0.0.1")
	control := &testcontrol.Server{DERPMap: derpMap, Logf: logger.Discard}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)
	controlURL := control.HTTPTestServer.URL

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	// "NAS": Subnetz-Router fuer lan
	nas := &tsnet.Server{Dir: filepath.Join(t.TempDir(), "nas"), ControlURL: controlURL, Hostname: "nas",
		Store: new(mem.Store), Ephemeral: true, Logf: logger.Discard}
	if os.Getenv("TS_NAS_LOG") != "" {
		nas.Logf = t.Logf
	}
	t.Cleanup(func() { nas.Close() })
	st, err := nas.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	nlc, _ := nas.LocalClient()
	if _, err := nlc.EditPrefs(ctx, &ipn.MaskedPrefs{Prefs: ipn.Prefs{AdvertiseRoutes: []netip.Prefix{lan}}, AdvertiseRoutesSet: true}); err != nil {
		t.Fatal(err)
	}
	control.SetSubnetRoutes(st.Self.PublicKey, []netip.Prefix{lan}) // = "Edit route settings" -> Haken
	nasIP := st.TailscaleIPs[0]

	// weiteres Geraet ohne Routen im selben Rechner: meldet dieselbe LAN-Adresse wie der
	// Router (wie ein Laptop in einem fremden Netz mit gleichem Adressbereich)
	laptop := &tsnet.Server{Dir: filepath.Join(t.TempDir(), "laptop"), ControlURL: controlURL, Hostname: "laptop",
		Store: new(mem.Store), Ephemeral: true, Logf: logger.Discard}
	t.Cleanup(func() { laptop.Close() })
	lst, err := laptop.Up(ctx)
	if err != nil {
		t.Fatal(err)
	}
	laptopIP := lst.TailscaleIPs[0]

	cfg := fmt.Sprintf(`{"control":%q}`, controlURL)
	if err := StartTailscale("ts4", filepath.Join(t.TempDir(), "app"), cfg, "phone"); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(StopAll)
	if !WaitHandshake("ts4", 20000) {
		t.Fatalf("nicht verbunden: %s", TailscaleStatus("ts4"))
	}
	// Route muss in der App ankommen und per Tailscale gewaehlt werden
	var s tsStatus
	deadline := time.Now().Add(15 * time.Second)
	for {
		json.Unmarshal([]byte(TailscaleStatus("ts4")), &s)
		_, via, _ := tsTunnels["ts4"].srv.Sys().Dialer.Get().UserDialPlan(ctx, "tcp", target)
		if s.Routes != "" && via {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("Route nicht aktiv: routes=%q viaTailscale=%v\n%s", s.Routes, via, TailscaleLog("ts4"))
		}
		time.Sleep(200 * time.Millisecond)
	}

	// Diagnose wie in der App ("Verbindung testen"): Weg ueber Tailscale erkannt
	d := TailscaleProbe("ts4", target, 3000)
	t.Log(d)
	if !strings.Contains(d, "via Tailscale") || !strings.Contains(d, "tunnel to nas: OK") {
		t.Fatalf("Probe: %s", d)
	}
	// Dienst AUF dem Router selbst, angesprochen ueber dessen LAN-Adresse (z. B. Web-Dienst
	// auf dem NAS, das Subnetz-Router ist): muss ueber die Tailscale-IP des Routers laufen.
	nasLn, err := nas.Listen("tcp", ":8081")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(nasLn, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "dienst auf dem nas")
	}))
	onNas := net.JoinHostPort(lanIP.String(), "8081")
	// erst weiter, wenn beide Geraete die LAN-Adresse melden (sonst prueft der Test nichts)
	tt := tsTunnels["ts4"]
	deadline = time.Now().Add(15 * time.Second)
	for {
		peers, _ := tt.netmapPeers(ctx)
		seen := map[netip.Addr]bool{}
		for _, p := range peers {
			for _, ep := range p.endpoints {
				if ep.Unmap() == lanIP {
					seen[p.tsIP] = true
				}
			}
		}
		if seen[nasIP] && seen[laptopIP] {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("Endpunkte fehlen: %+v", peers)
		}
		time.Sleep(200 * time.Millisecond)
	}
	tt.mu.Lock()
	tt.lanMap = nil
	tt.mu.Unlock()
	if got, want := tt.rewrite(ctx, onNas), net.JoinHostPort(nasIP.String(), "8081"); got != want {
		t.Fatalf("Umleitung auf %s, erwartet Router %s (Laptop %s)", got, want, laptopIP)
	}
	if d := TailscaleProbe("ts4", onNas, 5000); !strings.Contains(d, "is the device itself") || !strings.Contains(d, "connection OK") {
		t.Fatalf("Probe Router selbst: %s", d)
	}
	pp, err := StartProxy("geheim")
	if err != nil {
		t.Fatal(err)
	}
	pu := &url.URL{Scheme: "http", User: url.UserPassword(ProxyUser, "geheim"), Host: fmt.Sprintf("127.0.0.1:%d", pp)}
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu)}, Timeout: 20 * time.Second}
	SetRoute(lanIP.String(), "ts4")
	t.Cleanup(ClearRoutes)
	resp, err := client.Get("http://" + onNas + "/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "dienst auf dem nas" {
		t.Fatalf("got %d %q", resp.StatusCode, body)
	}

	// Hinweis: Ein tsnet-"Router" beantwortet freigegebene Subnetz-Adressen selbst
	// (registriert sie in seinem netstack) statt ins LAN weiterzuleiten - den echten
	// Weiterleitungs-Teil (tailscaled) kann dieser Test deshalb nicht pruefen.
}
