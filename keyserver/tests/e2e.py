#!/usr/bin/env python3
"""Ende-zu-Ende-Test gegen einen laufenden Schluesseldienst – macht dieselben Schritte wie die App
(Einladung anlegen, Geraet binden, Schluessel holen, sperren, freigeben, loeschen) und raeumt danach auf.
Funktioniert nur, solange auf dem Server Play Integrity aus ist.

Aufruf: python keyserver/tests/e2e.py https://camperflower.de/keys/v1/
Braucht: pip install requests cryptography
"""
import base64
import hashlib
import os
import sys
import time

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


class Device:
    def __init__(self):
        self.priv = ec.generate_private_key(ec.SECP256R1())
        self.pub = self.priv.public_key().public_bytes(serialization.Encoding.DER,
                                                       serialization.PublicFormat.SubjectPublicKeyInfo)

    def signed(self, action: str, iid: str, **extra) -> dict:
        ts, nonce = int(time.time()), b64u(os.urandom(16))
        sig = self.priv.sign(f"HH1|{action}|{iid}|{ts}|{nonce}".encode(), ec.ECDSA(hashes.SHA256()))
        return {"id": iid, "ts": ts, "nonce": nonce, "sig": b64u(sig), **extra}


def main():
    base = (sys.argv[1] if len(sys.argv) > 1 else "https://camperflower.de/keys/v1/").rstrip("/") + "/"
    s = requests.Session()
    s.headers["User-Agent"] = "HomeHyrax-E2E"
    fehler = []

    def call(path, body=None):
        r = s.post(base + path, json=body, timeout=20) if body is not None else s.get(base + path, timeout=20)
        return r.status_code, r.json()

    def check(name, ok, got):
        print(("ok    " if ok else "FEHLER"), name, "" if ok else got)
        if not ok:
            fehler.append(name)

    st, r = call("health")
    check("health", st == 200 and r.get("ok"), r)
    if r.get("integrity"):
        sys.exit("Play Integrity ist an – dieser Test braucht Integrity aus")

    owner = b64u(os.urandom(32))
    st, inv = call("create", {"owner": owner, "ts": int(time.time()), "nonce": b64u(os.urandom(16)),
                              "expires": int(time.time()) + 3600})
    check("create", st == 200 and len(inv.get("id", "")) == 22, inv)
    iid = inv["id"]
    try:
        dev = Device()
        st, r = call("bind", dev.signed("bind", iid, bind=inv["bind"], pub=b64u(dev.pub)))
        check("bind", st == 200 and r.get("ok"), r)
        st, r = call("key", dev.signed("key", iid))
        check("key = Schluessel aus create", st == 200 and r.get("key") == inv["key"], r)
        other = Device()
        st, r = call("key", other.signed("key", iid))
        check("fremdes Geraet abgelehnt", st == 403 and r.get("error") == "bad_signature", r)
        body = dev.signed("key", iid)
        call("key", body)
        st, r = call("key", body)
        check("Wiederholung abgelehnt", st == 409 and r.get("error") == "replay", r)
        st, r = call("manage", {"owner": owner, "id": iid, "action": "block"})
        check("sperren", st == 200, r)
        st, r = call("key", dev.signed("key", iid))
        check("gesperrt", st == 403 and r.get("error") == "blocked" and "key" not in r, r)
        st, r = call("info", {"owner": owner})
        check("info", st == 200 and [i["status"] for i in r.get("invites", [])] == ["blocked"], r)
        st, r = call("manage", {"owner": owner, "id": iid, "action": "activate"})
        st, r = call("key", dev.signed("key", iid))
        check("freigegeben", st == 200 and r.get("key") == inv["key"], r)
        r = s.get(base + "key", timeout=20)
        check("GET abgelehnt", r.status_code == 405, r.text)
        r = s.get(base.rsplit("/v1/", 1)[0] + "/config.php", timeout=20)
        check("config.php nicht erreichbar", "master_key" not in r.text, r.status_code)
        check("keine Zwischenspeicherung", "no-store" in s.post(base + "info", json={"owner": owner}).headers
              .get("Cache-Control", ""), "")
    finally:
        st, r = call("manage", {"owner": owner, "id": iid, "action": "delete"})
        check("loeschen (aufraeumen)", st == 200, r)
    st, r = call("info", {"owner": owner})
    check("nach dem Loeschen leer", st == 200 and r.get("invites") == [], r)

    print("\nOK" if not fehler else f"\n{len(fehler)} FEHLER")
    sys.exit(1 if fehler else 0)


if __name__ == "__main__":
    main()
