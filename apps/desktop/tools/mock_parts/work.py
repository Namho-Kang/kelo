"""Sample data for the 작업 (Work) page: a running task with a background job and its browser tab, a task-wide permission
("이 작업에서 허용한 일", DELETE revokes it), a job waiting for an answer, a follow-up on hold, watchers with events, schedule runs and
the archive, task and job details, and the open browser tabs with their last pictures. Shapes follow src/assistant/server.py."""

from __future__ import annotations

import re
import struct
import time
import zlib

NOW = time.time()


def _png(w: int, h: int, top: tuple, bottom: tuple) -> str:
    """A small two-tone PNG as a data: URL (a page's header bar over its body), for the tab pictures."""
    import base64
    rows = b""
    for y in range(h):
        c = top if y < h // 4 else bottom
        if h // 4 <= y < h // 4 + 3 or (y > h // 2 and y % 9 < 2 and 0):
            c = (200, 200, 200)
        line = bytes(c) * w
        if y > h // 3 and y % 10 < 3:   # a few "text lines"
            line = bytes(c) * 6 + bytes((150, 150, 150)) * (w * 2 // 3) + bytes(c) * (w - 6 - w * 2 // 3)
        rows += b"\x00" + line
    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
    return "data:image/png;base64," + base64.b64encode(png).decode()


SHOT_BLUE = _png(160, 100, (40, 90, 200), (245, 247, 250))
SHOT_GREEN = _png(160, 100, (20, 140, 90), (250, 250, 245))
SHOT_GREY = _png(160, 100, (90, 90, 90), (240, 240, 240))

# kept per server run: revoked grants and job messages (the UI test reads them back)
STATE = {"grants": None, "revoked": [], "messages": [], "erased": set()}


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _w(s, ko: str, en: str) -> str:
    return ko if _ko(s) else en


def _grants(s):
    if STATE["grants"] is None:
        STATE["grants"] = [{"id": "g1", "site": "flights.example.com", "kind": "browser_action",
                            "action": _w(s, "검색 양식 제출", "submit search forms"), "max_count": 10, "used": 3, "task_id": 2,
                            "expires_at": NOW + 3600}]
    return STATE["grants"]


def _tasks(s):
    t = s.t["tasks"]
    return [
        {"id": 2, "title": t[1][0], "status": "running", "result": "", "source": "chat", "started_at": NOW - 600, "finished_at": None,
         "live_status": _w(s, "항공권 가격 비교 중", "Comparing fares"), "seen": True},
        {"id": 1, "title": t[0][0], "status": "done", "result": t[0][2], "source": "schedule:1", "started_at": NOW - 3600 * 3,
         "finished_at": NOW - 3600 * 3 + 300, "seen": False},
        {"id": 3, "title": t[2][0], "status": "failed", "result": t[2][2], "source": "chat", "started_at": NOW - 86400 - 3600,
         "finished_at": NOW - 86400 - 3000, "seen": True},
        {"id": 4, "title": _w(s, "주간 장보기 목록", "Weekly shopping list"), "status": "done", "result": _w(s, "목록을 메모에 저장했어요.", "Saved the list to notes."),
         "source": "background", "started_at": NOW - 86400 * 2, "finished_at": NOW - 86400 * 2 + 120, "seen": True},
    ]


def _workers(s):
    return [
        {"id": "w-fares", "kind": "browser", "title": _w(s, "항공사 세 곳 가격 확인", "Check three airlines"), "status": "running",
         "parent_task_id": 2, "tab": "task:2", "started_at": NOW - 500, "updated_at": NOW - 20, "steps": 7, "max_steps": 20,
         "status_text": _w(s, "두 번째 사이트에서 날짜 고르는 중", "Picking dates on the second site"), "last_url": "https://flights.example.com",
         "thumbnail": SHOT_BLUE},
        {"id": "w-hotel", "kind": "research", "title": _w(s, "제주 숙소 후보 찾기", "Find hotels in Lisbon"), "status": "needs_input",
         "parent_task_id": None, "started_at": NOW - 4000, "updated_at": NOW - 900, "steps": 12, "max_steps": 30, "waiting_for": "answer",
         "reason": _w(s, "예산을 1박 15만 원으로 할까요, 20만 원으로 할까요?", "Should the budget be $150 or $200 a night?")},
    ]


def _tabs(s):
    return {"available": True, "items": [
        {"id": "task:2", "label": "", "kind": "task", "title": _w(s, "항공권 검색 결과", "Flight search results"), "url": "https://flights.example.com/search",
         "host": "flights.example.com", "status": "working", "last_activity": NOW - 15, "task_id": 2, "thumbnail": SHOT_BLUE,
         "thumbnail_width": 160, "thumbnail_height": 100},
        {"id": "7", "label": "Desktop · Kelo", "kind": "device", "title": _w(s, "치과 예약 페이지", "Dentist booking"), "url": "https://dental.example.com",
         "host": "dental.example.com", "status": "idle", "last_activity": NOW - 3600, "thumbnail": SHOT_GREEN, "thumbnail_width": 160, "thumbnail_height": 100},
        {"id": "schedule:1", "label": "", "kind": "schedule", "title": _w(s, "아침 뉴스", "Morning news"), "url": "https://news.example.com",
         "host": "news.example.com", "status": "ended", "last_activity": NOW - 3600 * 5, "thumbnail": SHOT_GREY, "thumbnail_width": 160, "thumbnail_height": 100},
    ]}


def _watchers(s):
    return [{"id": 1, "name": s.t["watch"][0], "description": s.t["watch"][1], "enabled": True, "state": "watching", "kind": "page",
             "url": "https://flights.example.com/cju", "every_minutes": 60, "last_value": _w(s, "112,000원", "$436"),
             "last_checked_at": NOW - 1200, "next_check_at": NOW + 2400, "can_enable": True,
             "dry_run": {"at": NOW - 1200, "ok": True, "value": _w(s, "112,000원", "$436"), "detail": _w(s, "아직 기준보다 높아요", "Still above the target")}},
            {"id": 2, "name": _w(s, "콘서트 예매 오픈", "Concert tickets on sale"), "description": _w(s, "예매 버튼이 생기면 알려 줘요", "Tells you when booking opens"),
             "enabled": False, "state": "off", "every_minutes": 30, "can_enable": False}]


def route(h, method: str, path: str, q: dict, s) -> bool:
    if path == "/api/tasks" and method == "GET":
        if q.get("before"):
            h.send_json({"items": [], "unseen": 1, "next_before": None})
        else:
            h.send_json({"items": _tasks(s), "unseen": 1, "next_before": None})
        return True
    if path == "/api/tasks/seen":
        h.send_json({"ok": True})
        return True
    m = re.fullmatch(r"/api/tasks/(\d+)(/stop)?", path)
    if m:
        tid = int(m.group(1))
        if m.group(2):
            h.send_json({"ok": True})
            return True
        t = next((x for x in _tasks(s) if x["id"] == tid), _tasks(s)[1])
        answer = {1: _w(s, "## 보고서 뼈대\n\n- 지난 분기 요약\n- 주요 지표\n- 다음 분기 계획\n\n`uploads/분기보고서-초안.docx`에 저장했어요.",
                           "## Report outline\n\n- Last quarter in short\n- Key numbers\n- Next quarter's plan\n\nSaved to `uploads/quarterly-draft.docx`.")}
        h.send_json(dict(t, answer=answer.get(tid, ""), error="", approvals=[
            {"id": "a1", "task_id": tid, "tool": "browser_click", "label": _w(s, "검색 양식 제출", "Submit a search form"), "site": "flights.example.com",
             "decision": "allowed_once", "asked_at": NOW - 550}] if tid == 2 else []))
        return True
    if path == "/api/workers" and method == "GET":
        h.send_json({"items": _workers(s), "running": 1, "limit": 3})
        return True
    m = re.fullmatch(r"/api/workers/([^/]+)(?:/(stop|resume|message))?", path)
    if m:
        if m.group(2):
            if m.group(2) == "message":
                STATE["messages"].append(h.body().get("text", ""))
            h.send_json({"ok": True})
            return True
        w = next((x for x in _workers(s) if x["id"] == m.group(1)), None)
        if w is None:
            h.send_json({"detail": "not found"}, 404)
            return True
        h.send_json(dict(w, task=_w(s, "세 항공사에서 12월 20일 김포-제주 왕복 가격을 비교해 줘", "Compare round trips to Lisbon on Dec 20 on three airlines"),
                         url="https://flights.example.com", files=[], report=None, error=None,
                         lines=[{"at": NOW - 480, "text": _w(s, "첫 번째 사이트 열기", "Opened the first site")},
                                {"at": NOW - 300, "text": _w(s, "가장 싼 왕복 98,000원 확인", "Cheapest round trip $412")},
                                {"at": NOW - 60, "text": _w(s, "두 번째 사이트에서 날짜 고르는 중", "Picking dates on the second site")}]))
        return True
    if path == "/api/task-permissions" and method == "GET":
        h.send_json({"items": _grants(s)})
        return True
    m = re.fullmatch(r"/api/task-permissions/([^/]+)", path)
    if m and method == "DELETE":
        STATE["revoked"].append(m.group(1))
        STATE["grants"] = [g for g in _grants(s) if g["id"] != m.group(1)]
        h.send_json({"ok": True})
        return True
    if path == "/api/approvals/rules":
        h.send_json({"items": []})
        return True
    if path == "/api/approvals/history":
        h.send_json({"items": [], "next_before": None})
        return True
    if path == "/api/browser/sessions" and method == "GET" and q.get("thumbnails") == ["true"]:
        h.send_json(_tabs(s))
        return True
    if path == "/api/watchers" and method == "GET":
        h.send_json({"items": _watchers(s)})
        return True
    m = re.fullmatch(r"/api/watchers/(\d+)/(events|enable|dry-run)", path)
    if m:
        wid = int(m.group(1))
        w = next((x for x in _watchers(s) if x["id"] == wid), _watchers(s)[0])
        if m.group(2) == "events":
            h.send_json({"items": [{"id": 3, "at": NOW - 1200, "kind": "dry_run", "value": w.get("last_value")},
                                   {"id": 2, "at": NOW - 86400, "kind": "enabled"},
                                   {"id": 1, "at": NOW - 86400 * 2, "kind": "fired", "detail": _w(s, "98,000원으로 내려갔어요", "Dropped to $389")}]})
        elif m.group(2) == "enable":
            h.send_json(dict(w, enabled=bool(h.body().get("enabled"))))
        else:
            h.send_json({"result": w.get("dry_run"), "watcher": w})
        return True
    m = re.fullmatch(r"/api/schedules/(\d+)/runs", path)
    if m and m.group(1) == "4":   # the weekly order (it has standing permissions, see ops.py "grants")
        h.send_json({"items": [{"id": 3, "started_at": NOW - 86400 + 60, "status": "done",
                                "result": _w(s, "생수 2L 12병을 12,900원에 주문했어요.", "Ordered 12 bottles of water for ₩12,900.")}]})
        return True
    if m:
        h.send_json({"items": [{"id": 2, "started_at": NOW - 86400 + 60, "status": "done", "result": _w(s, "뉴스 다섯 개를 정리했어요.", "Summed up five stories.")},
                               {"id": 1, "started_at": NOW - 86400 * 2 + 60, "status": "failed", "error": _w(s, "사이트에 연결할 수 없었어요.", "Couldn't reach the site.")}]})
        return True
    if path == "/api/schedules/archive":
        kept = [] if 9 in STATE["erased"] else [{"id": 9, "name": _w(s, "여름 휴가 체크리스트", "Summer trip checklist"), "cron": "0 9 * * 1",
                                                 "enabled": False, "archived_at": NOW - 86400 * 3, "purge_at": NOW + 86400 * 27}]
        h.send_json({"items": kept + list(reversed(getattr(s, "sched_archived", []))), "keep_days": 30})
        return True
    m = re.fullmatch(r"/api/schedules/archive/(\d+)", path)
    if m and method == "DELETE":   # an archived schedule deleted for good
        sid = int(m.group(1))
        archived = getattr(s, "sched_archived", [])
        if sid == 9 and 9 not in STATE["erased"] or any(x["id"] == sid for x in archived):
            STATE["erased"].add(sid)
            archived[:] = [x for x in archived if x["id"] != sid]
            s.deleted.append(h.path)
            h.send_json({"ok": True})
        else:
            h.send_json({"detail": "not in the archive"}, 404)
        return True
    m = re.fullmatch(r"/api/schedules/(\d+)/restore", path)
    if m and method == "POST":   # back from the archive (switched off, as the server does)
        sid = int(m.group(1))
        archived = getattr(s, "sched_archived", [])
        row = next((x for x in archived if x["id"] == sid), None)
        archived[:] = [x for x in archived if x["id"] != sid]
        getattr(s, "sched_gone", set()).discard(sid)
        if sid == 9:
            STATE["erased"].add(9)   # the sample archived one leaves the archive (it is not in the sample list)
        h.send_json(dict(row or {"id": sid, "name": "", "cron": "0 9 * * 1"}, enabled=False))
        return True
    # GET /api/followups (the job held for a sign-in) is answered by chat.py, which serves the chat's follow-up too
    return False
