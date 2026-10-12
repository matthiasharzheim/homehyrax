package wgbridge

import (
	"context"
	"io"
	"net"
	"sync"
	"time"
)

// Portweiterleitung fuer Kameras: der Videoplayer (RTSP) kann keinen
// HTTP-Proxy, also bekommt er einen lokalen Port, dessen Verbindungen ueber
// denselben Wegweiser laufen wie der Proxy (Route/AllowedIPs -> Tunnel,
// sonst direkt). Nur 127.0.0.1 und nur, solange die Kamera angezeigt wird;
// die Kamera verlangt ihr eigenes Passwort.

type forward struct {
	ln    net.Listener
	mu    sync.Mutex
	conns map[net.Conn]struct{}
	active int // laufende Verbindungen (hoechstens 1)
}

var (
	fwdMu    sync.Mutex
	forwards = map[int]*forward{}
)

// Forward oeffnet einen lokalen Port (127.0.0.1, zufaellig), der jede
// Verbindung an target ("host:port") weiterreicht. Rueckgabe: der Port.
// Beenden mit StopForward.
func Forward(target string) (int, error) {
	if _, _, err := net.SplitHostPort(target); err != nil {
		return 0, err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	f := &forward{ln: ln, conns: map[net.Conn]struct{}{}}
	port := ln.Addr().(*net.TCPAddr).Port
	fwdMu.Lock()
	forwards[port] = f
	fwdMu.Unlock()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go f.serve(c, target)
		}
	}()
	return port, nil
}

func (f *forward) track(c net.Conn, on bool) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.conns == nil { // schon gestoppt
		return false
	}
	if on {
		f.conns[c] = struct{}{}
	} else {
		delete(f.conns, c)
	}
	return true
}

func (f *forward) serve(client net.Conn, target string) {
	defer client.Close()
	// nur eine Verbindung gleichzeitig (der Player der App): 127.0.0.1 erreicht jede App auf dem Handy,
	// eine zweite Verbindung bekommt kein Bild
	busy := true
	for i := 0; i < 10 && busy; i++ { // bis 500 ms warten: der Player baut evtl. gerade neu auf
		f.mu.Lock()
		if busy = f.active > 0; !busy {
			f.active++
		}
		f.mu.Unlock()
		if busy {
			time.Sleep(50 * time.Millisecond)
		}
	}
	if busy {
		return
	}
	defer func() { f.mu.Lock(); f.active--; f.mu.Unlock() }()
	if !f.track(client, true) {
		return
	}
	defer f.track(client, false)
	remote, err := dialRouted(context.Background(), "tcp", target)
	if err != nil {
		return
	}
	defer remote.Close()
	if !f.track(remote, true) {
		return
	}
	defer f.track(remote, false)
	// Endet eine Richtung, wird die ganze Verbindung geschlossen (RTSP kennt
	// kein halbes Schliessen; so bleibt nichts haengen).
	done := make(chan struct{})
	go func() {
		io.Copy(remote, client)
		remote.Close()
		close(done)
	}()
	io.Copy(client, remote)
	client.Close()
	remote.Close()
	<-done
}

// StopForward schliesst den Port und alle offenen Verbindungen darueber.
func StopForward(port int) {
	fwdMu.Lock()
	f, ok := forwards[port]
	delete(forwards, port)
	fwdMu.Unlock()
	if !ok {
		return
	}
	f.ln.Close()
	f.mu.Lock()
	conns := f.conns
	f.conns = nil
	f.mu.Unlock()
	for c := range conns {
		c.Close()
	}
}
