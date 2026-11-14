"""Sample endpoints of custom skills (src/assistant/custom_skills.py): the skills waiting for approval on My → Records, and "Save as
skill" on a finished task or job.

Loaded by mock_server.py (see README.txt). Nothing is saved; changes last as long as the mock runs. A draft asked for here appears
as a new proposal after DRAFT_SECONDS (the real server asks the model; no model is called here).
"""

from __future__ import annotations

import re
import threading
import time
import uuid

NOW = time.time()
DRAFT_SECONDS = 3.0


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _w(s, ko: str, en: str) -> str:
    return ko if _ko(s) else en


_state: dict = {}


def _st(s) -> dict:
    if not _state:
        ko = _ko(s)
        new_text = ("## 언제 쓰나\n도서관 대출 기한이 다가올 때.\n\n## 단계\n1. 도서관 사이트에 열쇠고리 로그인으로 들어가요.\n2. 내 서재 → 대출 중 목록을 열어요.\n"
                    "3. 기한이 3일 안에 끝나는 책을 골라 연장을 눌러요.\n4. 새 반납일을 확인해요.\n\n## 주의할 점\n- 연체료가 있으면 연장이 안 돼요.\n"
                    "- 예약이 걸린 책은 연장할 수 없어요.\n\n## 잘 됐는지 확인\n- 목록에 새 반납일이 보여요."
                    if ko else
                    "## When to use\nA library loan is about to run out.\n\n## Steps\n1. Sign in to the library site with the saved login.\n"
                    "2. Open My library → Loans.\n3. Pick the books due within 3 days and press Renew.\n4. Check the new due date.\n\n"
                    "## Pitfalls\n- Unpaid fines block renewing.\n- A book someone reserved cannot be renewed.\n\n"
                    "## How to check it worked\n- The list shows the new due date.")
        _state.update({
            "items": [
                {"id": "p-new-1", "skill": "renew-library-book", "name": _w(s, "도서관 책 연장", "Renew library books"), "kind": "new",
                 "description": _w(s, "도서관 사이트에서 빌린 책을 연장해요.", "Renew borrowed books on the library site."),
                 "text": new_text, "diff": [], "reason": _w(s, "도서관 책 연장해줘", "Renew my library books"),
                 "origin": {"type": "draft", "task_id": 4}, "outside_text": True, "created_at": NOW - 600, "secrets_removed": 0},
                {"id": "p-change-2", "skill": "weekly-grocery-list", "name": _w(s, "주간 장보기 목록", "Weekly grocery list"), "kind": "change",
                 "description": _w(s, "매주 장보기 목록을 만들어 메모에 저장해요.", "Make the weekly grocery list and save it to notes."),
                 "text": "", "reason": _w(s, "장보기 목록 만들 때 배운 점", "What the last grocery list showed"),
                 "diff": [
                     {"op": "gap", "text": ""},
                     {"op": "same", "text": _w(s, "## 단계", "## Steps")},
                     {"op": "same", "text": _w(s, "1. 지난주 목록을 열어요.", "1. Open last week's list.")},
                     {"op": "del", "text": _w(s, "2. 빠진 걸 물어봐요.", "2. Ask what is missing.")},
                     {"op": "add", "text": _w(s, "2. 냉장고 메모에서 떨어진 것을 먼저 채워요.", "2. Fill in what the fridge note says ran out.")},
                     {"op": "add", "text": _w(s, "3. 그다음 빠진 걸 한 번에 물어봐요.", "3. Then ask what else is missing, all at once.")},
                     {"op": "same", "text": _w(s, "4. 메모에 저장해요.", "4. Save it to notes.")},
                 ],
                 "origin": {"type": "chat", "task_id": 1}, "outside_text": False, "created_at": NOW - 3600, "secrets_removed": 0},
            ],
            "drafting": [],
            "custom": [{"id": "weekly-grocery-list", "name": _w(s, "주간 장보기 목록", "Weekly grocery list"),
                        "description": _w(s, "매주 장보기 목록", "Weekly grocery list"), "source": "custom", "state": "ready", "author": ""}],
        })
    return _state


def _finish_draft(s, key: str, task_id, worker_id) -> None:
    time.sleep(DRAFT_SECONDS)
    st = _st(s)
    with s.lock:
        st["drafting"] = [d for d in st["drafting"] if d.get("key") != key]
        st["items"].insert(0, {
            "id": "p-" + uuid.uuid4().hex[:8], "skill": "check-parcel", "name": _w(s, "택배 위치 확인", "Check a parcel"), "kind": "new",
            "description": _w(s, "택배가 어디쯤인지 확인해요.", "Find where a parcel is."),
            "text": _w(s, "## 언제 쓰나\n택배가 늦을 때.\n\n## 단계\n1. 택배사 조회 페이지를 열어요.\n2. 송장 번호를 넣어요.\n\n## 주의할 점\n- (없음)\n\n## 잘 됐는지 확인\n- 마지막 스캔 위치가 보여요.",
                       "## When to use\nA parcel is late.\n\n## Steps\n1. Open the carrier's tracking page.\n2. Enter the tracking number.\n\n## Pitfalls\n- (none noted)\n\n## How to check it worked\n- The latest scan shows."),
            "diff": [], "reason": "", "origin": {"type": "draft", "task_id": task_id, "worker_id": worker_id}, "outside_text": False,
            "created_at": time.time(), "secrets_removed": 0})


def route(h, method: str, path: str, q: dict, s) -> bool:
    st = _st(s)
    if path == "/api/skills" and method == "GET":
        h.send_json({"items": [{"id": "research", "name": "Research", "description": "Research a question.", "source": "builtin",
                                "state": "ready", "author": ""}, *st["custom"]]})
        return True
    if path == "/api/skills/proposals" and method == "GET":
        h.send_json({"items": st["items"], "drafting": [{k: v for k, v in d.items() if k != "key"} for d in st["drafting"]]})
        return True
    m = re.fullmatch(r"/api/skills/proposals/([\w-]+)/(approve|discard)", path)
    if m and method == "POST":
        with s.lock:
            got = next((p for p in st["items"] if p["id"] == m.group(1)), None)
            if got is None:
                h.send_json({"detail": "no such proposal"}, 404)
                return True
            st["items"].remove(got)
            if m.group(2) == "approve" and got["kind"] == "new":
                st["custom"].append({"id": got["skill"], "name": got["skill"], "description": got["description"], "source": "custom",
                                     "state": "ready", "author": ""})
        h.send_json({"ok": True, "skill": got["skill"]} if m.group(2) == "approve" else {"ok": True})
        return True
    m = re.fullmatch(r"/api/(tasks|workers)/([\w-]+)/skill-draft", path)
    if m and method == "POST":
        key = f"{m.group(1)}:{m.group(2)}"
        with s.lock:
            if any(d.get("key") == key for d in st["drafting"]):
                h.send_json({"detail": "a draft from this run is being written"}, 409)
                return True
            task_id = int(m.group(2)) if m.group(1) == "tasks" and m.group(2).isdigit() else None
            worker_id = m.group(2) if m.group(1) == "workers" else None
            st["drafting"].append({"key": key, **({"task_id": task_id} if task_id is not None else {}),
                                   **({"worker_id": worker_id} if worker_id else {})})
        threading.Thread(target=_finish_draft, args=(s, key, task_id, worker_id), daemon=True).start()
        h.send_json({"status": "drafting"}, 202)
        return True
    return False
