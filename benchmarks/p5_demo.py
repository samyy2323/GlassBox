"""P5 acceptance run against the live stack: every P5 feature once, through the same API the web app uses.

    python benchmarks/autotune_demo.py prepare   # once: account, documents, final test set (shared with the P4 run)
    python benchmarks/p5_demo.py feedback        # chat -> thumbs-down -> classified from the stored trace -> a FEEDBACK test question
    python benchmarks/p5_demo.py transformers    # none vs multi_query vs decompose on the newest final test set (retrieval only)
    python benchmarks/p5_demo.py budget          # a full-answer run with a tiny call cap stops at 120% and keeps what it measured
    python benchmarks/p5_demo.py change          # replace a document: spans re-located, regression run compared with the earlier one
    python benchmarks/p5_demo.py report          # PDF, read-only link opened with no sign-in, revoked link refused

All numbers printed come from the running system; nothing is hard-coded.
"""
import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from autotune_demo import CORPUS, CREDS, call, login, pct  # noqa: E402


def newest_final(base, token, ws):
    sets = call(base, "GET", "/api/testsets", token, ws)
    sets = sets["items"] if isinstance(sets, dict) else sets
    final = [s for s in sets if s.get("status") == "FINAL"]
    if not final:
        sys.exit("no final test set; run autotune_demo.py prepare first")
    return max(final, key=lambda s: s.get("createdAt", ""))


def chat(base, token, ws, question):
    """POST /api/chat and read the event stream until 'done'; returns the done payload."""
    req = urllib.request.Request(base + "/api/chat", method="POST", data=json.dumps({"question": question}).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}", "X-Workspace-Id": ws})
    event = None
    with urllib.request.urlopen(req, timeout=600) as res:
        for raw in res:
            line = raw.decode().rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:") and event == "done":
                return json.loads(line[5:])
            elif line.startswith("data:") and event == "error":
                sys.exit("chat failed: " + line[5:200])
    sys.exit("chat ended without a done event")


def wait_experiment(base, token, ws, eid, limit=1800):
    end = time.time() + limit
    while time.time() < end:
        e = call(base, "GET", f"/api/experiments/{eid}", token, ws)
        if e["status"] in ("DONE", "FAILED", "ABORTED", "CANCELLED"):
            return e
        time.sleep(4)
    sys.exit("experiment did not finish in time")


def metric(run, name):
    for m in run["metrics"]:
        if m["metric"] == name and m.get("slice") == "all" and m.get("method") == "question":
            return m
    return None


def feedback(args):
    token, ws, _ = login(args.base, args.creds)
    ts = newest_final(args.base, token, ws)
    questions = call(args.base, "GET", f"/api/testsets/{ts['id']}/questions?limit=5", token, ws)["items"]
    q = args.question or questions[0]["question"]
    print(f"asking: {q}")
    done = chat(args.base, token, ws, q)
    print(f"answer ({done['latencyMs']} ms, abstained={done['abstained']}): {done['answer'][:200]}")
    item = call(args.base, "POST", f"/api/traces/{done['traceId']}/feedback", token, ws, {"rating": "DOWN", "comment": "demo: not what I asked"})
    detail = call(args.base, "GET", f"/api/feedback/{item['id']}", token, ws)
    print(f"inbox item {item['status']}; trace available={detail['traceAvailable']}; retrieved {len(detail['retrieved'])} passages")
    best = next((p for p in detail["retrieved"] if p["disposition"] == "IN_CONTEXT"), detail["retrieved"][0])
    spans = [{"documentId": best["documentId"], "charStart": best["charStart"], "charEnd": best["charEnd"]}]
    diag = call(args.base, "POST", f"/api/feedback/{item['id']}/diagnose", token, ws, {"spans": spans})
    print(f"diagnosis with that passage marked as the right one: {json.dumps(diag)[:300]}")
    conv = call(args.base, "POST", f"/api/feedback/{item['id']}/convert", token, ws,
                {"testsetId": ts["id"], "expectedAnswer": done["answer"][:200], "qtype": "FACTUAL", "spans": spans})
    print(f"converted into test set version (new version={conv['newVersion']}): {conv['question']['id']} origin={conv['question'].get('origin')}")
    again = call(args.base, "POST", f"/api/traces/{done['traceId']}/feedback", token, ws, {"rating": "DOWN", "comment": "again"})
    print("a second thumbs-down on the same answer ->", again["status"])
    s = call(args.base, "GET", "/api/feedback/summary", token, ws)
    print(f"summary: {s['questionsFromFeedback']} of {s['questionsTotal']} questions came from feedback")


def transformers(args):
    token, ws, _ = login(args.base, args.creds)
    ts = newest_final(args.base, token, ws)
    base = {"mode": "HYBRID", "topN": 5}
    configs = [dict(base, name="no transformer"), dict(base, name="multi_query", transformer="MULTI_QUERY"),
               dict(base, name="hyde", transformer="HYDE"), dict(base, name="decompose", transformer="DECOMPOSE")]
    body = {"testsetId": ts["id"], "name": "P5 query transformers", "mode": "RETRIEVAL", "configs": configs, "questionLimit": args.questions}
    est = call(args.base, "POST", "/api/experiments/estimate", token, ws, {k: body[k] for k in ("testsetId", "mode", "configs", "questionLimit")})
    print(f"estimate: {est['questions']} questions, {est['retrievalCalls']} searches, {est['llmCalls']} model calls, ${est['estimatedUsd']:.4f}")
    for n in est["notes"]:
        print("  note:", n)
    exp = call(args.base, "POST", "/api/experiments", token, ws, dict(body, confirm=True))
    t0 = time.time()
    e = wait_experiment(args.base, token, ws, exp["id"])
    print(f"finished {e['status']} in {time.time() - t0:.0f} s")
    for run in e["runs"]:
        parts = [(f"{n}={pct(metric(run, n)['value'])} [{pct(metric(run, n).get('ciLow'))}, {pct(metric(run, n).get('ciHigh'))}]" if metric(run, n) else f"{n}=-") for n in ("context_recall", "context_pass", "mrr")]
        print(f"  {run['label']:<16} " + "  ".join(parts))
    print("experiment id (for the report step):", exp["id"])


def budget(args):
    token, ws, _ = login(args.base, args.creds)
    ts = newest_final(args.base, token, ws)
    body = {"testsetId": ts["id"], "name": "P5 capped run", "mode": "FULL", "configs": [{"name": "hybrid", "mode": "HYBRID"}],
            "questionLimit": 20, "capCalls": args.cap_calls}
    est = call(args.base, "POST", "/api/experiments/estimate", token, ws, {k: body[k] for k in ("testsetId", "mode", "configs", "questionLimit")})
    print(f"estimate without the cap: {est['llmCalls']} model calls; the cap is {args.cap_calls}, so the run should stop at {int(args.cap_calls * 1.2)}")
    req = urllib.request.Request(args.base + "/api/experiments", method="POST", data=json.dumps(dict(body, confirm=True)).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}", "X-Workspace-Id": ws})
    try:
        exp = json.loads(urllib.request.urlopen(req, timeout=60).read())
    except urllib.error.HTTPError as err:
        print(f"refused before anything was created: HTTP {err.code} {json.loads(err.read())['detail']}")
        exp = None
    if exp:
        e = wait_experiment(args.base, token, ws, exp["id"])
        print(f"status: {e['status']}; samples measured: {[r['samplesDone'] for r in e['runs']]}")
    b = call(args.base, "GET", "/api/budget", token, ws)
    print("budget page:", json.dumps(b)[:400])


def change(args):
    token, ws, creds = login(args.base, args.creds)
    docs = call(args.base, "GET", "/api/documents?limit=100", token, ws)["items"]
    doc = docs[0]
    new_version = (doc.get("version") or 1) + 1
    text = call(args.base, "GET", f"/api/documents/{doc['id']}/content", token, ws)
    body = text.get("text") if isinstance(text, dict) else str(text)
    paragraphs = body.split("\n\n")
    at = next((i for i, x in enumerate(paragraphs) if args.edit_containing and args.edit_containing in x), len(paragraphs) // 2)
    paragraphs[at] = f"This section was rewritten for the demo (revision {new_version}) and no longer holds the earlier facts."
    path = pathlib.Path(args.tmp) / f"{doc['title'].rsplit('.', 1)[0]}.md"
    path.write_text("\n\n".join(paragraphs), encoding="utf-8")
    import subprocess
    out = subprocess.run(["curl", "-s", "-f", "-X", "PUT", f"{args.base}/api/documents/{doc['id']}", "-H", f"Authorization: Bearer {token}",
                          "-H", f"X-Workspace-Id: {ws}", "-F", f"file=@{path}"], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit("replace failed: " + (out.stderr or out.stdout))
    print(f"replaced {doc['title']}; waiting for ingestion and the impact job")
    end = time.time() + 1200
    impact = None
    while time.time() < end:
        r = subprocess.run(["curl", "-s", f"{args.base}/api/documents/{doc['id']}/versions/{new_version}/impact", "-H", f"Authorization: Bearer {token}",
                            "-H", f"X-Workspace-Id: {ws}"], capture_output=True, text=True)
        try:
            impact = json.loads(r.stdout)
        except ValueError:
            impact = None
        if impact and impact.get("status") in ("FAILED", "SKIPPED") or (impact and impact.get("status") == "DONE" and impact.get("experimentStatus") != "RUNNING"):
            break
        time.sleep(5)
    print(json.dumps(impact, indent=2)[:2500])


def report(args):
    token, ws, _ = login(args.base, args.creds)
    exps = call(args.base, "GET", "/api/experiments", token, ws)
    exps = exps["items"] if isinstance(exps, dict) else exps
    done = [e for e in exps if e["status"] == "DONE"]
    if not done:
        sys.exit("no finished experiment; run the transformers step first")
    e = done[0]
    rep = call(args.base, "POST", "/api/reports", token, ws, {"kind": "EXPERIMENT", "refId": e["id"], "redact": False})
    link = call(args.base, "POST", f"/api/reports/{rep['id']}/links", token, ws, {"days": 7})
    tok = link.get("token") or link.get("url", "").rsplit("/", 1)[-1]
    print(f"report {rep['id']} made; link token shown once ({len(tok)} chars)")
    pub = urllib.request.urlopen(f"{args.base}/api/public/reports/{tok}", timeout=60)
    snap = json.loads(pub.read())
    print(f"public link, no sign-in: HTTP {pub.status}, title={snap['title']!r}, cache-control={pub.headers.get('Cache-Control')}")
    pdf = urllib.request.urlopen(f"{args.base}/api/public/reports/{tok}/pdf", timeout=60).read()
    out = pathlib.Path(args.tmp) / "p5_report.pdf"
    out.write_bytes(pdf)
    print(f"PDF: {len(pdf)} bytes, starts {pdf[:5]!r}, saved to {out}")
    call(args.base, "DELETE", f"/api/reports/{rep['id']}/links/{link.get('id')}", token, ws)
    try:
        urllib.request.urlopen(f"{args.base}/api/public/reports/{tok}", timeout=60)
        print("revoked link STILL OPENS (bug)")
    except urllib.error.HTTPError as err:
        print(f"after revoking: HTTP {err.code}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("phase", choices=["feedback", "transformers", "budget", "change", "report"])
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--creds", default=str(CREDS))
    ap.add_argument("--question", default=None)
    ap.add_argument("--edit-containing", default=None, help="change: rewrite the paragraph that contains this text")
    ap.add_argument("--questions", type=int, default=30)
    ap.add_argument("--cap-calls", type=int, default=10)
    ap.add_argument("--tmp", default=str(pathlib.Path.home()))
    args = ap.parse_args()
    {"feedback": feedback, "transformers": transformers, "budget": budget, "change": change, "report": report}[args.phase](args)


if __name__ == "__main__":
    main()
