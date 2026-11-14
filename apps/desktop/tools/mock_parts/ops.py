"""Sample endpoints for My → Operator, Keychain, Payment cards and Permissions (shapes as src/assistant/server.py answers them).

The operator endpoints answer as for the owner's own device. Beta requests can be approved or declined, reports marked handled,
rules removed, logins added; everything stays in memory.
"""

from __future__ import annotations

import os
import re
import threading
import time

NOW = time.time()
LOCK = threading.Lock()


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


STATE: dict = {}


def _state(s) -> dict:
    with LOCK:
        if not STATE:
            ko = _ko(s)
            STATE.update({
                "beta": [
                    {"id": 1, "created_at": NOW - 3600, "email": "minji@example.com", "products": ["gmail", "calendar"], "status": "requested", "notice": "sent"},
                    {"id": 2, "created_at": NOW - 86400 * 2, "email": "tom@example.com", "products": ["gmail"], "status": "approved", "notice": "sent"},
                ],
                "reports": [
                    {"id": 1, "created_at": NOW - 7200, "reason": "inaccurate", "comment": "날짜가 틀렸어요" if ko else "The date was wrong",
                     "reply": "치과 예약은 목요일이에요." if ko else "Your dentist appointment is on Thursday.",
                     "asked": "치과 언제야?" if ko else "When is the dentist?", "provider": "antigravity-oauth", "model": "gemini-3.8-flash-high",
                     "app_version": "1.4.0", "handled": False},
                ],
                "rules": [
                    {"id": 1, "kind": "browser_action", "kind_label": "", "site": "coupang.com", "created_at": NOW - 86400 * 9, "uses": 4, "last_used": NOW - 86400},
                    {"id": 2, "kind": "login", "kind_label": "", "site": "naver.com", "created_at": NOW - 86400 * 20, "uses": 11, "last_used": NOW - 3600},
                ],
                "history": [
                    {"id": "h1", "tool": "calendar_add_event", "label": "일정 추가" if ko else "Adding an event", "site": "", "detail": "치과 예약" if ko else "Dentist",
                     "decision": "allowed_once", "asked_at": NOW - 5000},
                    {"id": "h2", "tool": "browser_click", "label": "주문하기" if ko else "Placing an order", "site": "coupang.com", "detail": "",
                     "decision": "allowed_always", "asked_at": NOW - 86400 * 9},
                    {"id": "h3", "tool": "email_send", "label": "메일 보내기" if ko else "Sending an email", "site": "", "detail": "team@example.com",
                     "decision": "denied", "asked_at": NOW - 86400 * 3},
                ],
                "vault": [
                    {"name": "login-naver", "label": "네이버" if ko else "Naver", "kind": "login", "hosts": ["naver.com", "nid.naver.com"], "has_secret": True,
                     "auto_allow": False, "last_used": NOW - 3600, "username_hint": "chee***", "last_result": "ok", "last_result_at": NOW - 3600},
                    {"name": "login-coupang", "label": "쿠팡" if ko else "Coupang", "kind": "login", "hosts": ["coupang.com"], "has_secret": True,
                     "auto_allow": True, "last_used": NOW - 86400, "username_hint": "me@example.com", "last_result": "failed", "last_result_at": NOW - 86400},
                    {"name": "openweather", "label": "OpenWeather", "kind": "api", "hosts": ["api.openweathermap.org"], "has_secret": True,
                     "placement": {"type": "query_param", "param": "appid"}, "last_used": NOW - 7200},
                    {"name": "mail-gmail", "label": "Gmail · me@example.com", "kind": "email", "hosts": ["imap.gmail.com"], "has_secret": True,
                     "placement": {"via": "app_password"}, "last_used": NOW - 600, "last_result": "ok", "last_result_at": NOW - 600},
                ],
                "locked": False,
                "cards": [
                    {"id": "c1", "label": "생활비 카드" if ko else "Everyday card", "brand": "visa", "last4": "4242", "exp_month": 8, "exp_year": 2029,
                     "holder": "KIM CHEESE" if ko else "SAM LEE", "created_at": NOW - 86400 * 30, "auto_pay": True, "server_copy": True, "e2e": True,
                     "usable": True, "expired": False, "last_used": NOW - 86400, "default": True},
                    {"id": "c2", "label": "회사 카드" if ko else "Work card", "brand": "mastercard", "last4": "5100", "exp_month": 1, "exp_year": 2030,
                     "holder": "", "created_at": NOW - 86400 * 10, "auto_pay": False, "server_copy": False, "e2e": True, "usable": True, "expired": False},
                    {"id": "c3", "label": "예전 카드" if ko else "Old card", "brand": "amex", "last4": "0005", "exp_month": 2, "exp_year": 2024,
                     "created_at": NOW - 86400 * 900, "auto_pay": False, "server_copy": False, "e2e": True, "usable": False, "expired": True},
                ],
                "paused": False,
                # what schedule 4 (the weekly order) may do without asking, allowed once at setup (GET /api/schedules/4/grants)
                "grants": [
                    {"id": 1, "schedule_id": 4, "schedule_name": "주간 생수 주문" if ko else "Weekly water order",
                     "schedule_when": "매주 월요일 09:00" if ko else "Mondays at 9:00 AM", "kind": "purchase", "site": "coupang.com",
                     "what": "생수 2L 12병" if ko else "12 bottles of water (2 L)", "max_amount": 15000, "currency": "KRW", "monthly_cap": 75000,
                     "runs_per_month": 5, "max_amount_text": "15,000원" if ko else "₩15,000", "monthly_cap_text": "75,000원" if ko else "₩75,000",
                     "spent_this_month": 12900, "created_at": NOW - 86400 * 6, "uses": 1, "last_used": NOW - 86400},
                    {"id": 2, "schedule_id": 4, "schedule_name": "주간 생수 주문" if ko else "Weekly water order",
                     "schedule_when": "매주 월요일 09:00" if ko else "Mondays at 9:00 AM", "kind": "login", "site": "coupang.com",
                     "login": "login-coupang", "login_label": "쿠팡" if ko else "Coupang", "created_at": NOW - 86400 * 6, "uses": 1,
                     "last_used": NOW - 86400},
                ],
                "cloudflare": {"account_id": "0123456789abcdef0123456789abcdef", "zone_id": "fedcba9876543210fedcba9876543210", "zone_name": "kelo.example.net",
                               "has_token": True, "max_names": 500, "used": 37, "enabled": True, "idle_days": 30},
                "firebase": {"enabled": True, "project_id": "kelo-push", "client_email": "push@kelo-push.iam.gserviceaccount.com"},
                "apps": {
                    "github": {"client_id": "Iv1.0a1b2c3d4e5f", "has_secret": True, "is_broker": True, "callback": "https://kelo.example.net/api/github/web/callback"},
                    "google": {"client_id": "1234-abc.apps.googleusercontent.com", "has_secret": True, "is_broker": True,
                               "callback": "https://kelo.example.net/api/google/callback", "open_to_all": False},
                },
            })
        return STATE


REASONS = {"not_auto": ("이 카드는 자동결제가 허용되지 않았어요", "Auto-pay is not allowed for this card"),
           "paused": ("자동결제가 멈춰 있어요", "Auto-pay is paused")}


def _cards(st, ko) -> list:
    """The cards with whether each pays on its own now and why not (as Wallet.cards() adds them). KELO_MOCK_WALLET=ask: the first
    card was also saved to ask each time, so no card pays on its own (the wallet as an owner sees it right after adding one card)."""
    out = []
    for c in st["cards"]:
        auto = bool(c["auto_pay"]) and bool(c["server_copy"])
        if os.environ.get("KELO_MOCK_WALLET") == "ask":
            auto = False
            c = dict(c, auto_pay=False, server_copy=False)
        reason = "" if auto and not st["paused"] else ("paused" if auto else "not_auto")
        c = {k: v for k, v in c.items() if not k.startswith("_")}   # the stored ciphertext is not part of a card
        out.append(dict(c, pays_automatically=not reason, ask_reason=reason,
                        ask_reason_text=REASONS[reason][0 if ko else 1] if reason else ""))
    return out


def _card_payments(st, ko, card_id: str, limit: int, before) -> dict:
    """GET /api/wallet/cards/{id}/payments: the everyday card has a few weeks of orders (a page of 20 and an older page), the work
    card two approved ones, the old card none."""
    card = next((c for c in st["cards"] if c["id"] == card_id), None)
    if card is None:
        return {"items": [], "next_before_id": None}
    shops = [("coupang.com", "생수 2L 12병" if ko else "12 bottles of water (2 L)", 12900, "schedule:4", 41),
             ("kurly.com", "샐러드 3팩" if ko else "Three salad packs", 15800, "chat", 38),
             ("yes24.com", "책 한 권 (가제본)" if ko else "One book (paperback)", 16200, "chat", 35)]
    rows = []
    if card_id == "c1":
        for i in range(26):
            site, what, amount, source, task = shops[i % 3]
            rows.append({"id": 100 + i, "at": NOW - 86400 * (26 - i) - 3600 * (i % 5), "kind": "auto" if source.startswith("schedule") else
                         ("approved" if i % 2 else "auto"), "site": site, "host": "www." + site, "summary": what, "amount": amount,
                         "currency": "KRW", "amount_text": f"{amount:,}원" if ko else f"₩{amount:,}", "card_id": card_id,
                         "card_label": card["label"], "month": "2026-11", "seen": True, "task_id": task + i, "source": source})
    elif card_id == "c2":
        for i, (site, what, amount) in enumerate([("store.steampowered.com", "", 23100), ("ktx.co.kr", "서울→부산 KTX 1장" if ko else "One KTX ticket, Seoul to Busan", 59800)]):
            rows.append({"id": 200 + i, "at": NOW - 86400 * (8 - 3 * i), "kind": "approved", "site": site, "host": site, "summary": what,
                         "amount": amount, "currency": "KRW", "amount_text": f"{amount:,}원" if ko else f"₩{amount:,}", "card_id": card_id,
                         "card_label": card["label"], "month": "2026-11", "seen": True, "task_id": None, "source": "chat"})
    rows.sort(key=lambda r: -r["id"])
    if before:
        rows = [r for r in rows if r["id"] < int(before)]
    page = rows[:max(1, limit)]
    return {"items": page, "next_before_id": page[-1]["id"] if len(rows) > len(page) else None}


def route(h, method: str, path: str, q: dict, s) -> bool:
    st = _state(s)
    ko = _ko(s)
    # ---- operator (the owner's device)
    m = re.fullmatch(r"/api/oauth-apps/(github|google)(/open)?", path)
    if m:
        app = st["apps"][m.group(1)]
        if method == "PUT":
            b = h.body()
            if m.group(2):
                app["open_to_all"] = bool(b.get("open"))
            else:
                app["client_id"] = b.get("client_id", app["client_id"])
                if b.get("client_secret"):
                    app["has_secret"] = True
        h.send_json(app)
        return True
    if path == "/api/operator/status":
        h.send_json({"owner": True, "operator": True})
        return True
    if path == "/api/operator/firebase":
        if method == "DELETE":
            st["firebase"] = {"enabled": False, "project_id": "", "client_email": ""}
        h.send_json(st["firebase"])
        return True
    if path == "/api/operator/cloudflare":
        if method == "PUT":
            b = h.body()
            cf = st["cloudflare"]
            if "api_token" in b:
                cf["has_token"] = bool(b["api_token"])
                cf["enabled"] = bool(b["api_token"])
            for k in ("account_id", "zone_id", "zone_name", "max_names"):
                if k in b:
                    cf[k] = b[k]
        h.send_json(st["cloudflare"])
        return True
    if path == "/api/operator/cloudflare/reclaim":
        h.send_json({"released": ["old-name"]})
        return True
    if path == "/api/operator/beta":
        h.send_json({"requests": st["beta"], "open": sum(1 for r in st["beta"] if r["status"] == "requested"),
                     "mail_from": "support@kelo.example.net", "receiving": True})
        return True
    m = re.fullmatch(r"/api/operator/beta/(\d+)", path)
    if m:
        rid = int(m.group(1))
        if method == "DELETE":
            st["beta"] = [r for r in st["beta"] if r["id"] != rid]
            h.send_json({"ok": True})
            return True
        b = h.body()
        for r in st["beta"]:
            if r["id"] == rid:
                r["status"] = "approved" if b.get("action") == "approve" else "declined"
        h.send_json({"mailed": bool(b.get("email")), "mail_error": ""})
        return True
    if path == "/api/operator/reports":
        items = st["reports"] if "handled" not in q else [r for r in st["reports"] if not r["handled"]]
        h.send_json({"reports": items, "open": sum(1 for r in st["reports"] if not r["handled"]), "keep_days": 365, "receiving": True})
        return True
    m = re.fullmatch(r"/api/operator/reports/(\d+)", path)
    if m:
        rid = int(m.group(1))
        if method == "DELETE":
            st["reports"] = [r for r in st["reports"] if r["id"] != rid]
        else:
            b = h.body()
            for r in st["reports"]:
                if r["id"] == rid:
                    r["handled"] = bool(b.get("handled"))
        h.send_json({"ok": True})
        return True
    # ---- permissions
    if path == "/api/approvals/rules":
        h.send_json({"items": st["rules"]})
        return True
    m = re.fullmatch(r"/api/approvals/rules/(\d+)", path)
    if m and method == "DELETE":
        st["rules"] = [r for r in st["rules"] if r["id"] != int(m.group(1))]
        h.send_json({"ok": True})
        return True
    if path == "/api/approvals/history":
        h.send_json({"items": st["history"], "next_before": None})
        return True
    # ---- keychain
    if path == "/api/vault" and method == "GET":
        items = [dict(v, schedule_grants=[g for g in st["grants"] if g.get("login") == v["name"]]) if v["kind"] == "login" else v
                 for v in st["vault"]]
        h.send_json({"locked": st["locked"], "mail_codes_auto": st.get("mail_codes_auto", True), "items": items, "pending": [],
                     "entered_once": [], "verifying": []})
        return True
    if path == "/api/vault/lock":
        st["locked"] = bool(h.body().get("locked"))
        h.send_json({"locked": st["locked"]})
        return True
    if path == "/api/vault/settings":
        b = h.body()
        if "mail_codes_auto" in b:
            st["mail_codes_auto"] = bool(b["mail_codes_auto"])
        h.send_json({"mail_codes_auto": st.get("mail_codes_auto", True)})
        return True
    if path == "/api/vault/log":
        h.send_json([{"ts": NOW - 3600, "item": "login-naver", "action": "used", "detail": "naver.com", "ok": True},
                     {"ts": NOW - 86400, "item": "login-coupang", "action": "failed", "detail": "coupang.com", "ok": False},
                     {"ts": NOW - 86400 * 3, "item": "openweather", "action": "added", "detail": "", "ok": True}])
        return True
    if path == "/api/vault/logins" and method == "POST":
        b = h.body()
        site = re.sub(r"^https?://", "", b.get("site", "")).split("/")[0] or "example.com"
        name = "login-" + site.split(".")[0]
        st["vault"].append({"name": name, "label": b.get("label") or site, "kind": "login", "hosts": [site], "has_secret": False})
        h.send_json({"name": name})
        return True
    m = re.fullmatch(r"/api/vault/([^/]+)", path)
    if m and method == "PATCH":
        b = h.body()
        for v in st["vault"]:
            if v["name"] == m.group(1):
                if "auto_allow" in b:
                    v["auto_allow"] = bool(b["auto_allow"])
                if b.get("clear_result"):
                    v["last_result"] = None
        h.send_json({"ok": True})
        return True
    m = re.fullmatch(r"/api/vault/([^/]+)/login", path)
    if m and method == "PUT":
        for v in st["vault"]:
            if v["name"] == m.group(1):
                v["has_secret"] = True
                v["username_hint"] = (h.body().get("username") or "")[:3] + "***"
        h.send_json({"ok": True})
        return True
    m = re.fullmatch(r"/api/credentials/([^/]+)(/secret)?", path)
    if m and method in ("DELETE", "PUT"):
        if method == "DELETE":
            st["vault"] = [v for v in st["vault"] if v["name"] != m.group(1)]
        h.send_json({"ok": True})
        return True
    # ---- payment cards
    if path == "/api/wallet":
        cards = _cards(st, ko)
        h.send_json({"paused": st["paused"], "cards": cards, "timezone": "Asia/Seoul", "month": "2026-11",
                     "auto_cards": sum(1 for c in cards if c["pays_automatically"] and c["usable"]),
                     "schedule_grants": [g for g in st["grants"] if g["kind"] == "purchase"]})
        return True
    if path == "/api/wallet/cards" and method == "GET":
        h.send_json({"paused": st["paused"], "cards": _cards(st, ko)})
        return True
    m = re.fullmatch(r"/api/schedules/(\d+)/grants(?:/(\d+))?", path)
    if m:
        sid = int(m.group(1))
        if method == "DELETE" and m.group(2):
            st["grants"] = [g for g in st["grants"] if g["id"] != int(m.group(2))]
            h.send_json({"ok": True})
        else:
            h.send_json({"items": [g for g in st["grants"] if g["schedule_id"] == sid]})
        return True
    if path == "/api/wallet/cards" and method == "POST":
        # a card the app added: the phone's own ciphertext is kept (never a number: an auto-pay card's number is dropped here)
        b = h.body()
        e2e = b.get("e2e") or {}
        n = sum(1 for _ in st["cards"]) + 1
        last4 = str(b.get("last4") or str(b.get("number") or "")[-4:])
        card = {"id": f"c{n + 10}", "label": b.get("label") or f"{str(b.get('brand') or 'card').capitalize()} ••{last4}",
                "brand": b.get("brand") or "visa", "last4": last4, "exp_month": b.get("exp_month"), "exp_year": b.get("exp_year"),
                "holder": b.get("holder") or "", "created_at": time.time(), "auto_pay": bool(b.get("auto_pay")),
                "server_copy": bool(b.get("auto_pay")), "e2e": bool(e2e), "e2e_alg": e2e.get("alg", ""), "e2e_key_id": e2e.get("key_id", ""),
                "usable": True, "expired": False, "default": not st["cards"], "_blob": e2e.get("blob", "")}
        st["cards"].append(card)
        h.send_json({k: v for k, v in card.items() if not k.startswith("_")}, 201)
        return True
    m = re.fullmatch(r"/api/wallet/cards/([^/]+)/blob", path)
    if m:
        card = next((c for c in st["cards"] if c["id"] == m.group(1)), None)
        if card is None or not card.get("_blob"):
            h.send_json({"detail": "this card has no end-to-end copy"}, 404)
        else:
            h.send_json({"blob": card["_blob"], "alg": card.get("e2e_alg", ""), "key_id": card.get("e2e_key_id", "")})
        return True
    m = re.fullmatch(r"/api/wallet/cards/([^/]+)/payments", path)
    if m:
        h.send_json(_card_payments(st, ko, m.group(1), int((q.get("limit") or ["20"])[0]), (q.get("before_id") or [None])[0]))
        return True
    m = re.fullmatch(r"/api/wallet/cards/([^/]+)", path)
    if m and method in ("PATCH", "DELETE"):
        if method == "DELETE":
            gone = next((c for c in st["cards"] if c["id"] == m.group(1)), None)
            rest = [c for c in st["cards"] if c["id"] != m.group(1)]
            nxt = (q.get("new_default") or [None])[0]
            if gone and gone.get("default") and len(rest) >= 2 and nxt not in {c["id"] for c in rest}:
                h.send_json({"detail": "this is the default card: say which card becomes the new default (new_default)"}, 409)
                return True
            st["cards"] = rest
            if gone and gone.get("default") and rest:
                for c in rest:
                    c["default"] = c["id"] == (nxt or rest[0]["id"])
            h.send_json({"ok": True})
            return True
        b = h.body()
        card = next((c for c in st["cards"] if c["id"] == m.group(1)), None)
        if card is None:
            h.send_json({"detail": "not found"}, 404)
            return True
        if "label" in b:
            card["label"] = b["label"]
        if b.get("default") is True:
            for c in st["cards"]:
                c["default"] = c is card
        if b.get("auto_pay") is False:
            card["auto_pay"] = False
            card["server_copy"] = False
        if b.get("auto_pay") is True:
            # the number and CVC the phone opened from its own copy: checked like the server does, then dropped (never kept here)
            if not (str(b.get("number") or "").endswith(card["last4"]) and str(b.get("cvc") or "").isdigit()):
                h.send_json({"detail": "that number is not this card (the last 4 digits differ)"}, 422)
                return True
            card["auto_pay"] = card["server_copy"] = True
        h.send_json({k: v for k, v in card.items() if not k.startswith("_")})
        return True
    if path == "/api/wallet/limits":
        h.send_json({"month": "2026-11", "timezone": "Asia/Seoul", "limits": [
            {"currency": "KRW", "per_payment": 50000, "monthly": 300000, "default_per_payment": 50000, "default_monthly": 300000,
             "custom": False, "spent_this_month": 42000},
            {"currency": "USD", "per_payment": 40, "monthly": 200, "default_per_payment": 40, "default_monthly": 200, "custom": False,
             "spent_this_month": 0}]})
        return True
    if path.startswith("/api/wallet/limits/"):
        h.send_json({"currency": path.rsplit("/", 1)[-1], "per_payment": 0, "monthly": 0})
        return True
    if path == "/api/wallet/pause":
        st["paused"] = bool(h.body().get("paused"))
        h.send_json({"paused": st["paused"]})
        return True
    if path in ("/api/wallet/payments", "/api/wallet/notifications"):
        items = [{"id": 2, "at": NOW - 86400, "kind": "auto", "site": "쿠팡" if ko else "Coupang", "host": "coupang.com",
                  "summary": "생수 2박스" if ko else "Two cases of water", "amount": 18900, "currency": "KRW", "amount_text": "18,900원" if ko else "₩18,900",
                  "card_id": "c1", "card_label": st["cards"][0]["label"] if st["cards"] else "", "month": "2026-11", "seen": path.endswith("payments")},
                 {"id": 1, "at": NOW - 86400 * 4, "kind": "approved", "site": "Steam", "host": "store.steampowered.com", "summary": "",
                  "amount": 23100, "currency": "KRW", "amount_text": "23,100원" if ko else "₩23,100", "card_id": "c2", "card_label": "", "month": "2026-11", "seen": True}]
        if path.endswith("notifications"):
            h.send_json({"items": items, "unseen": sum(1 for i in items if not i["seen"])})
        else:
            h.send_json({"items": items})
        return True
    if path == "/api/wallet/notifications/seen":
        h.send_json({"ok": True})
        return True
    return False
