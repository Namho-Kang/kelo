"""mock_server.py --batch-scene (KELO_MOCK_BATCH): the scripted reply shows the chips of the 2026-11 fix batch.

A message sent in the chat gets: one photo looked at twice (a retry repeats the call: the chip says one photo), two calendar events
added one after the other, each with its own approval card between the calls (one chip for both, the cards under it), and a closing
question with yes / no answer buttons (quick_replies under a normal reply). The first card is answered at once ("allowed"); the second
waits for the app (answer it there), then the reply ends.
"""

from __future__ import annotations

import os
import time

C = {"patched": False}


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def route(h, method: str, path: str, q: dict, s) -> bool:
    if not os.environ.get("KELO_MOCK_BATCH"):
        return False
    if path == "/api/followups" and method == "GET":   # only the scene's own reply: no sample follow-up under it
        h.send_json({"items": []})
        return True
    if C["patched"]:
        return False
    C["patched"] = True
    ko = _ko(s)
    photo = {"label": "사진 보는 중" if ko else "Looking at a photo", "group": "view_image", "done": "사진 {n}장 봄" if ko else "Looked at {n} photos"}
    add = {"label": "일정 넣는 중" if ko else "Adding an event", "group": "gcal_create_event", "done": "일정 {n}개 넣음" if ko else "Added {n} events"}
    events = [("출국 KE893 ICN→PVG" if ko else "Flight KE893 ICN→PVG", "11월 16일 (월) 09:00" if ko else "Mon, Nov 16, 9:00"),
              ("귀국 KE894 PVG→ICN" if ko else "Flight KE894 PVG→ICN", "11월 19일 (목) 18:30" if ko else "Thu, Nov 19, 6:30 PM")]
    words = {
        "seen": "사진 속 항공권 두 장을 확인했어요. 캘린더에 넣을게요." if ko else "I read both flights in the photo. Adding them to your calendar.",
        "end": "\n\n두 일정 모두 넣었어요. 출발 하루 전에 짐 챙기라고 알려 드릴까요?" if ko else "\n\nBoth are in. Shall I remind you to pack the day before?",
        "yes": "네" if ko else "Yes", "no": "아니요" if ko else "No",
        "cal": "캘린더" if ko else "Calendar", "title": "제목" if ko else "Title", "when": "시간" if ko else "When",
        "own": "개인" if ko else "Personal",
    }

    def started(i: str, name: str, meta: dict, args: dict) -> dict:
        return {"type": "tool_started", "id": i, "name": name, "arguments": args, "hidden": False, **meta}

    def result(i: str, name: str, meta: dict, approved=None) -> dict:
        return {"type": "tool_result", "id": i, "name": name, "output": "ok", "is_error": False, "approved": approved, **meta}

    def card(aid: str, i: str, title: str, when: str) -> dict:
        return {"type": "approval_request", "approval_id": aid, "id": i, "name": "gcal_create_event", "arguments": {"title": title},
                "label": add["label"], "ask": "일정을 넣을까요?" if ko else "Add this event?",
                "lines": [{"label": words["title"], "value": title}, {"label": words["when"], "value": when},
                          {"label": words["cal"], "value": words["own"]}]}

    def reply_events(turn: dict) -> list[dict]:
        age = time.time() - turn["started"]
        ev: list[dict] = [{"type": "thinking"}]
        if age > 0.5:
            ev += [started("v1", "view_image", photo, {"name": "IMG_2041.jpg"}), result("v1", "view_image", photo),
                   started("v2", "view_image", photo, {"name": "IMG_2041.jpg"}), result("v2", "view_image", photo)]
        if age > 1.2:
            ev.append({"type": "text", "text": words["seen"]})
        if age > 1.8:
            ev += [started("e1", "gcal_create_event", add, {"title": events[0][0]}), card(turn["approval"] + "-1", "e1", *events[0]),
                   result("e1", "gcal_create_event", add, True),
                   started("e2", "gcal_create_event", add, {"title": events[1][0]}), card(turn["approval"], "e2", *events[1])]
            answer = s.approved.get(turn["approval"])
            if answer is not None:
                ev.append(result("e2", "gcal_create_event", add, answer))
                ev.append({"type": "text", "text": words["end"]})
                ev.append({"type": "replies"})
                ev.append({"type": "done", "input_tokens": 900, "output_tokens": 120, "steps": 5})
        return ev

    s.reply_events = reply_events
    s.t["options"] = [words["yes"], words["no"]]
    return False
