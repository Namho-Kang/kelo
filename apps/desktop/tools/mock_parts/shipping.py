"""Shipping addresses, the country address forms, the in-chat address form and the one final order card, for mock_server.py.

The JSON in shipping_data/ is real output of the server's address code (the contract samples). Endpoints:

- GET /api/address/countries -> {"countries": [{"code", "name"}], "default"}   (names in English, as the server sends them)
- GET /api/address/form/{CC} -> that country's form, labels in the mock's language when a sample exists (else English, else a
  plain form)
- GET/POST /api/wallet/shipping, GET/PATCH/DELETE /api/wallet/shipping/{id}: the profiles, default first (422 names the field)
- POST /api/shipping-forms/{form_id}: the in-chat form's answer ({"address", "save", "label", "payment"} or {"cancel": true})
- A message with "머그" / "mug" in it gets a scripted order reply: the agent asks where to deliver (a "shipping_form" event), waits for
  the answer, then asks for the one final approval of the order (confirm_purchase with items, shipping, card, coupon).
  KELO_MOCK_SHIP_FORM=us|kr picks the form's sample (default: the mock's language). GET /mock/shipping shows what was answered.
"""

from __future__ import annotations

import copy
import json
import os
import re
import time
import uuid
from pathlib import Path

DATA = Path(__file__).parent / "shipping_data"
KEYS = ["name", "organization", "address_line1", "address_line2", "dependent_locality", "locality", "admin_area", "postal_code", "sorting_code"]
CALLING = {"KR": "+82", "US": "+1", "JP": "+81", "GB": "+44", "DE": "+49", "FR": "+33", "CA": "+1", "AU": "+61", "CN": "+86",
           "TW": "+886", "SG": "+65", "VN": "+84", "TH": "+66", "IN": "+91", "ES": "+34", "IT": "+39", "NL": "+31", "AD": "+376",
           "AE": "+971", "AF": "+93", "AG": "+1", "AC": "+247"}
COUNTRIES = [("AC", "Ascension Island"), ("AD", "Andorra"), ("AE", "United Arab Emirates"), ("AF", "Afghanistan"),
             ("AG", "Antigua And Barbuda"), ("AU", "Australia"), ("CA", "Canada"), ("CN", "China"), ("DE", "Germany"), ("ES", "Spain"),
             ("FR", "France"), ("GB", "United Kingdom"), ("IN", "India"), ("IT", "Italy"), ("JP", "Japan"), ("KR", "South Korea"),
             ("NL", "Netherlands"), ("SG", "Singapore"), ("TH", "Thailand"), ("TW", "Taiwan"), ("US", "United States"), ("VN", "Vietnam")]


def _picture() -> str:
    """chat.py's small page picture (the same one the other scripted reply shows)."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("mock_parts_chat_picture", Path(__file__).parent / "chat.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.PICTURE


PICTURE = _picture()


def _load(name: str) -> dict:
    return json.loads((DATA / name).read_text(encoding="utf-8"))


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _lang(s) -> str:
    return "ko" if _ko(s) else "en"


def form(cc: str, lang: str) -> dict:
    """The country's form in [lang] when a sample exists, else in English, else any sample of it, else a plain form."""
    cc = cc.upper()
    for f in (f"form_{cc}_{lang}.json", f"form_{cc}_en.json"):
        if (DATA / f).exists():
            return _load(f)
    any_one = sorted(DATA.glob(f"form_{cc}_*.json"))
    if any_one:
        return json.loads(any_one[0].read_text(encoding="utf-8"))
    name = dict(COUNTRIES).get(cc, cc)
    ko = lang == "ko"
    return {
        "country": cc, "country_name": name, "lang": "",
        "fields": [
            {"key": "name", "label": "이름" if ko else "Full name", "required": True, "autocomplete": "name"},
            {"key": "address_line1", "label": "주소 1" if ko else "Address line 1", "required": True, "autocomplete": "address-line1"},
            {"key": "address_line2", "label": "주소 2" if ko else "Address line 2", "required": False, "autocomplete": "address-line2"},
            {"key": "locality", "label": "도시" if ko else "City", "required": True, "autocomplete": "address-level2"},
            {"key": "postal_code", "label": "우편번호" if ko else "Postal code", "required": False, "autocomplete": "postal-code"},
        ],
        "phone": {"label": "전화번호" if ko else "Phone number", "calling_code": CALLING.get(cc, "+1"), "required": True},
        "note": {"label": "배송 메모" if ko else "Delivery note", "required": False},
        "labels": {"country": "국가" if ko else "Country", "nickname": "별칭" if ko else "Nickname", "optional": "선택" if ko else "optional"},
        "format": "%N%n%O%n%A%n%C %Z",
    }


def _lines(a: dict, fmt: str, with_name: bool) -> list[str]:
    def val(code: str) -> str:
        return {
            "N": a.get("name", "") if with_name else "", "O": a.get("organization", ""),
            "A": "\n".join(x for x in (a.get("address_line1", ""), a.get("address_line2", "")) if x),
            "C": a.get("locality", "").upper() if a.get("country") == "US" else a.get("locality", ""), "S": a.get("admin_area", ""),
            "Z": a.get("postal_code", ""), "D": a.get("dependent_locality", ""), "X": a.get("sorting_code", ""),
        }.get(code, "")
    out = re.sub(r"%([A-Zn])", lambda m: "\n" if m.group(1) == "n" else val(m.group(1)), fmt)
    lines = []
    for line in out.split("\n"):
        line = re.sub(r"\s+", " ", line).strip(" ,")
        if line and line != "〒":
            lines.append(line)
    return lines


def formatted(a: dict, with_name: bool = True) -> str:
    return "\n".join(_lines(a, form(a.get("country", "KR"), "en")["format"], with_name))


def one_line(a: dict) -> str:
    return ", ".join(_lines(a, form(a.get("country", "KR"), "en")["format"], False))


def e164(phone: str, cc: str) -> str:
    digits = re.sub(r"\D", "", phone)
    if phone.strip().startswith("+"):
        return "+" + digits
    code = CALLING.get(cc, "+1")
    return code + digits.lstrip("0")


def check(a: dict, lang: str) -> str | None:
    """The 422 detail for an address that does not fit its country's form, or None."""
    f = form(a.get("country", ""), lang)
    for fld in f["fields"]:
        v = str(a.get(fld["key"], "") or "").strip()
        if fld["required"] and not v:
            return f"{fld['key']}: {fld['label']} is required"
        if v and fld.get("pattern") and not re.fullmatch(fld["pattern"], v, re.IGNORECASE):
            return f"postal_code: {v} is not a valid {fld['label']} for {f['country_name']}" if fld["key"] == "postal_code" else f"{fld['key']}: not valid"
        if v and fld.get("options") and v not in [o["key"] for o in fld["options"]]:
            return f"{fld['key']}: pick one of the list"
    if not re.sub(r"\D", "", str(a.get("phone", ""))):
        return "phone: a phone number is required"
    return None


class _Ship:
    def __init__(self) -> None:
        self.profiles: list[dict] | None = None
        self.answers: dict[str, dict] = {}
        self.patched = False


S = _Ship()


def _seed(s) -> list[dict]:
    if S.profiles is None:
        rows = _load("profile_samples.json")["profiles"]
        if not _ko(s):   # English nicknames, the US address first and default
            rows[0]["label"], rows[0]["default"] = "Parents", False
            rows[1]["label"], rows[1]["default"] = "Home", True
            rows[2]["label"] = "Tokyo flat"
            rows = [rows[1], rows[0], rows[2]]
        S.profiles = rows
    return S.profiles


def _profile(body: dict, lang: str, old: dict | None = None) -> dict:
    p = dict(old or {"id": "ship_" + uuid.uuid4().hex[:12], "created_at": time.time(), "last_used": None, "default": False, "note": "",
                     "label": ""})
    p["country"] = (body.get("country") or p.get("country") or "KR").upper()
    for k in KEYS:
        if k in body or old is None:
            p[k] = str(body.get(k, "") or "").strip()
    if "phone" in body:
        p["phone"] = e164(str(body["phone"]), p["country"])
    if "note" in body:
        p["note"] = str(body.get("note") or "")
    if "label" in body:
        p["label"] = str(body.get("label") or "").strip() or ("집" if lang == "ko" else "Home")
    p["formatted"] = formatted(p)
    p["one_line"] = one_line(p)
    return p


def _sorted(rows: list[dict]) -> list[dict]:
    return sorted(rows, key=lambda r: (not r.get("default"), r.get("created_at", 0)))


def _set_default(pid: str) -> None:
    for r in S.profiles or []:
        r["default"] = r["id"] == pid


def _order_events(s, turn: dict) -> list[dict]:
    """The scripted order reply: ask where to deliver, wait, then the one final approval card."""
    ko = _ko(s)
    pick = os.environ.get("KELO_MOCK_SHIP_FORM", "kr" if ko else "us")
    sample = _load("shipping_form_event_sample.json" if pick == "kr" else "shipping_form_event_sample_en.json")
    form_id = turn.setdefault("form_id", "sf_" + turn["approval"][-6:])
    sample.update({"form_id": form_id, "expires_at": int(turn["started"] + 600)})
    ev: list[dict] = [{"type": "thinking"}]
    age = time.time() - turn["started"]
    if age < 0.4:
        return ev
    label = "배송지 묻기" if ko else "Asking where to deliver"
    ev.append({"type": "tool_started", "id": sample["id"], "name": "ask_shipping", "arguments": {}, "label": label})
    ev.append(sample)
    answer = S.answers.get(form_id)
    if answer is None:
        return ev
    if answer.get("cancel"):
        ev.append({"type": "tool_result", "id": sample["id"], "name": "ask_shipping", "output": "cancelled", "is_error": False,
                   "approved": None, "label": label})
        ev.append({"type": "text", "text": "알겠어요, 주문은 멈출게요." if ko else "Okay, I'll stop the order."})
        ev.append({"type": "done", "input_tokens": 900, "output_tokens": 60, "steps": 2})
        return ev
    ev.append({"type": "tool_result", "id": sample["id"], "name": "ask_shipping", "output": "ok", "is_error": False, "approved": None,
               "label": label})
    ev.append({"type": "text", "text": "주문 내용을 마지막으로 확인해 주세요." if ko else "Please check the order one last time."})
    order = _load("approval_order_sample.json" if ko else "approval_order_sample_en.json")
    order["approval_id"] = turn["approval"]
    order["expires_at"] = int(turn["started"] + 900)
    ev.append({"type": "tool_started", "id": order["id"], "name": "confirm_purchase", "arguments": {}, "label": order["label"]})
    ev.append({"type": "browser", "id": order["id"], "title": "Checkout", "url": "https://shop.example.com/checkout",
               "host": "shop.example.com", "status": "결제 화면" if ko else "At the checkout", "image": PICTURE, "width": 160,
               "height": 100, "at": time.time(), "tab": ""})
    ev.append(order)
    decided = s.approved.get(turn["approval"])
    if decided is None:
        return ev
    ev.append({"type": "tool_result", "id": order["id"], "name": "confirm_purchase", "output": "ok", "is_error": False,
               "approved": decided, "label": order["label"]})
    ev.append({"type": "text", "text": ("주문했어요." if decided else "주문하지 않았어요.") if ko else ("Ordered." if decided else "I didn't order it.")})
    ev.append({"type": "done", "input_tokens": 1500, "output_tokens": 120, "steps": 4})
    return ev


def _is_order(s, turn: dict) -> bool:
    i = turn.get("msg", 0)
    if not 0 < i <= len(s.messages):
        return False
    text = str(s.messages[i - 1].get("content", "")).lower()
    return "머그" in text or "mug" in text


def route(h, method: str, path: str, q: dict, s) -> bool:
    if s.fresh:
        return False
    lang = _lang(s)
    if not S.patched:
        S.patched = True
        original = s.reply_events

        def with_order(turn: dict) -> list[dict]:
            return _order_events(s, turn) if _is_order(s, turn) else original(turn)

        s.reply_events = with_order
    if path == "/api/address/countries" and method == "GET":
        rows = _seed(s)
        last = next((r["country"] for r in _sorted(rows)), "KR")
        h.send_json({"countries": [{"code": c, "name": n} for c, n in COUNTRIES], "default": last})
        return True
    m = re.fullmatch(r"/api/address/form/([A-Za-z]{2})", path)
    if m and method == "GET":
        cc = m.group(1).upper()
        if cc not in dict(COUNTRIES):
            h.send_json({"detail": f"unknown country {cc}"}, 404)
            return True
        h.send_json(form(cc, lang))
        return True
    if path == "/api/wallet/shipping":
        rows = _seed(s)
        if method == "GET":
            h.send_json({"profiles": _sorted(rows)})
            return True
        if method == "POST":
            b = h.body()
            a = {**b, "country": (b.get("country") or "").upper()}
            err = check(a, lang)
            if err:
                h.send_json({"detail": err}, 422)
                return True
            p = _profile(b, lang)
            rows.append(p)
            if b.get("default") or len(rows) == 1:
                _set_default(p["id"])
            h.send_json(p, 201)
            return True
    m = re.fullmatch(r"/api/wallet/shipping/([^/]+)", path)
    if m:
        rows = _seed(s)
        p = next((r for r in rows if r["id"] == m.group(1)), None)
        if p is None:
            h.send_json({"detail": "no such shipping address"}, 404)
            return True
        if method == "GET":
            h.send_json(p)
            return True
        if method == "PATCH":
            b = h.body()
            if any(k in b for k in KEYS + ["country", "phone"]):
                merged = {**p, **b, "country": (b.get("country") or p["country"]).upper()}
                err = check(merged, lang)
                if err:
                    h.send_json({"detail": err}, 422)
                    return True
            new = _profile(b, lang, p)
            rows[rows.index(p)] = new
            if b.get("default"):
                _set_default(new["id"])
            h.send_json(new)
            return True
        if method == "DELETE":
            rows.remove(p)
            if p.get("default") and rows:
                min(rows, key=lambda r: r.get("created_at", 0))["default"] = True
            h.send_json({"ok": True})
            return True
    m = re.fullmatch(r"/api/shipping-forms/([^/]+)", path)
    if m and method == "POST":
        fid = m.group(1)
        turn = next((t for t in s.turns.values() if t.get("form_id") == fid), None)
        if turn is None or fid in S.answers:
            h.send_json({"detail": "that form is no longer waiting"}, 404)
            return True
        b = h.body()
        if b.get("cancel"):
            S.answers[fid] = {"cancel": True}
            h.send_json({"ok": True})
            return True
        a = dict(b.get("address") or {})
        a["country"] = (a.get("country") or "").upper()
        err = check(a, lang)
        if err:
            h.send_json({"detail": err}, 422)
            return True
        saved = None
        if b.get("save"):
            rows = _seed(s)
            saved = _profile({**a, "label": b.get("label") or ""}, lang)
            rows.append(saved)
        S.answers[fid] = copy.deepcopy(b)
        h.send_json({"ok": True, "profile": saved, "formatted": formatted(a)})
        return True
    if path == "/mock/shipping" and method == "GET":
        h.send_json({"answers": S.answers, "profiles": _sorted(_seed(s))})
        return True
    return False
