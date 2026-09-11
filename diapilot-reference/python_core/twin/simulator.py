"""Forward glucose simulation from an anchor (fingerstick) using insulin only.

BG_model(T) = B_a + sum_i D_i * [G(T - t_i) - G(T_a - t_i)] + drift * (T - T_a)

The kernel G already fuses ISF and the personal activity shape. `drift` folds
in everything the twin cannot see (basal, dawn, endogenous, unlogged meals);
it defaults to 0 -- the honest "insulin-only" twin.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from .kernel import Kernel

LOOKBACK = pd.Timedelta(hours=6)


@dataclass
class TwinModel:
    kernel: Kernel
    drift: float = 0.0  # mmol/L per minute


def simulate_segment(
    model: TwinModel,
    anchor_ts: pd.Timestamp,
    anchor_bg: float,
    times: pd.DatetimeIndex,
    boluses: pd.DataFrame,
) -> np.ndarray:
    """Predict BG at each timestamp in `times`, starting from (anchor_ts, anchor_bg).

    `boluses` needs columns ts (tz-aware) and insulin; only those in
    [anchor_ts - LOOKBACK, times[-1]] with insulin>0 contribute.
    """
    k = model.kernel
    lo = anchor_ts - LOOKBACK
    hi = times[-1]
    rel = boluses[(boluses["ts"] >= lo) & (boluses["ts"] <= hi) & (boluses["insulin"] > 0)]
    b_ns = rel["ts"].astype("datetime64[ns, UTC]").astype("int64").to_numpy()
    b_dose = rel["insulin"].to_numpy(dtype=float)

    anchor_ns = anchor_ts.value
    out = np.empty(len(times))
    for idx, t in enumerate(times):
        t_ns = t.value
        insulin_delta = 0.0
        for j in range(len(b_dose)):
            tj_ns = b_ns[j]
            tau_now = (t_ns - tj_ns) / 6e10       # ns -> minutes
            tau_anch = (anchor_ns - tj_ns) / 6e10
            insulin_delta += b_dose[j] * (k.value(tau_now) - k.value(tau_anch))
        drift = model.drift * (t_ns - anchor_ns) / 6e10
        out[idx] = anchor_bg + insulin_delta + drift
    return out


def learn_drift(
    kernel: Kernel,
    readings: pd.DataFrame,
    boluses: pd.DataFrame,
    step_min: float = 5.0,
    max_gap_min: float = 30.0,
) -> float:
    """Estimate a constant drift on the training window: median of the residual
    (observed BG change - insulin-predicted change) per minute over short
    consecutive stretches. Captures average basal/dawn mismatch, not meals."""
    model = TwinModel(kernel=kernel, drift=0.0)
    ts = readings["ts"].to_numpy()
    bg = readings["bg"].to_numpy()
    residual_rates = []
    for i in range(len(ts) - 1):
        dt_min = (ts[i + 1] - ts[i]) / np.timedelta64(1, "m")
        if dt_min <= 0 or dt_min > max_gap_min:
            continue
        anchor = pd.Timestamp(ts[i]).tz_localize("UTC") if pd.Timestamp(ts[i]).tz is None else pd.Timestamp(ts[i])
        nxt = pd.DatetimeIndex([pd.Timestamp(ts[i + 1])])
        pred = simulate_segment(model, anchor, float(bg[i]), nxt, boluses)[0]
        obs_change = bg[i + 1] - bg[i]
        pred_change = pred - bg[i]
        residual_rates.append((obs_change - pred_change) / dt_min)
    if not residual_rates:
        return 0.0
    return float(np.median(residual_rates))
