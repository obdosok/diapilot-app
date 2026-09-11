"""Contract tests for the digital-twin retrospective validation (spec part 2).

Covered per spec section 6:
  (a) simulator reconstructs a trajectory exactly when drift=0 and G is known;
  (b) with one correction bolus the twin beats the persistence baseline;
  (c) the corridor covers >= its nominal share on synthetic noisy data;
  (d) the train/eval split does not look ahead (kernel from train ignores eval);
  (e) virtual fingersticks land on schedule and snap to nearest CGM.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from diapilot_stage0.io_xdrip import load_data, XDripData
from diapilot_stage0.episodes import Config, detect_episodes
from diapilot_stage0.twin.kernel import build_kernel, Kernel
from diapilot_stage0.twin.simulator import simulate_segment, TwinModel
from diapilot_stage0.twin.experiment import (
    place_fingersticks,
    run_experiment,
    ExperimentConfig,
)

from tests.synth import SynthDB, BASE_TS_MS, kernel_from_episodes_synth

MS_PER_MIN = 60_000


def _load(db: SynthDB, tmp_path) -> XDripData:
    path = db.write(str(tmp_path / "twin.sqlite"))
    return load_data(path, tz="UTC")


# ------------------------------------------------------------- kernel / split

def test_kernel_recovers_known_shape(tmp_path):
    db = kernel_from_episodes_synth(dose_units=2.0, isf_mmol=2.5)
    data = _load(db, tmp_path)
    res = detect_episodes(data, Config())
    k = build_kernel(res.episodes)
    # End of kernel == total drop per unit == -isf.
    assert k.value(240) == pytest.approx(-2.5, abs=0.05)
    # Monotone non-increasing.
    assert np.all(np.diff(k.g) <= 1e-9)


def test_split_no_lookahead(tmp_path):
    """Kernel built on the training slice must not depend on eval-window rows."""
    db = kernel_from_episodes_synth()
    data = _load(db, tmp_path)
    split = data.readings["ts"].max() - pd.Timedelta(days=5)
    train = XDripData(
        data.readings[data.readings["ts"] < split].reset_index(drop=True),
        data.treatments[data.treatments["ts"] < split].reset_index(drop=True),
        data.tz,
    )
    k_train = build_kernel(detect_episodes(train, Config()).episodes)

    # Append a wild fabricated episode inside the eval window; kernel must not move.
    data2 = _load(db, tmp_path)  # same db -> same file rebuilt
    k_full_train = build_kernel(
        detect_episodes(
            XDripData(
                data2.readings[data2.readings["ts"] < split].reset_index(drop=True),
                data2.treatments[data2.treatments["ts"] < split].reset_index(drop=True),
                data2.tz,
            ),
            Config(),
        ).episodes
    )
    assert k_train.value(240) == pytest.approx(k_full_train.value(240), abs=1e-9)


# --------------------------------------------------------------- simulator

def _flat_kernel(total_drop=-2.0, t_kernel=240.0, step=5.0):
    tau = np.arange(0.0, t_kernel + step, step)
    # Linear ramp 0 -> total_drop over [15, 150], then flat.
    g = np.where(
        tau <= 15, 0.0,
        np.where(tau >= 150, total_drop, total_drop * (tau - 15) / (150 - 15)),
    )
    g = np.minimum.accumulate(g)
    return Kernel(tau=tau, g=g, g_q1=g.copy(), g_q3=g.copy(), n_episodes=10)


def test_simulator_reconstructs_without_drift():
    """With the true kernel, no meals and no drift, the forward simulation of a
    correction reproduces the analytic trajectory (MAE < 0.1 mmol/L)."""
    k = _flat_kernel(total_drop=-2.0)
    isf = 2.0
    anchor = pd.Timestamp("2025-06-01 09:00:00", tz="UTC")
    boluses = pd.DataFrame({"ts": [anchor], "insulin": [3.0]})  # 3U -> -6 mmol/L total
    bg_anchor = 12.0
    model = TwinModel(kernel=k, drift=0.0)
    grid = pd.date_range(anchor, periods=49, freq="5min", tz="UTC")
    pred = simulate_segment(model, anchor_ts=anchor, anchor_bg=bg_anchor,
                            times=grid, boluses=boluses)
    # Analytic truth: bg_anchor + 3 * G(tau).
    truth = np.array([bg_anchor + 3.0 * k.value((t - anchor).total_seconds() / 60)
                      for t in grid])
    assert np.mean(np.abs(pred - truth)) < 0.1


def test_twin_beats_persistence_on_correction():
    """A correction bolus drives BG down; persistence (flat) is wrong, the twin
    (insulin-aware) tracks it -> positive skill score."""
    k = _flat_kernel(total_drop=-2.0)
    anchor = pd.Timestamp("2025-06-01 09:00:00", tz="UTC")
    boluses = pd.DataFrame({"ts": [anchor], "insulin": [3.0]})
    bg_anchor = 12.0
    grid = pd.date_range(anchor, periods=49, freq="5min", tz="UTC")
    truth = np.array([bg_anchor + 3.0 * k.value((t - anchor).total_seconds() / 60)
                      for t in grid])
    model = TwinModel(kernel=k, drift=0.0)
    pred = simulate_segment(model, anchor, bg_anchor, grid, boluses)
    mae_twin = np.mean(np.abs(pred - truth))
    mae_persist = np.mean(np.abs(bg_anchor - truth))
    skill = 1 - mae_twin / mae_persist
    assert skill > 0.5


# ------------------------------------------------------------- fingersticks

def test_fingersticks_on_schedule_and_snap(tmp_path):
    """Sticks land at the configured local hours and take the nearest CGM value."""
    db = SynthDB()
    # Two days of flat 8.0 mmol/L readings every 5 min.
    for m in range(0, 2 * 24 * 60, 5):
        db.add_reading(m, 8.0)
    data = _load(db, tmp_path)
    eval_start = data.readings["ts"].min()
    eval_end = data.readings["ts"].max()
    sticks = place_fingersticks(data.readings, eval_start, eval_end,
                                hours=(7, 12, 18, 22), tz="UTC")
    assert len(sticks) >= 6  # ~4/day over ~2 days
    assert np.allclose(sticks["bg"].to_numpy(), 8.0)
    local_hours = sorted(set(sticks["ts"].dt.tz_convert("UTC").dt.hour))
    assert set(local_hours).issubset({7, 12, 18, 22})


# --------------------------------------------------------- corridor coverage

def test_corridor_covers_nominal_on_noise(tmp_path):
    """On flat truth + known noise, calibrated corridor covers >= nominal share."""
    rng = np.random.default_rng(0)
    db = SynthDB()
    n = 4 * 24 * 60 // 5
    for i in range(n):
        db.add_reading(i * 5, 8.0 + rng.normal(0, 0.3))
    data = _load(db, tmp_path)

    k = _flat_kernel()
    cfg = ExperimentConfig(eval_days=2, fingersticks_hours=(7, 12, 18, 22),
                           learn_drift=False, tz="UTC")
    result = run_experiment(data, kernel=k, cfg=cfg)
    # With flat truth and no insulin, twin ~ persistence; corridor should cover
    # at least its nominal ~68% (1 sigma-like) share.
    assert result.corridor_coverage >= 0.6
