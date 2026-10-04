package wgbridge

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"strings"
	"sync"
	"time"
)

// ProxyUser ist der feste Benutzername fuer die Proxy-Anmeldung; das
// Passwort erzeugt die App bei jedem Start neu (SecureRandom).
const ProxyUser = "homehyrax"

// ProxyRealm muss die App in onReceivedHttpAuthRequest wiedererkennen.
const ProxyRealm = "HomeHyrax"

// ProxyMarkerHeader steht in jeder 407-Antwort des eigenen Proxys, Wert =
// ProxyMarker(). Ein Geraet im Heimnetz kennt den Wert nicht - so erkennt die
// App, ob die 407 wirklich vom eigenen Proxy kommt.
const ProxyMarkerHeader = "X-HomeHyrax-Proxy"

// ErrorHeader steht in jeder Fehlerseite (502), die der Proxy selbst erzeugt
// (Tunnel/Ziel nicht erreichbar). Go schreibt Kopfzeilen kanonisiert
// ("X-Homehyrax-Error") - die App muss den Namen ohne Gross-/Kleinschreibung
// vergleichen.
const ErrorHeader = "X-HomeHyrax-Error"

var (
	proxyMu     sync.Mutex
	proxyLn     net.Listener
	proxySrv    *http.Server
	proxyAuth   string
	proxySecret string

	// Ausweichmodus (ohne Passwort): einziges Ziel, das durch einen Tunnel
	// erreichbar ist ("host:port" der sichtbaren Kachel, leer = keins), und
	// die ohne Passwort geoeffneten Verbindungen (werden geschlossen, sobald
	// wieder ein Passwort gilt).
	fallbackTarget string
	openConns      = map[net.Conn]struct{}{}
)

type connKey struct{}

var proxyMarker = randomToken()

func randomToken() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// ProxyMarker liefert die Kennung dieses Prozesses (siehe ProxyMarkerHeader).
func ProxyMarker() string { return proxyMarker }

// StartProxy startet den lokalen Proxy auf 127.0.0.1 (zufaelliger Port) und
// liefert den Port. Mehrfachaufruf mit demselben Passwort = gleicher Port.
// Leeres Passwort = ohne Anmeldung (Ausweichmodus fuer alte WebViews).
//
// Schutz gegen andere Apps auf dem Handy: jede Anfrage braucht
// Proxy-Authorization mit dem Passwort, das nur diese App kennt. Der Port
// lauscht zwar nur auf 127.0.0.1, das erreicht aber jede App auf dem Handy -
// deshalb laesst der Ausweichmodus nur das Ziel aus SetProxyFallbackTarget
// durch einen Tunnel (siehe allowedWithoutAuth).
func StartProxy(secret string) (int, error) {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	if proxyLn != nil && secret == proxySecret {
		return proxyLn.Addr().(*net.TCPAddr).Port, nil
	}
	if proxySrv != nil {
		proxySrv.Close() // schliesst auch den alten Listener
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		proxyLn, proxySrv = nil, nil
		return 0, err
	}
	proxyLn = ln
	setSecretLocked(secret)
	proxySrv = &http.Server{
		Handler:           http.HandlerFunc(serveProxy),
		ReadHeaderTimeout: 30 * time.Second,
		// die Verbindung zur Anfrage merken, um sie spaeter schliessen zu koennen
		ConnContext: func(ctx context.Context, c net.Conn) context.Context {
			return context.WithValue(ctx, connKey{}, c)
		},
		ConnState: func(c net.Conn, st http.ConnState) {
			if st == http.StateClosed {
				untrack(c)
			}
		},
	}
	go proxySrv.Serve(ln)
	return ln.Addr().(*net.TCPAddr).Port, nil
}

// SetProxySecret aendert das Passwort des laufenden Proxys, der Port bleibt.
// Leer = ohne Anmeldung. Damit ist der Ausweichmodus nur offen, solange eine
// Web-App sichtbar ist. Mit Passwort werden alle ohne Passwort geoeffneten
// Verbindungen geschlossen (auch laufende CONNECT-Tunnel).
func SetProxySecret(secret string) {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	setSecretLocked(secret)
}

// SetProxyFallbackTarget legt fest, welches Ziel ("host:port", z. B.
// "192.168.178.20:80") der Ausweichmodus ohne Passwort durch einen Tunnel
// erreichen darf - die sichtbare Web-App. Leer = kein Ziel durch einen
// Tunnel. Ziele ausserhalb der Tunnel (Internet, direkt) bleiben erreichbar;
// die koennte jede App auch ohne den Proxy aufrufen.
func SetProxyFallbackTarget(hostport string) {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	fallbackTarget = normHostPort(hostport, "80")
}

func setSecretLocked(secret string) {
	proxySecret = secret
	proxyAuth = ""
	if secret != "" {
		proxyAuth = "Basic " + base64.StdEncoding.EncodeToString([]byte(ProxyUser+":"+secret))
		for c := range openConns {
			c.Close()
		}
		openConns = map[net.Conn]struct{}{}
	}
}

// normHostPort: "host:port" klein, ohne Klammern; fehlt der Port, gilt def.
func normHostPort(hp, def string) string {
	hp = strings.TrimSpace(hp)
	if hp == "" {
		return ""
	}
	host, port, err := net.SplitHostPort(hp)
	if err != nil {
		host, port = hp, def
	}
	return net.JoinHostPort(normHost(host), port)
}

var transport = &http.Transport{
	Proxy:                 nil,
	DialContext:           dialRouted,
	MaxIdleConnsPerHost:   2, // ESP32 & Co. haben nur wenige Sockets
	IdleConnTimeout:       10 * time.Second,
	TLSHandshakeTimeout:   15 * time.Second,
	ExpectContinueTimeout: time.Second,
}

var reverse = &httputil.ReverseProxy{
	Rewrite: func(pr *httputil.ProxyRequest) {
		pr.Out.URL = pr.In.URL
		pr.Out.Host = pr.In.Host
	},
	Transport:     transport,
	FlushInterval: -1, // sofort durchreichen: Server-Sent Events / Live-Werte
	ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
		// EventSource gibt bei einer Antwort != 200 endgueltig auf (Live-Werte
		// bleiben stehen), bei einem Netzwerkfehler verbindet sie selbst neu.
		// Deshalb die Verbindung abbrechen statt eine Fehlerseite zu senden.
		if isEventStream(r) {
			panic(http.ErrAbortHandler)
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set(ErrorHeader, "1")
		w.WriteHeader(http.StatusBadGateway)
		io.WriteString(w, "HomeHyrax: "+err.Error())
	},
}

// authorized prueft das Proxy-Passwort. open = Ausweichmodus ohne Passwort
// (die WebView kann sich am Proxy nicht anmelden).
func authorized(r *http.Request) (ok, open bool) {
	proxyMu.Lock()
	want := proxyAuth
	proxyMu.Unlock()
	if want == "" {
		return true, true
	}
	got := r.Header.Get("Proxy-Authorization")
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1, false
}

// allowedWithoutAuth: im Ausweichmodus nur das Ziel der sichtbaren Web-App
// durch einen Tunnel; alles, was ohnehin direkt ginge, bleibt erlaubt (die
// Seite laedt evtl. Schriften/Skripte aus dem Internet).
func allowedWithoutAuth(r *http.Request) bool {
	def := "80"
	if r.Method == http.MethodConnect || (r.URL != nil && r.URL.Scheme == "https") {
		def = "443"
	}
	hp := r.Host
	if r.URL != nil && r.URL.Host != "" {
		hp = r.URL.Host
	}
	hp = normHostPort(hp, def)
	if hp == "" {
		return false
	}
	proxyMu.Lock()
	target := fallbackTarget
	proxyMu.Unlock()
	if target != "" && hp == target {
		return true
	}
	host, _, _ := net.SplitHostPort(hp)
	dial, _, err := tunnelFor(host)
	return dial == nil && err == nil
}

// trackOpen merkt sich eine ohne Passwort geoeffnete Verbindung. false = in
// der Zwischenzeit gilt wieder ein Passwort (Verbindung nicht benutzen).
func trackOpen(c net.Conn) bool {
	if c == nil {
		return true
	}
	proxyMu.Lock()
	defer proxyMu.Unlock()
	if proxyAuth != "" {
		return false
	}
	openConns[c] = struct{}{}
	return true
}

func untrack(c net.Conn) {
	proxyMu.Lock()
	delete(openConns, c)
	proxyMu.Unlock()
}

func deny407(w http.ResponseWriter) {
	w.Header().Set("Proxy-Authenticate", `Basic realm="`+ProxyRealm+`"`)
	w.Header().Set(ProxyMarkerHeader, proxyMarker)
	w.WriteHeader(http.StatusProxyAuthRequired)
}

// isEventStream: Anfrage einer EventSource (Server-Sent Events).
func isEventStream(r *http.Request) bool {
	return strings.Contains(strings.ToLower(r.Header.Get("Accept")), "text/event-stream")
}

func serveProxy(w http.ResponseWriter, r *http.Request) {
	ok, open := authorized(r)
	if !ok {
		deny407(w)
		return
	}
	if open {
		if !allowedWithoutAuth(r) {
			http.Error(w, "HomeHyrax: target not allowed", http.StatusForbidden)
			return
		}
		c, _ := r.Context().Value(connKey{}).(net.Conn)
		if !trackOpen(c) {
			deny407(w)
			return
		}
	}
	if r.Method == http.MethodConnect {
		serveConnect(w, r, open)
		return
	}
	if !r.URL.IsAbs() {
		http.Error(w, "proxy requests only", http.StatusBadRequest)
		return
	}
	reverse.ServeHTTP(w, r)
}

// serveConnect tunnelt HTTPS (und WebSockets ueber TLS) als rohe TCP-Verbindung.
func serveConnect(w http.ResponseWriter, r *http.Request, open bool) {
	target, err := dialRouted(r.Context(), "tcp", r.Host)
	if err != nil {
		http.Error(w, "HomeHyrax: "+err.Error(), http.StatusBadGateway)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		target.Close()
		http.Error(w, "hijack not possible", http.StatusInternalServerError)
		return
	}
	client, buf, err := hj.Hijack()
	if err != nil {
		target.Close()
		return
	}
	if open {
		// auch das Ziel schliessen, wenn wieder ein Passwort gilt
		if !trackOpen(target) {
			client.Close()
			target.Close()
			return
		}
		defer untrack(target)
	}
	defer untrack(client)
	client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	go func() {
		io.Copy(target, bufferedReader(client, buf.Reader))
		if cw, ok := target.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
	}()
	io.Copy(client, target)
	client.Close()
	target.Close()
}

func bufferedReader(c net.Conn, br *bufio.Reader) io.Reader {
	if br != nil && br.Buffered() > 0 {
		return io.MultiReader(io.LimitReader(br, int64(br.Buffered())), c)
	}
	return c
}

// ProxyPort liefert den Port des laufenden Proxys, 0 = keiner.
func ProxyPort() int {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	if proxyLn == nil {
		return 0
	}
	return proxyLn.Addr().(*net.TCPAddr).Port
}
