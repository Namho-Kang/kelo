"""Sample endpoints for My → Server update (owner only; shapes as the server's /api/server-update answers them).

Off unless KELO_MOCK_SERVER_UPDATE names a scenario (an older server: every path answers 404), so the other screenshots keep
their My page. Scenarios:

  available        a newer release (0.10.0) with notes; [Update] starts a job that moves on with every GET and, at the
                   restart step, answers 502 a few times (the server restarting) before it is done
  running          an update frozen at "Downloading the new version" (42%)
  waiting          an update at the restart step whose GET keeps failing (502) until the scenario changes
  done             the update finished a few minutes ago
  failed           stopped before changing anything (download problem)
  rolled_back      the new version failed its check; back on 0.9.0, data restored
  rollback_failed  going back did not finish
  local            built from source on the server machine
  forbidden        403 (a device that is not the owner's)
  unavailable      state "unavailable"

POST /mock/server-update {"scenario": "..."} switches the scenario while the mock runs (screenshots of several states).
"""

from __future__ import annotations

import os
import threading
import time

LOCK = threading.Lock()
CURRENT, LATEST, PREVIOUS = "0.9.0", "0.10.0", "0.8.3"
STEPS = [("preflight", 4), ("download", 18), ("download", 42), ("verify", 55), ("backup", 64), ("restart", 72),
         ("restart", 76), ("health", 86), ("health", 94)]
DOWN_TICKS = 4

# one job id for the sample jobs, as the real server keeps a job's id across its restart (the app follows the job by its id)
JOB_ID = time.strftime("%Y%m%d-%H%M%S", time.localtime(time.time() - 600))

STATE: dict = {"scenario": os.environ.get("KELO_MOCK_SERVER_UPDATE", "").strip()}


def _ko(s) -> bool:
    return s.t.get("user") == "치즈"


def _notes(ko: bool) -> str:
    if ko:
        return ("• 느린 연결에서도 답이 더 빨리 시작돼요\n"
                "• 작업 탭에서 지난 작업을 일정별로 볼 수 있어요\n"
                "• 일부 메일 계정에서 로그인이 반복되던 문제를 고쳤어요")
    return ("• Replies start sooner on slow connections\n"
            "• The Work tab can show earlier work by schedule\n"
            "• Fixes a sign-in loop with some mail accounts")


def _job(state: str, step: str, progress: int, *, action: str = "update", ago: float = 0.0, error=None, restored=False,
         frm=CURRENT, to=LATEST, auto=False) -> dict:
    now = time.time()
    return {"id": JOB_ID, "action": action, "auto": auto, "state": state,
            "step": step, "progress": progress, "from": frm, "to": to, "error": error, "data_restored": restored,
            "started_at": now - ago - 240, "finished_at": None if state == "running" else now - ago}


def _base(s) -> dict:
    now = time.time()
    st = STATE
    if "auto" not in st:
        st["auto"] = False
        st["checked_at"] = now - 1200
    sc = st["scenario"]
    current = st.get("current", CURRENT)
    out = {
        "state": "ok", "message": "", "current": current,
        "latest": {"version": LATEST, "date": "2026-11-05T00:00:00Z", "notes": _notes(_ko(s))},
        "update_available": current != LATEST, "checked_at": st["checked_at"], "auto_update": st["auto"],
        "auto_time": "03:50" if st["auto"] else None, "job": None,
        "rollback": {"available": True, "version": PREVIOUS, "created_at": now - 86400 * 12},
    }
    if sc == "local":
        out.update(state="local", message=("서버 컴퓨터에서 소스로 빌드했어요: 거기서 kelo update로 업데이트해 주세요" if _ko(s)
                                           else "Built from source on the server machine: update it there with kelo update"),
                   rollback={"available": False, "version": None, "created_at": None})
    elif sc == "unavailable":
        out = {"state": "unavailable"}
    elif sc == "running":
        out["job"] = _job("running", "download", 42)
    elif sc == "waiting":
        out["job"] = _job("running", "restart", 74)
    elif sc == "done":
        out.update(current=LATEST, update_available=False, job=_job("done", "done", 100, ago=300),
                   rollback={"available": True, "version": CURRENT, "created_at": now - 600})
    elif sc == "failed":
        out["job"] = _job("failed", "download", 18, ago=420, error="download interrupted: connection reset by peer")
    elif sc == "rolled_back":
        out["job"] = _job("rolled_back", "rollback", 100, ago=360, restored=True,
                          error="health check failed: /healthz answered 503 for 120 s")
    elif sc == "rollback_failed":
        out["job"] = _job("rollback_failed", "rollback", 100, ago=360, error="rollback: database restore failed (disk full)")
    live = st.get("live")
    if live is not None:
        out["job"] = live
        if live["state"] == "done":
            out["current"] = live["to"]
            out["update_available"] = live["to"] != LATEST
            out["rollback"] = {"available": True, "version": live["from"], "created_at": live["started_at"]}
    return out


def _tick() -> bool:
    """Moves a live job on by one step; False while the 'server' is restarting (the GET fails)."""
    live = STATE.get("live")
    if live is None or live["state"] != "running":
        return True
    plan = STEPS if live["action"] == "update" else [("backup", 20), ("restart", 50), ("health", 80)]
    if live["step"] == "restart" and STATE.get("down", 0) < DOWN_TICKS:
        STATE["down"] = STATE.get("down", 0) + 1
        return False
    i = STATE.get("tick", 0)
    STATE["tick"] = i + 1
    if i < len(plan):
        live["step"], live["progress"] = plan[i]
    else:
        live.update(state="done", step="done", progress=100, finished_at=time.time())
        STATE["current"] = live["to"]
    return True


def route(h, method: str, path: str, q: dict, s) -> bool:
    if path == "/mock/server-update" and method == "POST":
        with LOCK:
            STATE.clear()
            STATE["scenario"] = str(h.body().get("scenario", "")).strip()
        h.send_json({"ok": True, "scenario": STATE["scenario"]})
        return True
    if not path.startswith("/api/server-update"):
        return False
    with LOCK:
        sc = STATE["scenario"]
        if not sc:
            h.send_json({"detail": "Not Found"}, 404)
            return True
        if sc == "forbidden":
            h.send_json({"detail": "only the owner's devices can do that"}, 403)
            return True
        ko = _ko(s)
        if path == "/api/server-update" and method == "GET":
            if sc == "waiting" or not _tick():
                h.send_json({"detail": "Bad Gateway"}, 502)
                return True
            h.send_json(_base(s))
            return True
        if path == "/api/server-update/check" and method == "POST":
            STATE["checked_at"] = time.time()
            h.send_json(_base(s))
            return True
        if path == "/api/server-update/settings" and method == "PUT":
            STATE["auto"] = bool(h.body().get("auto_update"))
            h.send_json(_base(s))
            return True
        if path in ("/api/server-update/update", "/api/server-update/rollback") and method == "POST":
            cur = _base(s)
            back = path.endswith("rollback")
            if cur.get("job") and cur["job"].get("state") == "running":
                h.send_json({"detail": "업데이트가 이미 진행 중이에요" if ko else "An update is already running"}, 409)
                return True
            if back and not cur["rollback"]["available"]:
                h.send_json({"detail": "되돌릴 버전이 없어요" if ko else "There is no earlier version to go back to"}, 409)
                return True
            if not back and not cur["update_available"]:
                h.send_json({"detail": "이미 최신 버전이에요" if ko else "Already up to date"}, 409)
                return True
            STATE["tick"] = 0
            STATE["down"] = 0
            STATE["live"] = _job("running", "preflight", 2, action="rollback" if back else "update", frm=cur["current"],
                                 to=cur["rollback"]["version"] if back else LATEST, restored=back)
            STATE["live"]["id"] = time.strftime("%Y%m%d-%H%M%S")   # a new job of its own, the same id until it ends
            h.send_json(_base(s), 202)
            return True
        h.send_json({"detail": "Not Found"}, 404)
        return True
