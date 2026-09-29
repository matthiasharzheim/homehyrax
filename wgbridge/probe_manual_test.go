package wgbridge

import (
	"encoding/json"
	"os"
	"testing"
	"time"
)

// Manuell: TS_PROBE=1 go test -run TestProbeRealControl -v .  (echter controlplane.tailscale.com)
func TestProbeRealControl(t *testing.T) {
	if os.Getenv("TS_PROBE") == "" {
		t.Skip("nur manuell")
	}
	// wie auf Android: Schnittstellen von aussen melden
	SetInterfaces("lo\t1\t65536\tul\t127.0.0.1/8\neth0\t2\t1500\tum\t10.0.0.5/24")
	t.Cleanup(func() { SetInterfaces("") }) // zurueck zum Standardweg (net.Interfaces)
	if err := StartTailscale("p", t.TempDir(), "{}", "homehyrax-probe"); err != nil {
		t.Fatal(err)
	}
	defer StopAll()
	for i := 0; i < 40; i++ {
		var s tsStatus
		json.Unmarshal([]byte(TailscaleStatus("p")), &s)
		t.Logf("%s url=%v err=%s", s.State, s.AuthURL != "", s.Error)
		if s.AuthURL != "" {
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Log(TailscaleLog("p"))
	t.Fatal("keine URL")
}
