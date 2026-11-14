#!/usr/bin/env python3
"""The KE2E gateway of apps/ios/tools/mock_e2e_gateway.py, with a server key that stays: kept in KEY_DIR (the server's own
<state>/e2e/server.key layout, 0600), so an app paired with the mock stays paired when the mock is started again.
Started by apps/desktop/tools/mock_server.py --e2e (default KEY_DIR: apps/desktop/build/mock-e2e).

    uv run python apps/desktop/tools/mock_e2e_keyed.py --port 8765 --upstream 8789 --key-dir apps/desktop/build/mock-e2e
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "apps" / "ios" / "tools"))

import uvicorn  # noqa: E402
from assistant import e2e  # noqa: E402
from mock_e2e_gateway import make_app  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--upstream", type=int, required=True, help="the mock server's port on 127.0.0.1")
    ap.add_argument("--key-dir", required=True, help="where the gateway's key is kept (made on the first start)")
    a = ap.parse_args()
    Path(a.key_dir).mkdir(parents=True, exist_ok=True)
    key = e2e.ServerKey.load_or_create(a.key_dir)
    print(f"KE2E gateway on 127.0.0.1:{a.port} -> 127.0.0.1:{a.upstream}, fingerprint {key.fingerprint} (key kept in {a.key_dir})", flush=True)
    uvicorn.run(make_app(f"http://127.0.0.1:{a.upstream}", key), host="127.0.0.1", port=a.port, log_level="warning")


if __name__ == "__main__":
    main()
