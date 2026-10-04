package wgbridge

import (
	"context"
	"crypto/md5"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// requestTransport: eigener Transport ohne Keep-Alive. net/http wiederholt
// eine GET-Anfrage selbsttaetig, wenn eine wiederverwendete Verbindung
// unterwegs abbricht - bei einem Schaltbefehl (Garagentor-Impuls) waere das
// ein zweites Ausloesen. Jede Anfrage bekommt deshalb eine frische Verbindung.
var requestTransport = &http.Transport{
	Proxy:               nil,
	DialContext:         dialRouted,
	DisableKeepAlives:   true,
	TLSHandshakeTimeout: 15 * time.Second,
}

// Request fuehrt eine einzelne HTTP-Anfrage aus - ueber denselben Wegweiser
// wie der Proxy (Route/AllowedIPs -> Tunnel, sonst direkt). Fuer
// "Schaltflaechen" (z. B. Shelly-Impuls fuer das Garagentor).
//
// user/pass optional. Die erste Anfrage geht bewusst OHNE Anmeldung; erst
// nach 401 mit passender Challenge folgt ein zweiter Versuch mit
// Zugangsdaten, und nur an dieselbe Adresse (Schema, Host, Port) wie url -
// nach einer Weiterleitung auf ein anderes Geraet gehen keine Zugangsdaten mit.
// authMode: "digest" (Shelly Gen2+, nie Basic), "basic" (Shelly Gen1),
// "" = wie vom Geraet verlangt (Digest oder Basic, Basic aber nur bei
// ausdruecklicher Basic-Challenge).
// Rueckgabe: "<HTTP-Status>\n<Anfang der Antwort>".
func Request(method, rawURL, user, pass, authMode string, timeoutMs int) (string, error) {
	switch authMode {
	case "", "digest", "basic":
	default:
		return "", errors.New("invalid auth mode " + authMode)
	}
	orig, err := url.Parse(rawURL)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	client := &http.Client{
		Transport: requestTransport,
		// Zugangsdaten nie an ein anderes Ziel weiterreichen
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 10 {
				return errors.New("too many redirects")
			}
			if !sameOrigin(orig, req.URL) {
				req.Header.Del("Authorization")
			}
			return nil
		},
	}

	do := func(target, auth string, basic bool) (*http.Response, error) {
		req, err := http.NewRequestWithContext(ctx, method, target, nil)
		if err != nil {
			return nil, err
		}
		req.Close = true
		if auth != "" {
			req.Header.Set("Authorization", auth)
		} else if basic {
			req.SetBasicAuth(user, pass)
		}
		return client.Do(req)
	}

	resp, err := do(rawURL, "", false)
	if err != nil {
		return "", err
	}
	if resp.StatusCode == http.StatusUnauthorized && (user != "" || pass != "") {
		final := resp.Request.URL // nach Weiterleitungen die zuletzt angefragte Adresse
		digest, basic := challenges(resp.Header.Values("WWW-Authenticate"))
		var retry func() (*http.Response, error)
		if sameOrigin(orig, final) {
			switch {
			case digest != "" && authMode != "basic":
				auth, derr := digestAuth(digest, method, final.RequestURI(), user, pass)
				if derr != nil {
					resp.Body.Close()
					return "", derr
				}
				retry = func() (*http.Response, error) { return do(final.String(), auth, false) }
			case basic && authMode != "digest":
				retry = func() (*http.Response, error) { return do(final.String(), "", true) }
			}
		}
		// ohne passende Challenge (oder fremdes Ziel): die 401 geht so an die App
		if retry != nil {
			resp.Body.Close()
			resp, err = retry()
			if err != nil {
				return "", err
			}
			// zweites 401 = falsches Passwort (Status geht so an die App)
		}
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
	return fmt.Sprintf("%d\n%s", resp.StatusCode, body), nil
}

// challenges sucht in den WWW-Authenticate-Kopfzeilen eine Digest-Challenge
// (ganzer Text) und eine Basic-Challenge.
func challenges(values []string) (digest string, basic bool) {
	for _, v := range values {
		v = strings.TrimSpace(v)
		low := strings.ToLower(v)
		switch {
		case digest == "" && strings.HasPrefix(low, "digest "):
			digest = v
		case low == "basic" || strings.HasPrefix(low, "basic "):
			basic = true
		}
	}
	return
}

// sameOrigin: gleiches Schema, gleicher Host, gleicher Port (Standardport
// ergaenzt).
func sameOrigin(a, b *url.URL) bool {
	port := func(u *url.URL) string {
		if p := u.Port(); p != "" {
			return p
		}
		if strings.EqualFold(u.Scheme, "https") {
			return "443"
		}
		return "80"
	}
	return strings.EqualFold(a.Scheme, b.Scheme) &&
		strings.EqualFold(a.Hostname(), b.Hostname()) &&
		port(a) == port(b)
}

// digestAuth beantwortet eine Digest-Challenge (RFC 7616, qop=auth).
func digestAuth(chal, method, uri, user, pass string) (string, error) {
	p := map[string]string{}
	for _, part := range splitParams(chal[len("digest "):]) {
		k, v, ok := strings.Cut(part, "=")
		if ok {
			p[strings.ToLower(strings.TrimSpace(k))] = strings.Trim(strings.TrimSpace(v), `"`)
		}
	}
	var h func() hash.Hash
	switch strings.ToUpper(p["algorithm"]) {
	case "", "MD5":
		h = md5.New
	case "SHA-256":
		h = sha256.New
	default:
		return "", fmt.Errorf("digest algorithm %s not supported", p["algorithm"])
	}
	hx := func(s string) string { d := h(); d.Write([]byte(s)); return hex.EncodeToString(d.Sum(nil)) }
	cb := make([]byte, 8)
	rand.Read(cb)
	cnonce := hex.EncodeToString(cb)
	nc := "00000001"
	ha1 := hx(user + ":" + p["realm"] + ":" + pass)
	ha2 := hx(method + ":" + uri)
	var resp, qop string
	for _, q := range strings.Split(p["qop"], ",") {
		if strings.TrimSpace(q) == "auth" { // "auth-int" zaehlt nicht
			qop = "auth"
		}
	}
	if qop != "" {
		resp = hx(ha1 + ":" + p["nonce"] + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2)
	} else {
		resp = hx(ha1 + ":" + p["nonce"] + ":" + ha2)
	}
	a := fmt.Sprintf(`Digest username="%s", realm="%s", nonce="%s", uri="%s", response="%s"`,
		quoteEsc(user), quoteEsc(p["realm"]), quoteEsc(p["nonce"]), quoteEsc(uri), resp)
	if alg := p["algorithm"]; alg != "" {
		a += ", algorithm=" + alg
	}
	if qop != "" {
		a += fmt.Sprintf(`, qop=%s, nc=%s, cnonce="%s"`, qop, nc, cnonce)
	}
	if op := p["opaque"]; op != "" {
		a += fmt.Sprintf(`, opaque="%s"`, quoteEsc(op))
	}
	return a, nil
}

// quoteEsc maskiert " und \ fuer einen quoted-string im Header.
func quoteEsc(s string) string {
	return strings.NewReplacer(`\`, `\\`, `"`, `\"`).Replace(s)
}

// splitParams trennt a="x, y", b=z an Kommas ausserhalb von Anfuehrungszeichen.
func splitParams(s string) []string {
	var out []string
	var cur strings.Builder
	quoted := false
	for _, r := range s {
		switch {
		case r == '"':
			quoted = !quoted
			cur.WriteRune(r)
		case r == ',' && !quoted:
			out = append(out, cur.String())
			cur.Reset()
		default:
			cur.WriteRune(r)
		}
	}
	return append(out, cur.String())
}
