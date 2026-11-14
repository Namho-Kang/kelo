#!/bin/bash
# The desktop app's KE2E end to end against the real Kelo server on this machine (no Docker), like apps/android/tools/ke2e_local_test.sh:
#   1. apps/android/tools/ke2e_local_server.py starts the server (src/assistant, a fake model) behind a proxy that records every byte,
#   2. the shared HTTP stack (Ke2eLocalServerTest: pair, upload, download, a reply) and the desktop's own wiring (DesktopKe2eLocalTest:
#      AppContainer, key sign-in with a TOFU pin, "verify with a link", pairing a second computer) run against it,
#   3. the proxy's record is checked: no token, no text and no API path crossed in the clear (only /api/e2e/info, plain on purpose).
#
#   apps/desktop/tools/ke2e_local_test.sh
#
# Needs uv (the server's environment) and JDK 17. Stops what it started, also after a failure.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/kelo-desktop-ke2e.XXXXXX")"
INFO="$WORK/info.json" STOP="$WORK/stop" VERDICT="$WORK/verdict.txt"
SERVER_PID=""

cleanup() {
  touch "$STOP"
  if [ -n "$SERVER_PID" ]; then
    for _ in $(seq 1 50); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 0.2; done
    kill "$SERVER_PID" 2>/dev/null || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

(cd "$REPO" && exec uv run python apps/android/tools/ke2e_local_server.py "$INFO" "$STOP" "$VERDICT") &
SERVER_PID=$!
for _ in $(seq 1 300); do [ -s "$INFO" ] && break; kill -0 "$SERVER_PID" 2>/dev/null || { echo "the server did not start" >&2; exit 1; }; sleep 0.2; done
[ -s "$INFO" ] || { echo "the server did not start in 60 s" >&2; exit 1; }

export JAVA_HOME="${KELO_JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export KELO_E2E_SERVER_INFO="$INFO"
(cd "$REPO/apps/desktop" && ./gradlew --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process \
  test --tests 'chat.kelo.app.core.e2e.Ke2eLocalServerTest' --tests 'chat.kelo.desktop.DesktopKe2eLocalTest' --rerun)

touch "$STOP"
for _ in $(seq 1 100); do [ -s "$VERDICT" ] && break; sleep 0.2; done
wait "$SERVER_PID" || true
SERVER_PID=""
[ -s "$VERDICT" ] || { echo "no verdict from the recording proxy" >&2; exit 1; }
if [ "$(cat "$VERDICT")" = "ok" ]; then
  echo "desktop KE2E end to end: ok (nothing but ciphertext crossed the recording proxy)"
else
  echo "desktop KE2E end to end FAILED:" >&2; cat "$VERDICT" >&2; exit 1
fi
