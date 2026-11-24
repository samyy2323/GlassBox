#!/usr/bin/env python3
"""Records what the interface reads from a seeded Glassbox into one JSON file, for the static demo (a copy of the real interface that needs no server).

Point it at a server with the demo switched on and the demo workspace seeded (see deploy/seed_demo.py and deploy/space/make_seed.sh):

    GLASSBOX_SERVER=http://localhost:7860 python deploy/record_demo.py

It writes apps/web/public/demo-data/api.json: {"GET /api/path?query": response}. Only successful reads are kept; the static demo answers every other call itself.
"""
import json
import os
import pathlib
import urllib.error
import urllib.request

BASE = os.environ.get("GLASSBOX_SERVER", "http://localhost:7860").rstrip("/")
OUT = pathlib.Path(__file__).resolve().parent.parent / "apps" / "web" / "public" / "demo-data" / "api.json"
TOKEN = ""
WS = ""
REC = {}
SKIPPED = []


def call(method, path, body=None, auth=True):
    h = {"Content-Type": "application/json"}
    if auth:
        h["Authorization"] = f"Bearer {TOKEN}"
        if WS:
            h["X-Workspace-Id"] = WS
    req = urllib.request.Request(BASE + path, method=method, headers=h, data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req, timeout=120) as res:
        raw = res.read()
        return json.loads(raw) if raw else None


def get(path):
    """Fetch and remember one read; returns None (and notes it) when the server refuses, so a missing optional page does not stop the crawl."""
    try:
        data = call("GET", path)
    except urllib.error.HTTPError as e:
        SKIPPED.append(f"{path} -> {e.code}")
        return None
    REC[f"GET {path}"] = data
    return data


def items(x):
    return x["items"] if isinstance(x, dict) and "items" in x else (x or [])


def main():
    global TOKEN, WS
    TOKEN = call("POST", "/api/auth/demo", auth=False)["accessToken"]
    workspaces = get("/api/workspaces")
    WS = workspaces[0]["id"]
    get("/api/workspaces")  # again, now with the workspace header, in case it matters

    for d in items(get("/api/documents?limit=200")):
        get(f"/api/documents/{d['id']}/content")
        versions = get(f"/api/documents/{d['id']}/versions") or []
        for v in versions:
            if v["version"] > 1:
                get(f"/api/documents/{d['id']}/versions/{v['version']}/impact")

    sets = get("/api/testsets") or []
    by_name = {}
    for t in sets:
        get(f"/api/testsets/{t['id']}")
        get(f"/api/testsets/{t['id']}/questions?limit=200")
        by_name.setdefault(t["name"], []).append(t)
    for versions in by_name.values():
        versions.sort(key=lambda t: t["version"])
        for older, newer in zip(versions, versions[1:]):
            get(f"/api/testsets/{newer['id']}/diff?against={older['id']}")

    for e in get("/api/experiments") or []:
        full = get(f"/api/experiments/{e['id']}")
        for r in (full or e).get("runs", []):
            get(f"/api/experiments/{e['id']}/results?run={r['id']}")
            get(f"/api/experiments/{e['id']}/diagnosis?run={r['id']}")
        runs = [r["id"] for r in (full or e).get("runs", [])]
        for other in runs[1:]:
            get(f"/api/experiments/compare?a={runs[0]}&b={other}")

    for a in get("/api/autotune") or []:
        get(f"/api/autotune/{a['id']}")

    for status in ("OPEN", "CONVERTED", "DISMISSED"):
        for f in get(f"/api/feedback?status={status}") or []:
            get(f"/api/feedback/{f['id']}")
    get("/api/feedback/summary")
    get("/api/calibration/summary")

    page = get("/api/traces?limit=30")
    for t in items(page):
        get(f"/api/traces/{t['id']}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(REC, separators=(",", ":")), encoding="utf-8")
    print(f"recorded {len(REC)} reads, {OUT.stat().st_size / 1024:.0f} KB -> {OUT}")
    for s in SKIPPED:
        print("  not recorded:", s)


if __name__ == "__main__":
    main()
