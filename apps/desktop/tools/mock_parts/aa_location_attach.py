"""Backlog 24 and 26 for screenshots (set KELO_MOCK_LOCATION_CARD=1; written by the Android agent, used by every app). Named
"aa_" so it is tried before chat.py, which answers GET /api/approvals/pending for every run:

- the chat history has the user's own files: one photo sent with nothing typed (auto_text, the bubble shows only the picture),
  three photos with long typed text, and two documents with long names (file chips) after typed text;
- GET /api/approvals/pending lists a waiting location card (type location_request) with a long reason, so a reloaded chat shows
  it; KELO_MOCK_LOCATION_SETTINGS=1 marks it "refused before" (the card offers the phone's settings);
- POST /api/location-requests/{id} takes the answer (the card ends), GET /api/uploads/<name>/thumb|file give a small picture.
"""

from __future__ import annotations

import os
import struct
import time
import zlib

C = {"answered": False}
NOW = time.time()


def _png(w: int, h: int, rgb: tuple[int, int, int]) -> bytes:
    def chunk(t: bytes, d: bytes) -> bytes:
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
    row = b"\x00" + bytes(rgb) * w
    raw = b"".join(row for _ in range(h))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


COLORS = [(244, 162, 97), (42, 157, 143), (233, 196, 106), (231, 111, 81), (38, 70, 83)]


def _file(name: str, kind: str) -> dict:
    visual = kind in ("image", "video")
    return {"name": name, "path": "uploads/" + name, "kind": kind, "mime": "image/jpeg" if visual else "application/pdf", "size": 120_000,
            "url": f"/api/uploads/{name}/file", "thumb": f"/api/uploads/{name}/thumb" if visual else None}


def _messages(ko: bool) -> list[dict]:
    t = NOW - 600
    long_name = ("2026년-3분기-가족여행-예산-정리-최종본-수정3.pdf" if ko else "family-trip-budget-third-quarter-2026-final-revised-v3.pdf")
    return [
        {"id": 101, "role": "user", "created_at": t, "content": "IMG_2041.jpg 내용을 읽고 요약해줘." if ko else "Read IMG_2041.jpg and summarise it.",
         "attachments": [_file("IMG_2041.jpg", "image")], "auto_text": True},
        {"id": 102, "role": "assistant", "created_at": t + 20,
         "content": "영수증 사진이에요. 합계는 48,500원이고 날짜는 11월 8일이에요." if ko else "It's a receipt: 48,500 won in total, dated November 8."},
        {"id": 103, "role": "user", "created_at": t + 60,
         "content": ("이번 주말 캠핑 사진 세 장이에요. 사진마다 어디서 찍었는지, 같이 간 사람이 누구인지 정리하고 앨범 제목도 하나 붙여 줄래요? 너무 길지 않게요."
                     if ko else "Here are three photos from this weekend's camping trip. Can you note where each was taken and who was there, and suggest one album title? Keep it short.")
                    + "\n\n📎 uploads/camp-1.jpg\n📎 uploads/camp-2.jpg\n📎 uploads/camp-3.jpg",
         "attachments": [_file("camp-1.jpg", "image"), _file("camp-2.jpg", "image"), _file("camp-3.jpg", "image")], "auto_text": False},
        {"id": 104, "role": "assistant", "created_at": t + 90, "content": "앨범 제목은 「가을 숲, 셋이서」 어때요?" if ko else "How about \"Autumn woods, the three of us\"?"},
        {"id": 105, "role": "user", "created_at": t + 120,
         "content": ("두 문서를 비교해서 다른 점만 알려줘." if ko else "Compare the two documents and tell me only what differs.")
                    + f"\n\n📎 uploads/{long_name}\n📎 uploads/notes.docx",
         "attachments": [_file(long_name, "document"), _file("notes.docx", "document")], "auto_text": False},
    ]


def _location(ko: bool) -> dict:
    reason = ("근처 주차장을 찾고, 지금 있는 곳에서 걸어서 갈 수 있는 거리인지 확인하려면 이 휴대폰의 현재 위치가 필요해요. 위치는 이번 답에만 써요."
              if ko else "To find parking nearby and check whether it is within walking distance of where you are now, I need this phone's location. It is used for this answer only.")
    return {"type": "location_request", "kind": "location", "approval_id": "loc-mock-1", "state": "waiting", "reason": reason,
            "settings": os.environ.get("KELO_MOCK_LOCATION_SETTINGS") == "1", "name": "my_location", "label": "Kelo needs your location",
            "ask": "Kelo needs your location", "detail": reason, "site": "", "asked_at": NOW - 30, "expires_at": int(NOW + 3600),
            "task_id": 1, "tab": "owner", "unattended": False, "source": "chat", "worker_id": None, "title": "",
            "can_remember": False, "can_remember_schedule": False}


def route(h, method: str, path: str, q: dict, s) -> bool:
    if os.environ.get("KELO_MOCK_LOCATION_CARD") != "1":
        return False
    ko = (s.t.get("user") == "치즈") if isinstance(getattr(s, "t", None), dict) else True
    if path == "/api/messages" and method == "GET":
        h.send_json(_messages(ko))
        return True
    if path == "/api/approvals/pending" and method == "GET":
        h.send_json({"items": [] if C["answered"] else [_location(ko)], "resolved": []})
        return True
    if path.startswith("/api/location-requests/") and method == "POST":
        h.body()
        C["answered"] = True
        h.send_json({"ok": True})
        return True
    if path.startswith("/api/uploads/") and (path.endswith("/thumb") or path.endswith("/file")) and method == "GET":
        name = path.split("/")[3]
        png = _png(160, 160, COLORS[sum(map(ord, name)) % len(COLORS)])
        h.send_response(200)
        h.send_header("Content-Type", "image/png")
        h.send_header("Content-Length", str(len(png)))
        h.end_headers()
        h.wfile.write(png)
        return True
    return False
