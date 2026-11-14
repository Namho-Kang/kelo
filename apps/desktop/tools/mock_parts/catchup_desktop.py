"""Sample data for the desktop catch-up screens (apps/desktop/CATCHUP.md), switched on with KELO_MOCK_CATCHUP=1 so the other walks stay
as they were. Shapes follow src/assistant/server.py (briefing.py, payment_steps.py, schedule_grants.py, calendars.py).

- GET /api/messages: a short history whose newest agent message is the morning briefing (weather, today, missed, an hours suggestion).
- GET /api/approvals/pending: the setup card of a "when X happens, do Y" task (task_allow, urgent) and a permission for many items
  (37 senders to unsubscribe from) with its list; POST /api/approvals/{id} answers either.
- GET /api/payment-steps: one "approve in another app" step (code, QR, 5 minutes) and, with KELO_MOCK_CATCHUP=pad, the payment page's
  PIN keypad instead; POST /api/payment-steps/{id} answers (approved -> finishing -> done), .../pad is a PNG keypad, .../pad/tap and
  .../pad/clear count taps.
- GET/PUT /api/proactive with weekday and weekend hours, a learned weekend window and a weekend suggestion; .../hours/{id}/accept|keep,
  .../hours/learned/{kind}/apply.
- GET/PUT /api/briefing (KELO_MOCK_LOCATION=1: a phone location is known), POST /api/briefing/location, GET/PUT /api/briefing/sources, POST .../sources/check,
  POST /api/briefing/run, POST/DELETE /api/briefing/keys/{id}.
- GET /api/watchers/1/grants and /api/workers/w-fares/grants: what that task may do without asking; DELETE revokes.
- GET /api/payment-pins (DELETE removes one), GET /api/caldav, POST /api/caldav/accounts.
"""

from __future__ import annotations

import base64
import os
import re
import struct
import time
import zlib

NOW = time.time()


def _on() -> str:
    return os.environ.get("KELO_MOCK_CATCHUP", "")


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _w(s, ko: str, en: str) -> str:
    return ko if _ko(s) else en


def _png(w: int, h: int, paint) -> bytes:
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            row += bytes(paint(x, y))
        rows.append(bytes(row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))


def _qr() -> str:
    """A QR-like square (a fixed pattern, not a real code) as a data: URL."""
    n, cell = 25, 6

    def dark(cx: int, cy: int) -> bool:
        for fx, fy in ((0, 0), (n - 7, 0), (0, n - 7)):
            if fx <= cx < fx + 7 and fy <= cy < fy + 7:
                dx, dy = cx - fx, cy - fy
                return dx in (0, 6) or dy in (0, 6) or (2 <= dx <= 4 and 2 <= dy <= 4)
        return ((cx * 7 + cy * 13 + cx * cy) % 5) < 2

    png = _png(n * cell, n * cell, lambda x, y: (20, 20, 20) if dark(x // cell, y // cell) else (255, 255, 255))
    return "data:image/png;base64," + base64.b64encode(png).decode()


# a 3x5 pixel font for the keypad's digits
DIGITS = {
    "1": ["010", "110", "010", "010", "111"], "2": ["111", "001", "111", "100", "111"], "3": ["111", "001", "111", "001", "111"],
    "4": ["101", "101", "111", "001", "001"], "5": ["111", "100", "111", "001", "111"], "6": ["111", "100", "111", "101", "111"],
    "7": ["111", "001", "010", "010", "010"], "8": ["111", "101", "111", "101", "111"], "9": ["111", "101", "111", "001", "111"],
    "0": ["111", "101", "101", "101", "111"],
}
KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", ""]


def _pad() -> bytes:
    """The payment page's keypad region (a shuffled-looking 3x4 grid of digits) as PNG bytes."""
    w, h, cw, ch = 300, 240, 100, 60

    def paint(x: int, y: int):
        col, row = x // cw, y // ch
        lx, ly = x % cw, y % ch
        if lx < 3 or ly < 3:
            return (210, 214, 222)
        key = KEYS[row * 3 + col]
        if key:
            gx, gy = (lx - 38) // 8, (ly - 10) // 8
            if 0 <= gx < 3 and 0 <= gy < 5 and DIGITS[key][gy][gx] == "1":
                return (30, 34, 48)
        return (246, 247, 250)

    return _png(w, h, paint)


class _State:
    def __init__(self) -> None:
        self.answered: dict[str, bool] = {}
        self.step_state = "waiting"
        self.taps = 0
        self.proactive: dict | None = None
        self.briefing: dict | None = None
        self.sources: dict | None = None
        self.job_grants: list[dict] | None = None
        self.pins = [{"id": "pin1", "site": "shop.example.kr", "label": "", "digits": 6}]
        self.caldav: list[dict] = []


S = _State()


def _history(s) -> list[dict]:
    ko = _ko(s)
    briefing = {
        "day": time.strftime("%Y-%m-%d"), "summary": _w(s, "오후 비 · 일정 2개 · 답 기다리는 일 1개", "Rain this afternoon · 2 events · 1 waiting"),
        "line": _w(s, "오후 3시부터 비가 와요. 치과 가실 때 우산 챙기세요.", "Rain from 3 PM: take an umbrella to the dentist."),
        "place": {"name": _w(s, "서울", "Lisbon")},
        "weather": {"high": 14, "low": 6, "unit": "C", "headline": _w(s, "흐리고 오후 비 · 최고 14° · 최저 6°", "Cloudy, rain later · high 14° · low 6°"),
                    "notes": [{"kind": "rain_start", "text": _w(s, "오후 3시쯤 비가 시작돼요", "Rain starts around 3 PM")},
                              {"kind": "temp_drop", "text": _w(s, "어제보다 6° 추워요", "6° colder than yesterday")}],
                    "provider": "open_meteo", "attribution": "Weather data by Open-Meteo.com (CC BY 4.0)",
                    "attribution_url": "https://open-meteo.com/"},
        "events": [{"title": _w(s, "팀 주간 회의", "Team weekly"), "start": NOW + 3600, "where": _w(s, "3층 회의실", "Room 3"),
                    "open": {"kind": "event", "start": NOW + 3600}, "source": "Google"},
                   {"title": _w(s, "치과", "Dentist"), "start": NOW + 6 * 3600, "open": {"kind": "event", "start": NOW + 6 * 3600},
                    "source": "iCloud"}],
        "missed": [{"kind": "approval", "title": _w(s, "항공권 결제 페이지 열기", "Open the flight's checkout page"),
                    "detail": _w(s, "어제 밤에 물어봤어요", "Asked last night"), "open": {"kind": "followup", "id": 1}},
                   {"kind": "mail", "title": _w(s, "계약서 검토 요청", "Contract review request"), "detail": "kim@example.com",
                    "open": {"kind": "mail", "subject": _w(s, "계약서 검토 요청", "Contract review request")}, "source": "Gmail"},
                   {"kind": "goal", "title": _w(s, "운동 주 3회", "Exercise 3 times a week"), "detail": _w(s, "내일까지", "Due tomorrow"),
                    "open": {"kind": "goal", "id": 3}}],
        "hours": {"id": "hs-weekend", "day_type": "weekend", "wake_start": "10:00", "wake_end": "23:30",
                  "current_start": "07:00", "current_end": "22:00"},
    }
    # the smarter card (server briefing.py): a headline (also copied into "line"), notes across sources with ids of card items, next steps
    briefing["weather"]["id"] = "w"
    briefing["events"][0]["id"], briefing["events"][1]["id"] = "e1", "e2"
    briefing["missed"][0]["id"], briefing["missed"][1]["id"], briefing["missed"][2]["id"] = "m1", "m2", "m3"
    if os.environ.get("KELO_MOCK_BRIEFING") == "long":
        # one long variant: long sentences and spaceless long labels (German compounds), to check the wrapping
        briefing["headline"] = briefing["line"] = _w(
            s, "오늘은 오후 3시부터 비가 오고, 3층 회의실 주간 회의와 치과 예약 사이에 이동 시간이 거의 없어서 하루가 빠듯해 보이니 일정 순서를 한번 확인해 보시면 좋겠어요.",
            "Rain from 3 PM, and with the weekly meeting and the dentist so close together there is hardly any travel time: the day looks tight, so it is worth a look at the order of things.")
        briefing["events"][0]["title"] = _w(s, "팀주간회의및분기계획공유세션", "Donaudampfschifffahrtsgesellschaftskapitaensbesprechung")
        briefing["connections"] = [
            {"text": _w(s, "치과 예약 시간에 비가 시작되는데, 어제 보낸 항공권 질문도 아직 답을 기다리고 있어서 우산과 함께 한꺼번에 챙기는 게 좋아 보여요.",
                        "The dentist visit lands right when the rain starts, and the flight question from last night is still waiting for your answer."),
             "refs": ["e2", "w", "m1"]},
            {"text": _w(s, "계약서 검토 요청 메일과 운동 목표 마감이 모두 내일까지라서 오늘 저녁 시간을 비워 두면 좋겠어요.",
                        "The contract review mail and the exercise goal are both due by tomorrow, so keep this evening free."),
             "refs": ["m2", "m3", "e1", "zz9"]}]
        briefing["suggestions"] = [
            {"text": _w(s, "김 팀장님께 계약서 검토 요청에 대한 답장 초안을 지금 바로 써 드릴까요, 아니면 저녁에 한꺼번에 정리해 드릴까요?",
                        "Shall I draft a reply to Kim about the contract review request right now, or collect everything for this evening?"), "refs": ["m2"]},
            {"text": _w(s, "치과 가기 전에 우산 챙기라고 2시 30분에 알려 드릴까요?", "Shall I remind you about the umbrella at 2:30 PM, before the dentist?"), "refs": []}]
    else:
        briefing["headline"] = briefing["line"]
        briefing["connections"] = [
            {"text": _w(s, "치과 예약 시간쯤 비가 시작돼요. 우산을 챙기세요.", "Rain starts around the dentist visit: take an umbrella."), "refs": ["w", "e2"]},
            {"text": _w(s, "계약서 검토 메일과 운동 목표가 모두 내일까지예요.", "The contract mail and the exercise goal are both due by tomorrow."), "refs": ["m2", "m3"]}]
        briefing["suggestions"] = [
            {"text": _w(s, "김 팀장님께 계약서 검토 답장 초안을 써 드릴까요?", "Shall I draft a reply to Kim about the contract?"), "refs": ["m2"]},
            {"text": _w(s, "치과 가기 전에 우산 챙기라고 알려 드릴까요?", "Shall I remind you about the umbrella before the dentist?"), "refs": []}]
    return [
        {"id": 1, "role": "user", "content": _w(s, "내일 아침에 오늘 일정이랑 날씨 알려줘", "Tell me today's plan and weather in the morning"),
         "created_at": NOW - 86400},
        {"id": 2, "role": "assistant", "content": _w(s, "네, 매일 아침 브리핑으로 알려 드릴게요.", "Sure, you'll get a morning briefing every day."),
         "created_at": NOW - 86400 + 20},
        {"id": 3, "role": "assistant", "content": briefing["summary"], "created_at": NOW - 600, "briefing": briefing},
    ]


def _pending(s) -> list[dict]:
    ko = _ko(s)
    items = []
    if "ap-grant" not in S.answered:
        items.append({
            "type": "approval_request", "approval_id": "ap-grant", "id": "g1", "name": "task_allow", "arguments": {},
            "label": _w(s, "작업 허용", "Allow a task"), "urgent": True,
            "missed": _w(s, "다른 사람이 표를 먼저 살 수 있어요", "someone else may get the tickets first"),
            "grant": {"scope": "watcher", "task_name": _w(s, "콘서트 예매 오픈", "Concert tickets on sale"),
                      "task_when": _w(s, "30분마다 확인", "checked every 30 minutes"),
                      "trigger": _w(s, "예매 버튼이 생기면", "When the booking button shows up"),
                      "action": _w(s, "R석 2장을 예매해요", "Book 2 seats in section R"), "once": True, "urgent": True,
                      "missed": _w(s, "다른 사람이 표를 먼저 살 수 있어요", "someone else may get the tickets first"),
                      "purchase": {"site": "tickets.example.com", "what": _w(s, "콘서트 R석 2장", "2 concert seats, section R"),
                                   "max_amount": 300000 if ko else 240, "currency": "KRW" if ko else "USD",
                                   "max_amount_text": _w(s, "300,000원", "$240"), "max_count": 1,
                                   "monthly_cap_text": _w(s, "300,000원", "$240"),
                                   "card": {"label": _w(s, "생활비 카드", "Everyday card"), "last4": "4242", "auto_pay": True,
                                            "auto_limit": 50000 if ko else 50, "auto_limit_text": _w(s, "50,000원", "$50")}},
                      "login": {"label": "tickets", "site": "tickets.example.com"}},
            "site": "tickets.example.com", "asked_at": NOW - 120, "source": "chat", "title": "",
        })
    if "ap-bulk" not in S.answered:
        senders = [f"deals{i}.example.com" for i in range(1, 38)]
        items.append({
            "type": "approval_request", "approval_id": "ap-bulk", "id": "b1", "name": "browser_click", "arguments": {},
            "label": _w(s, "구독 취소", "Unsubscribe"),
            "ask": _w(s, "홍보 메일 보낸 곳 37개 모두: 구독 취소. 진행할까요?", "All 37 senders of promotional mail: unsubscribe. Go ahead?"),
            "lines": [{"label": _w(s, "할 일", "What"), "value": _w(s, "각 메일의 ‘수신 거부’ 링크 열고 확인 누르기",
                                                                   "Open each mail's 'Unsubscribe' link and confirm")},
                      {"label": _w(s, "되돌리기", "Undo"), "value": _w(s, "언제든 다시 구독할 수 있어요", "You can subscribe again any time")}],
            "items": senders, "site": "", "asked_at": NOW - 60, "source": "chat", "title": "",
        })
    return items


def _step(s) -> dict:
    pad = _on() == "pad"
    base = {
        "type": "payment_step", "step_id": "st-pad" if pad else "st-app", "kind": "pin_pad" if pad else "app", "state": S.step_state,
        "site": "shop.example.kr" if _ko(s) else "shop.example.com",
        "order": {"summary": _w(s, "블루 머그 외 1건", "Blue mug and 1 more"), "amount_text": _w(s, "32,000원", "$27.00"),
                  "pay_with": _w(s, "토스페이 · 생활비 카드", "PayApp · Everyday card"),
                  "items": [{"name": _w(s, "블루 머그", "Blue mug"), "quantity": 2}, {"name": _w(s, "코스터", "Coaster"), "quantity": 1}]},
        "opened_at": NOW - 30, "tab": "task:w-mock",
    }
    if pad:
        base.update(instruction=_w(s, "결제 비밀번호 6자리를 눌러 주세요", "Enter your 6-digit payment PIN"), taps=S.taps, can_clear=True)
    else:
        base.update(code="482913", qr=_qr(), expires_at=int(NOW) + 300, seconds_total=300, can_resend=True,
                    instruction=_w(s, "카드 앱에 뜬 번호가 482 913인지 확인하고 승인하세요", "Check the number 482 913 in your card app and approve"))
    return base


def _proactive(s) -> dict:
    if S.proactive is None:
        S.proactive = {
            "enabled": True, "wake_start": "07:30", "wake_end": "22:00", "every_minutes": 120, "daily_regular": 2, "daily_total": 3,
            "daily_now": 3, "today": {"regular": 1}, "awake": True, "auto_hours": False, "hours_source": "manual",
            "weekend_start": "07:30", "weekend_end": "22:00", "weekend_source": "default",
            "hours_suggestion": None,
            "weekend_suggestion": {"id": "hs-weekend", "day_type": "weekend", "wake_start": "10:00", "wake_end": "23:30",
                                   "current_start": "07:30", "current_end": "22:00", "active_days": 6, "days": 28},
            "learned": {"weekday": {"wake_start": "08:00", "wake_end": "23:00", "active_days": 18}, "weekend": None},
            "holiday_country": "KR" if _ko(s) else "PT",
        }
    return S.proactive


def _briefing(s) -> dict:
    if S.briefing is None:
        S.briefing = {
            "enabled": True, "time_mode": "auto", "fixed_time": "07:30", "offset_min": 10, "early_open": True,
            "sections": {"weather": True, "calendar": True, "missed": True}, "quiet_days": "short", "next_at": NOW + 50000,
            # where the weather is for: the last location a phone sent (none until KELO_MOCK_LOCATION=1 or a phone posts one)
            "weather": {"provider": "auto",
                        "here": ({"name": _w(s, "서울특별시", "Lisbon"), "lat": 37.57 if _ko(s) else 38.72, "lon": 126.98 if _ko(s) else -9.14,
                                  "country_code": "KR" if _ko(s) else "PT", "source": "phone", "at": NOW - 600, "accuracy_m": 40}
                                 if os.environ.get("KELO_MOCK_LOCATION") else None),
                        "keys": {"kma": False, "weatherapi": True},
                        "providers": [{"id": "open_meteo", "name": "Open-Meteo", "needs_key": False,
                                       "attribution": "Weather data by Open-Meteo.com (CC BY 4.0)", "attribution_url": "https://open-meteo.com/"}]},
        }
    return S.briefing


def _sources(s) -> dict:
    if S.sources is None:
        S.sources = {
            "calendar": {"accounts": [
                {"id": "google:me", "kind": "calendar", "provider": "google", "name": "Google Calendar", "address": "me@gmail.com", "label": "Google", "on": True},
                {"id": "caldav:icloud", "kind": "calendar", "provider": "icloud", "name": _w(s, "iCloud 캘린더", "iCloud Calendar"),
                 "address": "me@icloud.com", "label": "iCloud", "on": True,
                 "problem": _w(s, "앱 암호가 만료됐어요. 커넥터에서 다시 입력해 주세요.", "The app password expired. Enter it again in Connectors.")},
                {"id": "phone", "kind": "calendar", "provider": "phone", "name": _w(s, "휴대폰 캘린더", "Phone calendar"), "address": "",
                 "label": _w(s, "휴대폰", "Phone"), "on": False}]},
            "mail": {"accounts": [
                {"id": "mail:gmail", "kind": "mail", "provider": "gmail", "name": "Gmail", "address": "me@gmail.com", "label": "Gmail", "on": True}]},
        }
    return S.sources


def _job_grants(s) -> list[dict]:
    if S.job_grants is None:
        S.job_grants = [
            {"id": 21, "scope": "watcher", "scope_ref": "1", "kind": "purchase", "site": "flights.example.com",
             "what": _w(s, "제주 왕복 항공권 1장", "1 return flight to Lisbon"), "max_amount": 150000, "currency": "KRW" if _ko(s) else "USD",
             "max_amount_text": _w(s, "150,000원", "$480"), "monthly_cap_text": _w(s, "150,000원", "$480"), "max_count": 1, "used": 0,
             "once": True, "trigger": _w(s, "가격이 12만 원 아래로 내려가면", "When the fare drops below $420"),
             "task_name": _w(s, "제주 항공권 가격", "Lisbon fares"), "card_label": _w(s, "여행 카드", "Travel card"), "card_last4": "1881"},
            {"id": 22, "scope": "job", "scope_ref": "w-fares", "kind": "send", "targets": ["connector:slack", "#travel"],
             "template": _w(s, "{항공사} {가격} 찾았어요", "Found {airline} at {price}"), "max_count": 5, "used": 2,
             "trigger": _w(s, "더 싼 표를 찾으면", "When a cheaper fare turns up"), "task_name": _w(s, "항공권 비교", "Compare fares")},
            {"id": 23, "scope": "job", "scope_ref": "w-fares", "kind": "change", "what": _w(s, "여행 일정 메모", "the trip notes"),
             "targets": ["connector:notion"], "allow_delete": False, "max_count": 0, "used": 0,
             "task_name": _w(s, "항공권 비교", "Compare fares")},
        ]
    return S.job_grants


def _send_png(h, data: bytes) -> None:
    h.send_response(200)
    h.send_header("Content-Type", "image/png")
    h.send_header("Content-Length", str(len(data)))
    h.end_headers()
    h.wfile.write(data)


def _briefing_route(h, method: str, path: str, s) -> bool:
    """The morning briefing's settings and 읽는 계정: served in every walk (not only the catch-up one), since they are settings pages."""
    # ---- the morning briefing's settings
    if path == "/api/briefing":
        b = _briefing(s)
        if method == "PUT":
            body = h.body()
            if "sections" in body:
                b["sections"].update(body.pop("sections"))
            if "provider" in body:
                b["weather"]["provider"] = body.pop("provider")
            b.update(body)
        h.send_json(b)
        return True
    if path == "/api/briefing/location" and method == "POST":
        body = h.body()
        _briefing(s)["weather"]["here"] = {"name": body.get("name") or "", "lat": body.get("lat"), "lon": body.get("lon"),
                                           "country_code": body.get("country_code") or "", "source": "phone", "at": time.time(),
                                           "accuracy_m": round(body["accuracy"]) if body.get("accuracy") is not None else None}
        h.send_json({"ok": True})
        return True
    if path == "/api/briefing/sources":
        src = _sources(s)
        if method == "PUT":
            body = h.body()
            for kind in ("calendar", "mail"):
                part = body.get(kind) or {}
                for aid, on in (part.get("accounts") or {}).items():
                    for a in src[kind]["accounts"]:
                        if a["id"] == aid:
                            a["on"] = bool(on)
        h.send_json(src)
        return True
    if path == "/api/briefing/sources/check" and method == "POST":
        h.send_json(_sources(s))
        return True
    if path == "/api/briefing/run" and method == "POST":
        h.send_json({"ok": True})
        return True
    m = re.fullmatch(r"/api/briefing/keys/(kma|weatherapi)", path)
    if m and method in ("POST", "DELETE"):
        b = _briefing(s)
        b["weather"]["keys"][m.group(1)] = method == "POST"
        h.send_json(b)
        return True
    return False


def route(h, method: str, path: str, q: dict, s) -> bool:
    if path.startswith("/api/briefing") and not s.fresh:
        return _briefing_route(h, method, path, s)
    if not _on() or s.fresh:
        return False
    # ---- chat: history with the briefing, waiting cards, payment steps
    if path == "/api/messages" and method == "GET":
        h.send_json(_history(s))
        return True
    if path == "/api/approvals/pending" and method == "GET":
        h.send_json({"items": _pending(s), "resolved": []})
        return True
    m = re.fullmatch(r"/api/approvals/(ap-grant|ap-bulk)", path)
    if m and method == "POST":
        S.answered[m.group(1)] = bool(h.body().get("approve"))
        h.send_json({"ok": True})
        return True
    if path == "/api/payment-steps" and method == "GET":
        h.send_json({"items": [_step(s)]})
        return True
    m = re.fullmatch(r"/api/payment-steps/([^/]+)(/pad(?:/(tap|clear))?)?", path)
    if m:
        if m.group(3) == "tap":
            S.taps = min(S.taps + 1, 6)
            h.send_json({"taps": S.taps})
        elif m.group(3) == "clear":
            S.taps = 0
            h.send_json({"taps": 0})
        elif m.group(2):
            _send_png(h, _pad())
        else:
            answer = h.body().get("answer")
            S.step_state = {"approved": "finishing", "cancel": "cancelled", "new_code": "renewing"}.get(answer, S.step_state)
            h.send_json({"ok": True, "step": _step(s)})
        return True
    # ---- contact hours with weekends
    if path == "/api/proactive":
        p = _proactive(s)
        if method == "PUT":
            body = h.body()
            if any(k in body for k in ("wake_start", "wake_end")):
                body["hours_source"] = "manual"
            if any(k in body for k in ("weekend_start", "weekend_end")):
                body["weekend_source"] = "manual" if body.get("weekend_start") else "default"
                if not body.get("weekend_start"):
                    body.update(weekend_start=p["wake_start"], weekend_end=p["wake_end"])
            p.update(body)
        h.send_json(p)
        return True
    m = re.fullmatch(r"/api/proactive/hours/([^/]+)/(accept|keep)", path)
    if m and method == "POST":
        p = _proactive(s)
        for key, start, end, src in (("hours_suggestion", "wake_start", "wake_end", "hours_source"),
                                     ("weekend_suggestion", "weekend_start", "weekend_end", "weekend_source")):
            sug = p.get(key)
            if sug and sug["id"] == m.group(1):
                if m.group(2) == "accept":
                    p.update({start: sug["wake_start"], end: sug["wake_end"], src: "learned"})
                p[key] = None
        h.send_json(p)
        return True
    m = re.fullmatch(r"/api/proactive/hours/learned/(weekday|weekend)/apply", path)
    if m and method == "POST":
        p = _proactive(s)
        win = (p.get("learned") or {}).get(m.group(1))
        if win:
            if m.group(1) == "weekday":
                p.update(wake_start=win["wake_start"], wake_end=win["wake_end"], hours_source="learned")
            else:
                p.update(weekend_start=win["wake_start"], weekend_end=win["wake_end"], weekend_source="learned")
        h.send_json(p)
        return True
    # ---- what a watcher or a background job may do without asking
    m = re.fullmatch(r"/api/(watchers|workers)/([^/]+)/grants(?:/(\d+))?", path)
    if m:
        scope = "watcher" if m.group(1) == "watchers" else "job"
        if method == "DELETE" and m.group(3):
            S.job_grants = [g for g in _job_grants(s) if g["id"] != int(m.group(3))]
            h.send_json({"ok": True})
        else:
            h.send_json({"items": [g for g in _job_grants(s) if g["scope"] == scope and g["scope_ref"] == m.group(2)]})
        return True
    # ---- payment PINs (listed, never the PIN) and CalDAV calendars
    if path == "/api/payment-pins" and method == "GET":
        h.send_json({"items": S.pins})
        return True
    m = re.fullmatch(r"/api/payment-pins/([^/]+)", path)
    if m and method == "DELETE":
        S.pins = [p for p in S.pins if p["id"] != m.group(1)]
        h.send_json({"ok": True})
        return True
    if path == "/api/caldav" and method == "GET":
        h.send_json({"accounts": S.caldav})
        return True
    if path == "/api/caldav/accounts" and method == "POST":
        b = h.body()
        S.caldav.append({"name": "caldav:" + b.get("username", ""), "address": b.get("username", ""), "url": b.get("url", "")})
        h.send_json({"ok": True})
        return True
    return False
