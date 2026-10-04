package wgbridge

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"
)

// wgConfig ist die geparste Form einer wg-quick-Konfiguration, wie sie die
// FRITZ!Box (oder jeder andere WireGuard-Server) als Datei/QR-Code ausgibt.
type wgConfig struct {
	privateKey []byte
	addresses  []netip.Addr
	dns        []netip.Addr
	mtu        int
	peers      []wgPeer
}

type wgPeer struct {
	publicKey    []byte
	presharedKey []byte
	endpoint     string // host:port, host evtl. DynDNS-Name
	allowedIPs   []netip.Prefix
	keepalive    int // Sekunden; 0 = aus, -1 = nicht angegeben (-> Standard)
}

func decodeKey(s string) ([]byte, error) {
	k, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil || len(k) != 32 {
		return nil, errors.New("invalid key")
	}
	return k, nil
}

func splitList(v string) []string {
	var out []string
	for _, p := range strings.Split(v, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func parseConfig(text string) (*wgConfig, error) {
	cfg := &wgConfig{mtu: 1280}
	section := ""
	var peer *wgPeer
	// Editoren unter Windows schreiben gern ein UTF-8-BOM vor "[Interface]"
	text = strings.TrimPrefix(text, "\uFEFF")
	sc := bufio.NewScanner(strings.NewReader(text))
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if i := strings.IndexByte(line, '#'); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[] "))
			switch section {
			case "interface":
			case "peer":
				cfg.peers = append(cfg.peers, wgPeer{keepalive: -1})
				peer = &cfg.peers[len(cfg.peers)-1]
			default:
				return nil, fmt.Errorf("unknown section %s", line)
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k = strings.ToLower(strings.TrimSpace(k))
		v = strings.TrimSpace(v)
		var err error
		switch section {
		case "interface":
			switch k {
			case "privatekey":
				cfg.privateKey, err = decodeKey(v)
			case "address":
				for _, a := range splitList(v) {
					p, e := parsePrefixOrAddr(a)
					if e != nil {
						return nil, fmt.Errorf("Address: %v", e)
					}
					cfg.addresses = append(cfg.addresses, p.Addr())
				}
			case "dns":
				// Suchdomains (keine IP) werden ignoriert.
				for _, a := range splitList(v) {
					if ip, e := netip.ParseAddr(a); e == nil {
						cfg.dns = append(cfg.dns, ip)
					}
				}
			case "mtu":
				cfg.mtu, err = strconv.Atoi(v)
				if err == nil && (cfg.mtu < 576 || cfg.mtu > 65535) {
					err = errors.New("out of range")
				}
			}
		case "peer":
			switch k {
			case "publickey":
				peer.publicKey, err = decodeKey(v)
			case "presharedkey":
				peer.presharedKey, err = decodeKey(v)
			case "endpoint":
				peer.endpoint = v
			case "allowedips":
				for _, a := range splitList(v) {
					p, e := parsePrefixOrAddr(a)
					if e != nil {
						return nil, fmt.Errorf("AllowedIPs: %v", e)
					}
					peer.allowedIPs = append(peer.allowedIPs, p.Masked())
				}
			case "persistentkeepalive":
				// wg-quick: "off" oder 0 = aus
				if strings.EqualFold(v, "off") {
					peer.keepalive = 0
				} else {
					peer.keepalive, err = strconv.Atoi(v)
					if err == nil && (peer.keepalive < 0 || peer.keepalive > 65535) {
						err = errors.New("out of range")
					}
				}
			}
		}
		if err != nil {
			return nil, fmt.Errorf("%s: %v", k, err)
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if cfg.privateKey == nil {
		return nil, errors.New("PrivateKey missing")
	}
	if len(cfg.addresses) == 0 {
		return nil, errors.New("Address missing")
	}
	if len(cfg.peers) == 0 {
		return nil, errors.New("no [Peer]")
	}
	for _, p := range cfg.peers {
		if p.publicKey == nil || p.endpoint == "" {
			return nil, errors.New("[Peer] without PublicKey or Endpoint")
		}
		_, port, err := net.SplitHostPort(p.endpoint)
		if err != nil {
			return nil, fmt.Errorf("Endpoint: %v", err)
		}
		if _, err := parsePort(port); err != nil {
			return nil, err
		}
	}
	return cfg, nil
}

// parsePrefixOrAddr: "10.0.0.0/24" oder eine einzelne Adresse (= /32 bzw. /128).
func parsePrefixOrAddr(s string) (netip.Prefix, error) {
	if p, err := netip.ParsePrefix(s); err == nil {
		return p, nil
	}
	ip, err := netip.ParseAddr(s)
	if err != nil {
		return netip.Prefix{}, err
	}
	return netip.PrefixFrom(ip, ip.BitLen()), nil
}

// resolveEndpoint loest DynDNS-Namen (z. B. xyz.myfritz.net) ueber das normale
// Netz auf. IPv4 bevorzugt, weil viele Mobilnetze kein IPv6 zum Ziel routen.
func resolveEndpoint(ep string) (string, error) {
	host, port, err := net.SplitHostPort(ep)
	if err != nil {
		return "", fmt.Errorf("Endpoint: %v", err)
	}
	pn, err := parsePort(port)
	if err != nil {
		return "", err
	}
	if ip, err := netip.ParseAddr(host); err == nil {
		return netip.AddrPortFrom(ip, pn).String(), nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil || len(ips) == 0 {
		return "", fmt.Errorf("%s cannot be resolved", host)
	}
	best := ips[0]
	for _, ip := range ips {
		if ip.Unmap().Is4() {
			best = ip.Unmap()
			break
		}
	}
	return netip.AddrPortFrom(best, pn).String(), nil
}

func parsePort(p string) (uint16, error) {
	n, err := strconv.Atoi(p)
	if err != nil || n < 1 || n > 65535 {
		return 0, fmt.Errorf("Endpoint: invalid port %q", p)
	}
	return uint16(n), nil
}

// endpointsUAPI: nur die Endpoints der Peers neu aufgeloest (DynDNS), fuer
// einen laufenden Tunnel. Schluessel und AllowedIPs bleiben unberuehrt.
func (c *wgConfig) endpointsUAPI() (string, error) {
	var b strings.Builder
	for _, p := range c.peers {
		ep, err := resolveEndpoint(p.endpoint)
		if err != nil {
			return "", err
		}
		fmt.Fprintf(&b, "public_key=%s\nupdate_only=true\nendpoint=%s\n", hex.EncodeToString(p.publicKey), ep)
	}
	return b.String(), nil
}

// nudgeTarget: eine Adresse, die sicher durch den Tunnel geht (erste
// nicht-Default-AllowedIP, sonst der DNS-Server im Tunnel).
func (c *wgConfig) nudgeTarget() (netip.Addr, bool) {
	for _, p := range c.peers {
		for _, a := range p.allowedIPs {
			if a.Bits() == 0 {
				continue
			}
			if a.Bits() == a.Addr().BitLen() {
				return a.Addr(), true
			}
			return a.Addr().Next(), true // Netzadresse + 1, meist der Router
		}
	}
	for _, d := range c.dns {
		for _, p := range c.peers {
			for _, a := range p.allowedIPs {
				if a.Contains(d) {
					return d, true
				}
			}
		}
	}
	return netip.Addr{}, false
}

// uapi baut die Konfiguration im WireGuard-UAPI-Format (Schluessel hex).
func (c *wgConfig) uapi() (string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\n", hex.EncodeToString(c.privateKey))
	for _, p := range c.peers {
		ep, err := resolveEndpoint(p.endpoint)
		if err != nil {
			return "", err
		}
		fmt.Fprintf(&b, "public_key=%s\n", hex.EncodeToString(p.publicKey))
		if p.presharedKey != nil {
			fmt.Fprintf(&b, "preshared_key=%s\n", hex.EncodeToString(p.presharedKey))
		}
		fmt.Fprintf(&b, "endpoint=%s\n", ep)
		ka := p.keepalive
		if ka < 0 {
			ka = 25 // nicht angegeben: NAT im Mobilnetz offen halten
		}
		fmt.Fprintf(&b, "persistent_keepalive_interval=%d\n", ka)
		b.WriteString("replace_allowed_ips=true\n")
		for _, a := range p.allowedIPs {
			fmt.Fprintf(&b, "allowed_ip=%s\n", a.String())
		}
	}
	return b.String(), nil
}

// covers: liegt ip in den AllowedIPs eines Peers? Eine Default-Route (/0)
// zaehlt bewusst NICHT - sonst liefe jeder Seitenaufruf durch den Tunnel.
func (c *wgConfig) covers(ip netip.Addr) bool {
	return c.coverBits(ip) >= 0
}

// overlaps: ueberschneiden sich die AllowedIPs (ohne /0) zweier Konfigurationen?
func (c *wgConfig) overlaps(o *wgConfig) bool {
	for _, p := range c.peers {
		for _, a := range p.allowedIPs {
			if a.Bits() == 0 {
				continue
			}
			for _, q := range o.peers {
				for _, b := range q.allowedIPs {
					if b.Bits() > 0 && a.Overlaps(b) {
						return true
					}
				}
			}
		}
	}
	return false
}

// coverBits: Laenge des laengsten AllowedIPs-Praefixes, das ip enthaelt
// (ohne /0); -1 = keins.
func (c *wgConfig) coverBits(ip netip.Addr) int {
	best := -1
	for _, p := range c.peers {
		for _, a := range p.allowedIPs {
			if a.Bits() > 0 && a.Bits() > best && a.Contains(ip) {
				best = a.Bits()
			}
		}
	}
	return best
}
