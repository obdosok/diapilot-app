"""Contract tests for the stage-1 collection core."""

from __future__ import annotations

import pandas as pd
import pytest

from diapilot_stage0.collector.xdrip_broadcast import (
    parse_bg_broadcast, parse_treatments,
    EXTRA_BG, EXTRA_TIME, EXTRA_SLOPE_NAME,
)
from diapilot_stage0.collector.store import Store
from diapilot_stage0.collector.meal_detect import detect_meals, MealConfig, MealEvent

BASE = 1_748_768_400_000  # ms


# ------------------------------------------------------- broadcast parser

def test_parse_bg_broadcast_ok():
    r = parse_bg_broadcast({EXTRA_BG: 180.0, EXTRA_TIME: BASE, EXTRA_SLOPE_NAME: "Flat"})
    assert r is not None
    assert r.mgdl == 180.0
    assert r.mmol == pytest.approx(180.0 / 18.0182, rel=1e-6)
    assert r.trend == "Flat"


def test_parse_bg_broadcast_garbled_returns_none():
    assert parse_bg_broadcast({}) is None
    assert parse_bg_broadcast({EXTRA_BG: 0, EXTRA_TIME: BASE}) is None
    assert parse_bg_broadcast({EXTRA_BG: "abc", EXTRA_TIME: BASE}) is None
    assert parse_bg_broadcast({EXTRA_BG: 120, EXTRA_TIME: None}) is None


def test_parse_treatments_filters_and_normalizes():
    items = [
        {"insulin": 3.0, "timestamp": BASE, "insulinType": "Fiasp"},
        {"carbs": 30, "timestamp": BASE + 1000},          # no insulin -> skipped
        {"insulin": 0, "timestamp": BASE + 2000},          # zero -> skipped
        {"insulin": 5.0, "mills": BASE + 3000},            # alt timestamp field
    ]
    evs = parse_treatments(items)
    assert len(evs) == 2
    assert evs[0].units == 3.0 and evs[0].insulin_type == "Fiasp"
    assert evs[1].ts_ms == BASE + 3000


# -------------------------------------------------------------- store

def test_store_idempotent(tmp_path):
    s = Store.open(str(tmp_path / "c.sqlite"))
    s.upsert_reading(BASE, 180.0, 9.99, "Flat")
    s.upsert_reading(BASE, 181.0, 10.05, "Flat")  # same ts -> update, not duplicate
    assert s.count("glucose_readings") == 1
    s.upsert_insulin(BASE, 3.0, "Fiasp")
    s.upsert_insulin(BASE, 3.0, "Fiasp")
    assert s.count("insulin_events") == 1


def test_store_labels_and_annotations(tmp_path):
    s = Store.open(str(tmp_path / "c.sqlite"))
    a = s.add_annotation(BASE, "text", "обычный завтрак")
    assert a == 1
    id1 = s.get_or_create_label("овсянка")
    id2 = s.get_or_create_label("овсянка")
    assert id1 == id2
    cnt = s.con.execute("SELECT use_count FROM meal_labels WHERE name='овсянка'").fetchone()[0]
    assert cnt == 2


# --------------------------------------------------------- meal detector

def _readings(points):
    """points: list of (minute, bg). Returns a readings DataFrame."""
    ts = pd.to_datetime([BASE + int(m * 60_000) for m, _ in points], unit="ms", utc=True)
    return pd.DataFrame({"ts": ts, "bg": [b for _, b in points]})


def _boluses(points):
    ts = pd.to_datetime([BASE + int(m * 60_000) for m, _ in points], unit="ms", utc=True)
    return pd.DataFrame({"ts": ts, "insulin": [u for _, u in points]})


def test_meal_rise_with_bolus_is_announced():
    pts = [(m, 6.0) for m in range(-30, 5, 5)]
    pts += [(5, 6.5), (10, 7.5), (15, 8.8), (20, 9.5), (25, 9.8), (30, 9.9)]
    pts += [(m, 9.9) for m in range(35, 60, 5)]
    meals = detect_meals(_readings(pts), _boluses([(0, 6.0)]))
    assert len(meals) == 1
    assert meals[0].kind == "announced"
    assert meals[0].rise >= 2.0
    assert meals[0].bolus_units == pytest.approx(6.0)


def test_meal_rise_without_bolus_is_unannounced():
    pts = [(m, 6.0) for m in range(-30, 5, 5)]
    pts += [(5, 6.6), (10, 7.6), (15, 8.9), (20, 9.6), (25, 9.9)]
    pts += [(m, 9.9) for m in range(30, 55, 5)]
    meals = detect_meals(_readings(pts), _boluses([]))
    assert len(meals) == 1
    assert meals[0].kind == "unannounced"


def test_correction_fall_is_not_a_meal():
    # BG falling after a bolus -> correction, no rise, no meal.
    pts = [(0, 12.0), (30, 11.0), (60, 9.5), (90, 8.0), (120, 7.0), (150, 6.5)]
    meals = detect_meals(_readings(pts), _boluses([(0, 4.0)]))
    assert len(meals) == 0


def test_micro_wiggle_ignored():
    pts = [(m, 6.0 + 0.3 * (m % 2)) for m in range(0, 120, 5)]  # tiny noise <2 mmol
    meals = detect_meals(_readings(pts), _boluses([]))
    assert len(meals) == 0
