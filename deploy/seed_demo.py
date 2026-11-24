#!/usr/bin/env python3
"""Builds the read-only demo workspace through the public API, once, so a visitor opens a product that already has results in it.

It signs in as the demo account (creating it on a fresh server) and uses that account's own workspace, then runs the features that need a language model:
uploads the sample handbook, writes and finalizes a test set, asks questions (X-Ray traces), runs a retrieval experiment, a full-answer experiment, a small
AutoTune, turns a thumbs-down into a test question, makes a shared report and replaces the handbook with its second edition (the Changes page).

Run it against a server started WITHOUT the demo switch (the demo account is read-only once that is on), with a language model reachable by the API:

    GLASSBOX_SERVER=http://localhost:8080 GLASSBOX_DEMO_PASSWORD=choose-one python deploy/seed_demo.py

Every step logs and a failing step does not stop the others. The password is only used here; the public demo signs in without one.
"""
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("GLASSBOX_SERVER", "http://localhost:8080").rstrip("/")
EMAIL = os.environ.get("GLASSBOX_DEMO_EMAIL", "demo@glassbox.local")
PASSWORD = os.environ.get("GLASSBOX_DEMO_PASSWORD", "")
QUESTIONS_IN_SET = int(os.environ.get("GLASSBOX_DEMO_QUESTIONS", "40"))
SAMPLES = pathlib.Path(__file__).resolve().parent.parent / "samples"
TOKEN = ""
WS = ""
R = {"failed": [], "links": {}}


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


def raw(method, path, body=None, headers=None, timeout=300):
    h = {"Authorization": f"Bearer {TOKEN}", "X-Workspace-Id": WS, **(headers or {})}
    data = None
    if body is not None and not isinstance(body, bytes):
        h["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    elif isinstance(body, bytes):
        data = body
    req = urllib.request.Request(BASE + path, method=method, headers=h, data=data)
    return urllib.request.urlopen(req, timeout=timeout)


def api(method, path, body=None, headers=None, timeout=300):
    for attempt in range(2):
        try:
            with raw(method, path, body, headers, timeout) as res:
                text = res.read()
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            if e.code == 401 and attempt == 0 and not path.startswith("/api/auth/"):
                sign_in()  # the access token is short-lived
                continue
            raise RuntimeError(f"{method} {path} -> HTTP {e.code} {e.read().decode()[:300]}")


def sign_in():
    global TOKEN, WS
    for path in ("/api/auth/login", "/api/auth/register"):
        req = urllib.request.Request(BASE + path, method="POST", data=json.dumps({"email": EMAIL, "password": PASSWORD}).encode(), headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as res:
                TOKEN = json.loads(res.read())["accessToken"]
                break
        except urllib.error.HTTPError as e:
            if path.endswith("register"):
                raise RuntimeError(f"cannot sign in or register {EMAIL}: HTTP {e.code} {e.read().decode()[:200]}")
    WS = api("GET", "/api/workspaces")[0]["id"] if not WS else WS


def upload(path, as_name=None):
    boundary = uuid.uuid4().hex
    name = as_name or path.name
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\nContent-Type: text/markdown\r\n\r\n").encode() + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    return api("POST", "/api/documents", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})


def replace(doc_id, path, as_name):
    boundary = uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{as_name}\"\r\nContent-Type: text/markdown\r\n\r\n").encode() + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    return api("PUT", f"/api/documents/{doc_id}", body, {"Content-Type": f"multipart/form-data; boundary={boundary}"})


def wait(path, done, limit_min=45, every=10, key="status"):
    end = time.time() + limit_min * 60
    while time.time() < end:
        x = api("GET", path)
        if x[key] in done:
            return x
        time.sleep(every)
    raise RuntimeError("timed out waiting for " + path)


def chat(question):
    for _ in range(8):
        req = urllib.request.Request(BASE + "/api/chat", method="POST", data=json.dumps({"question": question}).encode(),
                                     headers={"Authorization": f"Bearer {TOKEN}", "X-Workspace-Id": WS, "Content-Type": "application/json"})
        try:
            event = None
            with urllib.request.urlopen(req, timeout=600) as res:
                for line in res:
                    line = line.decode().rstrip("\n")
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:") and event == "done":
                        return json.loads(line[5:])
                    elif line.startswith("data:") and event == "error":
                        raise RuntimeError(line[5:200])
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(20)
                continue
            raise
    raise RuntimeError("chat stayed busy")


def step(name, fn):
    log(f"=== {name}")
    t0 = time.time()
    try:
        fn()
        log(f"--- {name} done in {time.time() - t0:.0f}s")
    except Exception as e:  # one failed feature must not stop the others
        log(f"!!! {name} FAILED: {e}")
        R["failed"].append(name)


def documents():
    """The Harbor handbook (it is later replaced by its second edition) and the twelve site handbooks, so the test set has real variety."""
    files = [SAMPLES / "Harbor-Logistics-Handbook.md"] + sorted((SAMPLES.parent / "benchmarks" / "corpus-p4").glob("*.md"))
    for f in files:
        upload(f)
    end = time.time() + 900
    while time.time() < end:
        docs = api("GET", "/api/documents?limit=100")["items"]
        if len(docs) >= len(files) and all(d["status"] in ("READY", "FAILED") for d in docs):
            bad = [d["title"] for d in docs if d["status"] != "READY"]
            if bad:
                raise RuntimeError("did not index: " + ", ".join(bad))
            R["doc"] = next(d["id"] for d in docs if d["title"].startswith("Harbor-Logistics"))
            log(f"{len(docs)} documents indexed")
            return
        time.sleep(3)
    raise RuntimeError("indexing did not finish")


def testset():
    t = api("POST", "/api/testsets/generate", {"name": "Harbor handbook questions", "count": QUESTIONS_IN_SET})["testset"]
    x = wait(f"/api/testsets/{t['id']}", {"DRAFT", "FAILED"}, 60, 15)
    if x["status"] == "FAILED":
        raise RuntimeError(x.get("error") or "generation failed")
    # The reviewer's job, done by the script: the automatic checks flag a lot with a small model. A question flagged only as "ambiguous" is kept (the model's
    # own judgement of ambiguity is noisy); one that copies the passage or that the evidence cannot answer is rejected; the rest were accepted by the rules.
    api("POST", f"/api/testsets/{t['id']}/bulk-accept")
    kept = dropped = 0
    for q in api("GET", f"/api/testsets/{t['id']}/questions?limit=500")["items"]:
        if q["reviewStatus"] != "PENDING":
            continue
        bad = {"COPY_RISK", "LOW_ANSWERABILITY"} & set(q["flags"])
        api("PATCH", f"/api/questions/{q['id']}", {"reviewStatus": "REJECTED" if bad else "ACCEPTED"})
        dropped, kept = dropped + bool(bad), kept + (not bad)
    log(f"reviewed: kept {kept} flagged-as-ambiguous questions, rejected {dropped} that copy the text or cannot be answered")
    done = api("POST", f"/api/testsets/{t['id']}/finalize", {"allowPending": True})
    R["set1"] = t["id"]
    log(f"test set finalized: {done['stats']['accepted']} accepted of {done['stats']['total']}")
    R["links"]["Test set"] = f"/testsets/{t['id']}/review"


QUESTIONS = [
    "How many days of annual leave does a full-time employee get?",
    "How many unused leave days can be carried over, and by when must they be used?",
    "What is the maximum forklift speed in the container yard?",
    "Within how many hours must the written incident form be submitted?",
    "Above what amount does an expense need a receipt and department head approval?",
    "Who should I phone if I am sick, and by what time for the early shift?",
    "What is the capital of France?",
]


def chats():
    for q in QUESTIONS:
        d = chat(q)
        log(f"chat: {q} -> {d['answer'][:90]!r}{' (abstained)' if d.get('abstained') else ''}")


def retrieval():
    cfgs = [{"name": "vector", "mode": "VECTOR"}, {"name": "keyword", "mode": "KEYWORD"}, {"name": "hybrid", "mode": "HYBRID"},
            {"name": "hybrid + reranker", "mode": "HYBRID", "reranker": "CROSS_ENCODER"}]
    e = api("POST", "/api/experiments", {"testsetId": R["set1"], "name": "Retrieval: which search works best", "mode": "RETRIEVAL", "configs": cfgs, "confirm": True})
    x = wait(f"/api/experiments/{e['id']}", {"DONE", "FAILED", "ABORTED", "CANCELLED"}, 20)
    log(f"retrieval experiment {x['status']}")
    R["links"]["Retrieval experiment"] = f"/experiments/{e['id']}"


def full():
    cfgs = [{"name": "hybrid", "mode": "HYBRID"}, {"name": "hybrid + reranker", "mode": "HYBRID", "reranker": "CROSS_ENCODER"}]
    e = api("POST", "/api/experiments", {"testsetId": R["set1"], "name": "Full answers: judged by the model", "mode": "FULL", "configs": cfgs, "confirm": True})
    x = wait(f"/api/experiments/{e['id']}", {"DONE", "FAILED", "ABORTED", "CANCELLED"}, 60, 20)
    log(f"full experiment {x['status']}")
    R["exp_full"] = e["id"]
    R["links"]["Full-answer experiment"] = f"/experiments/{e['id']}"


def autotune():
    a = api("POST", "/api/autotune", {"testsetId": R["set1"], "name": "Search 1", "stage1Candidates": 6, "stage2Candidates": 6, "finalists": 2, "seed": 7, "confirm": True})
    x = wait(f"/api/autotune/{a['id']}", {"DONE", "FAILED", "CANCELLED"}, 40)
    log(f"AutoTune {x['status']} ({x.get('stage')}) {x.get('error') or ''}")
    R["links"]["AutoTune"] = f"/autotune/{a['id']}"


def feedback():
    d = chat("Who is the company doctor and when is the clinic?")
    item = api("POST", f"/api/traces/{d['traceId']}/feedback", {"rating": "DOWN", "comment": "please quote the sentence it came from"})
    detail = api("GET", f"/api/feedback/{item['id']}")
    best = next((p for p in detail["retrieved"] if p["disposition"] == "IN_CONTEXT"), detail["retrieved"][0])
    spans = [{"documentId": best["documentId"], "charStart": best["charStart"], "charEnd": best["charEnd"]}]
    api("POST", f"/api/feedback/{item['id']}/diagnose", {"spans": spans})
    conv = api("POST", f"/api/feedback/{item['id']}/convert", {"testsetId": R["set1"], "expectedAnswer": "Dr. Priya Raman, every Tuesday from 10:00 to 12:00 at Port Merrow", "qtype": "FACTUAL", "spans": spans})
    api("POST", f"/api/testsets/{conv['testsetId']}/finalize", {"allowPending": True})
    log("a thumbs-down became a test question; test set v2 finalized")


def report():
    rep = api("POST", "/api/reports", {"kind": "EXPERIMENT", "refId": R.get("exp_full") or "", "redact": False})
    api("POST", f"/api/reports/{rep['id']}/links", {"days": 90})
    log("report made")


def change():
    replace(R["doc"], SAMPLES / "Harbor-Logistics-Handbook-v2.md", "Harbor-Logistics-Handbook.md")
    end = time.time() + 15 * 60
    while time.time() < end:
        try:
            imp = api("GET", f"/api/documents/{R['doc']}/versions/2/impact")
        except RuntimeError:
            imp = None
        if imp and imp.get("status") in ("DONE", "FAILED", "SKIPPED", "NO_QUESTIONS") and imp.get("experimentStatus") not in ("RUNNING", "QUEUED"):
            log(f"change impact: {imp['status']}, stale {imp.get('stale')}")
            return
        time.sleep(10)
    raise RuntimeError("no impact report")


if __name__ == "__main__":
    if not PASSWORD:
        sys.exit("set GLASSBOX_DEMO_PASSWORD (any value; it is only used by this script)")
    sign_in()
    log(f"signed in as {EMAIL}, workspace {WS}")
    step("upload and index the handbook", documents)
    if R["failed"]:
        sys.exit("cannot continue without a document")
    step("test set", testset)
    step("chat and X-Ray traces", chats)
    step("retrieval experiment", retrieval)
    step("full-answer experiment", full)
    step("AutoTune", autotune)
    step("feedback to test question", feedback)
    step("report and share link", report)
    step("document change impact", change)
    log("finished" + (f"; failed: {', '.join(R['failed'])}" if R["failed"] else ", every step worked"))
    for k, v in R["links"].items():
        log(f"  {k}: {BASE}{v}")
