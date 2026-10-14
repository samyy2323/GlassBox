"""P3 acceptance run: measure pipeline configurations on a finalized test set and print what the Eval Lab shows.

    python benchmarks/testset_demo.py          # first: makes the demo account and generates a test set (finalize it in the UI)
    python benchmarks/eval_demo.py             # retrieval experiment: vector vs keyword vs hybrid vs hybrid + rerank
    python benchmarks/eval_demo.py --full 12   # also a full experiment (answers, claim checks, judge) on 12 sampled questions

It signs in with the throwaway account that testset_demo.py wrote (--creds), takes the newest FINAL test set, runs the experiments through
the API and prints each configuration's metrics with their 95% intervals and the verdict sentences the page shows. It never prints the password.
"""
import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request


def call(base, token, workspace, method, path, body=None):
    headers = {"Content-Type": "application/json", "Authorization": f"Bearer {token}", "X-Workspace-Id": workspace}
    req = urllib.request.Request(base + path, method=method, headers=headers, data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=300) as res:
            raw = res.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} failed: HTTP {e.code} {e.read().decode()[:400]}")


def pct(v):
    return "-" if v is None else f"{v * 100:.1f}%"


def metric(run, name, slice_="all", method="question"):
    for m in run["metrics"]:
        if m["metric"] == name and m["slice"] == slice_ and m["method"] == method:
            return m
    return None


def cell(m):
    if not m or m.get("value") is None:
        return "-"
    if m["metric"].startswith("latency"):
        return f"{m['value']:.0f} ms"
    if m.get("ciLow") is None:
        return pct(m["value"])
    return f"{pct(m['value'])} [{pct(m['ciLow'])}, {pct(m['ciHigh'])}]"


def wait(base, token, workspace, experiment_id, label):
    started = time.time()
    last = None
    while True:
        e = call(base, token, workspace, "GET", f"/api/experiments/{experiment_id}")
        progress = [r["samplesDone"] for r in e["runs"]]
        if progress != last:
            last = progress
            print(f"  {label}: {progress} of {e['questionCount']} questions per run, {int(time.time() - started)} s", flush=True)
        if e["status"] not in ("QUEUED", "RUNNING"):
            return e, time.time() - started
        time.sleep(5)


def report(e, headline, names):
    print(f"\n{e['name']}: {e['status']}, {e['questionCount']} questions" + (f" ({e['staleCount']} left out: document changed)" if e["staleCount"] else ""))
    width = max(len(r["label"]) for r in e["runs"]) + 2
    for name in names:
        print(f"\n  {name}")
        for r in e["runs"]:
            print(f"    {r['label']:<{width}}{cell(metric(r, name))}")
    print("\n  Is the difference real? (each configuration against the baseline,", e["runs"][0]["label"] + ")")
    for c in e["vsBaseline"]:
        row = next((x for x in c["rows"] if x["metric"] == headline), None)
        if row:
            hint = f"; this test set can detect about {row['minDetectable'] * 100:.1f} points" if row.get("minDetectable") is not None else ""
            print(f"    {c['label']}: {row['verdict']} [{row['test']}, p adjusted {row['pAdjusted']:.3f}, n={row['n']}{hint}]")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--creds", default=str(pathlib.Path.home() / ".glassbox-demo-account.json"))
    ap.add_argument("--only-full", action="store_true", help="skip the retrieval experiments and run only the full one (needs --full)")
    ap.add_argument("--no-depth", action="store_true", help="skip the experiment on how many chunks reach the prompt")
    ap.add_argument("--full", type=int, default=0, help="also run a full experiment on this many sampled questions")
    args = ap.parse_args()

    c = json.loads(pathlib.Path(args.creds).read_text())
    req = urllib.request.Request(args.base + "/api/auth/login", method="POST", headers={"Content-Type": "application/json"},
                                 data=json.dumps({"email": c["email"], "password": c["password"]}).encode())
    token = json.load(urllib.request.urlopen(req))["accessToken"]
    ws = c["workspace"]

    finals = [t for t in call(args.base, token, ws, "GET", "/api/testsets") if t["status"] == "FINAL"]
    if not finals:
        sys.exit("no FINAL test set: review and finalize one first (python benchmarks/testset_demo.py, then the Test sets page)")
    ts = sorted(finals, key=lambda t: t["createdAt"])[-1]
    print(f"test set: {ts['name']} v{ts['version']} ({ts['stats']['accepted'] + ts['stats']['edited']} accepted questions)")

    if not args.only_full:
        configs = [{"name": "Vector", "mode": "VECTOR"}, {"name": "Keyword", "mode": "KEYWORD"}, {"name": "Hybrid", "mode": "HYBRID"},
                   {"name": "Hybrid + rerank", "mode": "HYBRID", "reranker": "CROSS_ENCODER"}]
        est = call(args.base, token, ws, "POST", "/api/experiments/estimate", {"testsetId": ts["id"], "mode": "RETRIEVAL", "configs": configs})
        print(f"retrieval experiment: {est['questions']} questions x {est['runs']} configurations = {est['retrievalCalls']} searches, no model tokens")
        created = call(args.base, token, ws, "POST", "/api/experiments", {"testsetId": ts["id"], "name": "Vector vs keyword vs hybrid", "mode": "RETRIEVAL", "configs": configs})
        e, seconds = wait(args.base, token, ws, created["id"], "retrieval")
        report(e, "context_pass", ["context_pass", "recall@5", "recall@10", "mrr", "ndcg@10", "latency_p50_ms"])
        print(f"\n  finished in {seconds:.0f} s; open {args.base}/experiments/{e['id']}")

        if not args.no_depth:
            depth = [{"name": "Top 1", "mode": "VECTOR", "topN": 1}, {"name": "Top 3", "mode": "VECTOR", "topN": 3}, {"name": "Top 5", "mode": "VECTOR", "topN": 5}]
            created = call(args.base, token, ws, "POST", "/api/experiments", {"testsetId": ts["id"], "name": "How many chunks reach the prompt", "mode": "RETRIEVAL", "configs": depth})
            e, seconds = wait(args.base, token, ws, created["id"], "depth")
            report(e, "context_pass", ["context_pass", "context_recall", "mrr"])
            top1, top3 = e["runs"][0]["id"], e["runs"][1]["id"]
            d = call(args.base, token, ws, "GET", f"/api/experiments/compare?a={top1}&b={top3}")
            row = next(x for x in d["rows"] if x["metric"] == "context_pass")
            print(f"\n  Compared on their own (one comparison, so no correction across configurations), Top 1 vs Top 3: {row['verdict']}")
            flips = [f["question"] for f in d["failToPass"]]
            print(f"  {len(flips)} questions had their evidence missed with Top 1 and found with Top 3, for example: {flips[0] if flips else '-'}")
            print(f"\n  finished in {seconds:.0f} s; open {args.base}/experiments/{e['id']}")

    if args.full:
        full_configs = [{"name": "Vector", "mode": "VECTOR"}, {"name": "Hybrid", "mode": "HYBRID"}]
        body = {"testsetId": ts["id"], "mode": "FULL", "configs": full_configs, "questionLimit": args.full}
        est = call(args.base, token, ws, "POST", "/api/experiments/estimate", body)
        print(f"\nfull experiment: {est['questions']} sampled questions x {est['runs']} configurations, about {est['llmCalls']} model calls")
        created = call(args.base, token, ws, "POST", "/api/experiments", {**body, "name": "Answers: vector vs hybrid"})
        e, seconds = wait(args.base, token, ws, created["id"], "full")
        report(e, "correctness", ["correctness", "faithfulness", "relevance", "citation_precision", "false_abstention", "context_pass", "latency_p50_ms"])
        j = e.get("judge") or {}
        print(f"\n  judge: {j.get('model')} (rubric {j.get('rubricVersion')}); status "
              + ", ".join(f"{k}={v['status']}" for k, v in (j.get("metrics") or {}).items()))
        for w in j.get("warnings", []):
            print("  warning:", w)
        print(f"  finished in {seconds / 60:.1f} min; open {args.base}/experiments/{e['id']}")


if __name__ == "__main__":
    main()
