//go:build android

package wgbridge

import "tailscale.com/net/netmon"

func setDefaultInterface(name string) { netmon.UpdateLastKnownDefaultRouteInterface(name) }
