"""P2 acceptance run: generate a test set from the sample PDFs against a running stack and report what happened.

    python benchmarks/testset_demo.py                  # 50 questions, default model, http://localhost:8080
    python benchmarks/testset_demo.py --count 10       # a quicker run

It creates a throwaway account (random credentials, written to --creds so you can sign in and review the questions in the
UI), uploads benchmarks/corpus/*.md (five policy documents, twelve sections each), waits for ingestion, starts generation, waits for it, and prints the numbers
that go in the README: questions by type, quality flags, and how long it took. It never prints the password.
"""
import argparse
import json
import pathlib
import secrets
import subprocess
import sys
import time
import urllib.error
import urllib.request

DOCS = sorted((pathlib.Path(__file__).parent / "corpus").glob("*.md"))


def call(base, method, path, token=None, workspace=None, body=None):
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if workspace:
        headers["X-Workspace-Id"] = workspace
    req = urllib.request.Request(base + path, method=method, headers=headers, data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            raw = res.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} failed: HTTP {e.code} {e.read().decode()[:300]}")


def upload(base, token, workspace, doc):
    out = subprocess.run(
        ["curl", "-s", "-f", "-X", "POST", base + "/api/documents", "-H", f"Authorization: Bearer {token}",
         "-H", f"X-Workspace-Id: {workspace}", "-F", f"file=@{doc}"],
        capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"upload of {doc.name} failed: {out.stderr or out.stdout}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--count", type=int, default=50)
    ap.add_argument("--name", default="Acceptance demo")
    ap.add_argument("--creds", default=str(pathlib.Path.home() / ".glassbox-demo-account.json"), help="where to write the throwaway login")
    ap.add_argument("--seed", type=int, default=2026)
    args = ap.parse_args()

    email = f"demo-{secrets.token_hex(4)}@example.com"
    password = secrets.token_urlsafe(18)
    auth = call(args.base, "POST", "/api/auth/register", body={"email": email, "password": password})
    token = auth["accessToken"]
    workspace = call(args.base, "GET", "/api/workspaces", token)[0]["id"]
    pathlib.Path(args.creds).write_text(json.dumps({"email": email, "password": password, "workspace": workspace}))
    print(f"account created; login saved to {args.creds}")

    for doc in DOCS:
        upload(args.base, token, workspace, doc)
    deadline = time.time() + 600
    while time.time() < deadline:
        docs = call(args.base, "GET", "/api/documents?limit=50", token, workspace)["items"]
        if len(docs) == len(DOCS) and all(d["status"] == "READY" for d in docs):
            break
        time.sleep(3)
    else:
        sys.exit("documents did not become ready in time")
    print(f"{len(DOCS)} documents ready")

    started = time.time()
    made = call(args.base, "POST", "/api/testsets/generate", token, workspace, {"name": args.name, "count": args.count, "seed": args.seed})
    tid = made["testset"]["id"]
    print(f"generation started: {args.count} questions, about {made['estimatedLlmCalls']} model calls")
    last = -1
    while True:
        t = call(args.base, "GET", f"/api/testsets/{tid}", token, workspace)
        if t["generatedCount"] != last:
            last = t["generatedCount"]
            print(f"  {last}/{args.count} after {int(time.time() - started)} s", flush=True)
        if t["status"] != "GENERATING":
            break
        time.sleep(10)
    seconds = time.time() - started
    print(f"\nstatus {t['status']}" + (f": {t['error']}" if t.get("error") else ""))
    s = t["stats"]
    print(f"generated {t['generatedCount']} questions in {seconds / 60:.1f} min ({seconds / max(1, t['generatedCount']):.0f} s per question)")
    print("by type: ", s["byType"])
    flagged = call(args.base, "GET", f"/api/testsets/{tid}/questions?limit=200&flag=ANY", token, workspace)["items"]
    print("by flag: ", s["byFlag"], f"({len(flagged)} of {s['total']} questions carry at least one flag)")
    print(f"test set id {tid}; review it at {args.base}/testsets/{tid}/review")


if __name__ == "__main__":
    main()
