"""Keyguard states for screenshots (docs/keyguard.md; written by the Android agent, usable by every app). Off unless
KELO_MOCK_KEYGUARD is set:

    KELO_MOCK_KEYGUARD=off      GET /api/keyguard mode "server": the Security page offers to turn it on (enroll gives a key and a
                                recovery code, finish stores the phone's blob and turns it on)
    KELO_MOCK_KEYGUARD=on       on, this device (id 7) holds a copy, unlocked
    KELO_MOCK_KEYGUARD=locked   on and locked: /api/agent/status says "locked", secrets answer 423 {"code": "locked"}; the blob the
                                app stored earlier (or none) is handed back; unlock or the recovery code opens it

Named "aa_" so it is tried before chat.py, which answers /api/agent/status for every run. Nothing here is a real key.
"""

from __future__ import annotations

import os
import re
import time

MOCK_KEY = "bW9jay1rZXlndWFyZC1rZXktbm90LXJlYWwtMDAwMDA="   # 44 characters like a Fernet key; not a real key
MOCK_CODE = "MQ7KTR4WX2ZPLNB8HVDC3FGJ"
DEVICE = 7

S = {"mode": None, "locked": False, "blob": None, "phones": []}


def _init() -> str:
    m = os.environ.get("KELO_MOCK_KEYGUARD", "")
    if S["mode"] is None and m:
        S["mode"] = "server" if m == "off" else "phone"
        S["locked"] = m == "locked"
        if m in ("on", "locked"):
            S["phones"] = [{"device_id": DEVICE, "name": "Pixel 8 · Kelo", "alg": 1, "created": time.time() - 86400},
                           {"device_id": 9, "name": "Galaxy Tab · Kelo", "alg": 1, "created": time.time() - 3600}]
    return m


def _view() -> dict:
    return {"mode": S["mode"], "locked": S["locked"], "env_key": False, "recovery": S["mode"] == "phone", "phones": S["phones"]}


def route(h, method: str, path: str, q: dict, s) -> bool:
    if not _init():
        return False
    if path == "/api/keyguard" and method == "GET":
        h.send_json(_view())
        return True
    if path == "/api/keyguard/enroll" and method == "POST":
        enabling = S["mode"] != "phone"
        out = {"enrollment_id": "mock-enroll-0001", "key": MOCK_KEY, "enabling": enabling}
        if enabling:
            out["recovery_code"] = MOCK_CODE
        h.send_json(out)
        return True
    if path == "/api/keyguard/enroll/finish" and method == "POST":
        b = h.body()
        S["blob"] = b.get("blob")
        S["mode"] = "phone"
        S["phones"] = [p for p in S["phones"] if p["device_id"] != DEVICE] + [{"device_id": DEVICE, "name": "Pixel 8 · Kelo", "alg": 1, "created": time.time()}]
        h.send_json(_view())
        return True
    if path == "/api/keyguard/blob" and method == "GET":
        if S["blob"] is None:
            h.send_json({"detail": {"code": "not_enrolled"}, "code": "not_enrolled"}, 404)
        else:
            h.send_json({"alg": 1, "blob": S["blob"]})
        return True
    if path in ("/api/keyguard/unlock", "/api/keyguard/recover") and method == "POST":
        b = h.body()
        ok = b.get("key") == MOCK_KEY or re.sub(r"[^A-Z0-9]", "", str(b.get("code", "")).upper()) == MOCK_CODE
        if not ok:
            h.send_json({"detail": "that is not the key", "code": "wrong_key"}, 422)
        else:
            S["locked"] = False
            h.send_json({"ok": True, "locked": False})
        return True
    m = re.fullmatch(r"/api/keyguard/phones/(\d+)", path)
    if m and method == "DELETE":
        if len(S["phones"]) <= 1:
            h.send_json({"detail": "the last phone", "code": "last_phone"}, 409)
        else:
            S["phones"] = [p for p in S["phones"] if p["device_id"] != int(m.group(1))]
            h.send_json(_view())
        return True
    if path == "/api/keyguard/disable" and method == "POST":
        S["mode"], S["phones"], S["blob"] = "server", [], None
        h.send_json(_view())
        return True
    if path == "/api/devices" and method == "GET":
        h.send_json({"devices": [{"id": DEVICE, "name": "Pixel 8 · Kelo", "created_at": time.time() - 86400 * 30, "owner": True}], "current": DEVICE})
        return True
    if S["locked"] and path == "/api/agent/status":
        h.send_json({"busy": False, "locked": True, "agent_name": "Kelo", "pending_approvals": 0, "open_followups": 0})
        return True
    if S["locked"] and (path.startswith("/api/vault") or path.startswith("/api/wallet")):
        h.send_json({"detail": "Kelo is locked", "code": "locked"}, 423)
        return True
    return False
