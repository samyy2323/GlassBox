"""P4 acceptance run: generate a hard test set, search for a better configuration with AutoTune, and print what the pages show.

    python benchmarks/make_corpus.py                   # once: writes benchmarks/corpus-p4 (twelve similar site handbooks)
    python benchmarks/autotune_demo.py prepare         # account, upload, generate the test set, review it by rule, finalize it
    python benchmarks/autotune_demo.py tune            # AutoTune on the newest final test set, then the failure report of the baseline
    python benchmarks/autotune_demo.py tune --full 20  # also measure full answers for the finalists (slow on a small local model)

`prepare` makes a throwaway account (login saved to --creds, password never printed). The review is automatic so the run can be repeated:
questions with no quality flag are accepted and flagged ones are rejected. That is a shortcut for this benchmark; for real use a person
reviews the questions on the test set page. Everything else goes through the same API the web app uses.
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

CORPUS = sorted((pathlib.Path(__file__).parent / "corpus-p4").glob("*.md"))
CREDS = pathlib.Path.home() / ".glassbox-autotune-account.json"


def call(base, method, path, token=None, workspace=None, body=None):
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if workspace:
        headers["X-Workspace-Id"] = workspace
    req = urllib.request.Request(base + path, method=method, headers=headers, data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=300) as res:
            raw = res.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} failed: HTTP {e.code} {e.read().decode()[:400]}")


def login(base, creds):
    c = json.loads(pathlib.Path(creds).read_text())
    auth = call(base, "POST", "/api/auth/login", body={"email": c["email"], "password": c["password"]})
    return auth["accessToken"], c["workspace"], c


def pct(v):
    return "-" if v is None else f"{v * 100:.1f}%"


def prepare(args):
    if not CORPUS:
        sys.exit("run benchmarks/make_corpus.py first")
    email = f"autotune-{secrets.token_hex(4)}@example.com"
    password = secrets.token_urlsafe(18)
    auth = call(args.base, "POST", "/api/auth/register", body={"email": email, "password": password})
    token = auth["accessToken"]
    workspace = call(args.base, "GET", "/api/workspaces", token)[0]["id"]
    creds = {"email": email, "password": password, "workspace": workspace}
    pathlib.Path(args.creds).write_text(json.dumps(creds))
    print(f"account created; login saved to {args.creds}")

    for doc in CORPUS:
        out = subprocess.run(["curl", "-s", "-f", "-X", "POST", args.base + "/api/documents", "-H", f"Authorization: Bearer {token}",
                              "-H", f"X-Workspace-Id: {workspace}", "-F", f"file=@{doc}"], capture_output=True, text=True)
        if out.returncode != 0:
            sys.exit(f"upload of {doc.name} failed: {out.stderr or out.stdout}")
    deadline = time.time() + 900
    while time.time() < deadline:
        docs = call(args.base, "GET", "/api/documents?limit=100", token, workspace)["items"]
        if len(docs) == len(CORPUS) and all(d["status"] == "READY" for d in docs):
            break
        time.sleep(3)
    else:
        sys.exit("documents did not become ready in time")
    print(f"{len(CORPUS)} documents ready")

    started = time.time()
    mix = {"FACTUAL": 45, "NUMERIC": 20, "MULTI_HOP": 10, "COMPARISON": 15, "UNANSWERABLE": 10}
    made = call(args.base, "POST", "/api/testsets/generate", token, workspace, {"name": args.name, "count": args.count, "mix": mix, "seed": args.seed})
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
        time.sleep(15)
    seconds = time.time() - started
    if t["status"] != "DRAFT":
        sys.exit(f"generation ended as {t['status']}: {t.get('error')}")
    s = t["stats"]
    print(f"generated {t['generatedCount']} questions in {seconds / 60:.1f} min ({seconds / max(1, t['generatedCount']):.0f} s per question)")
    print("by type:", s["byType"], "| by flag:", s["byFlag"])

    items = call(args.base, "GET", f"/api/testsets/{tid}/questions?limit=200", token, workspace)["items"]
    accepted = rejected = 0
    for q in items:
        status = "ACCEPTED" if not q["flags"] else "REJECTED"
        call(args.base, "PATCH", f"/api/questions/{q['id']}", token, workspace, {"reviewStatus": status})
        accepted += status == "ACCEPTED"
        rejected += status == "REJECTED"
    final = call(args.base, "POST", f"/api/testsets/{tid}/finalize", token, workspace, {"allowPending": False})
    print(f"review by rule: {accepted} accepted, {rejected} rejected (flagged); finalized as version {final['version']}")
    print("accepted by type:", {t: sum(1 for q in items if q["qtype"] == t and not q["flags"]) for t in sorted({q['qtype'] for q in items})})
    creds["testset"] = tid
    pathlib.Path(args.creds).write_text(json.dumps(creds))


def wait_autotune(base, token, workspace, aid):
    started = time.time()
    last = None
    while True:
        a = call(base, "GET", f"/api/autotune/{aid}", token, workspace)
        state = (a["stage"], a["candidates"].get("STAGE1"), a["candidates"].get("STAGE2"), a["spent"].get("retrievalCalls"), a["spent"].get("llmCalls"))
        if state != last:
            last = state
            print(f"  stage {a['stage']}: {a['spent'].get('retrievalCalls', 0)} searches, {a['spent'].get('llmCalls', 0)} model calls, {int(time.time() - started)} s", flush=True)
        if a["status"] not in ("QUEUED", "RUNNING"):
            return a, time.time() - started
        time.sleep(5)


def report(base, token, workspace, a, seconds):
    print(f"\nAutoTune '{a['name']}': {a['status']} in {seconds / 60:.1f} min" + (f" ({a['error']})" if a.get("error") else ""))
    if a["status"] != "DONE":
        return
    split, r, h = a["split"], a["result"], a["result"]["headline"]
    print(f"split: {split['tuning']} questions to tune on ({split['tuningAnswerable']} answerable), {split['heldOut']} held out ({split['heldOutAnswerable']} answerable)")
    print(f"spent: {a['spent'].get('retrievalCalls', 0)} searches, {a['spent'].get('llmCalls', 0)} model calls" + (" - stopped at the spend cap" if a.get("stoppedBy") else ""))
    for name, st in r["stages"].items():
        rounds = "; ".join(f"{x['entered']} on {x['sample']} questions -> {x['kept']}" for x in st["rounds"])
        print(f"  {name}: {st['candidates']} candidates. rounds: {rounds}")
    print(f"\nquality = {h['qualityDefinition']}")
    print("\nheld-out result, finalists against the baseline (paired, Holm-corrected across finalists):")
    for c in r["candidates"]:
        ho = c["heldOut"]
        vs = f" | {c['vsBaseline']['verdict']}" if c.get("vsBaseline") else ""
        tuning = pct(c["tuning"]["quality"]) if c.get("tuning") else "-"
        print(f"  [{c['role']:<8}] {c['label']}\n      tuning {tuning}; held-out {pct(ho['quality'])} [{pct(ho['ciLow'])}, {pct(ho['ciHigh'])}] on {ho['n']} questions; "
              f"{ho['promptTokens']:.0f} prompt tokens; {ho['latencyMs']:.0f} ms; frontier={c['onFrontier']}{vs}")
    print(f"\nheadline: {h['winnerLabel']}\n  {h['verdict']}")
    if h.get("shrinkage") is not None:
        print(f"  tuning {pct(h['tuning']['quality'])} vs held-out {pct(h['heldOut']['quality'])}: shrinkage {h['shrinkage'] * 100:+.1f} points")
    by_key = {c["key"]: c["label"] for c in r["candidates"]}
    print("\nrecommendations:", {k: by_key.get(v, v) for k, v in r["recommendations"].items()})
    groups = {}
    for e in r["effects"]:
        groups.setdefault((e["stage"], e["dimension"]), []).append(e)
    ranked = sorted(groups.items(), key=lambda kv: -(max(x["averageQuality"] for x in kv[1]) - min(x["averageQuality"] for x in kv[1])))
    print("\nwhat mattered (average context recall by setting, largest spread first):")
    for (stage, dim), rows in ranked[:6]:
        rows = sorted(rows, key=lambda x: -x["averageQuality"])
        print(f"  {dim}: " + ", ".join(f"{x['value']} {pct(x['averageQuality'])}" for x in rows))

    exp = call(base, "GET", f"/api/experiments/{a['experimentId']}", token, workspace)
    held = [run for run in exp["runs"] if run["label"].endswith("held-out")]
    for run in held[:1] + held[-1:]:
        d = call(base, "GET", f"/api/experiments/{exp['id']}/diagnosis?run={run['id']}", token, workspace)
        print(f"\nfailure report for {run['label']}: {d['failed']} of {d['questions']} held-out questions failed")
        for stage, n in d["byStage"].items():
            if n:
                print(f"  {stage}: {n}")
        for rec in d["recommendations"]:
            print(f"  -> {rec['text']}" + (f" [one click: {rec['changeLabel']}]" if rec.get("change") else ""))


def tune(args):
    token, workspace, creds = login(args.base, args.creds)
    tid = creds.get("testset")
    if not tid:
        sys.exit("no test set recorded: run 'prepare' first")
    body = {"testsetId": tid, "name": args.name, "stage1Candidates": args.stage1, "stage2Candidates": args.stage2, "finalists": args.finalists, "seed": args.seed}
    if args.full:
        body.update({"fullStage": True, "fullQuestions": args.full, "capMinutes": args.cap_minutes})
    est = call(args.base, "POST", "/api/autotune/estimate", token, workspace, body)
    print(f"estimate: {est['tuningQuestions']} tuning / {est['heldOutQuestions']} held-out questions, about {est['retrievalCalls']} searches, {est['llmCalls']} model calls")
    for n in est["notes"]:
        print("  -", n)
    a = call(args.base, "POST", "/api/autotune", token, workspace, body)
    print(f"started {a['id']}")
    a, seconds = wait_autotune(args.base, token, workspace, a["id"])
    report(args.base, token, workspace, a, seconds)
    print(f"\nopen it at {args.base}/autotune/{a['id']}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")  # configuration labels contain a middle dot
    ap = argparse.ArgumentParser()
    ap.add_argument("phase", choices=["prepare", "tune"])
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--creds", default=str(CREDS))
    ap.add_argument("--count", type=int, default=120)
    ap.add_argument("--name", default=None)
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--stage1", type=int, default=18)
    ap.add_argument("--stage2", type=int, default=24)
    ap.add_argument("--finalists", type=int, default=5)
    ap.add_argument("--full", type=int, default=0, help="also measure full answers for the finalists, on this many questions per part")
    ap.add_argument("--cap-minutes", type=int, default=120)
    args = ap.parse_args()
    if args.name is None:
        args.name = "Site handbooks" if args.phase == "prepare" else ("AutoTune with full answers" if args.full else "AutoTune")
    {"prepare": prepare, "tune": tune}[args.phase](args)


if __name__ == "__main__":
    main()
