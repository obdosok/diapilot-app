"""Aggregation of per-episode ISF values and confidence levels.

Anomalous episodes (ISF <= 0) are reported but excluded from the median/IQR
and from the confidence-level count: a single unlogged meal would otherwise
drag the aggregate down.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .episodes import Episode, TOD_BUCKETS

CONF_NONE = "insufficient"       # < 5 valid episodes
CONF_PRELIMINARY = "preliminary"  # 5-14
CONF_STABLE = "stable"            # >= 15

CONF_LABELS_RU = {
    CONF_NONE: "данных недостаточно",
    CONF_PRELIMINARY: "предварительная оценка",
    CONF_STABLE: "устойчивая оценка",
}


@dataclass
class Aggregate:
    n_valid: int
    n_anomalies: int
    confidence: str
    median: float | None
    q1: float | None
    q3: float | None

    @property
    def iqr(self) -> float | None:
        if self.q1 is None or self.q3 is None:
            return None
        return self.q3 - self.q1


def _confidence(n: int) -> str:
    if n < 5:
        return CONF_NONE
    if n < 15:
        return CONF_PRELIMINARY
    return CONF_STABLE


def aggregate(episodes: list[Episode]) -> Aggregate:
    valid = [e.isf for e in episodes if not e.anomaly]
    n_anom = sum(1 for e in episodes if e.anomaly)
    conf = _confidence(len(valid))
    if conf == CONF_NONE:
        return Aggregate(len(valid), n_anom, conf, None, None, None)
    arr = np.array(valid)
    return Aggregate(
        n_valid=len(valid),
        n_anomalies=n_anom,
        confidence=conf,
        median=float(np.median(arr)),
        q1=float(np.percentile(arr, 25)),
        q3=float(np.percentile(arr, 75)),
    )


def aggregate_by_tod(episodes: list[Episode]) -> dict[str, Aggregate]:
    """Per time-of-day aggregates, each with its own confidence level."""
    out: dict[str, Aggregate] = {}
    for name, _, _ in TOD_BUCKETS:
        out[name] = aggregate([e for e in episodes if e.tod_bucket == name])
    return out
