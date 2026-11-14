"""The chat's sample endpoints for mock_server.py: questions waiting from work the chat is not following, follow-up buttons, and a
browser card in the scripted reply.

- GET /api/approvals/pending: a background job's approval (with a site it can remember) and a human check on that job's browser
  tab. The human check ends by itself HUMAN_CHECK_SECONDS after the first time the list is asked for (as if it was solved in the
  live view), and is then listed under "resolved" ({"approval_id", "outcome": "solved"}).
- POST /api/approvals/{id} answers either (it leaves the list).
- GET /api/agent/status counts the waiting ones (pending_approvals, open_followups: the apps' red badge), so the app asks the list
  again when it changes; it also reports unseen tasks and notifications, which must never light the badge.
- GET /api/followups: one open follow-up on the newest agent message of the history, with Snooze and Drop buttons;
  POST /api/followups/{id}/snooze|drop settles it.
- The scripted reply (GET /api/turns/{id}) gets a browser card for the tab "task:w-mock" after its first tool.
With --fresh (a brand-new server) nothing waits: the pending list is empty.
KELO_MOCK_NO_ASKS=1: nothing waits either (the chat shows only the reply a test starts).
"""

from __future__ import annotations

import base64
import os
import struct
import time
import zlib

HUMAN_CHECK_SECONDS = 6.0
TAB = "task:w-mock"


def _png(w: int, h: int) -> str:
    """A small picture of a page (a header bar, a few lines) as a data: URL, made here so nothing is fetched."""
    rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            if y < h // 6:
                px = (63, 81, 181)
            elif (y // 6) % 3 == 0 and 8 < x < w - 8 - (y * 7) % (w // 2):
                px = (200, 205, 215)
            else:
                px = (250, 250, 252)
            row += bytes(px)
        rows.append(bytes(row))
    raw = zlib.compress(b"".join(rows))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", raw) + chunk(b"IEND", b"")
    return "data:image/png;base64," + base64.b64encode(png).decode()


PICTURE = _png(160, 100)


class _Chat:
    def __init__(self) -> None:
        self.first_asked: float | None = None
        self.answered: dict[str, bool] = {}
        self.followup_open = True
        self.patched = False


C = _Chat()


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _pending(s) -> tuple[list[dict], list[dict]]:
    if os.environ.get("KELO_MOCK_NO_ASKS"):   # a test that wants only its own reply in the chat (ShippingPagesTest)
        return [], []
    ko = _ko(s)
    now = time.time()
    if C.first_asked is None:
        C.first_asked = now
    items, resolved = [], []
    if "ap-job" not in C.answered:
        items.append({
            "type": "approval_request", "approval_id": "ap-job", "id": "j1", "name": "browser_click",
            "arguments": {}, "label": "결제 페이지 열기" if ko else "Opening the checkout page",
            "ask": "항공권 결제 페이지를 열까요?" if ko else "Open the flight's checkout page?",
            "lines": [{"label": "사이트" if ko else "Site", "value": "flights.example.com"}],
            "site": "flights.example.com", "can_remember": True, "asked_at": now - 240, "unattended": False,
            "source": "worker", "worker_id": "w-mock", "title": "제주 항공권 찾기" if ko else "Find a flight to Lisbon",
        })
    check_done = now - C.first_asked > HUMAN_CHECK_SECONDS
    if "ap-check" in C.answered:
        pass
    elif not check_done:
        items.append({
            "type": "approval_request", "approval_id": "ap-check", "id": "j2", "name": "human_check", "kind": "human_check",
            "tab": TAB, "arguments": {}, "label": "", "site": "flights.example.com", "asked_at": now - 60,
            "source": "worker", "worker_id": "w-mock", "title": "제주 항공권 찾기" if ko else "Find a flight to Lisbon",
        })
    else:
        resolved.append({"approval_id": "ap-check", "outcome": "solved"})
    return items, resolved


def _open_entries(s) -> int:
    """With --secret-entries: the open fill-in cards, counted as the real server does (GET /api/agent/status "secret_requests")."""
    if not os.environ.get("KELO_MOCK_SECRET_ENTRIES"):
        return 0
    import sys
    entries = sys.modules.get("mock_parts_agent_entries")
    return sum(1 for r in entries._requests(s) if r["state"] == "open") if entries else 0


def route(h, method: str, path: str, q: dict, s) -> bool:
    if s.fresh:   # a brand-new server (--fresh) has no work and no chat yet: nothing waits for an answer
        if path == "/api/approvals/pending" and method == "GET":
            h.send_json({"items": [], "resolved": []})
            return True
        return False
    if not C.patched:   # the scripted reply gets a browser card after its first tool
        C.patched = True
        original = s.reply_events

        def with_browser(turn: dict) -> list[dict]:
            ev = original(turn)
            at = next((i for i, e in enumerate(ev) if e["type"] == "tool_result" and e.get("id") == "c1"), None)
            if at is not None:
                ev.insert(at + 1, {"type": "browser", "id": "c1", "title": "Flights", "url": "https://flights.example.com/",
                                   "host": "flights.example.com", "status": "검색 결과 보는 중" if _ko(s) else "Looking at the results",
                                   "image": PICTURE, "width": 160, "height": 100, "at": time.time(), "tab": TAB})
            return ev

        s.reply_events = with_browser

    if path == "/api/approvals/pending" and method == "GET":
        items, resolved = _pending(s)
        h.send_json({"items": items, "resolved": resolved})
        return True
    if method == "POST" and path in ("/api/approvals/ap-job", "/api/approvals/ap-check"):
        C.answered[path.rsplit("/", 1)[-1]] = bool(h.body().get("approve"))
        h.send_json({"ok": True})
        return True
    if path == "/api/agent/status":
        items, _ = _pending(s)
        busy = any(not any(e["type"] == "done" for e in s.reply_events(tt)) for tt in s.turns.values())
        quiet = bool(os.environ.get("KELO_MOCK_NO_ASKS"))
        # what waits for the owner lights the apps' red badge; unseen finished work (2 here) and notifications never do
        h.send_json({"busy": busy, "status": s.t["tool"] if busy else None, "pending_approvals": len(items), "agent_name": s.t["agent"],
                     "tone": s.profile.get("tone", "polite"), "open_followups": 0 if quiet else 1 + int(C.followup_open),
                     "secret_requests": _open_entries(s), "unseen_tasks": 2, "unseen_notifications": 1, "avatar_version": 0})
        return True
    if path == "/api/followups" and method == "GET":
        # the chat's follow-up on the newest agent message, and a background job held for a sign-in (the Work page's asks)
        items = [{"id": "f-hold", "kind": "login", "title": "택배 조회 사이트에 로그인이 필요해요" if _ko(s) else "Sign in to the parcel tracking site",
                  "stage": "on_hold", "created_at": time.time() - 86400, "status": "open", "origin": "background",
                  "actions": [{"kind": "open_login", "name": "parcel"}, {"kind": "snooze", "minutes": 60},
                              {"kind": "drop", "followup_id": "f-hold"}]}]
        last = next((m["id"] for m in reversed(s.messages) if m["role"] == "assistant"), None)
        if last is not None:
            items.append({
                "id": "f1", "kind": "job", "title": "보고서 표 채우기" if _ko(s) else "Fill in the report's tables", "stage": "first",
                "created_at": time.time() - 3600, "message_id": last, "status": "open" if C.followup_open else "resolved",
                "actions": [{"kind": "snooze", "followup_id": "f1", "minutes": 60}, {"kind": "drop", "followup_id": "f1"}],
            })
        h.send_json({"items": items})
        return True
    if method == "POST" and path.startswith("/api/followups/f1/"):
        C.followup_open = False
        h.send_json({"ok": True})
        return True
    return False
