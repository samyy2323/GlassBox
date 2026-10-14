import math
import os

os.environ["SIDECAR_FAKE"] = "1"  # no model downloads in tests

from fastapi.testclient import TestClient  # noqa: E402

from app.main import MAX_CHARS, MAX_TEXTS, app  # noqa: E402


def cosine(a, b):
    return sum(x * y for x, y in zip(a, b))


def test_health():
    with TestClient(app) as c:
        r = c.get("/health")
        assert r.status_code == 200
        assert "bge-small-en-v1.5" in r.json()["embedModels"]


def test_embed_returns_normalised_vectors_of_the_model_dimension():
    with TestClient(app) as c:
        r = c.post("/embed", json={"texts": ["pump inspection every 90 days", "valve maintenance"]})
        assert r.status_code == 200
        body = r.json()
        assert body["model"] == "bge-small-en-v1.5" and body["dim"] == 384
        assert len(body["embeddings"]) == 2
        for v in body["embeddings"]:
            assert len(v) == 384
            assert math.isclose(math.sqrt(sum(x * x for x in v)), 1.0, rel_tol=1e-6)


def test_embed_is_deterministic_and_similar_texts_are_closer():
    with TestClient(app) as c:
        texts = ["pump inspection schedule", "pump inspection schedule", "quarterly revenue forecast"]
        v = c.post("/embed", json={"texts": texts}).json()["embeddings"]
        assert v[0] == v[1]
        assert cosine(v[0], v[1]) > cosine(v[0], v[2])


def test_embed_other_models_have_their_own_dimension():
    with TestClient(app) as c:
        r = c.post("/embed", json={"texts": ["x"], "model": "bge-base-en-v1.5"})
        assert r.json()["dim"] == 768


def test_unknown_model_is_rejected():
    with TestClient(app) as c:
        assert c.post("/embed", json={"texts": ["x"], "model": "nope"}).status_code == 422
        assert c.post("/rerank", json={"query": "q", "documents": ["d"], "model": "nope"}).status_code == 422


def test_limits():
    with TestClient(app) as c:
        assert c.post("/embed", json={"texts": []}).status_code == 422
        assert c.post("/embed", json={"texts": ["x"] * (MAX_TEXTS + 1)}).status_code == 422
        assert c.post("/embed", json={"texts": ["x" * (MAX_CHARS + 1)]}).status_code == 413


def test_rerank_scores_each_document_in_order():
    with TestClient(app) as c:
        docs = ["unrelated cooking text", "pump inspection every 90 days", "pump"]
        r = c.post("/rerank", json={"query": "pump inspection", "documents": docs})
        assert r.status_code == 200
        scores = r.json()["scores"]
        assert len(scores) == 3
        assert scores[1] > scores[2] > scores[0]


def test_nli_scores_each_pair_with_probabilities_that_sum_to_one():
    with TestClient(app) as c:
        pairs = [
            {"premise": "Pumps must be inspected every 90 days.", "hypothesis": "Pumps are inspected every 90 days."},
            {"premise": "Pumps must be inspected every 90 days.", "hypothesis": "The cafeteria serves lunch on Fridays."},
            {"premise": "Pumps must be inspected every 90 days.", "hypothesis": "This contradicts the schedule."},
        ]
        r = c.post("/nli", json={"pairs": pairs})
        assert r.status_code == 200
        results = r.json()["results"]
        assert len(results) == 3
        for s in results:
            assert math.isclose(s["entailment"] + s["neutral"] + s["contradiction"], 1.0, rel_tol=1e-6)
        assert results[0]["entailment"] > 0.8
        assert results[1]["entailment"] < 0.4
        assert results[2]["contradiction"] > 0.8


def test_nli_limits_and_unknown_model():
    with TestClient(app) as c:
        assert c.post("/nli", json={"pairs": []}).status_code == 422
        too_many = [{"premise": "a", "hypothesis": "b"}] * 65
        assert c.post("/nli", json={"pairs": too_many}).status_code == 422
        assert c.post("/nli", json={"pairs": [{"premise": "a", "hypothesis": "b"}], "model": "nope"}).status_code == 422
        assert c.post("/nli", json={"pairs": [{"premise": "x" * (MAX_CHARS + 1), "hypothesis": "b"}]}).status_code == 413
        assert "nli-deberta-v3-xsmall" in c.get("/health").json()["nliModels"]
