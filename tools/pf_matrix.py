#!/usr/bin/env python3
"""Run Thunder's allowlisted local Pathfinder diagnostic scenarios."""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.parse
import urllib.request


DEFAULT_PORT = int(os.environ.get("HAVEN_DEV_CONTROL_PORT", "18762"))
LOCAL_MATRIX = (
    "observe",
    "select_open_ground",
    "select_obstacle_corridor",
    "move_to_auto_open_ground",
    "move_to_auto_obstacle_corridor",
    "surface_long_open_ground",
    "surface_single_tree_detour",
    "surface_clustered_obstacles",
)


def request(port: int, path: str, post: bool = False, timeout: float = 30.0) -> dict:
    url = f"http://127.0.0.1:{port}{path}"
    req = urllib.request.Request(url, data=b"" if post else None,
                                 method="POST" if post else "GET")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def run_one(port: int, scenario: str, timeout: float) -> dict:
    query = urllib.parse.urlencode({"scenario": scenario})
    started = request(port, "/pf/run?" + query, post=True, timeout=15.0)
    run_id = started.get("run_id")
    if not started.get("ok") or not run_id:
        return {"scenario": scenario, "status": "FAIL", "start": started}
    deadline = time.time() + timeout
    body = None
    while time.time() < deadline:
        body = request(port, "/pf/result?run_id=" + urllib.parse.quote(run_id), timeout=10.0)
        if body.get("status") != "running":
            break
        time.sleep(0.25)
    facts = (body or {}).get("facts") or {}
    refusal = facts.get("refusal")
    if refusal in ("NO_FIXTURE", "NO_GAME"):
        status = "NOT_RUN"
    elif body and body.get("verdict") == "PASS":
        status = "PASS"
    else:
        status = "FAIL"
    return {"scenario": scenario, "status": status, "run_id": run_id, "result": body}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    sub.add_parser("list")
    run = sub.add_parser("run")
    run.add_argument("scenario")
    run.add_argument("--timeout", type=float, default=120.0)
    matrix = sub.add_parser("matrix")
    matrix.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args()

    if args.command == "status":
        result = request(args.port, "/status")
    elif args.command == "list":
        result = request(args.port, "/pf/scenarios")
    elif args.command == "run":
        result = run_one(args.port, args.scenario, args.timeout)
    else:
        available = set(request(args.port, "/pf/scenarios").get("scenarios") or [])
        rows = [run_one(args.port, name, args.timeout)
                for name in LOCAL_MATRIX if name in available]
        result = {"rows": rows, "status":
                  "FAIL" if any(row["status"] == "FAIL" for row in rows)
                  else "PASS" if rows and all(row["status"] == "PASS" for row in rows)
                  else "NOT_RUN"}
    print(json.dumps(result, indent=2, sort_keys=True))
    return 1 if result.get("status") == "FAIL" else 0


if __name__ == "__main__":
    raise SystemExit(main())
