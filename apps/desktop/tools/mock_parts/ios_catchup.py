"""Sample data for the iPhone app's catch-up screens (apps/ios/CATCHUP.md), switched on by environment variables so the other walks
are unchanged:

- KELO_MOCK_LONG_HISTORY=<n>: the chat history has n messages (dated one every 7 minutes up to now) and GET /api/messages honours
  limit and before_id, as the real server does, so scrolling up loads older pages (the chat's freeze check).
- KELO_MOCK_BRIEFING=1: the newest agent message is the morning briefing with its card (weather, today, missed, an hours suggestion).
- KELO_MOCK_IOS=1: a chat message picks a scripted reply by its words:
    "메일" / "mail"      tool steps of one kind folded into one chip, one browser card over two sites, a permission for 37 items,
                         then the step limit handing the rest to a background job (no error bubble);
    "알림" / "watch"     the task setup card (task_allow) of an urgent "when X happens, buy Y" task;
    "결제" / "pay"       a payment step "approve in another app" (code, QR, countdown) and, after [승인했어요], a PIN to release;
    "키패드" / "keypad"  a payment step with the page's PIN keypad shown in the card.
  GET/POST /api/payment-steps(/{id}(/pad(/tap|/clear))), GET /api/payment-pins answer for them.
(KELO_MOCK_CATCHUP is the desktop walk's, catchup_desktop.py: its settings pages serve the iPhone's too.)
"""

from __future__ import annotations

import base64
import os
import struct
import time
import zlib


def _long(s, n: int) -> list[dict]:
    if getattr(s, "_ios_long", None) is None:
        ko = s.t.get("user") == "치즈"
        now = time.time()
        out = []
        for i in range(n):
            mine = i % 2 == 0
            k = i // 2 + 1
            if mine:
                text = (f"{k}번째 질문이에요. 오늘 일정 다시 알려줘" if ko else f"Question {k}: what's on today again?")
            else:
                text = ((f"{k}번째 답이에요.\n\n- 오전 10시 회의\n- 오후 3시 치과\n\n더 필요한 게 있으면 말해 주세요." if ko else
                         f"Answer {k}.\n\n- 10 AM meeting\n- 3 PM dentist\n\nTell me if you need anything else.")
                        + ("\n\n" + ("길게 쓴 문단이에요. " if ko else "A longer paragraph. ") * 12 if k % 5 == 0 else ""))
            out.append({"id": i + 1, "role": "user" if mine else "assistant", "content": text, "created_at": now - 420 * (n - i)})
        s._ios_long = out
        with s.lock:
            s.messages[:] = out
    return s.messages


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _png(w: int, h: int, fill) -> str:
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            row += bytes(fill(x, y))
        rows.append(bytes(row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))
    return base64.b64encode(png).decode()


def _qr(x, y):
    cell = (x // 6, y // 6)
    finder = any(cx <= cell[0] < cx + 7 and cy <= cell[1] < cy + 7 for cx, cy in ((0, 0), (14, 0), (0, 14)))
    on = finder and not (1 <= (cell[0] % 14 if cell[0] < 7 or cell[0] >= 14 else cell[0]) <= 5 and 1 <= cell[1] % 14 <= 5) \
        or (not finder and (cell[0] * 7 + cell[1] * 13) % 5 < 2)
    return (20, 20, 20) if on else (255, 255, 255)


def _pad(x, y):
    # a 3 x 4 keypad of light keys on a white page
    kx, ky = x % 60, y % 44
    return (226, 230, 238) if 6 < kx < 54 and 6 < ky < 38 else (255, 255, 255)


QR = "data:image/png;base64," + _png(126, 126, _qr)
PAD = base64.b64decode(_png(180, 176, _pad))
PAGE = "data:image/png;base64," + _png(160, 100, lambda x, y: (63, 81, 181) if y < 16 else ((200, 205, 215) if (y // 6) % 3 == 0 and 8 < x < 120 else (250, 250, 252)))


class _Steps:
    def __init__(self) -> None:
        self.steps: dict[str, dict] = {}
        self.taps: dict[str, int] = {}


ST = _Steps()


def _step(sid: str, kind: str, ko: bool, started: float) -> dict:
    ev = ST.steps.get(sid)
    if ev is None:
        ev = {"type": "payment_step", "step_id": sid, "kind": kind, "state": "waiting", "site": "shop.example.com",
              "order": {"summary": "블루 머그 2개" if ko else "Blue mug × 2", "amount_text": "24,000원" if ko else "$24.00",
                        "pay_with": "생활비 카드 ••4242" if ko else "Everyday card ••4242",
                        "items": [{"name": "블루 머그" if ko else "Blue mug", "quantity": 2}]},
              "instruction": ("카드사 앱에서 결제 요청을 열고 아래 번호가 같은지 확인한 뒤 승인해 주세요." if ko else
                              "Open the payment request in your card app, check the number matches and approve it."),
              "tab": "owner", "opened_at": started}
        if kind == "app":
            ev.update({"code": "482913", "qr": QR, "expires_at": int(started + 180), "seconds_total": 180, "can_resend": True})
        else:
            ev.update({"taps": 0, "can_clear": True, "instruction": "가게 결제창의 비밀번호 키패드예요. 아래 키패드를 직접 눌러 주세요." if ko else
                       "This is the shop's PIN keypad. Tap the keys below yourself."})
        ST.steps[sid] = ev
    return dict(ev)


def _catchup_events(s, turn: dict) -> list[dict] | None:
    i = turn.get("msg", 0)
    text = str(s.messages[i - 1].get("content", "")).lower() if 0 < i <= len(s.messages) else ""
    ko = _ko(s)
    age = time.time() - turn["started"]
    ev: list[dict] = [{"type": "thinking"}]
    if "메일" in text or "mail" in text:
        n = min(4, int(age / 0.6))
        for k in range(1, 5):
            if n >= k:
                ev.append({"type": "tool_started", "id": f"m{k}", "name": "email_read", "arguments": {"subject": f"뉴스레터 {k}" if ko else f"Newsletter {k}"},
                           "label": "메일 읽는 중" if ko else "Reading mail", "group": "mail", "done": "메일 {n}개 읽음" if ko else "Read {n} emails"})
            if n > k or (n == 4 and age > 3):
                ev.append({"type": "tool_result", "id": f"m{k}", "name": "email_read", "output": "ok", "is_error": False, "approved": None,
                           "label": "메일 읽는 중" if ko else "Reading mail", "group": "mail", "done": "메일 {n}개 읽음" if ko else "Read {n} emails"})
        if age > 3.2:
            for k, host in enumerate(["deals.example", "news.example", "deals.example"]):
                ev.append({"type": "tool_started", "id": f"b{k}", "name": "browser_click", "arguments": {"url": f"https://{host}/unsubscribe"},
                           "label": "페이지 조작 중" if ko else "Working on the page"})
                ev.append({"type": "browser", "id": f"b{k}", "title": host, "url": f"https://{host}/unsubscribe", "host": host,
                           "status": "구독 취소 페이지" if ko else "On the unsubscribe page", "image": PAGE, "width": 160, "height": 100,
                           "at": time.time(), "tab": ""})
                ev.append({"type": "tool_result", "id": f"b{k}", "name": "browser_click", "output": "ok", "is_error": False, "approved": None})
            items = [(f"홍보 메일 {k}호 (sender{k}@deals.example)" if ko else f"Promo mail #{k} (sender{k}@deals.example)") for k in range(1, 38)]
            ev.append({"type": "tool_started", "id": "p1", "name": "browser_click", "arguments": {}, "label": "구독 취소" if ko else "Unsubscribe"})
            ev.append({"type": "approval_request", "approval_id": turn["approval"], "id": "p1", "name": "browser_click", "arguments": {},
                       "label": "구독 취소" if ko else "Unsubscribe",
                       "ask": "홍보 메일 보낸 곳 37개 모두: 구독 취소. 진행할까요?" if ko else "All 37 promo senders: unsubscribe. Go ahead?",
                       "lines": [{"label": "사이트" if ko else "Sites", "value": "deals.example 외 3곳" if ko else "deals.example and 3 more"}],
                       "items": items})
            answer = s.approved.get(turn["approval"])
            if answer is not None:
                ev.append({"type": "tool_result", "id": "p1", "name": "browser_click", "output": "ok", "is_error": False, "approved": answer})
                ev.append({"type": "step_limit", "steps": 40, "next": "background", "progress": 12})
                ev.append({"type": "text", "text": "단계가 많아서 나머지는 백그라운드에서 이어서 해요. 12개 끝났고 25개 남았어요." if ko else
                           "There are many steps, so I'll finish the rest in the background. 12 done, 25 to go."})
                ev.append({"type": "done", "input_tokens": 3000, "output_tokens": 200, "steps": 40})
        return ev
    if "알림" in text or "watch" in text:
        if age < 0.6:
            return ev
        ev.append({"type": "tool_started", "id": "g1", "name": "task_allow", "arguments": {}, "label": "허락 받기" if ko else "Asking to allow"})
        ev.append({"type": "approval_request", "approval_id": turn["approval"], "id": "g1", "name": "task_allow", "arguments": {},
                   "label": "허락 받기" if ko else "Asking to allow", "urgent": True,
                   "missed": "자리가 다른 사람에게 넘어가요" if ko else "the seat goes to someone else",
                   "grant": {"scope": "watcher", "task_name": "KTX 취소표 잡기" if ko else "Catch a cancelled train seat",
                             "task_when": "5분마다 확인" if ko else "checked every 5 minutes",
                             "trigger": "11월 21일 서울→부산 KTX 오전 좌석이 나오면" if ko else "When a morning seat Seoul → Busan on Nov 21 opens",
                             "action": "그 좌석을 바로 예매해요" if ko else "Book that seat at once",
                             "once": True, "urgent": True, "missed": "자리가 다른 사람에게 넘어가요" if ko else "the seat goes to someone else",
                             "login": {"label": "코레일" if ko else "Korail", "site": "letskorail.com"},
                             "purchase": {"site": "letskorail.com", "what": "KTX 일반실 1석" if ko else "1 standard KTX seat", "max_amount": 60000,
                                          "currency": "KRW", "max_amount_text": "60,000원" if ko else "₩60,000", "max_count": 1,
                                          "monthly_cap_text": "",
                                          "card": {"label": "생활비 카드" if ko else "Everyday card", "last4": "4242", "auto_pay": True,
                                                   "auto_limit": 30000, "auto_limit_text": "30,000원" if ko else "₩30,000"}}}})
        answer = s.approved.get(turn["approval"])
        if answer is not None:
            ev.append({"type": "tool_result", "id": "g1", "name": "task_allow", "output": "ok", "is_error": False, "approved": answer})
            ev.append({"type": "text", "text": "허락해 주신 대로 지켜볼게요." if ko else "I'll watch for it as you allowed."})
            ev.append({"type": "done", "input_tokens": 900, "output_tokens": 60, "steps": 2})
        return ev
    pad = "키패드" in text or "keypad" in text
    if pad or "결제" in text or "pay" in text:
        if age < 0.6:
            return ev
        sid = "ps_" + turn["approval"][-6:]
        ev.append({"type": "tool_started", "id": "s1", "name": "payment_step_wait", "arguments": {}, "label": "결제 승인 기다리는 중" if ko else "Waiting for the payment approval"})
        ev.append(_step(sid, "pin_pad" if pad else "app", ko, turn["started"]))
        cur = ST.steps[sid]
        if cur["state"] != "waiting":
            ev.append(dict(cur))
        if cur["state"] in ("done", "approved") and not pad:
            ev.append({"type": "pin_needed", "slot_id": "slot_" + sid, "site": "shop.example.com", "amount_text": "24,000원" if ko else "$24.00",
                       "expires_at": int(time.time() + 120), "pin": {"id": "pin_1", "label": "쇼핑몰 결제 비밀번호" if ko else "Shop payment PIN", "digits": 6}})
        return ev
    return None


def route(h, method: str, path: str, q: dict, s) -> bool:
    ios = bool(os.environ.get("KELO_MOCK_IOS"))
    if ios and not getattr(s, "_ios_catchup", False):
        s._ios_catchup = True
        original = s.reply_events

        def scripted(turn: dict) -> list[dict]:
            ev = _catchup_events(s, turn)
            return original(turn) if ev is None else ev

        s.reply_events = scripted
    if os.environ.get("KELO_MOCK_BRIEFING") and not getattr(s, "_ios_briefing", False):
        s._ios_briefing = True
        _add_briefing(s)
    if ios and path == "/api/payment-steps" and method == "GET":
        h.send_json({"items": [dict(v) for v in ST.steps.values()]})
        return True
    if ios and path.startswith("/api/payment-steps/"):
        rest = path[len("/api/payment-steps/"):].split("/")
        sid = rest[0]
        ev = ST.steps.get(sid)
        if ev is None:
            h.send_json({"detail": "that step is gone"}, 404)
            return True
        if len(rest) == 1 and method == "POST":
            ans = h.body().get("answer")
            ev["state"] = {"approved": "done", "cancel": "cancelled", "new_code": "renewing", "code": "checking", "done": "done"}.get(ans, ev["state"])
            h.send_json({"ok": True, "step_id": sid, "answer": ans})
            return True
        if rest[1:] == ["pad"] and method == "GET":
            h.send_response(200)
            h.send_header("Content-Type", "image/png")
            h.send_header("Content-Length", str(len(PAD)))
            h.end_headers()
            h.wfile.write(PAD)
            return True
        if rest[1:] == ["pad", "tap"] and method == "POST":
            ev["taps"] = ev.get("taps", 0) + 1
            h.send_json({"taps": ev["taps"]})
            return True
        if rest[1:] == ["pad", "clear"] and method == "POST":
            ev["taps"] = 0
            h.send_json({"taps": 0})
            return True
    if ios and path == "/api/payment-pins" and method == "GET":
        h.send_json({"items": [{"id": "pin_1", "site": "shop.example.com", "label": "쇼핑몰 결제 비밀번호" if _ko(s) else "Shop payment PIN",
                                "digits": 6, "e2e_alg": "A256GCM-IKC-PIN-1", "e2e_key_id": "other-phone", "created_at": time.time() - 86400}]})
        return True
    n = int(os.environ.get("KELO_MOCK_LONG_HISTORY") or 0)
    if n > 0 and path == "/api/messages" and method == "GET":
        msgs = _long(s, n)
        limit = int(q.get("limit", ["100"])[0])
        before = q.get("before_id", [""])[0]
        with s.lock:
            rows = [m for m in msgs if not before or m["id"] < int(before)]
            h.send_json(rows[-limit:])
        return True
    return False


def _add_briefing(s) -> None:
    ko = _ko(s)
    now = time.time()
    day = time.localtime(now)
    at = lambda hh, mm: time.mktime((day.tm_year, day.tm_mon, day.tm_mday, hh, mm, 0, 0, 0, -1))
    card = {
        "day": time.strftime("%Y-%m-%d", day), "summary": "오후 비 · 일정 2개 · 답 기다리는 일 1개" if ko else "Rain this afternoon · 2 events · 1 waiting",
        "line": "오후에 비가 오니 우산 챙기세요. 3시 치과 잊지 마세요." if ko else "Rain this afternoon, take an umbrella. Don't forget the dentist at 3.",
        "place": {"name": "서울" if ko else "Seoul"},
        "weather": {"high": 11, "low": 4, "unit": "C", "headline": "구름 조금 · 최고 11° · 최저 4°" if ko else "Partly cloudy · high 11° · low 4°",
                    "notes": [{"kind": "rain_start", "text": "오후 3시쯤 비가 시작돼요" if ko else "Rain starts around 3 PM"},
                              {"kind": "temp_drop", "text": "어제보다 6° 추워요" if ko else "6° colder than yesterday"}],
                    "provider": "open-meteo", "attribution": "Weather data by Open-Meteo.com (CC BY 4.0)", "attribution_url": "https://open-meteo.com"},
        "events": [{"title": "팀 회의" if ko else "Team meeting", "start": at(10, 0), "where": "3층 회의실" if ko else "Room 3", "open": {"kind": "event", "start": at(10, 0)},
                    "source": "Google"},
                   {"title": "치과 예약" if ko else "Dentist", "start": at(15, 0), "where": "", "open": {"kind": "event", "start": at(15, 0)}, "source": "iCloud"}],
        "missed": [{"kind": "followup", "title": "택배 조회 사이트에 로그인이 필요해요" if ko else "Sign in to the parcel tracking site", "open": {"kind": "followup", "id": 1}},
                   {"kind": "mail", "title": "견적서 회신 부탁드립니다" if ko else "Please reply about the quote", "detail": "kim@example.com",
                    "open": {"kind": "mail", "subject": "견적서 회신 부탁드립니다" if ko else "Please reply about the quote"},
                    "source": "Gmail"}],
        "hours": {"id": "hs_1", "day_type": "weekend", "wake_start": "10:00", "wake_end": "01:00", "current_start": "08:00", "current_end": "22:00"},
    }
    # the smarter card (server briefing.py): ids on the items, a headline (also in "line"), notes across sources, next steps;
    # KELO_MOCK_BRIEFING=long: long sentences and spaceless long labels for the wrapping check
    card["weather"]["id"] = "w"
    card["events"][0]["id"], card["events"][1]["id"] = "e1", "e2"
    card["missed"][0]["id"], card["missed"][1]["id"] = "m1", "m2"
    w = lambda ko_, en_: ko_ if ko else en_
    card["headline"] = card["line"]
    card["connections"] = [
        {"text": w("치과 예약 시간쯤 비가 시작돼요. 우산을 챙기세요.", "Rain starts around the dentist visit: take an umbrella."), "refs": ["w", "e2"]},
        {"text": w("견적서 메일과 팀 회의가 모두 오늘이에요.", "The quote mail and the team meeting both land today."), "refs": ["m2", "e1"]}]
    card["suggestions"] = [
        {"text": w("김 팀장님께 견적서 회신 초안을 써 드릴까요?", "Shall I draft a reply to Kim about the quote?"), "refs": ["m2"]},
        {"text": w("치과 가기 전에 우산 챙기라고 알려 드릴까요?", "Shall I remind you about the umbrella before the dentist?"), "refs": []}]
    if os.environ.get("KELO_MOCK_BRIEFING") == "long":
        card["headline"] = card["line"] = w(
            "오늘은 오후 3시부터 비가 오고, 3층 회의실 주간 회의와 치과 예약 사이에 이동 시간이 거의 없어서 하루가 빠듯해 보이니 일정 순서를 한번 확인해 보시면 좋겠어요.",
            "Rain from 3 PM, and with the weekly meeting and the dentist so close together there is hardly any travel time: the day looks tight, so it is worth a look at the order of things.")
        card["events"][0]["title"] = w("팀주간회의및분기계획공유세션입니다만길게이어지는이름", "Donaudampfschifffahrtsgesellschaftskapitaensbesprechung")
        card["connections"][0]["text"] = w("치과 예약 시간에 비가 시작되는데, 어제 보낸 견적서 질문도 아직 답을 기다리고 있어서 우산과 함께 한꺼번에 챙기는 게 좋아 보여요.",
                                           "The dentist visit lands right when the rain starts, and the quote question from last night is still waiting for your answer.")
        card["connections"][1]["refs"] = ["m2", "e1", "zz9"]
        card["suggestions"][0]["text"] = w("김 팀장님께 견적서 회신 요청에 대한 답장 초안을 지금 바로 써 드릴까요, 아니면 저녁에 한꺼번에 정리해 드릴까요?",
                                           "Shall I draft a reply to Kim about the quote request right now, or collect everything for this evening?")
    with s.lock:
        s.messages.append({"id": len(s.messages) + 1, "role": "assistant", "content": card["summary"], "created_at": now - 60, "briefing": card})
