package wgbridge

import (
	"os"
	"runtime/debug"
)

// SetCrashFile: Go-Abstuerze (panic, fatal error, SIGSYS ...) zusaetzlich in diese
// Datei schreiben, damit die App sie beim naechsten Start anzeigen kann.
// Unter Android landet stderr sonst nur im Logcat, das der Nutzer nicht sieht.
func SetCrashFile(path string) error {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	// SetCrashOutput dupliziert den Dateideskriptor, f wird danach nicht mehr gebraucht
	defer f.Close()
	return debug.SetCrashOutput(f, debug.CrashOptions{})
}
