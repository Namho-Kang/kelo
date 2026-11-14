"""Without KELO_MOCK_KEYGUARD (aa_keyguard.py then stays out of the way), GET /api/keyguard answers 404 like a server from before
Keyguard, instead of the mock's catch-all {"items": []}: an app reads that as "not known" (no auto-pay pause), so the sample cards
keep paying on their own in every other screenshot. Desktop's; named "ab_" so it comes right after aa_keyguard.py.
"""

from __future__ import annotations

import os


def route(h, method: str, path: str, q: dict, s) -> bool:
    if os.environ.get("KELO_MOCK_KEYGUARD"):
        return False
    if path.startswith("/api/keyguard"):
        h.send_json({"detail": "Not Found"}, 404)
        return True
    return False
