"""Secure entries the agent asked for (src/assistant/secret_requests.py), for mock_server.py --secret-entries. (Named so it is
tried before ops.py, which answers the vault and key calls otherwise.)

Three open requests, as GET /api/secret-requests lists them: a service key (OpenWeather, tried once before it is saved) under the
newest agent message, and a website sign-in and a payment card under the first agent message. Endpoints:

- GET  /api/secret-requests                       {"items": [...]} the open ones, oldest first (message_id: the agent message)
- PUT  /api/credentials/{name}/secret             a key for an open key request: saved ({"ok", "checked", "turn_id": null})
- PUT  /api/vault/{name}/login                    a sign-in for an open login request: saved
- POST /api/secret-requests/{id}/card {card_id}   the wallet's card form added a card for the card request: saved
- POST /api/secret-requests/{id}/cancel           cancelled
- GET  /mock/secret-entries                       every request with its state (what was answered)

Values are never kept or logged here: only that something was saved. Off unless KELO_MOCK_SECRET_ENTRIES is set (the flag sets it).
"""

from __future__ import annotations

import os
import re
import time

_REQUESTS: list[dict] | None = None


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _requests(s) -> list[dict]:
    global _REQUESTS
    if _REQUESTS is None:
        agent = [m["id"] for m in s.messages if m["role"] == "assistant"]
        first, last = (agent[0], agent[-1]) if agent else (None, None)
        now = time.time()
        ko = _ko(s)
        _REQUESTS = [
            {"id": "sr-login", "kind": "login", "name": "coupang", "label": "쿠팡" if ko else "Coupang", "hosts": ["www.coupang.com"],
             "reason": "주문 내역을 확인하려면 로그인이 필요해요" if ko else "Signing in lets me check your order history",
             "check": False, "source": "agent", "turn_id": None, "after_id": 0, "state": "open", "message_id": first,
             "created_at": now - 900, "expires_at": now - 900 + 86400, "result": {}},
            {"id": "sr-card", "kind": "card", "name": "", "label": "콘서트 예매" if ko else "Concert tickets", "hosts": ["tickets.interpark.com"],
             "reason": "콘서트 예매" if ko else "Concert tickets", "check": False, "source": "agent", "turn_id": None, "after_id": 0,
             "state": "open", "message_id": first, "created_at": now - 600, "expires_at": now - 600 + 86400, "result": {}},
            {"id": "sr-key", "kind": "key", "name": "openweather", "label": "OpenWeather", "hosts": ["api.openweathermap.org"],
             "reason": "매일 아침 날씨를 알려 드리려면 OpenWeather 키가 필요해요" if ko else "I need an OpenWeather key to send you the weather each morning",
             "check": True, "source": "agent", "turn_id": None, "after_id": 0, "state": "open", "message_id": last,
             "created_at": now - 60, "expires_at": now - 60 + 86400, "result": {}},
        ]
    return _REQUESTS


def _answer(r: dict, state: str, result: dict | None = None) -> None:
    r["state"] = state
    r["result"] = result or {}
    r["answered_at"] = time.time()


def route(h, method: str, path: str, q: dict, s) -> bool:
    if not os.environ.get("KELO_MOCK_SECRET_ENTRIES"):
        return False
    reqs = _requests(s)
    if path == "/api/secret-requests" and method == "GET":
        h.send_json({"items": [r for r in reqs if r["state"] == "open"]})
        return True
    if path == "/mock/secret-entries":
        h.send_json({"items": reqs})
        return True
    m = re.fullmatch(r"/api/secret-requests/([^/]+)/(cancel|card)", path)
    if m and method == "POST":
        r = next((x for x in reqs if x["id"] == m.group(1)), None)
        if r is None:
            h.send_json({"detail": "no such request"}, 404)
            return True
        body = h.body()
        if m.group(2) == "cancel":
            if r["state"] == "open":
                _answer(r, "cancelled")
            h.send_json({"ok": True, "request": r})
            return True
        if r["kind"] != "card":
            h.send_json({"detail": "this request is not for a card"}, 422)
            return True
        _answer(r, "saved", {"card": {"id": str(body.get("card_id", "")), "label": "", "brand": "visa", "last4": "4242", "default": False}})
        h.send_json({"ok": True, "request": r, "turn_id": None})
        return True
    m = re.fullmatch(r"/api/credentials/([^/]+)/secret", path) or re.fullmatch(r"/api/vault/([^/]+)/login", path)
    if m and method == "PUT":
        h.body()   # read and dropped: never kept
        r = next((x for x in reqs if x["name"] == m.group(1) and x["state"] == "open"), None)
        if r is None:
            return False   # not one of these: the other sample endpoints answer
        login = path.endswith("/login")
        _answer(r, "saved", {"mode": "after_success"} if login else {"checked": "ok" if r["check"] else "unchecked"})
        h.send_json({"ok": True, "checked": bool(r["check"]) and not login, "turn_id": None})
        return True
    return False
