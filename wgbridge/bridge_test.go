package wgbridge

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/net/dns/dnsmessage"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func keypair(t *testing.T) (priv, pub []byte) {
	priv = make([]byte, 32)
	rand.Read(priv)
	priv[0] &= 248
	priv[31] = (priv[31] & 127) | 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return
}

// startServer baut eine "FRITZ!Box": WireGuard-Peer mit netstack, auf dem
// 10.9.0.1:80 ein HTTP-Server (inkl. SSE), :7 ein Echo-Server und :53 ein
// DNS-Server (kennt nur nas.example.net) laufen.
func startServer(t *testing.T, serverPriv, clientPub []byte) (udpPort int) {
	tunDev, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.9.0.1")}, nil, 1280)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "srv "))
	// ohne listen_port waehlt wireguard-go selbst einen freien Port (kein
	// Reservieren-und-Freigeben mit Wettlauf)
	err = dev.IpcSet(fmt.Sprintf("private_key=%s\npublic_key=%s\nallowed_ip=10.9.0.2/32\n",
		hex.EncodeToString(serverPriv), hex.EncodeToString(clientPub)))
	if err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(dev.Close)
	st, err := dev.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(st, "\n") {
		if v, ok := strings.CutPrefix(line, "listen_port="); ok {
			udpPort, _ = strconv.Atoi(v)
		}
	}
	if udpPort == 0 {
		t.Fatalf("kein UDP-Port: %q", st)
	}
	startDNS(t, tnet)

	ln, err := tnet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.9.0.1"), Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "hallo vom geraet %s", r.URL.Path)
	})
	mux.HandleFunc("/api/rule/override", func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		fmt.Fprintf(w, "%s %s", r.Method, b)
	})
	// Shelly Gen1: Basic-Auth
	mux.HandleFunc("/relay/0", func(w http.ResponseWriter, r *http.Request) {
		if u, pw, ok := r.BasicAuth(); !ok || u != "admin" || pw != "tor" {
			w.Header().Set("WWW-Authenticate", `Basic realm="shelly"`)
			w.WriteHeader(401)
			return
		}
		fmt.Fprintf(w, `{"ison":true,"q":"%s"}`, r.URL.RawQuery)
	})
	// Shelly Gen2: Digest SHA-256
	mux.HandleFunc("/rpc/Switch.Set", func(w http.ResponseWriter, r *http.Request) {
		// Passwort darf nie als Basic (Klartext) bei einem Digest-Geraet ankommen
		if _, _, ok := r.BasicAuth(); ok {
			w.WriteHeader(http.StatusBadRequest)
			io.WriteString(w, "basic an digest-geraet")
			return
		}
		if !checkDigest(r, "admin", "tor") {
			w.Header().Set("WWW-Authenticate", `Digest qop="auth", realm="shellyplus1-abc", nonce="60dc59c6", algorithm=SHA-256`)
			w.WriteHeader(401)
			return
		}
		fmt.Fprintf(w, `{"was_on":false,"q":"%s"}`, r.URL.RawQuery)
	})
	mux.HandleFunc("/events", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		for i := 0; i < 3; i++ {
			fmt.Fprintf(w, "data: {\"n\":%d}\n\n", i)
			w.(http.Flusher).Flush()
			time.Sleep(50 * time.Millisecond)
		}
	})
	go http.Serve(ln, mux)

	echo, err := tnet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.9.0.1"), Port: 7})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := echo.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	return udpPort
}

// startDNS: kleiner DNS-Server im Tunnel. nas.example.net -> 10.9.0.1,
// alles andere NXDOMAIN.
func startDNS(t *testing.T, tnet *netstack.Net) {
	pc, err := tnet.ListenUDPAddrPort(netip.MustParseAddrPort("10.9.0.1:53"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			var m dnsmessage.Message
			if m.Unpack(buf[:n]) != nil || len(m.Questions) != 1 {
				continue
			}
			q := m.Questions[0]
			m.Header.Response = true
			m.Header.RecursionAvailable = true
			if strings.EqualFold(q.Name.String(), "nas.example.net.") {
				if q.Type == dnsmessage.TypeA {
					m.Answers = []dnsmessage.Resource{{
						Header: dnsmessage.ResourceHeader{Name: q.Name, Type: dnsmessage.TypeA, Class: dnsmessage.ClassINET, TTL: 60},
						Body:   &dnsmessage.AResource{A: [4]byte{10, 9, 0, 1}},
					}}
				}
			} else {
				m.Header.RCode = dnsmessage.RCodeNameError
			}
			if out, err := m.Pack(); err == nil {
				pc.WriteTo(out, from)
			}
		}
	}()
}

func setup(t *testing.T) (proxyURL *url.URL) {
	return setupDNS(t, "DNS = 10.9.0.1, fritz.box")
}

// setupDNS wie setup, mit eigener DNS-Zeile (leer = kein DNS im Tunnel).
func setupDNS(t *testing.T, dnsLine string) (proxyURL *url.URL) {
	sPriv, sPub := keypair(t)
	cPriv, cPub := keypair(t)
	port := startServer(t, sPriv, cPub)
	cfg := fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = 10.9.0.2/24
%s

[Peer]
PublicKey = %s
AllowedIPs = 10.9.0.0/24, 0.0.0.0/0
Endpoint = 127.0.0.1:%d
`, base64.StdEncoding.EncodeToString(cPriv), dnsLine, base64.StdEncoding.EncodeToString(sPub), port)
	if msg := ValidateConfig(cfg); msg != "" {
		t.Fatal(msg)
	}
	if err := StartTunnel("home", cfg); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(StopAll)
	if !WaitHandshake("home", 5000) {
		t.Fatalf("kein Handshake: %s", LastError("home"))
	}
	pp, err := StartProxy("geheim")
	if err != nil {
		t.Fatal(err)
	}
	return &url.URL{Scheme: "http", User: url.UserPassword(ProxyUser, "geheim"), Host: fmt.Sprintf("127.0.0.1:%d", pp)}
}

func TestProxyThroughTunnel(t *testing.T) {
	pu := setup(t)
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu)}, Timeout: 10 * time.Second}

	// IP liegt in AllowedIPs -> Tunnel, auch ohne explizite Route
	resp, err := client.Get("http://10.9.0.1/index.html")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(b) != "hallo vom geraet /index.html" {
		t.Fatalf("got %q", b)
	}

	// POST mit Body (Schalten)
	resp, err = client.Post("http://10.9.0.1/api/rule/override", "application/json", strings.NewReader(`{"id":3,"on":true}`))
	if err != nil {
		t.Fatal(err)
	}
	b, _ = io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(b) != `POST {"id":3,"on":true}` {
		t.Fatalf("got %q", b)
	}

	// SSE kommt gestreamt an (erstes Event vor Stream-Ende)
	resp, err = client.Get("http://10.9.0.1/events")
	if err != nil {
		t.Fatal(err)
	}
	line, _ := bufio.NewReader(resp.Body).ReadString('\n')
	resp.Body.Close()
	if line != "data: {\"n\":0}\n" {
		t.Fatalf("sse got %q", line)
	}

	// Route hat Vorrang vor AllowedIPs: zeigt sie auf einen Tunnel, der nicht
	// laeuft -> Fehler vom Proxy statt still direkt oder ueber einen anderen Tunnel
	SetRoute("10.9.0.1", "gibtsnicht")
	t.Cleanup(ClearRoutes)
	resp, err = client.Get("http://10.9.0.1/x")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadGateway || resp.Header.Get(ErrorHeader) == "" {
		t.Fatalf("Route auf fehlenden Tunnel: status %d", resp.StatusCode)
	}
	// EventSource-Anfrage bei nicht erreichbarem Ziel: Verbindung abbrechen statt
	// 502 - sonst gibt die EventSource endgueltig auf statt neu zu verbinden
	req, _ := http.NewRequest("GET", "http://10.9.0.1/events", nil)
	req.Header.Set("Accept", "text/event-stream")
	if resp, err = client.Do(req); err == nil {
		resp.Body.Close()
		t.Fatalf("SSE auf fehlenden Tunnel: status %d statt Verbindungsabbruch", resp.StatusCode)
	}
	ClearRoutes()
	resp, err = client.Get("http://10.9.0.1/x")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("nach ClearRoutes: status %d", resp.StatusCode)
	}
}

// Zwei Tunnel decken dieselbe IP ab: laengstes Praefix gewinnt, bei Gleichstand
// die kleinste ID - unabhaengig von der Map-Reihenfolge.
func TestCoveringDeterministic(t *testing.T) {
	mk := func(id string, pfx ...string) *tunnel {
		var p []netip.Prefix
		for _, s := range pfx {
			p = append(p, netip.MustParsePrefix(s))
		}
		return &tunnel{id: id, cfg: &wgConfig{peers: []wgPeer{{allowedIPs: p}}}}
	}
	mu.Lock()
	saved := tunnels
	tunnels = map[string]*tunnel{
		"c": mk("c", "10.1.0.0/16"),
		"b": mk("b", "10.0.0.0/8"),
		"a": mk("a", "10.1.0.0/16", "0.0.0.0/0"),
		"d": mk("d", "10.1.0.0/16"),
	}
	defer func() { tunnels = saved; mu.Unlock() }()
	for i := 0; i < 50; i++ {
		if got := coveringLocked(netip.MustParseAddr("10.1.2.3")); got == nil || got.id != "a" {
			t.Fatalf("10.1.2.3 -> %v, erwartet a", got)
		}
		if got := coveringLocked(netip.MustParseAddr("10.2.0.1")); got == nil || got.id != "b" {
			t.Fatalf("10.2.0.1 -> %v, erwartet b", got)
		}
		if got := coveringLocked(netip.MustParseAddr("8.8.8.8")); got != nil {
			t.Fatalf("8.8.8.8 -> %v, erwartet direkt", got.id)
		}
	}
}

// DNS-Rueckfall nur bei "Name nicht gefunden", nicht bei Verbindungsfehlern.
func TestNameNotFound(t *testing.T) {
	cases := []struct {
		err  error
		want bool
	}{
		{&net.OpError{Op: "dial", Err: &net.DNSError{Err: "no such host", Name: "nas.example.net", IsNotFound: true}}, true},
		{&net.OpError{Op: "dial", Err: &net.DNSError{Err: "i/o timeout", Name: "nas.example.net", IsTimeout: true}}, false},
		// netstack ohne DNS-Server: kein "nicht gefunden" (wird vorher abgefangen, siehe dialRouted)
		{&net.OpError{Op: "dial", Err: &net.DNSError{Err: "cannot marshal DNS message", Name: "nas.example.net"}}, false},
		{&net.OpError{Op: "dial", Err: errors.New("missing address")}, true},
		{&net.OpError{Op: "dial", Err: errors.New("connection refused")}, false},
		{context.DeadlineExceeded, false},
	}
	for _, c := range cases {
		if got := nameNotFound(c.err); got != c.want {
			t.Errorf("%v: got %v", c.err, got)
		}
	}
}

// stubSystemDNS ersetzt den Resolver des normalen Netzes: name -> 10.9.0.1,
// zaehlt die Aufrufe.
func stubSystemDNS(t *testing.T) *atomic.Int32 {
	var calls atomic.Int32
	saved := systemLookup
	systemLookup = func(ctx context.Context, network, host string) ([]netip.Addr, error) {
		calls.Add(1)
		return []netip.Addr{netip.MustParseAddr("10.9.0.1")}, nil
	}
	t.Cleanup(func() { systemLookup = saved })
	return &calls
}

func getVia(t *testing.T, pu *url.URL, u string) string {
	t.Helper()
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu)}, Timeout: 20 * time.Second}
	resp, err := client.Get(u)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	return fmt.Sprintf("%d %s", resp.StatusCode, b)
}

// Hostnamen-Route mit DNS im Tunnel: bekannter Name ueber das Tunnel-DNS,
// unbekannter (NXDOMAIN) ueber das normale Netz, IP dann durch den Tunnel.
func TestHostnameRouteTunnelDNS(t *testing.T) {
	pu := setup(t)
	calls := stubSystemDNS(t)
	SetRoute("nas.example.net", "home")
	SetRoute("cam.example.net", "home")
	t.Cleanup(ClearRoutes)
	if got := getVia(t, pu, "http://nas.example.net/a"); got != "200 hallo vom geraet /a" {
		t.Fatalf("nas: %s", got)
	}
	if calls.Load() != 0 {
		t.Fatal("Name aus dem Tunnel-DNS ging an das normale DNS")
	}
	if got := getVia(t, pu, "http://cam.example.net/b"); got != "200 hallo vom geraet /b" {
		t.Fatalf("cam: %s", got)
	}
	if calls.Load() != 1 {
		t.Fatalf("NXDOMAIN: %d Rueckfaelle", calls.Load())
	}
}

// Hostnamen-Route ohne DNS im Tunnel: gleich ueber das normale Netz aufloesen
// (netstack koennte ohne DNS-Server gar nicht fragen).
func TestHostnameRouteNoTunnelDNS(t *testing.T) {
	pu := setupDNS(t, "")
	calls := stubSystemDNS(t)
	SetRoute("nas.example.net", "home")
	t.Cleanup(ClearRoutes)
	if got := getVia(t, pu, "http://nas.example.net/c"); got != "200 hallo vom geraet /c" {
		t.Fatalf("got %s", got)
	}
	if calls.Load() != 1 {
		t.Fatalf("%d Aufrufe", calls.Load())
	}
}

func TestOverlappingTunnels(t *testing.T) {
	mk := func(id string, pfx ...string) *tunnel {
		var p []netip.Prefix
		for _, s := range pfx {
			p = append(p, netip.MustParsePrefix(s))
		}
		return &tunnel{id: id, cfg: &wgConfig{peers: []wgPeer{{allowedIPs: p}}}}
	}
	mu.Lock()
	saved := tunnels
	defer func() { mu.Lock(); tunnels = saved; mu.Unlock() }()
	tunnels = map[string]*tunnel{
		"a": mk("a", "192.168.178.0/24", "0.0.0.0/0"),
		"b": mk("b", "10.0.0.0/8", "0.0.0.0/0"),
	}
	mu.Unlock()
	if got := OverlappingTunnels(); got != "" {
		t.Fatalf("nur /0 gemeinsam: %q", got)
	}
	mu.Lock()
	tunnels["c"] = mk("c", "192.168.178.20/32")
	mu.Unlock()
	if got := OverlappingTunnels(); got != "a,c" {
		t.Fatalf("got %q", got)
	}
}

func TestProxyAuthRequired(t *testing.T) {
	pu := setup(t)
	noAuth := *pu
	noAuth.User = nil
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(&noAuth)}}
	resp, err := client.Get("http://10.9.0.1/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("status %d", resp.StatusCode)
	}
	if !strings.Contains(resp.Header.Get("Proxy-Authenticate"), ProxyRealm) {
		t.Fatal("realm fehlt")
	}
	wrong := *pu
	wrong.User = url.UserPassword(ProxyUser, "falsch")
	client = &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(&wrong)}}
	resp, err = client.Get("http://10.9.0.1/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusProxyAuthRequired {
		t.Fatalf("status %d", resp.StatusCode)
	}
}

// Kaputte neue Config darf den laufenden Tunnel nicht abreissen.
func TestRestartKeepsOldOnError(t *testing.T) {
	pu := setup(t)
	if err := StartTunnel("home", "[Interface]\nPrivateKey = kaputt\n"); err == nil {
		t.Fatal("kaputte Config akzeptiert")
	}
	if !IsRunning("home") || !WaitHandshake("home", 2000) {
		t.Fatal("alter Tunnel weg")
	}
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(pu)}, Timeout: 10 * time.Second}
	resp, err := client.Get("http://10.9.0.1/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
}

// Kamera (RTSP): lokaler Port reicht durch den Tunnel zum Geraet durch;
// nach StopForward ist der Port zu.
func TestForwardThroughTunnel(t *testing.T) {
	setup(t)
	port, err := Forward("10.9.0.1:7")
	if err != nil {
		t.Fatal(err)
	}
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	c.SetDeadline(time.Now().Add(10 * time.Second))
	fmt.Fprint(c, "OPTIONS rtsp://kamera/live0 RTSP/1.0\n")
	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil || line != "OPTIONS rtsp://kamera/live0 RTSP/1.0\n" {
		t.Fatalf("echo %q %v", line, err)
	}
	StopForward(port)
	// offene Verbindung wird mit geschlossen (EOF/Reset - kein Timeout), neuer Aufbau scheitert
	c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := c.Read(make([]byte, 1)); err == nil {
		t.Fatal("Verbindung nach StopForward noch offen")
	} else if ne, ok := err.(net.Error); ok && ne.Timeout() {
		t.Fatal("Verbindung nach StopForward nicht geschlossen (Timeout)")
	}
	c.Close()
	if c2, err := net.DialTimeout("tcp", addr, time.Second); err == nil {
		c2.Close()
		t.Fatal("Port nach StopForward noch offen")
	}
}

func TestConnectThroughTunnel(t *testing.T) {
	pu := setup(t)
	c, err := net.Dial("tcp", pu.Host)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	auth := base64.StdEncoding.EncodeToString([]byte(ProxyUser + ":geheim"))
	fmt.Fprintf(c, "CONNECT 10.9.0.1:7 HTTP/1.1\r\nHost: 10.9.0.1:7\r\nProxy-Authorization: Basic %s\r\n\r\n", auth)
	br := bufio.NewReader(c)
	status, _ := br.ReadString('\n')
	if !strings.Contains(status, "200") {
		t.Fatalf("status %q", status)
	}
	br.ReadString('\n') // Leerzeile
	fmt.Fprint(c, "ping\n")
	echo, _ := br.ReadString('\n')
	if echo != "ping\n" {
		t.Fatalf("echo %q", echo)
	}
}

func TestDefaultRouteNotCaptured(t *testing.T) {
	cfg := &wgConfig{peers: []wgPeer{{allowedIPs: []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0")}}}}
	if cfg.covers(netip.MustParseAddr("8.8.8.8")) {
		t.Fatal("0.0.0.0/0 darf nicht jede IP in den Tunnel ziehen")
	}
}

func TestParseFritzConfig(t *testing.T) {
	// Aufbau wie der FRITZ!Box-Export (Schluessel ersetzt)
	cfg := `[Interface]
PrivateKey = ` + base64.StdEncoding.EncodeToString(make([]byte, 32)) + `
Address = 192.168.178.202/24,fd00::202/64
DNS = 192.168.178.1,fd00::1
DNS = fritz.box

[Peer]
PublicKey = ` + base64.StdEncoding.EncodeToString(make([]byte, 32)) + `
PresharedKey = ` + base64.StdEncoding.EncodeToString(make([]byte, 32)) + `
AllowedIPs = 192.168.178.0/24,0.0.0.0/0,fd00::/64,::/0
Endpoint = 203.0.113.7:51820
PersistentKeepalive = 25
`
	c, err := parseConfig(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.addresses) != 2 || len(c.dns) != 2 || len(c.peers[0].allowedIPs) != 4 {
		t.Fatalf("%+v", c)
	}
	if !c.covers(netip.MustParseAddr("192.168.178.20")) || c.covers(netip.MustParseAddr("1.1.1.1")) {
		t.Fatal("covers falsch")
	}
	if ValidateConfig("[Interface]\nPrivateKey = kaputt\n") == "" {
		t.Fatal("kaputte Config akzeptiert")
	}
	for name, bad := range map[string]string{
		"sektion": strings.Replace(cfg, "[Peer]", "[Peers]", 1),
		"port":    strings.Replace(cfg, ":51820", ":99999", 1),
		"mtu":     strings.Replace(cfg, "[Peer]", "MTU = 20\n[Peer]", 1),
	} {
		if ValidateConfig(bad) == "" {
			t.Fatalf("%s: kaputte Config akzeptiert", name)
		}
	}
	if a, ok := c.nudgeTarget(); !ok || a != netip.MustParseAddr("192.168.178.1") {
		t.Fatalf("nudgeTarget %v", a)
	}

	// UTF-8-BOM (Windows-Editoren)
	if msg := ValidateConfig("\uFEFF" + cfg); msg != "" {
		t.Fatalf("BOM: %s", msg)
	}
	// ueberlange Zeile: sauberer Fehler statt stillem Abbruch mitten in der Datei
	if ValidateConfig(strings.Replace(cfg, "[Peer]", "# "+strings.Repeat("x", 2<<20)+"\n[Peer]", 1)) == "" {
		t.Fatal("ueberlange Zeile akzeptiert")
	}
	// lange Kommentarzeile (> 64 KiB) ist kein Problem
	if msg := ValidateConfig(strings.Replace(cfg, "[Peer]", "# "+strings.Repeat("x", 100<<10)+"\n[Peer]", 1)); msg != "" {
		t.Fatalf("lange Zeile: %s", msg)
	}
	// AllowedIPs ohne Praefixlaenge = einzelne Adresse
	c, err = parseConfig(strings.Replace(cfg, "AllowedIPs = 192.168.178.0/24,", "AllowedIPs = 192.168.178.20, fd00::20,", 1))
	if err != nil {
		t.Fatal(err)
	}
	if a := c.peers[0].allowedIPs; a[0] != netip.MustParsePrefix("192.168.178.20/32") || a[1] != netip.MustParsePrefix("fd00::20/128") {
		t.Fatalf("AllowedIPs %v", a)
	}
}

// PersistentKeepalive: fehlt -> 25 (NAT offen halten), "off"/0 -> aus.
func TestKeepalive(t *testing.T) {
	base := `[Interface]
PrivateKey = ` + base64.StdEncoding.EncodeToString(make([]byte, 32)) + `
Address = 192.168.178.202/24

[Peer]
PublicKey = ` + base64.StdEncoding.EncodeToString(make([]byte, 32)) + `
AllowedIPs = 192.168.178.0/24
Endpoint = 203.0.113.7:51820
`
	for line, want := range map[string]string{
		"":                          "persistent_keepalive_interval=25\n",
		"PersistentKeepalive = 0":   "persistent_keepalive_interval=0\n",
		"PersistentKeepalive = off": "persistent_keepalive_interval=0\n",
		"PersistentKeepalive = 10":  "persistent_keepalive_interval=10\n",
	} {
		c, err := parseConfig(base + line + "\n")
		if err != nil {
			t.Fatalf("%q: %v", line, err)
		}
		u, err := c.uapi()
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(u, want) {
			t.Fatalf("%q: %s", line, u)
		}
	}
	if ValidateConfig(base+"PersistentKeepalive = -1\n") == "" {
		t.Fatal("negativer Wert akzeptiert")
	}
}

func TestNoAuthFallback(t *testing.T) {
	pu := setup(t)
	port, err := StartProxy("")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { StartProxy("geheim"); SetProxyFallbackTarget("") })
	u := &url.URL{Scheme: "http", Host: fmt.Sprintf("127.0.0.1:%d", port)}
	if u.Host == pu.Host {
		t.Fatal("Ausweichmodus muss neuen Port bekommen")
	}
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(u)}, Timeout: 10 * time.Second}
	get := func(target string) int {
		resp, err := client.Get(target)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	// ohne Ziel: nichts durch den Tunnel
	if s := get("http://10.9.0.1/"); s != http.StatusForbidden {
		t.Fatalf("ohne Ziel: status %d", s)
	}
	SetProxyFallbackTarget("10.9.0.1")
	if s := get("http://10.9.0.1/"); s != 200 {
		t.Fatalf("Ziel: status %d", s)
	}
	// anderes Ziel im Tunnel (anderer Port) bleibt zu
	if s := get("http://10.9.0.1:7/"); s != http.StatusForbidden {
		t.Fatalf("anderer Port: status %d", s)
	}
	// direkt erreichbares Ziel (Internet, hier ein lokaler Server) bleibt erlaubt
	direct := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer direct.Close()
	if s := get(direct.URL); s != 200 {
		t.Fatalf("direkt: status %d", s)
	}
}

// Ohne Passwort geoeffnete CONNECT-Verbindung wird geschlossen, sobald wieder
// ein Passwort gilt.
func TestFallbackConnectClosedOnSecret(t *testing.T) {
	pu := setup(t)
	SetProxyFallbackTarget("10.9.0.1:7")
	SetProxySecret("")
	t.Cleanup(func() { SetProxySecret("geheim"); SetProxyFallbackTarget("") })
	c, err := net.Dial("tcp", pu.Host)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fmt.Fprint(c, "CONNECT 10.9.0.1:7 HTTP/1.1\r\nHost: 10.9.0.1:7\r\n\r\n")
	br := bufio.NewReader(c)
	c.SetDeadline(time.Now().Add(10 * time.Second))
	status, _ := br.ReadString('\n')
	if !strings.Contains(status, "200") {
		t.Fatalf("status %q", status)
	}
	br.ReadString('\n')
	fmt.Fprint(c, "ping\n")
	if echo, _ := br.ReadString('\n'); echo != "ping\n" {
		t.Fatalf("echo %q", echo)
	}
	SetProxySecret("geheim")
	c.SetDeadline(time.Now().Add(3 * time.Second))
	_, err = br.ReadByte()
	if err == nil {
		t.Fatal("Verbindung nach SetProxySecret noch offen")
	}
	if ne, ok := err.(net.Error); ok && ne.Timeout() {
		t.Fatal("Verbindung nach SetProxySecret nicht geschlossen (Timeout)")
	}
}

// Ausweichmodus nur zeitweise: Passwort weg und wieder an, gleicher Port;
// eigene 407 traegt die Prozess-Kennung.
func TestProxySecretToggle(t *testing.T) {
	pu := setup(t)
	noAuth := *pu
	noAuth.User = nil
	client := &http.Client{Transport: &http.Transport{Proxy: http.ProxyURL(&noAuth)}, Timeout: 10 * time.Second}
	get := func() *http.Response {
		resp, err := client.Get("http://10.9.0.1/")
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp
	}
	resp := get()
	if resp.StatusCode != http.StatusProxyAuthRequired || resp.Header.Get(ProxyMarkerHeader) != ProxyMarker() {
		t.Fatalf("status %d, marker %q", resp.StatusCode, resp.Header.Get(ProxyMarkerHeader))
	}
	SetProxyFallbackTarget("10.9.0.1:80")
	SetProxySecret("")
	t.Cleanup(func() { SetProxySecret("geheim"); SetProxyFallbackTarget("") })
	if s := get().StatusCode; s != 200 {
		t.Fatalf("offen: status %d", s)
	}
	SetProxySecret("geheim")
	if s := get().StatusCode; s != http.StatusProxyAuthRequired {
		t.Fatalf("wieder zu: status %d", s)
	}
}

func checkDigest(r *http.Request, user, pass string) bool {
	h := r.Header.Get("Authorization")
	if !strings.HasPrefix(h, "Digest ") {
		return false
	}
	p := map[string]string{}
	for _, part := range splitParams(h[len("Digest "):]) {
		k, v, _ := strings.Cut(part, "=")
		p[strings.TrimSpace(k)] = strings.Trim(strings.TrimSpace(v), `"`)
	}
	hx := func(s string) string { d := sha256.Sum256([]byte(s)); return hex.EncodeToString(d[:]) }
	ha1 := hx(user + ":" + p["realm"] + ":" + pass)
	ha2 := hx(r.Method + ":" + p["uri"])
	want := hx(ha1 + ":" + p["nonce"] + ":" + p["nc"] + ":" + p["cnonce"] + ":auth:" + ha2)
	return p["response"] == want && p["uri"] == r.URL.RequestURI()
}

func TestRequestShellyAuth(t *testing.T) {
	setup(t)
	// Gen2 Digest, Impuls
	for _, mode := range []string{"digest", ""} {
		out, err := Request("GET", "http://10.9.0.1/rpc/Switch.Set?id=0&on=true&toggle_after=1", "admin", "tor", mode, 5000)
		if err != nil || !strings.HasPrefix(out, "200\n") || !strings.Contains(out, "toggle_after=1") {
			t.Fatalf("gen2 %q: %q %v", mode, out, err)
		}
	}
	// Gen1 Basic
	for _, mode := range []string{"basic", ""} {
		out, err := Request("GET", "http://10.9.0.1/relay/0?turn=on&timer=1", "admin", "tor", mode, 5000)
		if err != nil || !strings.HasPrefix(out, "200\n") {
			t.Fatalf("gen1 %q: %q %v", mode, out, err)
		}
	}
	// Gen2-Modus sendet nie Basic, auch wenn das Geraet es verlangt
	out, _ := Request("GET", "http://10.9.0.1/relay/0?turn=on", "admin", "tor", "digest", 5000)
	if !strings.HasPrefix(out, "401") {
		t.Fatalf("digest-Modus an Basic-Geraet: %q", out)
	}
	// falsches Passwort -> 401, kein Fehler
	out, _ = Request("GET", "http://10.9.0.1/rpc/Switch.Set?id=0&on=true", "admin", "falsch", "digest", 5000)
	if !strings.HasPrefix(out, "401") {
		t.Fatalf("wrong pw: %q", out)
	}
	// ohne Passwort, ungeschuetzter Pfad
	out, err := Request("GET", "http://10.9.0.1/x", "", "", "", 5000)
	if err != nil || !strings.HasPrefix(out, "200\n") {
		t.Fatalf("plain: %q %v", out, err)
	}
	if _, err := Request("GET", "http://10.9.0.1/x", "", "", "ntlm", 5000); err == nil {
		t.Fatal("unbekannter authMode akzeptiert")
	}
}

// authServer zaehlt Anfragen und merkt sich, ob je Zugangsdaten ankamen.
type authServer struct {
	hits, withAuth atomic.Int32
}

func (a *authServer) handler(chal string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		a.hits.Add(1)
		if r.Header.Get("Authorization") != "" {
			a.withAuth.Add(1)
		}
		if chal != "" {
			w.Header().Set("WWW-Authenticate", chal)
		}
		w.WriteHeader(http.StatusUnauthorized)
	}
}

// 401 ohne Challenge: kein zweiter Versuch, schon gar nicht mit Basic.
func TestRequestNoChallengeNoBasic(t *testing.T) {
	a := &authServer{}
	srv := httptest.NewServer(a.handler(""))
	defer srv.Close()
	out, err := Request("GET", srv.URL+"/relay/0", "admin", "tor", "", 5000)
	if err != nil || !strings.HasPrefix(out, "401") {
		t.Fatalf("%q %v", out, err)
	}
	if a.hits.Load() != 1 || a.withAuth.Load() != 0 {
		t.Fatalf("hits %d, mit Anmeldung %d", a.hits.Load(), a.withAuth.Load())
	}
}

// Weiterleitung auf ein anderes Geraet: dort gehen keine Zugangsdaten hin.
func TestRequestRedirectNoCredentials(t *testing.T) {
	a := &authServer{}
	other := httptest.NewServer(a.handler(`Basic realm="x"`))
	defer other.Close()
	first := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL+"/steal", http.StatusFound)
	}))
	defer first.Close()
	out, err := Request("GET", first.URL+"/relay/0", "admin", "tor", "", 5000)
	if err != nil || !strings.HasPrefix(out, "401") {
		t.Fatalf("%q %v", out, err)
	}
	if a.withAuth.Load() != 0 {
		t.Fatal("Zugangsdaten an fremdes Ziel geschickt")
	}
}

// Keine Wiederverwendung von Verbindungen: bricht eine Verbindung nach dem
// Senden ab, darf net/http den Schaltbefehl nicht selbst wiederholen.
func TestRequestNoAutomaticRetry(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	var hits atomic.Int32
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				br := bufio.NewReader(c)
				for n := 0; ; n++ {
					req, err := http.ReadRequest(br)
					if err != nil {
						return
					}
					hits.Add(1)
					if n > 0 {
						return // zweite Anfrage auf derselben Verbindung: ohne Antwort abbrechen
					}
					io.WriteString(c, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
					if req.Close {
						return
					}
				}
			}()
		}
	}()
	u := "http://" + ln.Addr().String() + "/relay/0?turn=on"
	for i := 0; i < 2; i++ {
		if out, err := Request("GET", u, "", "", "", 5000); err != nil || !strings.HasPrefix(out, "200") {
			t.Fatalf("%d: %q %v", i, out, err)
		}
	}
	if n := hits.Load(); n != 2 {
		t.Fatalf("%d Anfragen statt 2", n)
	}
}
