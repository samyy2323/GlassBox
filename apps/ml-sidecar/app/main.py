"""Glassbox ML sidecar: local embeddings, cross-encoder reranking and NLI claim checking (SPEC section 3 and 10).

Runs ONNX models through fastembed, so no PyTorch is needed. Set SIDECAR_FAKE=1 to use
deterministic fake backends (used by tests and CI so no model is downloaded).
"""
import hashlib
import math
import os
import re
import threading
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

# Public model ids (what Glassbox stores in embeddings.model) -> fastembed model names.
EMBED_MODELS = {
    "bge-small-en-v1.5": "BAAI/bge-small-en-v1.5",
    "bge-base-en-v1.5": "BAAI/bge-base-en-v1.5",
    "all-MiniLM-L6-v2": "sentence-transformers/all-MiniLM-L6-v2",
}
DEFAULT_EMBED_MODEL = "bge-small-en-v1.5"
RERANK_MODELS = {"ms-marco-MiniLM-L-6-v2": "Xenova/ms-marco-MiniLM-L-6-v2"}
DEFAULT_RERANK_MODEL = "ms-marco-MiniLM-L-6-v2"

NLI_MODEL = "nli-deberta-v3-xsmall"
MAX_PAIRS = 64
MAX_TEXTS = 256
MAX_CHARS = 20_000
FAKE = os.environ.get("SIDECAR_FAKE") == "1"
FAKE_DIMS = {"bge-small-en-v1.5": 384, "bge-base-en-v1.5": 768, "all-MiniLM-L6-v2": 384}


class Backends:
    """Loads models lazily and caches them; loading is serialised so concurrent requests do not double-load."""

    def __init__(self):
        self._embed, self._rerank, self._nli = {}, {}, None
        self._lock = threading.Lock()

    def embedder(self, model_id: str):
        with self._lock:
            if model_id not in self._embed:
                if FAKE:
                    self._embed[model_id] = _FakeEmbedder(FAKE_DIMS[model_id])
                else:
                    from fastembed import TextEmbedding

                    self._embed[model_id] = TextEmbedding(model_name=EMBED_MODELS[model_id])
            return self._embed[model_id]

    def reranker(self, model_id: str):
        with self._lock:
            if model_id not in self._rerank:
                if FAKE:
                    self._rerank[model_id] = _FakeReranker()
                else:
                    from fastembed.rerank.cross_encoder import TextCrossEncoder

                    self._rerank[model_id] = TextCrossEncoder(model_name=RERANK_MODELS[model_id])
            return self._rerank[model_id]

    def nli(self):
        with self._lock:
            if self._nli is None:
                if FAKE:
                    self._nli = _FakeNli()
                else:
                    from app.nli_model import NliModel

                    self._nli = NliModel()
            return self._nli


_TOKEN = re.compile(r"\w+")


class _FakeEmbedder:
    """Hashed bag-of-words: texts sharing words get similar vectors. Deterministic, no model."""

    def __init__(self, dim: int):
        self.dim = dim

    def embed(self, texts):
        for t in texts:
            v = [0.0] * self.dim
            for w in _TOKEN.findall(t.lower()):
                v[int(hashlib.sha256(w.encode()).hexdigest(), 16) % self.dim] += 1.0
            norm = math.sqrt(sum(x * x for x in v)) or 1.0
            yield [x / norm for x in v]


class _FakeReranker:
    def rerank(self, query, documents):
        q = set(_TOKEN.findall(query.lower()))
        for d in documents:
            words = set(_TOKEN.findall(d.lower()))
            yield len(q & words) / (len(q) or 1)


backends = Backends()


@asynccontextmanager
async def lifespan(_: FastAPI):
    # Load the defaults at startup so the first request is fast and a broken image fails the healthcheck.
    backends.embedder(DEFAULT_EMBED_MODEL)
    backends.reranker(DEFAULT_RERANK_MODEL)
    backends.nli()
    yield


app = FastAPI(title="glassbox-ml-sidecar", lifespan=lifespan)


class EmbedRequest(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=MAX_TEXTS)
    model: str = DEFAULT_EMBED_MODEL


class EmbedResponse(BaseModel):
    model: str
    dim: int
    embeddings: list[list[float]]


class RerankRequest(BaseModel):
    query: str
    documents: list[str] = Field(min_length=1, max_length=MAX_TEXTS)
    model: str = DEFAULT_RERANK_MODEL


class RerankResponse(BaseModel):
    model: str
    scores: list[float]


class NliPair(BaseModel):
    premise: str
    hypothesis: str


class NliRequest(BaseModel):
    pairs: list[NliPair] = Field(min_length=1, max_length=MAX_PAIRS)
    model: str = NLI_MODEL


class NliScore(BaseModel):
    entailment: float
    neutral: float
    contradiction: float


class NliResponse(BaseModel):
    model: str
    results: list[NliScore]


def _check_texts(texts: list[str]) -> None:
    if any(len(t) > MAX_CHARS for t in texts):
        raise HTTPException(status_code=413, detail=f"each text must be at most {MAX_CHARS} characters")


@app.get("/health")
def health():
    return {"status": "ok", "fake": FAKE, "embedModels": list(EMBED_MODELS), "rerankModels": list(RERANK_MODELS), "nliModels": [NLI_MODEL]}


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if req.model not in EMBED_MODELS:
        raise HTTPException(status_code=422, detail=f"unknown model {req.model}; known: {list(EMBED_MODELS)}")
    _check_texts(req.texts)
    vectors = [_normalize([float(x) for x in v]) for v in backends.embedder(req.model).embed(req.texts)]
    return EmbedResponse(model=req.model, dim=len(vectors[0]), embeddings=vectors)


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    if req.model not in RERANK_MODELS:
        raise HTTPException(status_code=422, detail=f"unknown model {req.model}; known: {list(RERANK_MODELS)}")
    _check_texts(req.documents + [req.query])
    scores = [float(s) for s in backends.reranker(req.model).rerank(req.query, req.documents)]
    return RerankResponse(model=req.model, scores=scores)


@app.post("/nli", response_model=NliResponse)
def nli(req: NliRequest):
    if req.model != NLI_MODEL:
        raise HTTPException(status_code=422, detail=f"unknown model {req.model}; known: {[NLI_MODEL]}")
    _check_texts([t for p in req.pairs for t in (p.premise, p.hypothesis)])
    scores = backends.nli().score([(p.premise, p.hypothesis) for p in req.pairs])
    return NliResponse(model=req.model, results=[NliScore(**s) for s in scores])


def _normalize(v: list[float]) -> list[float]:
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


class _FakeNli:
    """Deterministic stand-in: entailment is the share of the hypothesis's words found in the premise; the word 'contradicts' forces a contradiction."""

    def score(self, pairs):
        out = []
        for premise, hypothesis in pairs:
            hyp = _TOKEN.findall(hypothesis.lower())
            if "contradicts" in hyp:
                out.append({"entailment": 0.03, "neutral": 0.07, "contradiction": 0.90})
                continue
            prem = set(_TOKEN.findall(premise.lower()))
            share = (sum(1 for w in hyp if w in prem) / len(hyp)) if hyp else 0.0
            ent = 0.98 * share
            rest = 1.0 - ent
            out.append({"entailment": ent, "neutral": rest * 0.9, "contradiction": rest * 0.1})
        return out
