#!/usr/bin/env python3
"""A pretend Kelo server for trying the desktop app's screens with scripted data: no model, no network, nothing saved.

    python3 apps/desktop/tools/mock_server.py [--port 8765] [--lang ko|en] [--fresh [--signin]]

Pair with the link it prints (http://127.0.0.1:8765/#pair=...). A message sent in the chat gets a scripted reply that streams in
over a few seconds through GET /api/turns/{id} like a real one: a tool chip, an approval card (answer it in the app), more text
and answer buttons. Everything the app's pages read (goals, work, connectors, usage, documents...) has a few sample rows.
--fresh answers like a brand-new server: no model chosen (model_ready false), no provider ready, and the same /api/providers rows
a fresh src/assistant/server.py gives (24 services); --signin adds the five sign-in services a server lists after
`assistant enable oauth`. Then a key (PUT /api/providers/{name}/key), an address (PUT .../endpoint, for custom and azure-foundry)
or a sign-in (POST .../login/start: Claude pastes back "MOCK-CODE", ChatGPT is approved by itself after a few seconds) makes a
service ready (DELETE .../login signs it out again, refused while it runs the main model), its models are listed, and PUT /api/profile chooses one (an unofficial service asks for consent first, 428).
Only for screenshots and manual tests of the UI; the real server is src/assistant/server.py.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import threading
import time
import uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

PAIR_CODE = "desktopmockpair0001"


def _next_monday_9() -> float:
    """The next Monday 09:00 in Seoul (the weekly sample schedule's next run)."""
    from datetime import datetime, timedelta, timezone
    now = datetime.now(timezone(timedelta(hours=9)))
    day = now.replace(hour=9, minute=0, second=0, microsecond=0) + timedelta(days=(7 - now.weekday()) % 7)
    return (day if day > now else day + timedelta(days=7)).timestamp()
TOKEN = "mock-desktop-token"
# with --e2e: the port and key (base64url) of the KE2E gateway in front of this mock, for the pairing links it hands out
PUBLIC: dict = {"port": 0, "k": ""}
NOW = time.time()

TEXT = {
    "ko": {
        "user": "치즈", "agent": "Kelo",
        "history": [
            ("user", "이번 주 할 일 정리해줘"),
            ("assistant", "이번 주에 챙길 것들이에요.\n\n1. **월요일** 분기 보고서 초안\n2. **수요일** 치과 예약 확인\n3. **금요일** 가족 저녁 장소 예약\n\n보고서는 지난번 문서를 바탕으로 제가 먼저 뼈대를 잡아 둘까요?"),
            ("user", "응, 뼈대 잡아줘"),
            ("assistant", "보고서 뼈대를 `uploads/분기보고서-초안.docx`로 만들어 뒀어요. 목차는 이렇게 잡았어요.\n\n- 지난 분기 요약\n- 주요 지표\n- 다음 분기 계획\n\n<message_break/>필요하면 표도 채워 둘게요."),
        ],
        "tool": "사이트 여는 중", "tool2": "일정 확인",
        "reply1": "치과 예약 시간을 확인했어요. 이번 주 **수요일 오후 3시**예요. ",
        "reply2": "캘린더에 넣어 둘게요. 승인해 주세요.",
        "approval_label": "일정 추가", "lines": [["제목", "치과 예약"], ["시간", "11월 12일 (수) 15:00"], ["캘린더", "개인"]],
        "done": "캘린더에 넣었어요. 하루 전에 한 번 더 알려 드릴까요?",
        "options": ["좋아요", "30분 전에 알려줘", "괜찮아요"],
        "goals": [("매일 30분 걷기", "건강", 2, 5, "저녁 산책 코스 정하기"), ("영어 회화 3개월", "공부", 1, 6, None), ("이사 준비", "생활", 0, 0, None)],
        "goal_done": "가계부 정리",
        "schedules": [("아침 뉴스 요약", "매일 07:30"), ("주간 회고", "매주 금요일 18:00"), ("월세 알림", "매달 25일 09:00"), ("주간 생수 주문", "매주 월요일 09:00")],
        "tasks": [("분기 보고서 뼈대 만들기", "done", "보고서 초안을 만들었어요."), ("항공권 가격 확인", "running", ""), ("메일 정리", "failed", "메일함에 연결할 수 없었어요.")],
        "watch": ("제주 항공권 가격", "10만 원 아래로 내려가면 알려 줘요"),
        "suggest": ("주 2회 근력 운동", "대화에서 허리가 아프다고 하셨어요"),
        "facts": [("목표 시간", "하루 30분"), ("편한 시간", "저녁 8시 이후")],
        "steps": [("산책 코스 정하기", "done", "user"), ("걷기 기록 앱 연결", "done", "agent"), ("주간 걸음 수 확인", "doing", "agent"), ("주말 긴 코스 도전", "todo", "user"), ("한 달 돌아보기", "todo", "agent")],
    },
    "en": {
        "user": "Sam", "agent": "Kelo",
        "history": [
            ("user", "Sort out my week"),
            ("assistant", "Here's what's on this week.\n\n1. **Monday** draft the quarterly report\n2. **Wednesday** confirm the dentist\n3. **Friday** book the family dinner\n\nShall I sketch the report from last quarter's document?"),
            ("user", "Yes please"),
            ("assistant", "I put an outline in `uploads/quarterly-draft.docx`:\n\n- Last quarter in short\n- Key numbers\n- Next quarter's plan\n\n<message_break/>I can fill in the tables too."),
        ],
        "tool": "Opening a site", "tool2": "Checking the calendar",
        "reply1": "Your dentist appointment is on **Wednesday at 3 pm**. ",
        "reply2": "I'll add it to your calendar. Please approve.",
        "approval_label": "Adding an event", "lines": [["Title", "Dentist"], ["When", "Wed, Nov 12, 3:00 PM"], ["Calendar", "Personal"]],
        "done": "It's in your calendar. Want a reminder the day before?",
        "options": ["Sounds good", "30 minutes before", "No thanks"],
        "goals": [("Walk 30 minutes a day", "Health", 2, 5, "Pick an evening route"), ("Conversational Spanish in 3 months", "Learning", 1, 6, None), ("Get ready to move", "Home", 0, 0, None)],
        "goal_done": "Budget spreadsheet",
        "schedules": [("Morning news roundup", "Every day at 7:30 AM"), ("Weekly review", "Fridays at 6:00 PM"), ("Rent reminder", "Monthly on the 25th at 9:00 AM"), ("Weekly water order", "Mondays at 9:00 AM")],
        "tasks": [("Outline the quarterly report", "done", "The draft is ready."), ("Check flight prices", "running", ""), ("Tidy the inbox", "failed", "Couldn't reach the mailbox.")],
        "watch": ("Flight to Lisbon", "Tells you when it drops below $400"),
        "suggest": ("Strength training twice a week", "You mentioned back pain in the chat"),
        "facts": [("Target", "30 minutes a day"), ("Best time", "After 8 pm")],
        "steps": [("Pick a route", "done", "user"), ("Connect a step tracker", "done", "agent"), ("Check weekly steps", "doing", "agent"), ("Try a long weekend walk", "todo", "user"), ("Look back after a month", "todo", "agent")],
    },
}


# A fresh server's GET /api/providers, row for row (src/assistant/server.py get_providers with no key, no address, no sign-in):
# (name, display_name, takes_key, takes_url, configured, effort_levels)
FRESH = [
    ("alibaba", "Alibaba Cloud (Qwen)", True, False, False, []),
    ("anthropic", "anthropic", True, False, False, []),
    ("azure-foundry", "Azure Foundry", True, True, False, []),
    ("bedrock", "bedrock", False, False, None, []),
    ("cerebras", "Cerebras", True, False, False, []),
    ("custom", "Custom endpoint", True, True, False, ["low", "medium", "high", "max"]),
    ("deepinfra", "DeepInfra", True, False, False, []),
    ("deepseek", "DeepSeek", True, False, False, []),
    ("fireworks", "Fireworks AI", True, False, False, []),
    ("google", "Google", True, False, False, ["low", "medium", "high"]),
    ("groq", "Groq", True, False, False, []),
    ("huggingface", "HuggingFace", True, False, False, []),
    ("minimax", "minimax", True, False, False, []),
    ("mistral", "Mistral AI", True, False, False, []),
    ("nebius-token-factory", "Nebius Token Factory", True, False, False, []),
    ("novita", "NovitaAI", True, False, False, []),
    ("nvidia", "NVIDIA NIM", True, False, False, []),
    ("ollama-cloud", "ollama-cloud", True, False, False, []),
    ("openai", "OpenAI (API key)", True, False, False, ["low", "medium", "high"]),
    ("openrouter", "OpenRouter", True, False, False, ["low", "medium", "high"]),
    ("together", "Together AI", True, False, False, []),
    ("upstage", "Upstage Solar", True, False, False, []),
    ("xai", "xai", True, False, False, []),
    ("zai", "Z.AI (GLM)", True, False, False, []),
]

# ... and the rows `assistant enable oauth` adds (Claude's CLI is installed but signed out, agy is not installed)
SIGNIN = [
    {"cli": {"installed": False, "installable": True, "logged_in": None, "version": None, "update_hint": "In a terminal: `agy update`",
             "hint": "Run `agy` once in a terminal and sign in with your Google account"},
     "name": "antigravity-oauth", "display_name": "Google Antigravity (agy CLI)", "oauth": False, "logged_in": None,
     "effort_levels": [], "effort_note": "For agy this is set by the low/medium/high at the end of the model name. Choose it in the model picker",
     "vendor": "Google", "kind": "url_code"},
    {"cli": {"installed": True, "installable": False, "logged_in": False, "account": None, "version": "2.1.319 (Claude Code)",
             "update_hint": "In a terminal: `claude update`", "hint": "Run `claude` in a terminal and sign in with /login"},
     "name": "claude-subscription-directsdk-experimental", "display_name": "Claude subscription", "oauth": False, "logged_in": None,
     "effort_levels": ["low", "medium", "high", "xhigh", "max"], "effort_note": None, "vendor": "Anthropic", "kind": "url_code"},
    {"cli": None, "name": "minimax-oauth", "display_name": "MiniMax (OAuth)", "oauth": True, "logged_in": False, "effort_levels": [],
     "effort_note": None, "vendor": "MiniMax", "kind": "device_code"},
    {"cli": None, "name": "openai-codex", "display_name": "openai-codex", "oauth": True, "logged_in": False, "effort_levels": ["low", "medium", "high"],
     "effort_note": None, "vendor": "OpenAI", "kind": "device_code"},
    {"cli": None, "name": "qwen-oauth", "display_name": "qwen-oauth", "oauth": True, "logged_in": False, "effort_levels": [],
     "effort_note": None, "vendor": "Alibaba", "kind": "device_code"},
]

# What a provider lists once it is ready (scripted; nothing is asked of any real service)
FRESH_MODELS = {
    "openai": ["gpt-5.5", "gpt-5.5-mini", "o5"], "openrouter": ["anthropic/claude-sonnet-5-5", "google/gemini-3.8-flash", "deepseek/deepseek-v4"],
    "custom": ["qwen3:8b", "llama4:16b"], "claude-subscription-directsdk-experimental": ["claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5"],
    "openai-codex": ["gpt-5.5-codex", "gpt-5.5"],
}


class FreshServer:
    """A brand-new server's providers: keys, addresses, sign-ins and consent are kept in memory only."""

    def __init__(self, signin: bool) -> None:
        self.keys: dict[str, str] = {}
        self.urls: dict[str, str] = {}
        self.signed: dict[str, str] = {}        # name -> account
        self.consent: dict[str, dict] = {}
        self.logins: dict[str, dict] = {}       # session -> {name, kind, started}
        self.signouts: list[str] = []           # the providers signed out (DELETE .../login), for the tests: GET /api/_mock/signouts
        self.signin = signin

    def rows(self) -> list[dict]:
        out = []
        for name, display, takes_key, takes_url, configured, levels in FRESH:
            key = self.keys.get(name)
            ready = configured if not takes_key and not takes_url else ((name in self.urls) if takes_url else key is not None)
            out.append({"cli": None, "name": name, "display_name": display, "supported": True, "oauth": False, "logged_in": None,
                        "signed_in": None, "account": None, "configured": ready, "effort_levels": levels, "effort_note": None,
                        "takes_key": takes_key, "key_source": "app" if key else None, "key_hint": key[-4:] if key else None,
                        "takes_url": takes_url, **({"base_url": self.urls.get(name, "")} if takes_url else {}),
                        "unofficial": False, "signin": False})
        if self.signin:
            for r in SIGNIN:
                acct = self.signed.get(r["name"])
                cli = dict(r["cli"], logged_in=True, account=acct) if r["cli"] and acct else r["cli"]
                out.append({"cli": cli, "name": r["name"], "display_name": r["display_name"], "supported": True, "oauth": r["oauth"],
                            "logged_in": (acct is not None) if r["oauth"] else None, "signed_in": acct is not None, "account": acct,
                            "configured": acct is not None, "effort_levels": r["effort_levels"], "effort_note": r["effort_note"],
                            "takes_key": False, "key_source": None, "key_hint": None, "takes_url": False, "unofficial": True, "signin": True,
                            "vendor": r["vendor"], "consent": self.consent.get(r["name"]), "consent_version": 1})
        return sorted(out, key=lambda r: r["name"])

    def row(self, name: str) -> dict | None:
        if name == "claude-subscription":
            name = "claude-subscription-directsdk-experimental"
        return next((r for r in self.rows() if r["name"] == name), None)

    def models(self, name: str) -> dict:
        r = self.row(name)
        if r is None:
            return {"provider": name, "models": [], "labels": {}, "info": {}, "error": f"unknown provider {name!r}"}
        name = r["name"]
        if not r["configured"]:
            err = ("No base URL is set. Enter the address first" if r["takes_url"] else
                   "Not signed in" if r["signin"] else f"No API key ({name.upper().replace('-', '_')}_API_KEY)")
            return {"provider": name, "models": [], "labels": {}, "info": {}, "error": err}
        found = FRESH_MODELS.get(name, [f"{name}-large", f"{name}-small"])
        return {"provider": name, "models": found, "labels": {}, "info": {m: {"images": True} for m in found}, "error": None}


# token use by kind of work (GET /api/usage "jobs"): kind, calls, input, output, cache-read tokens. Sample numbers; a cache share only
# where the sample provider reports one, as the real server shows it.
USAGE_TODAY = [("chat", 14, 150000, 7600, 98000), ("compaction", 2, 21000, 900, 0), ("proactive", 3, 9000, 300, 0), ("memory", 1, 2000, 600, 0)]
USAGE_MONTH = [("chat", 310, 3900000, 171000, 2450000), ("schedule", 42, 610000, 28000, 300000), ("reflection", 9, 240000, 12000, 0),
               ("memory", 18, 160000, 9000, 0), ("compaction", 21, 150000, 7000, 0), ("journal", 9, 90000, 5000, 0), ("proactive", 60, 70000, 2000, 0),
               ("worker", 6, 64000, 3000, 21000)]


def usage_part(rows: list) -> dict:
    kinds = [{"kind": k, "calls": n, "input_tokens": i, "output_tokens": o, "cache_read_tokens": r, "cache_write_tokens": 0,
              "cache_share": round(r / i, 3) if i else None} for k, n, i, o, r in rows]
    tot = {key: sum(k[key] for k in kinds) for key in ("calls", "input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens")}
    return {"since": "", **tot, "cache_share": round(tot["cache_read_tokens"] / tot["input_tokens"], 3) if tot["input_tokens"] else None,
            "kinds": kinds}


def usage_background(s) -> dict:
    return {"provider": s.profile.get("background_provider", ""), "model": s.profile.get("background_model", ""),
            "kinds": ["compaction", "journal", "memory", "proactive", "reflection"], "last_fallback": None}


class State:
    def __init__(self, lang: str, fresh: FreshServer | None = None) -> None:
        self.t = TEXT[lang]
        self.fresh = fresh
        self.lock = threading.Lock()
        self.messages = [
            {"id": i + 1, "role": r, "content": c, "created_at": NOW - 3600 * (5 - i)} for i, (r, c) in enumerate(self.t["history"])
        ]
        self.turns: dict[str, dict] = {}
        self.approved: dict[str, bool] = {}
        self.profile = {"user_name": self.t["user"], "agent_name": self.t["agent"], "provider": "antigravity-oauth",
                        "model": "gemini-3.8-flash-high", "fallback_provider": "claude-subscription", "fallback_model": "claude-sonnet-5-5",
                        "background_provider": "", "background_model": "", "effort": "", "tone": "polite", "avatar_version": 0, "model_ready": True}
        self.goals = [
            {"id": i + 1, "title": g[0], "category": g[1], "note": "", "status": "tracking", "origin": "user",
             "plan": {"done": g[2], "total": g[3]}, "next_step": g[4], "asking": i == 1, "schedules_on": 2 if i == 0 else 0}
            for i, g in enumerate(self.t["goals"])
        ] + [{"id": 9, "title": self.t["goal_done"], "status": "done", "plan": {"done": 3, "total": 3}}]
        self.proactive = {"enabled": True, "wake_start": "08:00", "wake_end": "22:00"}
        self.deleted: list[str] = []   # DELETE paths with their query (GET /mock/deleted: the goal-delete test reads them)
        self.sched_gone: set[int] = set()   # schedules deleted or put in the archive (no longer in GET /api/schedules)
        self.sched_archived: list[dict] = []   # schedules put in the archive here (GET /api/schedules/archive, mock_parts/work.py)
        if fresh:   # as a new server answers: nothing chosen, no names yet, no chat
            self.profile = {"user_name": "", "agent_name": "", "provider": "", "model": "", "fallback_provider": "", "fallback_model": "",
                            "background_provider": "", "background_model": "", "effort": "", "timezone": "", "tone": "polite", "avatar_version": 0,
                            "model_ready": False}
            self.messages = []

    def reply_events(self, turn: dict) -> list[dict]:
        """The scripted reply as it stands now: events appear with time, the second half after the approval was answered."""
        t = self.t
        age = time.time() - turn["started"]
        ev: list[dict] = [{"type": "thinking"}]
        if REACTIONS:   # --reactions: 👀 right before the model's first event (its thinking here, at once), as server.py sends it
            ev.insert(0, {"type": "reaction", "message_id": turn.get("msg", 0), "emoji": READ})
        if age > 0.8:
            ev.append({"type": "tool_started", "id": "c1", "name": "browser_open", "arguments": {}, "label": t["tool"]})
        if age > 1.8:
            ev.append({"type": "tool_result", "id": "c1", "name": "browser_open", "output": "ok", "is_error": False, "approved": None, "label": t["tool"]})
            ev.append({"type": "tool_started", "id": "c2", "name": "calendar_list", "arguments": {}, "label": t["tool2"]})
        if age > 2.6:
            ev.append({"type": "tool_result", "id": "c2", "name": "calendar_list", "output": "ok", "is_error": False, "approved": None, "label": t["tool2"]})
        text = t["reply1"] + t["reply2"]
        if age > 3.0:
            # whole 12-character pieces only, so the events already sent never change (as on the real server)
            shown = min(len(text), int((age - 3.0) * 30))
            end = len(text) if shown >= len(text) else shown - shown % 12
            for i in range(0, end, 12):
                ev.append({"type": "text", "text": text[i:min(i + 12, end)]})
        if age > 3.0 + len(text) / 30 + 0.3:
            ev.append({"type": "tool_started", "id": "c3", "name": "calendar_add_event", "arguments": {}, "label": t["approval_label"]})
            ev.append({"type": "approval_request", "approval_id": turn["approval"], "id": "c3", "name": "calendar_add_event", "arguments": {},
                       "label": t["approval_label"], "lines": [{"label": a, "value": b} for a, b in t["lines"]]})
            answer = self.approved.get(turn["approval"])
            if answer is not None:
                ev.append({"type": "tool_result", "id": "c3", "name": "calendar_add_event", "output": "ok", "is_error": False, "approved": answer,
                           "label": t["approval_label"]})
                ev.append({"type": "text", "text": "\n\n" + t["done"]})
                ev.append({"type": "replies"})
                if REACTIONS:   # the work is over: one emoji that fits it
                    ev.append({"type": "reaction", "message_id": turn.get("msg", 0), "emoji": FINAL_REACTION})   # 🦷 unless --final-reaction
                ev.append({"type": "done", "input_tokens": 1200, "output_tokens": 180, "steps": 3})
        return ev


def load_parts() -> list:
    """The modules in mock_parts/ next to this file, by name (each answers its own endpoints first)."""
    import importlib.util
    import pathlib
    out = []
    for f in sorted((pathlib.Path(__file__).parent / "mock_parts").glob("*.py")):
        if f.name.startswith("_"):
            continue
        spec = importlib.util.spec_from_file_location("mock_parts_" + f.stem, f)
        mod = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = mod   # one part may read another's state (chat.py's status counts agent_entries' open cards)
        spec.loader.exec_module(mod)
        out.append(mod)
    return out


PARTS: list = load_parts()


class Handler(BaseHTTPRequestHandler):
    state: State

    def log_message(self, fmt: str, *args) -> None:  # noqa: D401 - quiet
        pass

    def end_headers(self) -> None:
        # every answer says which server release it is, like src/assistant/server.py (docs/versioning.md);
        # KELO_MOCK_SERVER_VERSION=0.8.0+1 pretends an older server, =none an older one without the header
        import os
        v = os.environ.get("KELO_MOCK_SERVER_VERSION", "0.9.0+1")
        if v != "none":
            self.send_header("X-Kelo-Server", v)
        # this server speaks HTTP/1.0 and drops the connection after each answer; without saying so, OkHttp pools it and its next
        # request with a body that can be sent only once (an upload part) fails on the closed socket (seen without the KE2E gateway, as on CI)
        self.send_header("Connection", "close")
        super().end_headers()

    def send_json(self, obj, code: int = 200) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        try:
            return json.loads(raw or b"{}")
        except ValueError:
            return {}

    def authed(self) -> bool:
        if self.headers.get("Authorization") == f"Bearer {TOKEN}":
            return True
        self.send_json({"detail": "unauthorized"}, 401)
        return False

    def route(self, method: str) -> None:
        url = urlsplit(self.path)
        path, q = url.path, parse_qs(url.query)
        s = self.state
        t = s.t
        if method == "POST" and path == "/api/pair":
            b = self.body()
            return self.send_json({"token": TOKEN, "device_id": 7} if b.get("code") == PAIR_CODE else {"detail": "this link has expired"},
                                  200 if b.get("code") == PAIR_CODE else 400)
        if path == "/healthz":
            return self.send_json({"ok": True})
        if method == "PUT" and path.startswith("/api/uploads/") and not path.startswith("/api/uploads/parts/"):
            n = int(self.headers.get("Content-Length") or 0)
            left = n
            while left > 0:
                left -= len(self.rfile.read(min(left, 1 << 16)))
            if not self.authed():
                return
            limit = os.environ.get("MOCK_UPLOAD_SINGLE_MAX", "")   # a route with a per-request limit (tools/mock_parts/uploads.py)
            if limit.isdigit() and n > int(limit):
                return self.send_json({"detail": "Through this connection one request can be up to 100 MB; the app sends bigger files in parts."}, 413)
            return self.send_json({"path": "uploads/" + path.rsplit("/", 1)[-1]})
        if not self.authed():
            return
        if s.fresh and self.fresh_route(method, path, q):
            return
        # more sample endpoints, one module per part of the app (tools/mock_parts/*.py: route(handler, method, path, q, state) -> bool)
        for part in PARTS:
            if part.route(self, method, path, q, s):
                return
        if path == "/api/profile":
            if method == "PUT":
                b = {k: v for k, v in self.body().items() if v is not None}
                if "tone" in b and b["tone"] not in ("friendly", "polite"):   # as the real server: only the two tones
                    return self.send_json({"detail": "tone must be friendly or polite"}, 422)
                s.profile.update(b)
            return self.send_json(s.profile)
        if path == "/api/providers":
            return self.send_json([
                {"name": "antigravity-oauth", "display_name": "Antigravity", "configured": True, "unofficial": True, "vendor": "Google",
                 "company": "Google", "consent": {"accepted_at": NOW, "version": 1}, "consent_version": 1, "effort_note": "The level is part of the model's name."},
                {"name": "claude-subscription", "display_name": "Claude", "configured": True, "unofficial": True, "vendor": "Anthropic",
                 "company": "Anthropic", "consent": {"accepted_at": NOW, "version": 1}, "consent_version": 1, "effort_levels": ["low", "medium", "high"]},
                {"name": "openrouter", "display_name": "OpenRouter", "configured": False, "company": "OpenRouter"},
            ])
        if path == "/api/models":
            p = q.get("provider", [""])[0]
            models = {"antigravity-oauth": ["gemini-3.8-flash-high", "gemini-3.8-flash-low", "gemini-3.5-pro"],
                      "claude-subscription": ["claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5"]}.get(p, [])
            return self.send_json({"provider": p, "models": models, "info": {m: {"images": True} for m in models}})
        if path == "/api/messages":
            limit = int(q.get("limit", ["100"])[0])
            with s.lock:
                return self.send_json(s.messages[-limit:])
        if path == "/api/ai-consent":   # --ai-consent: asking once before chats go to the AI service (src/assistant/ai_consent.py)
            if method == "PUT":
                AI_CONSENT["accepted"] = True
            return self.send_json({"accepted": AI_CONSENT["accepted"], "needed": not AI_CONSENT["accepted"], "company": AI_CONSENT["company"],
                                   "privacy_url": "https://kelo.chat/privacy", "version": 1})
        if path == "/api/turns" and method == "POST":
            body = self.body()
            text = body.get("text", "")
            if not AI_CONSENT["accepted"] and body.get("ai_consent") == "check":
                return self.send_json({"detail": {"code": "ai_consent_required", "company": AI_CONSENT["company"],
                                                  "privacy_url": "https://kelo.chat/privacy", "version": 1}}, 428)
            if SECRETS and body.get("secrets") != "allow":   # --secrets: the real server's check (src/assistant/secret_guard.py)
                hits = SECRET_GUARD.find(text)
                if hits:
                    return self.send_json({"detail": SECRET_GUARD.describe(text, hits, "[hidden key]")}, 409)
            tid = uuid.uuid4().hex[:12]
            with s.lock:
                # no 👀 yet: the server puts it on once the model starts answering (the reply's first events, below)
                s.messages.append({"id": len(s.messages) + 1, "role": "user", "content": text, "created_at": time.time()})
                s.turns[tid] = {"started": time.time(), "approval": "ap-" + tid, "saved": False, "msg": len(s.messages)}
            return self.send_json({"id": tid})
        m = re.fullmatch(r"/api/turns/([^/]+)", path)
        if m and method == "GET":
            turn = s.turns.get(m.group(1))
            if not turn:
                return self.send_json({"detail": "that reply is gone"}, 404)
            after = int(q.get("after", ["0"])[0])
            deadline = time.time() + min(float(q.get("wait", ["20"])[0]), 1.0)
            while True:
                ev = s.reply_events(turn)
                done = any(e["type"] == "done" for e in ev)
                if len(ev) > after or done or time.time() > deadline:
                    break
                time.sleep(0.1)
            if REACTIONS and not turn.get("read") and 0 < turn.get("msg", 0) <= len(s.messages):
                with s.lock:   # the model has started (its first events went out): 👀 is kept with the message too
                    turn["read"] = True
                    if not s.messages[turn["msg"] - 1].get("reaction"):
                        s.messages[turn["msg"] - 1]["reaction"] = READ
            if done and not turn["saved"]:
                with s.lock:
                    turn["saved"] = True
                    if REACTIONS and 0 < turn.get("msg", 0) <= len(s.messages):
                        s.messages[turn["msg"] - 1]["reaction"] = FINAL_REACTION   # kept with the message, as the server does
                    s.messages.append({"id": len(s.messages) + 1, "role": "assistant", "created_at": time.time(),
                                       "content": "".join(e["text"] for e in ev if e["type"] == "text"),
                                       "replies": {"questions": [{"question": "", "options": t["options"]}]}})
            return self.send_json({"events": ev[after:], "next": len(ev), "done": done})
        if m and method == "DELETE":
            s.turns.pop(m.group(1), None)
            return self.send_json({"ok": True})
        m = re.fullmatch(r"/api/approvals/([^/]+)", path)
        if m and method == "POST" and m.group(1) not in ("pending",):
            s.approved[m.group(1)] = bool(self.body().get("approve"))
            return self.send_json({"ok": True})
        if path == "/api/approvals/pending":
            return self.send_json({"items": [{"type": "approval_request", "approval_id": "ap-sched", "id": "x", "name": "email_send",
                                              "arguments": {}, "label": "메일 보내기" if t is TEXT["ko"] else "Sending an email",
                                              "lines": [{"label": "To" if t is TEXT["en"] else "받는 사람", "value": "team@example.com"}],
                                              "site": "", "asked_at": NOW - 600, "unattended": True}]})
        if path == "/api/agent/status":
            busy = any(not any(e["type"] == "done" for e in s.reply_events(tt)) for tt in s.turns.values())
            return self.send_json({"busy": busy, "status": t["tool"] if busy else None, "pending_approvals": 0, "agent_name": t["agent"],
                                   "tone": s.profile.get("tone", "polite"), "unseen_tasks": 1, "unseen_notifications": 0, "avatar_version": 0})
        if path == "/api/goals" and method == "GET":
            return self.send_json(s.goals)
        if path == "/api/goals/suggestions":
            return self.send_json({"items": [{"id": 1, "title": t["suggest"][0], "reason": t["suggest"][1]}]})
        m = re.fullmatch(r"/api/goals/(\d+)", path)
        if m and method == "GET":
            g = next((x for x in s.goals if x["id"] == int(m.group(1))), s.goals[0])
            return self.send_json({"goal": g, "facts": [{"key": k, "value": v} for k, v in t["facts"]],
                                   "steps": [{"id": i + 1, "title": a, "status": b, "owner": o} for i, (a, b, o) in enumerate(t["steps"])],
                                   "metrics": [{"id": 1, "name": "걸음 수" if t is TEXT["ko"] else "Steps", "unit": "", "start": 3000, "target": 8000,
                                                "values": [{"value": 3000, "at": NOW - 86400 * 7}, {"value": 6400, "at": NOW}]}],
                                   "log": [{"id": 1, "at": NOW - 86400, "text": t["steps"][1][0]}]})
        if path == "/mock/deleted":
            return self.send_json({"items": s.deleted})
        if path.startswith("/api/goals"):
            if method == "DELETE":
                s.deleted.append(self.path)
            return self.send_json({"ok": True})
        if path == "/api/tasks":
            items = [{"id": i + 1, "title": a, "status": b, "result": r, "source": "chat" if i else "schedule:1",
                      "started_at": NOW - 3600 * (i + 1), "finished_at": None if b == "running" else NOW - 3000 * (i + 1),
                      "live_status": t["tool"] if b == "running" else None} for i, (a, b, r) in enumerate(t["tasks"])]
            return self.send_json({"items": items, "unseen": 1})
        if path == "/api/workers":
            return self.send_json({"items": []})
        if path == "/api/schedules" or re.fullmatch(r"/api/schedules/\d+", path):
            items = [{"id": i + 1, "name": a, "description": b, "cron": "0 9 * * 1" if i == 3 else "30 7 * * *", "enabled": i != 2,
                      **({"prompt": "쿠팡에서 생수 2L 12병을 주문하고 결과를 알려 줘" if t is TEXT["ko"] else
                          "Order 12 bottles of water (2 L) on Coupang and tell me the result"} if i == 3 else {}),
                      "next_run_ts": _next_monday_9() if i == 3 else NOW + 3600 * (3 + 20 * i)} for i, (a, b) in enumerate(t["schedules"])]
            m = re.fullmatch(r"/api/schedules/(\d+)", path)
            if m and method == "DELETE":   # as server.py: into the archive, or ?forever=true: gone at once
                sid = int(m.group(1))
                row = next((x for x in items if x["id"] == sid and sid not in s.sched_gone), None)
                if row is None:
                    return self.answer({"detail": "no such schedule"}, 404)
                s.deleted.append(self.path)
                s.sched_gone.add(sid)
                if q.get("forever") != ["true"]:
                    s.sched_archived.append(dict(row, enabled=False, archived_at=time.time(), purge_at=time.time() + 86400 * 30))
                return self.send_json({"ok": True, "archived": q.get("forever") != ["true"]})
            if not m:   # (GET/PUT of one schedule: left to the answers below, as before)
                return self.send_json({"items": [x for x in items if x["id"] not in s.sched_gone], "timezone": "Asia/Seoul"})
        if path == "/api/watchers":
            return self.send_json({"items": [{"id": 1, "name": t["watch"][0], "description": t["watch"][1], "enabled": True, "state": "watching"}]})
        if path == "/api/connectors":
            rows = [("gmail", "Gmail", "connected", "google"), ("google_calendar", "Google Calendar", "connected", "google"),
                    ("github", "GitHub", "available", "device"), ("notion", "Notion", "available", "mcp"), ("slack", "Slack", "available", "mcp"),
                    ("linear", "Linear", "available", "mcp"), ("openweather", "OpenWeather", "available", "key"), ("naver_mail", "Naver Mail", "available", "mail")]
            desc = {"ko": "메일을 읽고 정리하고, 승인하면 보내요.", "en": "Reads and sorts your mail, and sends when you approve."}
            return self.send_json([{"id": i, "name": n, "description": desc["ko" if t is TEXT["ko"] else "en"], "category": "", "status": st, "connect": c,
                                    "item": i, "featured": k < 5, "rank": k, "account": "me@example.com" if st == "connected" else ""}
                                   for k, (i, n, st, c) in enumerate(rows)])
        if path == "/api/usage":
            return self.send_json({"today": {"input_tokens": 182000, "output_tokens": 9400}, "total": {"input_tokens": 9120000, "output_tokens": 410000},
                                   "subscription": {"provider": "antigravity-oauth", "windows": [
                                       {"group": "Gemini", "label": "Current session", "used_percent": 34, "resets_at": None, "resets_text": "in 3 hours"},
                                       {"group": "Gemini", "label": "Current week", "used_percent": 61}]},
                                   "jobs": {"today": usage_part(USAGE_TODAY), "month": usage_part(USAGE_MONTH)}, "background": usage_background(s)})
        if path == "/api/usage/summary":
            models = [
                {"provider": "antigravity-oauth", "model": "gemini-3.8-flash", "calls": 420,
                 "input_tokens": 11230000, "output_tokens": 90000, "cache_read_tokens": 9230000},
                {"provider": "openai", "model": "gpt-5", "calls": 105,
                 "input_tokens": 1800000, "output_tokens": 100000, "cache_read_tokens": 1000000},
            ]
            total = {
                "calls": sum(m["calls"] for m in models),
                "input_tokens": sum(m["input_tokens"] for m in models),
                "output_tokens": sum(m["output_tokens"] for m in models),
                "cache_read_tokens": sum(m["cache_read_tokens"] for m in models),
            }
            return self.send_json({"since": "", "days": [], "kinds": [], "models": models, "total": total, "background": usage_background(s)})
        if path == "/api/uploads":
            return self.send_json([{"path": "uploads/분기보고서-초안.docx" if t is TEXT["ko"] else "uploads/quarterly-draft.docx", "bytes": 48210, "modified": NOW - 7200, "kind": "document"},
                                   {"path": "uploads/receipt.jpg", "bytes": 1832000, "modified": NOW - 86400 * 2, "kind": "image"}])
        if path == "/api/proactive":
            if method == "PUT":
                s.proactive.update(self.body())
            return self.send_json(s.proactive)
        if path == "/api/about":
            return self.send_json({"version": "0.9.0", "build": "mock"})
        if path == "/api/devices":
            return self.send_json({"devices": [{"id": 7, "name": "Desktop · Kelo", "created_at": NOW}], "current": 7})
        if path == "/api/devices/pair":
            port = PUBLIC["port"] or self.server.server_port
            k = f"&k={PUBLIC['k']}" if PUBLIC["k"] else ""
            return self.send_json({"code": PAIR_CODE, "expires_in": 600, "link": f"http://127.0.0.1:{port}/#pair={PAIR_CODE}{k}"})
        if path == "/api/notifications":
            return self.send_json({"items": [], "unseen": 0, "last_id": 0})
        if path in ("/api/browser/sessions", "/api/followups"):
            return self.send_json({"items": []})
        if path == "/api/vault":
            return self.send_json({"items": [], "pending": []})
        return self.send_json({"ok": True} if method != "GET" else {"items": []})

    def fresh_route(self, method: str, path: str, q: dict):
        """The provider setup of a brand-new server (--fresh). True when it answered, False for a path it leaves to route()."""
        s, f = self.state, self.state.fresh
        if path == "/api/profile":
            if method == "PUT":
                b = {k: v for k, v in self.body().items() if v is not None}
                for key in ("provider", "fallback_provider"):
                    r = f.row(b.get(key) or "") if b.get(key) else None
                    if r and r["unofficial"] and b.get(key) != s.profile[key] and not f.consent.get(r["name"]):
                        return self.answer({"detail": f"consent_required:{r['name']}"}, 428)
                main, fb = b.get("provider", s.profile["provider"]), b.get("fallback_provider", s.profile["fallback_provider"])
                if main and fb and main == fb:
                    b["fallback_provider"], b["fallback_model"] = "", ""
                s.profile.update(b)
            s.profile["model_ready"] = bool(s.profile["provider"] and s.profile["model"])
            return self.answer(s.profile)
        if path == "/api/providers":
            return self.answer(f.rows())
        if path == "/api/_mock/signouts":
            return self.answer({"items": f.signouts})
        if path == "/api/models":
            return self.answer(f.models(q.get("provider", [""])[0]))
        m = re.fullmatch(r"/api/providers/([^/]+)/(key|endpoint|consent|login|login/start|login/status|login/finish|login/cancel)", path)
        if not m:
            return False
        r = f.row(m.group(1))
        if r is None:
            return self.answer({"detail": f"unknown provider {m.group(1)!r}"}, 404)
        name, what = r["name"], m.group(2)
        if what == "key":
            if not r["takes_key"]:
                return self.answer({"detail": f"{name} does not use an API key"}, 400)
            if method == "DELETE":
                gone = f.keys.pop(name, None) is not None
                return self.answer({"ok": True} if gone else {"detail": "no stored key"}, 200 if gone else 404)
            key = str(self.body().get("api_key", "")).strip()
            if not key or any(c.isspace() for c in key):
                return self.answer({"detail": "API key must be a single line without spaces"}, 422)
            f.keys[name] = key
            return self.answer({"ok": True})
        if what == "endpoint":
            if not r["takes_url"]:
                return self.answer({"detail": f"{name} has a fixed address; it does not take a base URL"}, 400)
            url = str(self.body().get("base_url", "")).strip().rstrip("/")
            if not re.match(r"https?://[^/\s]+", url):
                return self.answer({"detail": "base_url must start with http:// or https://"}, 422)
            f.urls[name] = url
            return self.answer({"ok": True, "base_url": url})
        if what == "consent":
            f.consent[name] = {"accepted_at": time.time(), "version": 1}
            return self.answer({"name": name, "consent": f.consent[name]})
        if not r["signin"]:
            return self.answer({"detail": f"{name} does not use a sign-in"}, 400)
        kind = next(x["kind"] for x in SIGNIN if x["name"] == name)
        if what == "login":   # DELETE: sign out (as server.py remove_login: refused for the main model, a fallback on it emptied)
            if method != "DELETE":
                return self.answer({"detail": "method not allowed"}, 405)
            if name not in f.signed:
                return self.answer({"detail": "not signed in"}, 404)
            if f.row(s.profile["provider"] or "") and f.row(s.profile["provider"])["name"] == name:
                return self.answer({"detail": "main_model_uses_login: this sign-in runs the main model; choose another main model first"}, 409)
            fb_row = f.row(s.profile["fallback_provider"]) if s.profile["fallback_provider"] else None
            cleared = bool(fb_row and fb_row["name"] == name)
            if cleared:
                s.profile["fallback_provider"], s.profile["fallback_model"] = "", ""
            f.signed.pop(name, None)
            f.signouts.append(name)
            return self.answer({"ok": True, "name": name, "removed": True, "still_signed_in": False, "fallback_cleared": cleared})
        if what == "login/start":
            if not f.consent.get(name):
                return self.answer({"detail": f"consent_required:{name}"}, 428)
            sid = uuid.uuid4().hex[:10]
            f.logins[sid] = {"name": name, "kind": kind, "started": time.time()}
            return self.answer({"session": sid, "kind": kind, "url": "https://example.com/mock-sign-in", "expires_in": 600, "state": "pending",
                                   **({"user_code": "MOCK-1234"} if kind == "device_code" else {})})
        body = self.body() if method == "POST" else {}   # read once: the request body cannot be read twice
        sid = q.get("session", [""])[0] or str(body.get("session", ""))
        lg = f.logins.get(sid)
        if lg is None:
            return self.answer({"detail": "that sign-in is gone"}, 404)
        if what == "login/cancel":
            f.logins.pop(sid, None)
            return self.answer({"ok": True})
        done = False
        if what == "login/status":
            done = lg["kind"] == "device_code" and time.time() - lg["started"] > 6
        if what == "login/finish":
            done = lg["kind"] == "device_code" or str(body.get("code", "")).strip() == "MOCK-CODE"
            if not done:
                return self.answer({"detail": "That code didn't work. Copy it again from the page"}, 400)
        if done:
            f.signed[name] = "me@example.com"
        return self.answer({"session": sid, "kind": lg["kind"], "url": "https://example.com/mock-sign-in", "expires_in": 600,
                               "state": "done" if done else "pending", "account": "me@example.com" if done else None})

    def answer(self, obj, code: int = 200) -> bool:
        self.send_json(obj, code)
        return True

    def do_GET(self) -> None:
        self.route("GET")

    def do_POST(self) -> None:
        self.route("POST")

    def do_PUT(self) -> None:
        self.route("PUT")

    def do_PATCH(self) -> None:
        self.route("PATCH")

    def do_DELETE(self) -> None:
        self.route("DELETE")


def spread_history(messages: list[dict]) -> None:
    """--spread: the history as a long-running chat: 09:22 two years ago and a minute later, a week ago at 21:11, yesterday 09:31."""
    import datetime as dt
    today = dt.datetime.now().replace(second=0, microsecond=0)
    at = [today.replace(year=today.year - 2, hour=9, minute=22), today.replace(year=today.year - 2, hour=9, minute=23),
          (today - dt.timedelta(days=7)).replace(hour=21, minute=11), (today - dt.timedelta(days=1)).replace(hour=9, minute=31)]
    for m, when in zip(messages, at):
        m["created_at"] = when.timestamp()


def form_questions(lang: str) -> list[dict]:
    """--form: quick-reply questions of several kinds, as the server sends them (goals.py clean_questions)."""
    import datetime as dt
    due = (dt.date.today() + dt.timedelta(days=3)).isoformat()
    ko = lang == "ko"
    return [
        {"question": "언제까지 필요하세요?" if ko else "When do you need it?", "options": [], "kind": "date", "default": due},
        {"question": "표는 몇 개 넣을까요?" if ko else "How many tables?", "options": [], "kind": "number", "min": 1.0, "max": 10.0,
         "unit": "개" if ko else "tables"},
        {"question": "어떤 파일로 받을까요?" if ko else "Which file type?", "options": ["Word", "PDF"]},
        {"question": "진행 상황은 언제 알려 드릴까요?" if ko else "When should I give you updates?", "options": [], "kind": "weekdays_time",
         "default": "mon,thu 09:00"},
    ]


# --reactions: the user's messages carry the emoji the server puts on them (history), and a reply sends 👀 then its emoji
REACTIONS = False
READ = "\U0001F440"   # 👀
HISTORY_REACTIONS = ["\U0001F5D3\uFE0F", "\U0001F4DD"]   # 🗓️ (the week sorted out), 📝 (the outline written)
FINAL_REACTION = "\U0001F9B7"   # 🦷: the emoji a reply ends on (--final-reaction, e.g. a ZWJ sequence such as 🏃‍♂️)


# --ai-consent: the first chat message is held back (428) until the app's Agree (PUT /api/ai-consent); without it, already agreed
AI_CONSENT = {"accepted": True, "company": "Google"}

# --secrets: chat messages go through the real server's secret check, so the app's "this looks like a key or password" sheet shows
SECRETS = False
SECRET_GUARD = None


class Server(ThreadingHTTPServer):
    """A page opening in the app asks for many things at once. With the standard backlog of 5 waiting connections, Windows refuses
    the ones over it at once (Linux and macOS let them wait), and the app would show "could not connect"."""

    request_queue_size = 128
    daemon_threads = True


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--lang", choices=sorted(TEXT), default="ko")
    ap.add_argument("--fresh", action="store_true", help="answer like a brand-new server: no model, no provider ready")
    ap.add_argument("--signin", action="store_true", help="with --fresh: also list the sign-in services (`assistant enable oauth`)")
    ap.add_argument("--spread", action="store_true",
                    help="date the history over time (two years ago, a minute later, a week ago, yesterday) so the chat's time lines show")
    ap.add_argument("--e2e", action="store_true",
                    help="KE2E in front (apps from 0.9.0 never talk in the clear): the server's own gateway "
                         "(apps/ios/tools/mock_e2e_gateway.py, needs the server's .venv or uv) listens on --port, this mock behind it")
    ap.add_argument("--e2e-key-dir", default=str(Path(__file__).resolve().parents[1] / "build" / "mock-e2e"),
                    help="with --e2e: where the gateway's key is kept, so a paired app stays paired across restarts "
                         "(default apps/desktop/build/mock-e2e; delete it, or point elsewhere, for a new key)")
    ap.add_argument("--min-app-version", default="", help='the profile\'s min_app_version, e.g. \'{"ios": 999}\' (the required-update screen)')
    ap.add_argument("--form", action="store_true",
                    help="the last agent message asks with typed answer fields (a date, a number, a choice, days and a time)")
    ap.add_argument("--reactions", action="store_true",
                    help="the user's messages carry their emoji (🗓️, 📝) and a reply sends 👀 then its own (reactions.py)")
    ap.add_argument("--final-reaction", default="",
                    help="with --reactions: the emoji a reply ends on instead of 🦷 (one grapheme, e.g. 🏃‍♂️)")
    ap.add_argument("--secrets", action="store_true",
                    help="hold back a chat message that holds a key or password with 409 secret_in_message (the real secret_guard.py)")
    ap.add_argument("--secret-entries", action="store_true",
                    help="open secure entries the agent asked for: a key, a sign-in and a card (mock_parts/agent_entries.py)")
    ap.add_argument("--batch-scene", action="store_true",
                    help="a sent message gets the fix-batch reply: one photo looked at twice, two events each asked, yes/no buttons "
                         "(mock_parts/batch_scene.py)")
    ap.add_argument("--ai-consent", action="store_true",
                    help="nobody has agreed yet: the first chat message gets 428 ai_consent_required until Agree (ai_consent.py)")
    a = ap.parse_args()
    import os as _os
    if a.ai_consent or _os.environ.get("KELO_MOCK_AI_CONSENT") == "1":   # the env form for runs that start the mock themselves (ios.sh)
        AI_CONSENT["accepted"] = False
    if a.batch_scene:
        import os
        os.environ["KELO_MOCK_BATCH"] = "1"
    if a.secret_entries:
        import os
        os.environ["KELO_MOCK_SECRET_ENTRIES"] = "1"
    if a.secrets:
        global SECRETS, SECRET_GUARD
        import importlib.util
        spec = importlib.util.spec_from_file_location("secret_guard", Path(__file__).resolve().parents[3] / "server" / "src" / "assistant" / "secret_guard.py")
        SECRET_GUARD = importlib.util.module_from_spec(spec)
        if sys.version_info < (3, 10):   # the macOS python3 (3.9): its dataclass has no slots=
            import dataclasses
            plain = dataclasses.dataclass
            dataclasses.dataclass = lambda *args, **kw: plain(*args, **{k: v for k, v in kw.items() if k != "slots"})
        sys.modules["secret_guard"] = SECRET_GUARD   # dataclasses look the module up while the file runs
        spec.loader.exec_module(SECRET_GUARD)
        SECRETS = True
    Handler.state = State(a.lang, FreshServer(a.signin) if a.fresh else None)
    if a.form and Handler.state.messages:
        Handler.state.messages[-1]["replies"] = {"questions": form_questions(a.lang), "goal_id": None}
    if a.spread:
        spread_history(Handler.state.messages)
    if a.reactions:
        global REACTIONS
        REACTIONS = True
        if a.final_reaction:
            global FINAL_REACTION
            FINAL_REACTION = a.final_reaction
        mine = [m for m in Handler.state.messages if m["role"] == "user"]
        for m, e in zip(mine, HISTORY_REACTIONS):
            m["reaction"] = e
    if a.min_app_version:
        Handler.state.profile["min_app_version"] = json.loads(a.min_app_version)
    if a.e2e:
        srv = Server(("127.0.0.1", 0), Handler)
        k = start_e2e_gateway(a.port, srv.server_address[1], a.e2e_key_dir)
        PUBLIC.update(port=a.port, k=k)
        print(f"mock Kelo server (KE2E) on http://127.0.0.1:{a.port} - pair with: http://127.0.0.1:{a.port}/#pair={PAIR_CODE}&k={k}", flush=True)
    else:
        srv = Server(("127.0.0.1", a.port), Handler)
        print(f"mock Kelo server on http://127.0.0.1:{a.port} - pair with: http://127.0.0.1:{a.port}/#pair={PAIR_CODE}", flush=True)
    srv.serve_forever()


def start_e2e_gateway(port: int, upstream: int, key_dir: str) -> str:
    """Starts the KE2E gateway (apps/desktop/tools/mock_e2e_keyed.py: apps/ios/tools/mock_e2e_gateway.py with its key kept in [key_dir]) on
    [port] in front of this mock ([upstream]) and returns its key (base64url). Stopped with this process (also Ctrl-C, SIGTERM)."""
    import atexit
    import os
    import shutil
    import signal
    import subprocess
    import urllib.request
    root = Path(__file__).resolve().parents[3]
    venv = [root / ".venv" / "bin" / "python", root / ".venv" / "Scripts" / "python.exe"]
    py = next((str(v) for v in venv if v.exists()), None)
    cmd = [py] if py else ([shutil.which("uv") or "uv", "run", "--project", str(root), "python"])
    gw = subprocess.Popen(cmd + [str(root / "apps" / "desktop" / "tools" / "mock_e2e_keyed.py"), "--port", str(port), "--upstream", str(upstream),
                                 "--key-dir", key_dir], cwd=root)

    def stop(*_: object) -> None:
        if gw.poll() is None:
            gw.terminate()
            try:
                gw.wait(5)
            except subprocess.TimeoutExpired:
                gw.kill()
    atexit.register(stop)
    signal.signal(signal.SIGTERM, lambda *_: (stop(), os._exit(0)))
    end = time.time() + 60
    while time.time() < end:
        if gw.poll() is not None:
            sys.exit("the KE2E gateway did not start (it needs the server's environment: uv sync)")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/e2e/info", timeout=2) as r:
                return json.loads(r.read())["pk"]
        except Exception:
            time.sleep(0.3)
    stop()
    sys.exit("the KE2E gateway did not answer in 60 s")


if __name__ == "__main__":
    main()
