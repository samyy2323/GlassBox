"""Reference values for the Java statistics package, computed with SciPy, NumPy and scikit-learn.

    python benchmarks/stats_fixtures.py        # rewrites apps/api/src/test/resources/stats-fixtures.json

The Java tests (StatsFixtureTest) load that file and check the Java results against it:
  - McNemar exact, sign-flip permutation, percentiles, Cohen's kappa, precision and recall, power: SciPy / scikit-learn results,
    compared to 1e-9 (the permutation fixtures use n <= 16 so SciPy enumerates every flip, like Java).
  - Percentile bootstrap: scipy.stats.bootstrap; the intervals can only agree within Monte Carlo error, so the Java test uses a tolerance.
  - Cluster bootstrap and Holm: SciPy has neither, so these come from short NumPy reference implementations of the textbook definitions.
Inputs are generated with fixed seeds, so rerunning the script gives the same file.
"""
import json
import pathlib

import numpy as np
import scipy
import sklearn
from scipy import stats
from sklearn.metrics import cohen_kappa_score, precision_recall_fscore_support

OUT = pathlib.Path(__file__).resolve().parent.parent / "apps/api/src/test/resources/stats-fixtures.json"
rng = np.random.default_rng(20261014)


def mcnemar_cases():
    cases = []
    for n, pa, pb in [(40, 0.8, 0.6), (100, 0.7, 0.75), (25, 0.5, 0.5), (60, 0.9, 0.6), (12, 0.5, 0.9)]:
        a = (rng.random(n) < pa).astype(int)
        b = (rng.random(n) < pb).astype(int)
        bb = int(((a == 1) & (b == 0)).sum())
        cc = int(((a == 0) & (b == 1)).sum())
        p = 1.0 if bb + cc == 0 else float(stats.binomtest(min(bb, cc), bb + cc, 0.5).pvalue)
        cases.append({"a": a.tolist(), "b": b.tolist(), "b01": bb, "c01": cc, "p": p})
    for bb, cc in [(0, 0), (0, 10), (7, 7), (3, 12), (1, 0)]:
        p = 1.0 if bb + cc == 0 else float(stats.binomtest(min(bb, cc), bb + cc, 0.5).pvalue)
        cases.append({"a": None, "b": None, "b01": bb, "c01": cc, "p": p})
    return cases


def permutation_cases():
    cases = []
    for n in [5, 8, 11, 14, 16]:
        d = np.round(rng.normal(0.08, 0.2, n), 2)
        d[rng.integers(0, n)] = 0.0  # a tie at zero
        res = stats.permutation_test((d, np.zeros(n)), lambda x, y, axis: np.mean(x - y, axis=axis),
                                     permutation_type="samples", n_resamples=np.inf, alternative="two-sided")
        cases.append({"diffs": d.tolist(), "p": float(res.pvalue)})
    d = np.array([1.0, 1.0, 1.0, 1.0, 1.0, 1.0])  # every question improved: the smallest possible p is 2 / 2^n
    res = stats.permutation_test((d, np.zeros(6)), lambda x, y, axis: np.mean(x - y, axis=axis),
                                 permutation_type="samples", n_resamples=np.inf, alternative="two-sided")
    cases.append({"diffs": d.tolist(), "p": float(res.pvalue)})
    return cases


def bootstrap_cases():
    cases = []
    inputs = [
        (rng.random(100) < 0.8).astype(float),        # pass/fail, 100 questions
        np.round(rng.beta(5, 2, 50), 3),              # continuous scores
        np.round(rng.random(12), 3),                  # a small test set
    ]
    for i, x in enumerate(inputs):
        res = stats.bootstrap((x,), np.mean, n_resamples=10_000, confidence_level=0.95, method="percentile",
                              random_state=np.random.default_rng(100 + i))
        cases.append({"x": x.tolist(), "mean": float(x.mean()), "low": float(res.confidence_interval.low),
                      "high": float(res.confidence_interval.high)})
    return cases


def cluster_bootstrap_cases():
    cases = []
    for n, k in [(60, 6), (90, 9)]:
        groups = np.sort(rng.integers(0, k, n))
        effect = rng.normal(0, 0.15, k)  # questions of one document resemble each other
        x = np.clip(0.7 + effect[groups] + rng.normal(0, 0.1, n), 0, 1)
        ids = np.unique(groups)
        sums = np.array([x[groups == g].sum() for g in ids])
        counts = np.array([(groups == g).sum() for g in ids])
        r = np.random.default_rng(7)
        means = np.empty(10_000)
        for b in range(10_000):
            pick = r.integers(0, len(ids), len(ids))
            means[b] = sums[pick].sum() / counts[pick].sum()
        cases.append({"x": np.round(x, 4).tolist(), "groups": groups.tolist(), "mean": float(np.round(x, 4).mean()),
                      "low": float(np.percentile(means, 2.5)), "high": float(np.percentile(means, 97.5))})
        # recompute the reference from the rounded values so Java sees exactly the same inputs
        x = np.round(x, 4)
        sums = np.array([x[groups == g].sum() for g in ids])
        r = np.random.default_rng(7)
        for b in range(10_000):
            pick = r.integers(0, len(ids), len(ids))
            means[b] = sums[pick].sum() / counts[pick].sum()
        cases[-1].update(mean=float(x.mean()), low=float(np.percentile(means, 2.5)), high=float(np.percentile(means, 97.5)))
    return cases


def holm_cases():
    cases = []
    for p in [[0.01, 0.04, 0.03, 0.005], [0.2, 0.5, 0.9], [0.0001, 0.02, 0.5, 0.03, 0.04, 0.8]]:
        p = np.array(p)
        m = len(p)
        order = np.argsort(p)
        adj = np.empty(m)
        running = 0.0
        for rank, idx in enumerate(order):
            running = max(running, min(1.0, (m - rank) * p[idx]))
            adj[idx] = running
        cases.append({"p": p.tolist(), "adjusted": adj.tolist()})
    return cases


def kappa_cases():
    cases = []
    sets = [
        (["SUPPORTED", "PARTIAL", "UNSUPPORTED"], 60, 0.75),
        (["SUPPORTED", "UNSUPPORTED"], 40, 0.9),
        (["correct", "partial", "wrong"], 30, 0.5),
    ]
    for labels, n, agree in sets:
        human = rng.choice(labels, n)
        judge = np.where(rng.random(n) < agree, human, rng.choice(labels, n))
        classes = sorted(set(human) | set(judge))
        p, r, _, _ = precision_recall_fscore_support(human, judge, labels=classes, zero_division=0)
        cases.append({
            "human": human.tolist(), "judge": judge.tolist(), "labels": classes,
            "agreement": float((human == judge).mean()), "kappa": float(cohen_kappa_score(human, judge)),
            "precision": dict(zip(classes, map(float, p))), "recall": dict(zip(classes, map(float, r))),
        })
    return cases


def percentile_cases():
    cases = []
    for n in [1, 2, 7, 50]:
        x = np.round(rng.random(n) * 1000, 1)
        cases.append({"x": x.tolist(), "p50": float(np.percentile(x, 50)), "p95": float(np.percentile(x, 95)), "p2_5": float(np.percentile(x, 2.5))})
    return cases


def power_cases():
    z = stats.norm.ppf(0.975) + stats.norm.ppf(0.8)
    cases = []
    for n, p in [(100, 0.8), (43, 0.7), (200, 0.5)]:
        cases.append({"n": n, "rate": p, "halfWidth": float(stats.norm.ppf(0.975) * np.sqrt(p * (1 - p) / n)),
                      "minDetectable": float(z * np.sqrt(2 * p * (1 - p) / n))})
    d = np.round(rng.normal(0.05, 0.3, 40), 3)
    return {"cases": cases, "diffs": d.tolist(), "minDetectableFromDiffs": float(z * d.std(ddof=1) / np.sqrt(len(d)))}


def main():
    data = {
        "generatedWith": {"scipy": scipy.__version__, "numpy": np.__version__, "sklearn": sklearn.__version__},
        "mcnemar": mcnemar_cases(), "permutation": permutation_cases(), "bootstrap": bootstrap_cases(),
        "clusterBootstrap": cluster_bootstrap_cases(), "holm": holm_cases(), "kappa": kappa_cases(),
        "percentile": percentile_cases(), "power": power_cases(),
    }
    OUT.write_text(json.dumps(data, indent=1))
    print(f"wrote {OUT} (scipy {scipy.__version__}, numpy {np.__version__}, scikit-learn {sklearn.__version__})")


if __name__ == "__main__":
    main()
