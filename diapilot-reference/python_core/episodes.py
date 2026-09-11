"""Detection of clean correction episodes.

A candidate is any bolus. Filters are applied in a fixed order; a candidate
is counted under the FIRST reason that rejects it, so the rejection table in
the report sums up with found episodes to the total number of candidates.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from .io_xdrip import XDripData

MIN = pd.Timedelta(minutes=1)

TOD_BUCKETS = (
    ("night", 0, 6),
    ("morning", 6, 12),
    ("day", 12, 18),
    ("evening", 18, 24),
)

# Rejection reasons, in the order filters are applied.
REASONS = (
    "dose_too_small",
    "dose_too_large",
    "other_bolus_in_window",
    "carbs_in_window",
    "no_pre_bolus_readings",
    "start_bg_too_low",
    "insufficient_cgm_coverage",
    "hypo_in_window",
    "no_end_window_readings",
)


@dataclass
class Config:
    """Detection thresholds; defaults per spec section 3."""

    min_dose: float = 0.5            # U
    max_dose: float | None = None    # U; boluses above are excluded (likely meals)
    start_bg: float = 8.0            # mmol/L
    hypo_threshold: float = 3.9      # mmol/L
    carb_window: tuple[float, float] = (-120.0, 240.0)   # minutes around t0
    bolus_window: tuple[float, float] = (-180.0, 240.0)  # minutes around t0
    pre_window_min: float = 15.0     # [t0-15min, t0]
    end_window: tuple[float, float] = (180.0, 240.0)     # [t0+3h, t0+4h]
    obs_window_min: float = 240.0    # [t0, t0+4h]
    expected_step_min: float = 5.0   # expected CGM cadence
    min_coverage: float = 0.8        # share of expected points in [t0, t0+4h]
    tz: str = "UTC"                 # IANA zone of the user's local time; pass it explicitly


@dataclass
class Episode:
    t0: pd.Timestamp            # UTC
    t0_local: pd.Timestamp
    dose: float
    bg_start: float             # mmol/L, median of [t0-15min, t0]
    bg_end: float               # mmol/L, median of [t0+3h, t0+4h]
    isf: float                  # mmol/L per U
    anomaly: bool               # isf <= 0
    tod_bucket: str
    curve: pd.DataFrame = field(repr=False, default=None)  # minutes, delta_bg


@dataclass
class DetectionResult:
    episodes: list[Episode]
    rejections: dict[str, int]
    n_candidates: int


def _tod_bucket(local_ts: pd.Timestamp) -> str:
    h = local_ts.hour
    for name, lo, hi in TOD_BUCKETS:
        if lo <= h < hi:
            return name
    return "night"


def detect_episodes(data: XDripData, cfg: Config) -> DetectionResult:
    readings = data.readings
    treatments = data.treatments
    boluses = treatments[treatments["insulin"] > 0].reset_index(drop=True)
    carbs = treatments[treatments["carbs"] > 0]

    # int64 nanoseconds since epoch: unambiguous and fast for searchsorted.
    # Note: cast to ns explicitly — pandas may keep ms resolution from unit="ms".
    bg_ts_ns = readings["ts"].dt.tz_convert("UTC").astype("datetime64[ns, UTC]").astype("int64").to_numpy()
    bg_val = readings["bg"].to_numpy()

    episodes: list[Episode] = []
    rejections: dict[str, int] = {}

    def reject(reason: str) -> None:
        rejections[reason] = rejections.get(reason, 0) + 1

    def bg_slice(lo: pd.Timestamp, hi: pd.Timestamp):
        i = np.searchsorted(bg_ts_ns, lo.value, side="left")
        j = np.searchsorted(bg_ts_ns, hi.value, side="right")
        return bg_val[i:j], bg_ts_ns[i:j], i, j

    for _, row in boluses.iterrows():
        t0: pd.Timestamp = row["ts"]
        dose = float(row["insulin"])

        if dose < cfg.min_dose:
            reject("dose_too_small")
            continue

        if cfg.max_dose is not None and dose > cfg.max_dose:
            reject("dose_too_large")
            continue

        # Other boluses in [t0-3h, t0+4h] (excluding this one).
        lo = t0 + cfg.bolus_window[0] * MIN
        hi = t0 + cfg.bolus_window[1] * MIN
        others = boluses[(boluses["ts"] >= lo) & (boluses["ts"] <= hi) & (boluses["ts"] != t0)]
        if len(others) > 0:
            reject("other_bolus_in_window")
            continue

        # Carbs in [t0-2h, t0+4h].
        lo = t0 + cfg.carb_window[0] * MIN
        hi = t0 + cfg.carb_window[1] * MIN
        if len(carbs[(carbs["ts"] >= lo) & (carbs["ts"] <= hi)]) > 0:
            reject("carbs_in_window")
            continue

        # Pre-bolus readings: at least one point in [t0-15min, t0].
        pre_vals = bg_slice(t0 - cfg.pre_window_min * MIN, t0)[0]
        if len(pre_vals) == 0:
            reject("no_pre_bolus_readings")
            continue
        bg_start = float(np.median(pre_vals))

        if bg_start < cfg.start_bg:
            reject("start_bg_too_low")
            continue

        # CGM coverage in [t0, t0+4h].
        obs_vals, obs_ts, _, _ = bg_slice(t0, t0 + cfg.obs_window_min * MIN)
        expected_points = cfg.obs_window_min / cfg.expected_step_min
        if len(obs_vals) < cfg.min_coverage * expected_points:
            reject("insufficient_cgm_coverage")
            continue

        # Hypo in [t0, t0+4h].
        if obs_vals.min() < cfg.hypo_threshold:
            reject("hypo_in_window")
            continue

        # End window [t0+3h, t0+4h].
        end_vals = bg_slice(t0 + cfg.end_window[0] * MIN, t0 + cfg.end_window[1] * MIN)[0]
        if len(end_vals) == 0:
            reject("no_end_window_readings")
            continue
        bg_end = float(np.median(end_vals))

        isf = (bg_start - bg_end) / dose
        minutes = (obs_ts - t0.value) / 60e9  # ns -> minutes
        curve = pd.DataFrame({"minutes": minutes, "delta_bg": obs_vals - bg_start})

        episodes.append(
            Episode(
                t0=t0,
                t0_local=row["ts_local"],
                dose=dose,
                bg_start=bg_start,
                bg_end=bg_end,
                isf=isf,
                anomaly=isf <= 0,
                tod_bucket=_tod_bucket(row["ts_local"]),
                curve=curve,
            )
        )

    return DetectionResult(
        episodes=episodes, rejections=rejections, n_candidates=len(boluses)
    )
