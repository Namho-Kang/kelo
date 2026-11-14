"""KELO_MOCK_CUSTOM_EFFORT=levels|refused: the main model is a custom OpenAI-compatible endpoint (an EXAONE server) with a
thinking level, for the model chip, the model sheet and My → Model.

levels:  the server's GET /api/providers row for `custom` (effort.py: low, medium, high, max), the profile at "높음" (high).
refused: the same, but the endpoint answered 400 to `reasoning_effort` (providers/refused.py): GET /api/models says so for the
         model (effort_levels [], effort_note), so the sheet greys the row and the Model page shows the sentence.
Nothing here talks to a real service.
"""

from __future__ import annotations

import os

C = {"patched": False}
MODEL = "k-exaone_v2.5"
NOTE = {"ko": "이 서버는 생각 수준 설정을 받지 않아요", "en": "This server does not accept a thinking level"}


def _mode() -> str:
    return os.environ.get("KELO_MOCK_CUSTOM_EFFORT", "")


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def route(h, method: str, path: str, q: dict, s) -> bool:
    mode = _mode()
    if mode not in ("levels", "refused"):
        return False
    if not C["patched"]:
        C["patched"] = True
        s.profile.update({"provider": "custom", "model": MODEL, "effort": "high",
                          "fallback_provider": "claude-subscription", "fallback_model": "claude-sonnet-5-5"})
    if path == "/api/providers" and method == "GET":
        h.send_json([
            {"name": "custom", "display_name": "Custom endpoint", "configured": True, "supported": True, "oauth": False,
             "takes_url": True, "base_url": "https://api.exaone.ai/v1", "takes_key": True, "key_source": "app", "key_hint": "x9Qa",
             "effort_levels": ["low", "medium", "high", "max"], "effort_note": None, "unofficial": False, "signin": False},
            {"name": "claude-subscription", "display_name": "Claude", "configured": True, "unofficial": True, "vendor": "Anthropic",
             "company": "Anthropic", "consent": {"accepted_at": 1, "version": 1}, "consent_version": 1,
             "effort_levels": ["low", "medium", "high"]},
        ])
        return True
    if path == "/api/models" and method == "GET" and q.get("provider", [""])[0] == "custom":
        models = [MODEL, "k-exaone_v2.5-mini"]
        info = {m: {"images": False, "context": None} for m in models}
        if mode == "refused":
            info[MODEL].update({"effort_levels": [], "effort_note": NOTE["ko" if _ko(s) else "en"]})
        h.send_json({"provider": "custom", "models": models, "labels": {MODEL: "K-EXAONE v2.5"}, "info": info,
                     "error": None, "stale": False, "fetched_at": None, "retry_in": None})
        return True
    return False
