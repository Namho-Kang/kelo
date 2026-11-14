"""Goals and connectors for the mock server: a goal workspace with numbers, documents and its schedules; connectors with every way
of connecting (a code, a sign-in page, Google, a mailbox, a key, a site); the mailbox services in the server's order (mail.py);
the Google sign-in beta request (beta.py). Shapes follow src/assistant/server.py."""

from __future__ import annotations

import hashlib
import json
import os
import re
import time
from pathlib import Path

NOW = time.time()
_BETA = {"status": "none", "email": "", "products": [], "at": None, "open": False, "can_request": True}
_FLOWS: dict[str, dict] = {}
_MAIL_ACCOUNTS: list[dict] = []


# the server's own service icons (src/assistant/connector_icons, connector_icons.py), served the same way
_ICONS = Path(__file__).resolve().parents[4] / "server" / "src" / "assistant" / "connector_icons"


def _icon_manifest() -> dict:
    try:
        return json.loads((_ICONS / "manifest.json").read_text())
    except (OSError, ValueError):
        return {"icons": {}, "connectors": {}, "mail": {}}


def _icon_fields(key: str | None) -> dict:
    m = _icon_manifest()
    if not key or key not in m["icons"]:
        return {}
    tag = lambda n: hashlib.sha256((_ICONS / f"{n}.png").read_bytes()).hexdigest()[:16]
    return {"icon": f"/api/connectors/icons/{key}.png?v={tag(key)}", "icon_small": f"/api/connectors/icons/{key}-128.png?v={tag(key + '-128')}",
            "icon_color": m["icons"][key].get("color", "")}


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _connectors(s) -> list[dict]:
    ko = _ko(s)
    rows = [
        # id, name, status, connect, featured, extra
        ("gmail", "Gmail", "available", "mail", True, {"connect_state": "pending_review"}),
        ("google_calendar", "Google Calendar", "available", "google", True, {"connect_state": "pending_review",
                                                                            "fallback": {"connect": "key", "via": "google", "item": "google-sa", "blurb": ""}}),
        ("github", "GitHub", "available", "device", True, {"via": "github"}),
        ("notion", "Notion", "connected", "mcp", True, {"item": "mcp-notion", "account": "me@example.com"}),
        ("slack", "Slack", "available", "mcp", True, {}),
        ("imap_email", "메일 (IMAP)" if ko else "Mail (IMAP)", "available", "mail", True, {}),
        ("openweather", "OpenWeather", "available", "key", False, {"via": "openweather"}),
        ("coupang", "Coupang", "browser", "browser", False, {"site": "https://www.coupang.com"}),
        ("linear", "Linear", "available", "mcp", False, {}),
        ("figma", "Figma", "available", "mcp", False, {}),
        ("zoom", "Zoom", "available", "device", False, {}),
        ("todoist", "Todoist", "available", "mcp", False, {}),
        ("spotify", "Spotify", "available", "mcp", False, {}),
    ]
    if os.environ.get("KELO_MOCK_CALDAV"):
        # the server's "CalDAV Calendar" connector (catalog.py / connect_ways.py), for the iPhone's CalDAV form walk only
        rows.insert(0, ("caldav_calendar", "CalDAV 캘린더" if ko else "CalDAV Calendar", "available", "caldav", True, {"via": "caldav_calendar"}))
    desc = "메일을 읽고 정리하고, 승인하면 보내요." if ko else "Reads and sorts your mail, and sends when you approve."
    out = []
    for k, (i, n, st, c, f, extra) in enumerate(rows):
        row = {"id": i, "name": n, "description": desc, "category": "", "status": st, "connect": c, "item": "", "via": "",
               "featured": f, "rank": k}
        row.update(extra)
        row.update(_icon_fields(_icon_manifest()["connectors"].get(i)))
        out.append(row)
    return out


def route(h, method: str, path: str, q: dict, s) -> bool:
    ko = _ko(s)
    m = re.fullmatch(r"/api/connectors/icons/([a-z0-9_]+(?:-128)?)\.png", path)
    if m and method == "GET":
        f = _ICONS / f"{m[1]}.png"
        if not f.is_file():
            h.send_json({"detail": "not found"}, 404)
            return True
        data = f.read_bytes()
        h.send_response(200)
        h.send_header("Content-Type", "image/png")
        h.send_header("Content-Length", str(len(data)))
        h.send_header("Cache-Control", "public, max-age=86400")
        h.end_headers()
        h.wfile.write(data)
        return True
    if path == "/api/connectors" and method == "GET":
        h.send_json(_connectors(s))
        return True
    if path == "/api/credentials" and method == "GET":
        h.send_json([{"name": "mcp-notion", "label": "Notion · me@example.com", "kind": "api", "has_secret": True}])
        return True
    if path == "/api/mail/providers":
        # the server's order (mail.py PROVIDERS): Gmail, iCloud, Yahoo, Naver, Daum
        names = [("gmail", "Gmail"), ("icloud", "iCloud Mail"), ("yahoo", "Yahoo Mail"), ("naver", "네이버 메일" if ko else "Naver Mail"),
                 ("daum", "다음 메일" if ko else "Daum Mail")]
        h.send_json({"providers": [{"id": i, "name": n, "help": ["Create an app password.", "Paste it below."], **_icon_fields(_icon_manifest()["mail"].get(i))} for i, n in names],
                     "accounts": _MAIL_ACCOUNTS, "google_web": False})
        return True
    if path == "/api/mail/accounts" and method == "POST":
        b = h.body()
        _MAIL_ACCOUNTS.append({"name": "mail-" + b.get("provider", ""), "provider": b.get("provider", ""), "label": b.get("address", ""),
                               "address": b.get("address", "")})
        h.send_json({"ok": True})
        return True
    if path == "/api/google/beta":
        if method == "POST":
            b = h.body()
            _BETA.update({"status": "requested", "email": b.get("email", ""), "products": b.get("products", []), "at": time.time()})
        h.send_json(_BETA)
        return True
    if path == "/api/google/calendar":
        h.send_json({"available": False, "reason": "", "pending_review": True, "accounts": []})
        return True
    if path == "/api/oauth/providers":
        h.send_json([{"id": "github", "item": "github-api", "name": "GitHub", "has_client_id": True, "connected": False, "account": "",
                      "settings_url": "https://github.com/settings/applications", "web": False,
                      "help": [], "scopes": [{"id": "repo", "label": "repo", "detail": "Read and write your repositories"},
                                             {"id": "read", "label": "read:user", "detail": "Read your profile"}]}])
        return True
    m = re.fullmatch(r"/api/oauth/([^/]+)/start", path)
    if m and method == "POST":
        fid = f"flow{len(_FLOWS) + 1}"
        _FLOWS[fid] = {"id": fid, "provider": m.group(1), "status": "pending", "message": "", "account": "", "user_code": "WDJB-MJHT",
                       "verification_uri": "https://github.com/login/device", "expires_in": 900}
        h.send_json(_FLOWS[fid])
        return True
    m = re.fullmatch(r"/api/oauth/flows/([^/]+)", path)
    if m:
        f = _FLOWS.get(m.group(1))
        if method == "DELETE" and f:
            f["status"] = "cancelled"
        h.send_json(f or {"detail": "not found"}, 200 if f else 404)
        return True
    # /api/oauth-apps/* (the owner's sign-in app settings) is answered by ops.py: this mock is the owner's server (my.py)
    m = re.fullmatch(r"/api/keys/([^/]+)", path)
    if m and method == "GET":
        h.send_json({"id": m.group(1), "name": "OpenWeather", "item": "openweather", "help": ["Sign in at openweathermap.org.", "Copy your API key."],
                     "checked": True, "connected": False, "label": "",
                     "fields": [{"id": "key", "label": "API key", "secret": True, "placeholder": "API key"}]})
        return True
    m = re.fullmatch(r"/api/goals/(\d+)", path)
    if m and method == "GET":
        g = next((x for x in s.goals if x["id"] == int(m.group(1))), s.goals[0])
        t = s.t
        h.send_json({
            "goal": g,
            "facts": [{"key": k, "value": v} for k, v in t["facts"]] + [{"key": "예산" if ko else "Budget", "skipped": True}],
            "steps": [{"id": i + 1, "title": a, "status": b, "owner": o, "due": "2026-11-20" if i == 3 else None,
                       "detail": "주 3회 이상" if (ko and i == 2) else ""} for i, (a, b, o) in enumerate(t["steps"])],
            "metrics": [{"id": 1, "name": "걸음 수" if ko else "Steps", "unit": "보" if ko else "steps", "start": 3000, "target": 8000,
                         "values": [{"value": v, "at": NOW - 86400 * (6 - i)} for i, v in enumerate([3000, 4200, 3900, 5600, 6100, 6400])]}],
            "documents": [{"id": 1, "path": "uploads/walking-routes.md", "title": "산책 코스 정리" if ko else "Walking routes", "exists": True}],
            "schedules": [{"id": 2, "name": "주간 걸음 수 확인" if ko else "Weekly step check", "description": "매주 일요일 20:00" if ko else "Sundays at 8 PM",
                           "enabled": True}],
            "log": [{"id": 1, "at": NOW - 86400, "text": t["steps"][1][0]}, {"id": 2, "at": NOW - 86400 * 3, "text": t["steps"][0][0]}],
        })
        return True
    return False
