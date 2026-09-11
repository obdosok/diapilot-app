"""Meal detection from the glucose curve.

A meal shows up as a sustained BG rise from a local trough to a peak. Each rise
is paired with a nearby bolus (announced meal) or flagged as unannounced. A
bolus during a fall is a correction, not a meal.

Same curve math as the twin; complementary to the clean-correction detector of
stage 0 -- what this flags as an announced meal is exactly the large isolated
bolus that contaminated the ISF estimate.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd


@dataclass
class MealConfig:
    rise_threshold: float = 2.0     # mmol/L, min net rise to count as a meal
    rise_window_min: float = 90.0   # max minutes trough->peak
    min_trough_gap_min: float = 20.0  # ignore micro-wiggles shorter than this
    pair_before_min: float = 45.0   # bolus may precede onset (pre-bolus)
    pair_after_min: float = 20.0    # or follow onset shortly


@dataclass
class MealEvent:
    onset_ms: int
    peak_ms: int
    pre_bg: float
    peak_bg: float
    rise: float
    time_to_peak_min: float
    bolus_units: float | None
    kind: str  # "announced" | "unannounced"


def detect_meals(readings: pd.DataFrame, boluses: pd.DataFrame,
                 cfg: MealConfig | None = None) -> list[MealEvent]:
    """readings: columns ts (tz-aware) and bg (mmol/L). boluses: ts, insulin."""
    cfg = cfg or MealConfig()
    if readings.empty:
        return []
    r = readings.sort_values("ts").reset_index(drop=True)
    ts_ms = (r["ts"].astype("datetime64[ns, UTC]").astype("int64") // 1_000_000).to_numpy()
    bg = r["bg"].to_numpy()
    b_ms = ((boluses["ts"].astype("datetime64[ns, UTC]").astype("int64") // 1_000_000).to_numpy()
            if not boluses.empty else np.array([], dtype="int64"))
    b_u = boluses["insulin"].to_numpy() if not boluses.empty else np.array([])

    events: list[MealEvent] = []
    n = len(bg)
    i = 0
    while i < n - 1:
        # Find a local trough: bg[i] is a rise onset if the following points climb.
        if i > 0 and bg[i] > bg[i - 1]:
            i += 1
            continue
        # Walk up while BG keeps rising (allow tiny dips), within the rise window.
        j = i
        peak = i
        while j + 1 < n and (ts_ms[j + 1] - ts_ms[i]) <= cfg.rise_window_min * 60_000:
            if bg[j + 1] >= bg[peak] - 0.2:  # tolerate small noise
                if bg[j + 1] > bg[peak]:
                    peak = j + 1
                j += 1
            else:
                break
        rise = bg[peak] - bg[i]
        dur_min = (ts_ms[peak] - ts_ms[i]) / 60_000
        if rise >= cfg.rise_threshold and dur_min >= cfg.min_trough_gap_min:
            # Move onset to where the climb actually starts: the last point still
            # near the trough minimum before BG lifts off (handles flat plateaus).
            trough_min = bg[i:peak + 1].min()
            onset_idx = i
            for k in range(i, peak):
                if bg[k] <= trough_min + 0.3:
                    onset_idx = k
                else:
                    break
            onset_ms = int(ts_ms[onset_idx])
            rise = float(bg[peak] - bg[onset_idx])
            dur_min = (ts_ms[peak] - ts_ms[onset_idx]) / 60_000
            # Pair with nearest bolus in the window around onset.
            units = None
            if len(b_ms):
                lo = onset_ms - cfg.pair_before_min * 60_000
                hi = onset_ms + cfg.pair_after_min * 60_000
                mask = (b_ms >= lo) & (b_ms <= hi)
                if mask.any():
                    units = float(b_u[mask].sum())
            events.append(MealEvent(
                onset_ms=onset_ms, peak_ms=int(ts_ms[peak]),
                pre_bg=float(bg[i]), peak_bg=float(bg[peak]), rise=float(rise),
                time_to_peak_min=float(dur_min),
                bolus_units=units, kind="announced" if units else "unannounced",
            ))
            i = peak + 1  # continue after the peak (dedup overlapping rises)
        else:
            i += 1
    return events
