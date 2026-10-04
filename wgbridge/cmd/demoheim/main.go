// demoheim ist ein Demo-Heimnetz zum Ausprobieren und fuer Aufnahmen - ohne echten
// Router und ohne echte Geraete. Es spielt eine FRITZ!Box mit WireGuard-Zugang; im
// Tunnel liegen unter 192.168.178.x:
//
//	.1   Router-Startseite
//	.20  Camper-Weboberflaeche (Batterie, Solar, Tanks, Heizung)
//	.30  simulierter Shelly Gen2 (Switch.Set/Toggle/GetStatus, /shelly)
//	.40  Kamera: RTSP :554 wird an -rtsp weitergereicht (z. B. mediamtx auf dem PC)
//
// Dieselben Seiten gibt es direkt auf dem PC unter -direct (fuer "zuhause direkt").
// Die Handy-Konfiguration (wg-quick) schreibt das Programm nach -conf; die Schluessel
// bleiben in -state, damit die Konfiguration gueltig bleibt.
//
//	go run ./cmd/demoheim -endpoint 10.0.2.2   (10.0.2.2 = PC aus Sicht des Android-Emulators)
package main

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

type keys struct {
	Server, Client string // private keys, base64
}

func genKey() string {
	k := make([]byte, 32)
	rand.Read(k)
	k[0] &= 248
	k[31] = (k[31] & 127) | 64
	return base64.StdEncoding.EncodeToString(k)
}

func pub(priv string) []byte {
	k, _ := base64.StdEncoding.DecodeString(priv)
	p, err := curve25519.X25519(k, curve25519.Basepoint)
	if err != nil {
		log.Fatal(err)
	}
	return p
}

func hexKey(b64 string) string {
	k, _ := base64.StdEncoding.DecodeString(b64)
	return hex.EncodeToString(k)
}

func loadKeys(path string) keys {
	var k keys
	if b, err := os.ReadFile(path); err == nil && json.Unmarshal(b, &k) == nil && k.Server != "" {
		return k
	}
	k = keys{Server: genKey(), Client: genKey()}
	b, _ := json.MarshalIndent(k, "", "  ")
	if err := os.WriteFile(path, b, 0o600); err != nil {
		log.Fatal(err)
	}
	return k
}

func main() {
	port := flag.Int("port", 51820, "UDP port of the WireGuard router")
	endpoint := flag.String("endpoint", "10.0.2.2", "address at which the phone reaches this PC")
	state := flag.String("state", "demoheim-keys.json", "key file")
	confOut := flag.String("conf", "demoheim-handy.conf", "WireGuard configuration for the phone")
	rtsp := flag.String("rtsp", "127.0.0.1:8554", "camera stream on this PC (empty = no camera)")
	direct := flag.String("direct", "127.0.0.1:8080", "camper page served directly on this PC, e.g. :8080 for all interfaces (empty = off)")
	lang := flag.String("lang", "de", "language of the demo pages: de or en")
	flag.Parse()

	k := loadKeys(*state)
	conf := fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = 192.168.178.201/24
DNS = 192.168.178.1

[Peer]
PublicKey = %s
AllowedIPs = 192.168.178.0/24
Endpoint = %s:%d
PersistentKeepalive = 25
`, k.Client, base64.StdEncoding.EncodeToString(pub(k.Server)), *endpoint, *port)
	if err := os.WriteFile(*confOut, []byte(conf), 0o600); err != nil {
		log.Fatal(err)
	}

	addrs := []netip.Addr{}
	for _, a := range []string{"192.168.178.1", "192.168.178.20", "192.168.178.30", "192.168.178.40"} {
		addrs = append(addrs, netip.MustParseAddr(a))
	}
	tunDev, tnet, err := netstack.CreateNetTUN(addrs, nil, 1420)
	if err != nil {
		log.Fatal(err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "demoheim "))
	err = dev.IpcSet(fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=192.168.178.201/32\n",
		hexKey(k.Server), *port, hex.EncodeToString(pub(k.Client))))
	if err != nil {
		log.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		log.Fatal(err)
	}

	camper, router, shellyName := camperPage, routerPage, "Heizung"
	if *lang == "en" {
		camper, router, shellyName = englisch.Replace(camperPage), englisch.Replace(routerPage), "Heating"
	}
	sh := &shelly{name: shellyName}
	serve := func(ip string, port int, h http.Handler) {
		ln, err := tnet.ListenTCP(&net.TCPAddr{IP: net.ParseIP(ip), Port: port})
		if err != nil {
			log.Fatal(err)
		}
		go http.Serve(ln, h)
	}
	serve("192.168.178.1", 80, page(router))
	serve("192.168.178.20", 80, camperHandler(camper))
	serve("192.168.178.30", 80, sh)
	if *rtsp != "" {
		ln, err := tnet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("192.168.178.40"), Port: 554})
		if err != nil {
			log.Fatal(err)
		}
		go forward(ln, *rtsp)
	}
	if *direct != "" {
		mux := http.NewServeMux()
		mux.Handle("/", camperHandler(camper))
		go func() { log.Fatal(http.ListenAndServe(*direct, mux)) }()
	}
	log.Printf("demo home network running: WireGuard UDP %d, phone configuration in %s", *port, *confOut)
	select {}
}

// forward reicht Verbindungen aus dem Tunnel an einen Dienst auf dem PC weiter.
func forward(ln net.Listener, target string) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func() {
			defer c.Close()
			r, err := net.DialTimeout("tcp", target, 5*time.Second)
			if err != nil {
				return
			}
			defer r.Close()
			go func() { io.Copy(r, c); r.Close() }()
			io.Copy(c, r)
		}()
	}
}

func page(html string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, html)
	})
}

// ---------------------------------------------------------------- Shelly

type shelly struct {
	mu    sync.Mutex
	name  string
	on    bool
	timer *time.Timer
}

func (s *shelly) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	s.mu.Lock()
	defer s.mu.Unlock()
	q := r.URL.Query()
	switch r.URL.Path {
	case "/shelly":
		fmt.Fprintf(w, `{"name":%q,"id":"shellyplus1-demo","mac":"000000000000","model":"SNSW-001X16EU","gen":2,"app":"Plus1","auth_en":false}`, s.name)
	case "/rpc/Shelly.GetStatus":
		fmt.Fprintf(w, `{"switch:0":{"id":0,"output":%t,"apower":%s}}`, s.on, s.power())
	case "/rpc/Switch.GetStatus":
		fmt.Fprintf(w, `{"id":0,"source":"http","output":%t,"apower":%s}`, s.on, s.power())
	case "/rpc/Switch.Set", "/rpc/Switch.Toggle":
		was := s.on
		if r.URL.Path == "/rpc/Switch.Toggle" {
			s.on = !s.on
		} else {
			s.on = q.Get("on") == "true"
		}
		if s.timer != nil {
			s.timer.Stop()
			s.timer = nil
		}
		var secs float64
		fmt.Sscan(q.Get("toggle_after"), &secs)
		if secs > 0 {
			target := !s.on
			s.timer = time.AfterFunc(time.Duration(secs*float64(time.Second)), func() {
				s.mu.Lock()
				s.on = target
				s.mu.Unlock()
			})
		}
		fmt.Fprintf(w, `{"was_on":%t}`, was)
	default:
		http.NotFound(w, r)
	}
}

func (s *shelly) power() string {
	if s.on {
		return "1840.5"
	}
	return "0.0"
}

// ---------------------------------------------------------------- Camper

func camperHandler(html string) http.Handler {
	start := time.Now()
	mux := http.NewServeMux()
	mux.Handle("/", page(html))
	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		// leichte Bewegung in den Werten, damit die Seite lebendig wirkt
		t := time.Since(start).Seconds()
		solar := 310 + int(40*sinApprox(t/7))
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"soc":87,"volt":13.3,"solar":%d,"load":%d,"fresh":64,"grey":22,"inside":21.4,"outside":8.9}`,
			solar, 46+int(6*sinApprox(t/3)))
	})
	return mux
}

func sinApprox(x float64) float64 {
	// Dreieckswelle -1..1 reicht fuer die Anzeige
	x = x - float64(int(x/4))*4
	if x < 2 {
		return x - 1
	}
	return 3 - x
}

const routerPage = `<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Router</title>
<style>body{margin:0;font-family:system-ui,sans-serif;background:#eef1f4;color:#1c2833}
header{background:#1f5fa8;color:#fff;padding:18px 20px;font-size:20px;font-weight:600}
.card{background:#fff;margin:14px;border-radius:12px;padding:16px;box-shadow:0 1px 3px #0002}
.row{display:flex;justify-content:space-between;padding:8px 0;border-bottom:1px solid #eee}
.ok{color:#2e7d32;font-weight:600}</style></head><body>
<header>Heimnetz-Router</header>
<div class="card"><div class="row"><span>Internet</span><span class="ok">verbunden</span></div>
<div class="row"><span>WLAN</span><span class="ok">an · 2,4 + 5 GHz</span></div>
<div class="row"><span>Geräte im Heimnetz</span><span>14</span></div>
<div class="row"><span>WireGuard-Zugang</span><span class="ok">1 aktiv</span></div></div></body></html>`

const camperPage = `<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Wohnmobil</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#10161c;color:#e8eef3}
header{padding:22px 20px 56px;font-size:22px;font-weight:700}
.sub{padding:0 20px 14px;color:#8fa3b3;font-size:14px}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;padding:0 14px 20px}
.tile{background:#1b242d;border-radius:16px;padding:16px}
.label{color:#8fa3b3;font-size:13px}.val{font-size:28px;font-weight:700;margin-top:6px}
.unit{font-size:15px;color:#8fa3b3;font-weight:500}
.bar{height:8px;background:#2a3642;border-radius:4px;margin-top:10px;overflow:hidden}
.bar i{display:block;height:100%;background:#f2b544}
.wide{grid-column:1/3}
</style></head><body>
<header>Wohnmobil</header>
<div class="grid">
<div class="tile wide"><div class="label">Batterie</div><div class="val"><span id="soc">87</span> <span class="unit">% · <span id="volt">13,3</span> V</span></div><div class="bar"><i id="socbar" style="width:87%"></i></div></div>
<div class="tile"><div class="label">Solar</div><div class="val"><span id="solar">310</span> <span class="unit">W</span></div></div>
<div class="tile"><div class="label">Verbrauch</div><div class="val"><span id="load">46</span> <span class="unit">W</span></div></div>
<div class="tile"><div class="label">Frischwasser</div><div class="val"><span id="fresh">64</span> <span class="unit">%</span></div><div class="bar"><i id="freshbar" style="width:64%;background:#4fa3e0"></i></div></div>
<div class="tile"><div class="label">Grauwasser</div><div class="val"><span id="grey">22</span> <span class="unit">%</span></div><div class="bar"><i id="greybar" style="width:22%;background:#8d99a6"></i></div></div>
<div class="tile"><div class="label">Innen</div><div class="val"><span id="inside">21,4</span> <span class="unit">°C</span></div></div>
<div class="tile"><div class="label">Außen</div><div class="val"><span id="outside">8,9</span> <span class="unit">°C</span></div></div>
</div>
<script>
function de(n,d){return n.toFixed(d).replace('.',',')}
async function tick(){try{const s=await (await fetch('/api/status')).json();
soc.textContent=s.soc;volt.textContent=de(s.volt,1);socbar.style.width=s.soc+'%';
solar.textContent=s.solar;load.textContent=s.load;fresh.textContent=s.fresh;grey.textContent=s.grey;
inside.textContent=de(s.inside,1);outside.textContent=de(s.outside,1)}catch(e){}}
tick();setInterval(tick,2000);
</script></body></html>`

// englisch: Demo-Seiten fuer englische Aufnahmen (Beschriftungen, Dezimalpunkt statt Komma).
var englisch = strings.NewReplacer(
	`lang="de"`, `lang="en"`, "<title>Wohnmobil</title>", "<title>Camper</title>", "<header>Wohnmobil</header>", "<header>Camper</header>",
	"Batterie", "Battery", "Verbrauch", "Load", "Frischwasser", "Fresh water", "Grauwasser", "Grey water",
	"Innen", "Inside", "Außen", "Outside", "13,3", "13.3", "21,4", "21.4", "8,9", "8.9", "n.toFixed(d).replace('.',',')", "n.toFixed(d)",
	"Heimnetz-Router", "Home router", "verbunden", "connected", "an · 2,4 + 5 GHz", "on · 2.4 + 5 GHz",
	"Geräte im Heimnetz", "Devices at home", "WireGuard-Zugang", "WireGuard access", "1 aktiv", "1 active",
)
