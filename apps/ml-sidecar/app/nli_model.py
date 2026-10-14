"""Natural-language-inference model for claim verification (SPEC 10): does a passage entail a statement?

A small cross-encoder (cross-encoder/nli-deberta-v3-xsmall, about 70M parameters) run with onnxruntime. fastembed already depends
on onnxruntime, tokenizers and huggingface_hub, so this adds no new package. The file is standalone (it imports nothing from the app)
so the Dockerfile can bake the weights into the image before the application code is copied.
"""
import json
import os
import platform

REPO = "cross-encoder/nli-deberta-v3-xsmall"
MODEL_ID = "nli-deberta-v3-xsmall"
MAX_TOKENS = 512


def onnx_file() -> str:
    """The quantised graph that matches this CPU (smaller and faster); the full-precision graph runs everywhere."""
    machine = platform.machine().lower()
    if machine in ("aarch64", "arm64"):
        return "onnx/model_qint8_arm64.onnx"
    return "onnx/model_quint8_avx2.onnx"


def fetch(cache_dir: str | None = None) -> dict:
    """Downloads (or finds in the cache) the weights, tokenizer and label map; returns their local paths."""
    from huggingface_hub import hf_hub_download

    cache = cache_dir or os.environ.get("FASTEMBED_CACHE_PATH")
    paths = {name: hf_hub_download(REPO, name, cache_dir=cache) for name in ("config.json", "tokenizer.json")}
    try:
        paths["model"] = hf_hub_download(REPO, onnx_file(), cache_dir=cache)
    except Exception:  # no quantised graph for this architecture: use the portable one
        paths["model"] = hf_hub_download(REPO, "onnx/model.onnx", cache_dir=cache)
    return paths


class NliModel:
    """Scores (premise, hypothesis) pairs with entailment, neutral and contradiction probabilities."""

    def __init__(self):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer

        paths = fetch()
        self._np = np
        self._tokenizer = Tokenizer.from_file(paths["tokenizer.json"])
        self._tokenizer.enable_truncation(max_length=MAX_TOKENS)
        self._tokenizer.enable_padding()
        options = ort.SessionOptions()
        options.intra_op_num_threads = max(1, (os.cpu_count() or 2) // 2)
        self._session = ort.InferenceSession(paths["model"], sess_options=options, providers=["CPUExecutionProvider"])
        self._inputs = {i.name for i in self._session.get_inputs()}
        with open(paths["config.json"], encoding="utf-8") as f:
            id2label = json.load(f)["id2label"]
        self._labels = [id2label[str(i)].lower() for i in range(len(id2label))]
        for needed in ("entailment", "neutral", "contradiction"):
            if needed not in self._labels:
                raise RuntimeError(f"the NLI model has no '{needed}' output: {self._labels}")

    def score(self, pairs: list[tuple[str, str]]) -> list[dict]:
        np = self._np
        encodings = self._tokenizer.encode_batch(pairs)
        feeds = {
            "input_ids": np.array([e.ids for e in encodings], dtype=np.int64),
            "attention_mask": np.array([e.attention_mask for e in encodings], dtype=np.int64),
        }
        if "token_type_ids" in self._inputs:
            feeds["token_type_ids"] = np.array([e.type_ids for e in encodings], dtype=np.int64)
        logits = self._session.run(None, {k: v for k, v in feeds.items() if k in self._inputs})[0]
        shifted = logits - logits.max(axis=1, keepdims=True)
        probs = np.exp(shifted) / np.exp(shifted).sum(axis=1, keepdims=True)
        return [{label: float(row[i]) for i, label in enumerate(self._labels)} for row in probs]


if __name__ == "__main__":  # run at image build time to bake the weights in
    print(fetch())
