"""Sample endpoints of the My page and its sub pages (devices, resources, heads-ups, follow-ups, records, documents, clearing the chat).

Loaded by mock_server.py (see README.txt). Nothing is saved; changes last as long as the mock runs.

KELO_MOCK_RESOURCES picks what GET /api/resources answers (PUT /mock/resources {"mode": ...} changes it while the mock runs):
  (unset)     the usual sample (a 20 GB storage cap, the recommended size)
  phone       the exact answer of the server that left the Android page blank (0.1.402102): no caps, and minimum/recommended
              memory as floats (536870912.0), as servers before 2026-11 sent them
  phone-ints  the same answer with every size a whole number (a fixed server)
  fail        500 every time (the page's error with "try again")
"""

from __future__ import annotations

import os
import re
import time

NOW = time.time()
GB = 1024 ** 3

# GET /api/resources, byte for byte, from the phone whose 마이 → Kelo 서버 자원 page stayed blank
PHONE_RESOURCES = ('{"memory":{"used":109551616,"limit":null},"cpus":null,"disk":{"used":1609985,"limit":null,'
                   '"parts":{"database":765952,"notes":763710,"uploads":42978,"other":37066,"history":279},"free_on_host":16335097856},'
                   '"host":{"memory":12514037760,"cpus":4,"disk_free":16335097856,"disk_total":83953606656},"profile":"unlimited",'
                   '"minimum":{"memory":536870912.0,"cpus":0.5,"disk":5637144576},"recommended":{"memory":1073741824.0,"cpus":1.0,"disk":13555990528}}')
RESOURCES = {"mode": os.environ.get("KELO_MOCK_RESOURCES", "").strip()}


def _send_raw(h, text: str, code: int = 200) -> None:
    body = text.encode()
    h.send_response(code)
    h.send_header("Content-Type", "application/json")
    h.send_header("Content-Length", str(len(body)))
    h.end_headers()
    h.wfile.write(body)


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


_state: dict = {}


def _st(s) -> dict:
    if not _state:
        ko = _ko(s)
        _state.update({
            "devices": [
                {"id": 3, "name": "Galaxy S25 · Kelo", "created_at": NOW - 86400 * 40, "last_seen": NOW - 300, "owner": True},
                {"id": 5, "name": "iPad · Kelo", "created_at": NOW - 86400 * 9, "last_seen": NOW - 86400 * 3, "owner": False},
                {"id": 7, "name": "Desktop · Kelo", "created_at": NOW - 3600, "last_seen": NOW, "owner": False},
            ],
            "proactive": {"enabled": True, "wake_start": "08:00", "wake_end": "22:00", "every_minutes": 120, "daily_regular": 2,
                          "daily_total": 3, "daily_now": 3, "today": {"regular": 1}, "awake": True, "auto_hours": False,
                          "hours_source": "default",
                          # a night owl: the learned window runs past midnight (active_hours.py), offered on 마이 → 기록
                          "hours_suggestion": {"id": "h7c41e", "wake_start": "10:00", "wake_end": "01:00", "current_start": "08:00",
                                               "current_end": "22:00", "active_days": 21, "created_at": NOW - 3600}},
            "checklist": {"text": "", "items": 2, "list": ["월세 이체 확인" if ko else "Check the rent transfer",
                                                          "택배 도착 알림" if ko else "Parcel arrival"]},
            "followups": {"enabled": True, "first_delay_min": 30, "second_delay_min": 180},
            "reflection": {"enabled": True, "notes": [], "last_run": {"at": NOW - 36000, "status": "done", "reason": "", "changed": True},
                           "rules": [
                               {"id": 1, "rule": "답은 짧게, 요점부터" if ko else "Short answers, the point first",
                                "evidence": "긴 답을 두 번 줄여 달라고 하셨어요" if ko else "You asked twice for a shorter answer",
                                "date": time.strftime("%Y-%m-%d", time.localtime(NOW - 86400)), "confidence": 0.82, "status": "proposed"},
                               {"id": 2, "rule": "일정은 24시간 형식으로" if ko else "Times in 24-hour format", "evidence": "", "date": "",
                                "confidence": 0.9, "status": "accepted"}],
                           # the memory pass that keeps the notes about the user current (memory_upkeep.py)
                           "memory": {"last_pass": {"at": NOW - 35000, "status": "done", "code": "", "added": 3, "tidied": 2,
                                                    "files": ["MEMORY.md", "memory/people/jiwoo.md", "user.md"], "trigger": "nightly",
                                                    "undone": False, "can_undo": True},
                                      "last_attempt": {"at": NOW - 35000, "status": "done", "code": "", "trigger": "nightly"}}},
            "memory_changes": [
                {"file": "user.md", "added": ["시간대: Asia/Seoul" if ko else "Timezone: Asia/Seoul"], "removed": []},
                {"file": "MEMORY.md",
                 "added": ["분기 보고서 초안은 금요일까지" if ko else "Quarterly report draft due Friday"],
                 "removed": ["보고서 표 정리: 시트 3개 확인, 42행 수정, 두 번 다시 확인함" if ko
                             else "Report tables: checked 3 sheets, fixed 42 rows, checked twice more",
                             "지난주 장보기 목록 작성 완료" if ko else "Finished last week's shopping list"]},
                {"file": "memory/people/jiwoo.md", "added": ["지우: 같은 팀 동료, 월요일마다 회의" if ko
                                                             else "Jiwoo: teammate, meets on Mondays"], "removed": []},
            ],
        })
    return _state


def _journal(s, month: str) -> dict:
    ko = _ko(s)
    days = [time.strftime("%Y-%m-%d", time.localtime(NOW - 86400 * i)) for i in range(1, 4)]
    items = [
        {"id": 11, "date": days[0], "title": "분기 보고서 뼈대" if ko else "Quarterly report outline",
         "summary": "지난 분기 문서를 바탕으로 목차를 잡았어요." if ko else "Outlined the report from last quarter's document.", "created_at": NOW - 86400},
        {"id": 12, "date": days[0], "title": "치과 예약 확인" if ko else "Dentist confirmed",
         "summary": "수요일 오후 3시로 캘린더에 넣었어요." if ko else "Added Wednesday 3 pm to the calendar.", "created_at": NOW - 86000},
        {"id": 13, "date": days[2], "title": "항공권 가격 확인" if ko else "Flight prices",
         "summary": "제주 항공권이 10만 원대예요." if ko else "Flights to Lisbon are around $420.", "created_at": NOW - 86400 * 3},
    ]
    return {"month": month, "items": [i for i in items if i["date"].startswith(month)],
            "pending": [{"date": d, "status": "pending", "reason": ""} for d in days[1:2] if d.startswith(month)]}


def route(h, method: str, path: str, q: dict, s) -> bool:
    st = _st(s)
    if path == "/api/devices" and method == "GET":
        h.send_json({"devices": st["devices"], "current": 7})
        return True
    m = re.fullmatch(r"/api/devices/(\d+)", path)
    if m and method == "DELETE":
        st["devices"] = [d for d in st["devices"] if d["id"] != int(m.group(1))]
        h.send_json({"ok": True})
        return True
    if path == "/mock/resources" and method == "PUT":
        RESOURCES["mode"] = str(h.body().get("mode", "")).strip()
        h.send_json({"ok": True, "mode": RESOURCES["mode"]})
        return True
    if path == "/api/resources" and RESOURCES["mode"]:
        mode = RESOURCES["mode"]
        if mode == "fail":
            h.send_json({"detail": "mock: resources unavailable"}, 500)
        elif mode == "phone-ints":
            _send_raw(h, PHONE_RESOURCES.replace("536870912.0", "536870912").replace("1073741824.0", "1073741824"))
        else:
            _send_raw(h, PHONE_RESOURCES)
        return True
    if path == "/api/resources":
        h.send_json({"memory": {"used": int(1.4 * GB), "limit": None}, "cpus": None,
                     "disk": {"used": int(2.6 * GB), "limit": 20 * GB,
                              "parts": {"database": 120_000_000, "uploads": 1_900_000_000, "images": 300_000_000, "notes": 4_000_000, "history": 210_000_000}},
                     "host": {"memory": 16 * GB, "cpus": 8.0, "disk_free": 180 * GB, "disk_total": 500 * GB},
                     "profile": "recommended", "minimum": {"memory": 4 * GB, "cpus": 2.0, "disk": 10 * GB},
                     "recommended": {"memory": 8 * GB, "cpus": 4.0, "disk": 20 * GB}})
        return True
    if path == "/api/proactive":
        if method == "PUT":
            body = h.body()
            pro = st["proactive"]
            if any(k in body and body[k] != pro.get(k) for k in ("wake_start", "wake_end")):
                body["hours_source"] = "manual"
            pro.update(body)
        h.send_json(st["proactive"])
        return True
    m = re.fullmatch(r"/api/proactive/hours/([^/]+)/(accept|keep)", path)
    if m and method == "POST":
        pro = st["proactive"]
        sug = pro.get("hours_suggestion")
        if not sug or sug["id"] != m.group(1):
            h.send_json({"detail": "no such suggestion"}, 404)
            return True
        if m.group(2) == "accept":
            pro.update(wake_start=sug["wake_start"], wake_end=sug["wake_end"], hours_source="learned")
        pro["hours_suggestion"] = None
        h.send_json(pro)
        return True
    if path == "/api/checklist":
        if method == "PUT":
            items = h.body().get("items") or []
            st["checklist"].update({"list": items, "items": len(items)})
        h.send_json(st["checklist"])
        return True
    if path == "/api/followups/settings":
        if method == "PUT":
            st["followups"].update(h.body())
        h.send_json(st["followups"])
        return True
    if path == "/api/journal" and method == "GET":
        h.send_json(_journal(s, (q.get("month") or [""])[0]))
        return True
    if path.startswith("/api/journal"):
        h.send_json({"ok": True})
        return True
    if path == "/api/reflection":
        h.send_json(st["reflection"])
        return True
    if path == "/api/reflection/enabled" and method == "PUT":
        st["reflection"]["enabled"] = bool(h.body().get("enabled"))
        h.send_json(st["reflection"])
        return True
    if path == "/api/reflection/memory" and method == "GET":
        h.send_json({**st["reflection"]["memory"], "changes": st["memory_changes"]})
        return True
    if path == "/api/reflection/memory/undo" and method == "POST":
        last = st["reflection"]["memory"]["last_pass"]
        if not last or last["undone"]:
            h.send_json({"detail": "nothing to undo"}, 404)
            return True
        last.update(undone=True, can_undo=False)
        h.send_json({"restored": last["files"], "kept": [], **st["reflection"]["memory"]})
        return True
    m = re.fullmatch(r"/api/reflection/rules/(\d+)", path)
    if m and method == "PATCH":
        rule = next((r for r in st["reflection"]["rules"] if r["id"] == int(m.group(1))), None)
        if rule is None:
            h.send_json({"detail": "not found"}, 404)
            return True
        rule["status"] = h.body().get("status", rule["status"])
        h.send_json(rule)
        return True
    if path == "/api/operator/status":
        h.send_json({"owner": True, "operator": True})
        return True
    if path == "/api/messages" and method == "DELETE":
        with s.lock:
            s.messages.clear()
        h.send_json({"ok": True, "deleted": True})
        return True
    if path == "/api/agent/avatar" and method == "GET":
        h.send_json({"detail": "no picture"}, 404)
        return True
    m = re.fullmatch(r"/api/uploads/([^/]+)/text", path)
    if m:
        ko = _ko(s)
        h.send_json({"name": m.group(1), "text": "# 분기 보고서 (초안)\n\n- 지난 분기 요약\n- 주요 지표\n- 다음 분기 계획" if ko else
                     "# Quarterly report (draft)\n\n- Last quarter in short\n- Key numbers\n- Next quarter's plan", "truncated": False})
        return True
    if re.fullmatch(r"/api/uploads/([^/]+)/(thumb|file)", path):
        h.send_json({"detail": "no preview"}, 404)
        return True
    return False
