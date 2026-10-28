"""Prepares a fresh Glassbox server for the quality gate: an account, an API key with the run scope, the frozen documents, and the frozen test set.

    python benchmarks/frozen/bootstrap.py --base http://localhost:8081 --cli cli/target/glassbox-cli.jar [--env-file "$GITHUB_ENV"]

Prints GLASSBOX_API_KEY=... and GLASSBOX_TESTSET=... (and appends them to --env-file, which is how a GitHub job passes them to later steps). The documents are the
twelve site handbooks in docs/, the test set is testset.jsonl (81 questions exported from a real run, each quoting its evidence), so the gate always measures the same
questions on the same text. Standard library only, so it runs on a bare CI runner.
"""
import argparse
import json
import pathlib
import re
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent


def call(base, method, path, token=None, body=None, key=None):
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["X-API-Key"] = key
    req = urllib.request.Request(base + path, method=method, headers=headers, data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=120) as res:
            raw = res.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} failed: HTTP {e.code} {e.read().decode()[:300]}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--cli", default=str(HERE.parent.parent / "cli" / "target" / "glassbox-cli.jar"))
    ap.add_argument("--env-file", default=None)
    ap.add_argument("--name", default="frozen-handbooks")
    ap.add_argument("--wait-seconds", type=int, default=900)
    args = ap.parse_args()

    password = secrets.token_urlsafe(18)
    auth = call(args.base, "POST", "/api/auth/register", body={"email": f"gate-{secrets.token_hex(4)}@example.com", "password": password})
    token = auth["accessToken"]
    workspace = call(args.base, "GET", "/api/workspaces", token)[0]["id"]
    key = call(args.base, "POST", f"/api/workspaces/{workspace}/api-keys", token, {"label": "quality gate", "scopes": ["run"]})["key"]
    if args.env_file:
        print("::add-mask::" + key, flush=True)  # a GitHub job: keep the key out of the logs

    docs = sorted((HERE / "docs").glob("*.md"))
    for doc in docs:
        out = subprocess.run(["curl", "-s", "-f", "-X", "POST", args.base + "/api/documents", "-H", f"X-API-Key: {key}", "-F", f"file=@{doc}"], capture_output=True, text=True)
        if out.returncode != 0:
            sys.exit(f"upload of {doc.name} failed: {out.stderr or out.stdout}")
    deadline = time.time() + args.wait_seconds
    while True:
        items = call(args.base, "GET", "/api/documents?limit=100", key=key)["items"]
        if len(items) == len(docs) and all(d["status"] == "READY" for d in items):
            break
        if any(d["status"] == "FAILED" for d in items):
            sys.exit("a document failed to ingest: " + ", ".join(d["title"] for d in items if d["status"] == "FAILED"))
        if time.time() > deadline:
            sys.exit(f"documents were not ready after {args.wait_seconds} s")
        time.sleep(3)
    print(f"{len(docs)} documents ready", file=sys.stderr)

    import os
    env = dict(os.environ, GLASSBOX_SERVER=args.base, GLASSBOX_API_KEY=key, GLASSBOX_HOME=tempfile.mkdtemp())
    imported = subprocess.run(["java", "-jar", args.cli, "testset", "import", str(HERE / "testset.jsonl"), "--name", args.name, "--finalize"], capture_output=True, text=True, env=env)
    print(imported.stdout, file=sys.stderr)
    if imported.returncode != 0:
        sys.exit("test set import failed: " + imported.stderr)
    found = re.search(r"id ([0-9a-f-]{36})", imported.stdout)
    if not found:
        sys.exit("could not read the test set id from: " + imported.stdout)
    skipped = re.findall(r"skipped line", imported.stdout)
    if skipped:
        sys.exit(f"{len(skipped)} frozen questions could not be matched to the frozen documents: the frozen files are out of step")

    lines = [f"GLASSBOX_API_KEY={key}", f"GLASSBOX_TESTSET={found.group(1)}"]
    print("\n".join(lines))
    if args.env_file:
        with open(args.env_file, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
