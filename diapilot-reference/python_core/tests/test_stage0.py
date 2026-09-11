"""Contract tests for DiaPilot Stage 0 (written before implementation).

Covered per spec section 5:
  * a clean correction episode is detected;
  * an episode with carbs inside [t0-2h, t0+4h] is rejected;
  * an episode with hypo (<3.9 mmol/L) inside [t0, t0+4h] is rejected;
  * overlapping boluses inside [t0-3h, t0+4h] are rejected;
  * per-episode ISF matches a known synthetic answer within ±5%;
  * confidence levels switch with the number of valid episodes;
  * extra: low CGM coverage rejected; low start BG rejected; small dose rejected;
    ISF<=0 flagged as anomaly (not silently dropped);
    time-of-day bucketing uses local time.
"""

from __future__ import annotations

import pytest

from diapilot_stage0.io_xdrip import load_data
from diapilot_stage0.episodes import Config, detect_episodes
from diapilot_stage0.analysis import aggregate, CONF_NONE, CONF_PRELIMINARY, CONF_STABLE

from tests.synth import SynthDB

DAY_MIN = 24 * 60


@pytest.fixture
def cfg() -> Config:
    return Config()  # spec defaults


def build(db: SynthDB, tmp_path, cfg: Config):
    path = db.write(str(tmp_path / "synth.sqlite"))
    data = load_data(path, tz=cfg.tz)
    return detect_episodes(data, cfg)


# ---------------------------------------------------------------- detection

def test_clean_episode_found(tmp_path, cfg):
    db = SynthDB()
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=2.5)
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 1
    ep = result.episodes[0]
    assert ep.dose == pytest.approx(2.0)
    assert not ep.anomaly


def test_carbs_in_window_rejected(tmp_path, cfg):
    db = SynthDB()
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=2.5)
    db.add_carbs(minute=60, grams=30)  # inside [t0-2h, t0+4h]
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 0
    assert result.rejections.get("carbs_in_window", 0) == 1


def test_carbs_before_window_ok(tmp_path, cfg):
    db = SynthDB()
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=2.5)
    db.add_carbs(minute=-121, grams=30)  # just outside [t0-2h, ...]
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 1


def test_hypo_in_window_rejected(tmp_path, cfg):
    db = SynthDB()
    # Big dose drives BG from 12 down to 2 -> hypo inside the window.
    db.add_clean_episode(t0_minute=0, dose=4.0, isf_mmol=2.5)
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 0
    assert result.rejections.get("hypo_in_window", 0) == 1


def test_overlapping_boluses_rejected(tmp_path, cfg):
    db = SynthDB()
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=2.5)
    db.add_bolus(minute=90, units=1.0)  # inside [t0-3h, t0+4h]
    result = build(db, tmp_path, cfg)
    # Both candidates fail: each sees the other inside its window.
    assert len(result.episodes) == 0
    assert result.rejections.get("other_bolus_in_window", 0) >= 1


def test_low_start_bg_rejected(tmp_path, cfg):
    db = SynthDB()
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=1.0, bg_start=7.0)  # < 8.0
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 0
    assert result.rejections.get("start_bg_too_low", 0) == 1


def test_small_dose_rejected(tmp_path, cfg):
    db = SynthDB()
    db.add_clean_episode(t0_minute=0, dose=0.4, isf_mmol=2.5)  # < 0.5 U
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 0
    assert result.rejections.get("dose_too_small", 0) == 1


def test_low_coverage_rejected(tmp_path, cfg):
    db = SynthDB()
    # 20-minute CGM step -> ~25% of expected 5-min points, below 80%.
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=2.5, step_min=20.0)
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 0
    assert result.rejections.get("insufficient_cgm_coverage", 0) == 1


def test_negative_isf_flagged_as_anomaly(tmp_path, cfg):
    db = SynthDB()
    # "Correction" after which BG rises: isf < 0 in generator terms.
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=-1.0)
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 1
    assert result.episodes[0].anomaly
    assert result.episodes[0].isf <= 0


# ------------------------------------------------------------------- values

def test_isf_value_within_5_percent(tmp_path, cfg):
    db = SynthDB()
    true_isf = 2.0
    for i, dose in enumerate([1.0, 2.0, 3.0]):
        db.add_clean_episode(t0_minute=i * DAY_MIN, dose=dose, isf_mmol=true_isf)
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 3
    for ep in result.episodes:
        assert ep.isf == pytest.approx(true_isf, rel=0.05)


# --------------------------------------------------------------- aggregation

def _n_episodes_result(n: int, tmp_path, cfg, isf: float = 2.0):
    db = SynthDB()
    for i in range(n):
        db.add_clean_episode(t0_minute=i * DAY_MIN, dose=2.0, isf_mmol=isf)
    return build(db, tmp_path, cfg)


def test_confidence_none_below_5(tmp_path, cfg):
    agg = aggregate(_n_episodes_result(4, tmp_path, cfg).episodes)
    assert agg.confidence == CONF_NONE
    assert agg.median is None


def test_confidence_preliminary_5_to_14(tmp_path, cfg):
    agg = aggregate(_n_episodes_result(5, tmp_path, cfg).episodes)
    assert agg.confidence == CONF_PRELIMINARY
    assert agg.median == pytest.approx(2.0, rel=0.05)
    agg = aggregate(_n_episodes_result(14, tmp_path, cfg).episodes)
    assert agg.confidence == CONF_PRELIMINARY


def test_confidence_stable_at_15(tmp_path, cfg):
    agg = aggregate(_n_episodes_result(15, tmp_path, cfg).episodes)
    assert agg.confidence == CONF_STABLE
    assert agg.median == pytest.approx(2.0, rel=0.05)
    assert agg.iqr is not None


def test_anomalies_excluded_from_aggregate(tmp_path, cfg):
    db = SynthDB()
    for i in range(5):
        db.add_clean_episode(t0_minute=i * DAY_MIN, dose=2.0, isf_mmol=2.0)
    db.add_clean_episode(t0_minute=5 * DAY_MIN, dose=2.0, isf_mmol=-1.0)  # anomaly
    result = build(db, tmp_path, cfg)
    agg = aggregate(result.episodes)
    assert agg.n_valid == 5
    assert agg.n_anomalies == 1
    assert agg.median == pytest.approx(2.0, rel=0.05)


def test_time_of_day_bucket_local_tz(tmp_path, cfg):
    # BASE_TS is 09:00 UTC; the default zone is UTC -> "morning" (06-12).
    db = SynthDB()
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=2.5)
    result = build(db, tmp_path, cfg)
    assert result.episodes[0].tod_bucket == "morning"


def test_max_dose_rejected(tmp_path):
    cfg = Config(max_dose=3.0)
    db = SynthDB()
    db.add_clean_episode(t0_minute=0, dose=2.0, isf_mmol=2.0)
    db.add_clean_episode(t0_minute=DAY_MIN, dose=10.0, isf_mmol=0.3)  # meal-like
    result = build(db, tmp_path, cfg)
    assert len(result.episodes) == 1
    assert result.episodes[0].dose == pytest.approx(2.0)
    assert result.rejections.get("dose_too_large", 0) == 1
