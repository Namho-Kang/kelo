"""The server's upload limits and part-upload routes (src/assistant/api/files.py, "UPLOADS"), in memory, so the apps' big-file path can be
walked and tested with small files.

    MOCK_UPLOAD_SINGLE_MAX=<bytes>   the most one PUT /api/uploads/{name} may carry (default: none, like Tailscale or a direct domain;
                                     set e.g. 5000000 to act like a route through Cloudflare and force parts). mock_server.py's own
                                     single PUT answers 413 above it.
    MOCK_UPLOAD_PART_SIZE=<bytes>    the size of one part (default 4000000, so a 12 MB file is 3 parts)
    MOCK_UPLOAD_IMAGE_MAX=<bytes>    the photo limit (default 15000000)

Routes: GET /api/uploads/limits; POST /api/uploads/{name}/parts {"size"}; PUT /api/uploads/parts/{id}/{index} (raw body, exact size);
GET /api/uploads/parts/{id}; POST /api/uploads/parts/{id}/finish; DELETE /api/uploads/parts/{id}. The parts are joined at finish and
dropped; the answer is {"path": "uploads/<name>"} (nothing is listed under /api/uploads).
"""

from __future__ import annotations

import itertools
import os
import re
import threading

_lock = threading.Lock()
_open: dict[str, dict] = {}
_ids = itertools.count(1)
MAX_OPEN = 3


def single_max() -> int | None:
    v = os.environ.get("MOCK_UPLOAD_SINGLE_MAX", "")
    return int(v) if v.isdigit() and int(v) > 0 else None


def part_size() -> int:
    v = os.environ.get("MOCK_UPLOAD_PART_SIZE", "")
    return int(v) if v.isdigit() and int(v) > 0 else 4_000_000


def image_max() -> int:
    v = os.environ.get("MOCK_UPLOAD_IMAGE_MAX", "")
    return int(v) if v.isdigit() and int(v) > 0 else 15_000_000


def _drain(h) -> bytes:
    n = int(h.headers.get("Content-Length") or 0)
    out = bytearray()
    while len(out) < n:
        chunk = h.rfile.read(min(n - len(out), 1 << 16))
        if not chunk:
            break
        out += chunk
    return bytes(out)


def route(h, method: str, path: str, q: dict, s) -> bool:
    if method == "GET" and path == "/api/uploads/limits":
        h.send_json({"single_max": single_max(), "part_size": part_size(), "image_max": image_max()})
        return True
    m = re.fullmatch(r"/api/uploads/([^/]+)/parts", path)
    if method == "POST" and m and m.group(1) != "parts":
        size = int((h.body() or {}).get("size") or -1)
        name = m.group(1)
        if size < 0:
            h.send_json({"detail": "size must be given"}, 422)
        elif re.search(r"\.(jpe?g|png|webp|heic|heif)$", name, re.I) and size > image_max():
            h.send_json({"detail": f"Photos can be up to {image_max() // 1_000_000} MB."}, 413)
        else:
            with _lock:
                if len(_open) >= MAX_OPEN:
                    h.send_json({"detail": "Too many large uploads are open. Wait for one to finish or cancel it."}, 409)
                    return True
                uid = f"mock-up-{next(_ids)}"
                ps = part_size()
                _open[uid] = {"name": name, "size": size, "part_size": ps, "parts": {}}
            h.send_json({"upload_id": uid, "part_size": ps, "parts": max(1, -(-size // ps))})
        return True
    m = re.fullmatch(r"/api/uploads/parts/([^/]+)/(\d+)", path)
    if method == "PUT" and m:
        body = _drain(h)
        with _lock:
            up = _open.get(m.group(1))
            if up is None:
                h.send_json({"detail": "No such upload (it may have been removed)."}, 404)
                return True
            i = int(m.group(2))
            count = max(1, -(-up["size"] // up["part_size"]))
            want = up["part_size"] if i < count - 1 else up["size"] - up["part_size"] * (count - 1)
            if i >= count or len(body) != want:
                h.send_json({"detail": f"Part {i} must be {want} bytes."}, 422)
                return True
            up["parts"][i] = body
            h.send_json({"ok": True, "received": len(up["parts"])})
        return True
    m = re.fullmatch(r"/api/uploads/parts/([^/]+)", path)
    if m and method in ("GET", "DELETE"):
        with _lock:
            up = _open.get(m.group(1))
            if up is None:
                h.send_json({"detail": "No such upload (it may have been removed)."}, 404)
            elif method == "DELETE":
                del _open[m.group(1)]
                h.send_json({"ok": True})
            else:
                h.send_json({"name": up["name"], "size": up["size"], "part_size": up["part_size"], "received": sorted(up["parts"])})
        return True
    m = re.fullmatch(r"/api/uploads/parts/([^/]+)/finish", path)
    if method == "POST" and m:
        with _lock:
            up = _open.get(m.group(1))
            if up is None:
                h.send_json({"detail": "No such upload (it may have been removed)."}, 404)
                return True
            count = max(1, -(-up["size"] // up["part_size"]))
            if any(i not in up["parts"] for i in range(count)):
                h.send_json({"detail": "A part is missing. Send it and finish again."}, 422)
                return True
            joined = sum(len(up["parts"][i]) for i in range(count))
            del _open[m.group(1)]
        if joined != up["size"]:
            h.send_json({"detail": "The parts do not add up to the size."}, 422)
        else:
            h.send_json({"path": "uploads/" + up["name"], "ok": True})
        return True
    return False
