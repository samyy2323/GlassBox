"""P6 acceptance run: the quality gate blocks a deliberately bad change, with the real CLI against the live stack.

    python benchmarks/gate_demo.py              # needs `autotune_demo.py prepare` once (account, documents, a final test set)

It makes an API key with the run scope for the throwaway account, then calls the CLI jar three times and prints what each call printed and its exit code:
  1. a good configuration against absolute minimums                       -> exit 0
  2. a deliberately bad configuration (one chunk, a 100-token budget)
     against the good run as baseline, with --fail-on-regression         -> exit 1
  3. the same bad configuration with minimums only, then no rules at all  -> exit 1, exit 2
Build the jar first: (cd cli && ./mvnw -B package).
"""
import argparse
import json
import os
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from autotune_demo import CREDS, call, login  # noqa: E402

JAR = pathlib.Path(__file__).resolve().parent.parent / "cli" / "target" / "glassbox-cli.jar"


def cli(env, *args):
    out = subprocess.run(["java", "-jar", str(JAR), *args], capture_output=True, text=True, env=env)
    return out.returncode, out.stdout, out.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--creds", default=str(CREDS))
    ap.add_argument("--testset", default=None, help="id of a final test set (default: the newest final one)")
    args = ap.parse_args()
    if not JAR.exists():
        sys.exit("build the CLI first: (cd cli && ./mvnw -B package)")

    token, ws, _ = login(args.base, args.creds)
    testset = args.testset
    if not testset:
        sets = call(args.base, "GET", "/api/testsets", token, ws)
        sets = sets["items"] if isinstance(sets, dict) else sets
        final = [s for s in sets if s["status"] == "FINAL"]
        testset = max(final, key=lambda s: s["version"])["id"]
    key = call(args.base, "POST", f"/api/workspaces/{ws}/api-keys", token, body={"label": "gate-demo", "scopes": ["run"]})["key"]
    env = dict(os.environ, GLASSBOX_SERVER=args.base, GLASSBOX_API_KEY=key, GLASSBOX_HOME=tempfile.mkdtemp())
    print(f"test set {testset}\n")

    with tempfile.TemporaryDirectory() as tmp:
        good = pathlib.Path(tmp) / "good.json"
        bad = pathlib.Path(tmp) / "bad.json"
        good.write_text(json.dumps({"name": "good", "mode": "HYBRID"}))
        bad.write_text(json.dumps({"name": "bad", "mode": "HYBRID", "topN": 1, "tokenBudget": 100}))

        print("=== 1. a good configuration against minimums ===")
        code, out, err = cli(env, "run", "--testset", testset, "--config", str(good), "--name", "good change", "--fail-under", "context_recall=0.8,mrr=0.6", "--format", "json")
        result = json.loads(out)
        print(f"exit code {code}; state {result['state']}; {result['questions']} questions\n")
        baseline = result["runId"]
        code, out, err = cli(env, "run", "--testset", testset, "--config", str(good), "--name", "good change", "--fail-under", "context_recall=0.8,mrr=0.6")
        print(out)
        print(f"exit code {code}\n")

        print("=== 2. a deliberately bad change against the good run ===")
        code, out, err = cli(env, "run", "--testset", testset, "--config", str(bad), "--name", "bad change", "--baseline", baseline, "--fail-on-regression",
                             "--fail-under", "context_recall=0.8", "--regression-metric", "context_recall,mrr", "--junit", str(pathlib.Path(tmp) / "bad.xml"))
        print(out)
        print(f"exit code {code}")
        print("junit:", (pathlib.Path(tmp) / "bad.xml").read_text()[:600], "\n")

        print("=== 3. no rules, and a key without a server ===")
        code, out, err = cli(env, "run", "--testset", testset)
        print(f"exit code {code}: {err.strip()}")
        code, out, err = cli(dict(env, GLASSBOX_API_KEY="gb_wrong"), "run", "--testset", testset, "--fail-under", "mrr=0.5")
        print(f"exit code {code}: {err.strip()}")


if __name__ == "__main__":
    main()
