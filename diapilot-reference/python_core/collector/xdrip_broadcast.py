"""Parsers for xDrip local integration.

Two channels (both loopback/local, no cloud):
  * glucose  -> Intent action com.eveningoutpost.dexdrip.BgEstimate (glucose only)
  * insulin  -> xDrip Nightscout-compatible local web service (127.0.0.1:17580)

Parsers are tolerant: missing/garbled fields yield None rather than raising, so a
single bad broadcast never crashes the collector.
"""

from __future__ import annotations

from dataclasses import dataclass

MGDL_PER_MMOL = 18.0182

# Verified intent extras (BgEstimateBroadcaster).
ACTION_BG = "com.eveningoutpost.dexdrip.BgEstimate"
EXTRA_BG = "com.eveningoutpost.dexdrip.Extras.BgEstimate"       # double, mg/dL
EXTRA_TIME = "com.eveningoutpost.dexdrip.Extras.Time"           # long, ms epoch
EXTRA_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName"  # str trend
EXTRA_RAW = "com.eveningoutpost.dexdrip.Extras.Raw"             # double, optional


@dataclass
class Reading:
    ts_ms: int
    mgdl: float
    mmol: float
    trend: str | None
    source: str = "xdrip_broadcast"


@dataclass
class InsulinEvent:
    ts_ms: int
    units: float
    insulin_type: str | None
    source: str = "xdrip_treatments"


def parse_bg_broadcast(extras: dict) -> Reading | None:
    """Normalize xDrip BgEstimate intent extras into a Reading.

    Requires a positive glucose value and a timestamp; returns None otherwise.
    """
    if not extras:
        return None
    try:
        mgdl = float(extras.get(EXTRA_BG, 0) or 0)
    except (TypeError, ValueError):
        return None
    if mgdl <= 0:
        return None
    ts = extras.get(EXTRA_TIME)
    try:
        ts_ms = int(ts)
    except (TypeError, ValueError):
        return None
    if ts_ms <= 0:
        return None
    trend = extras.get(EXTRA_SLOPE_NAME) or None
    return Reading(ts_ms=ts_ms, mgdl=mgdl, mmol=mgdl / MGDL_PER_MMOL, trend=trend)


def parse_treatments(items: list[dict]) -> list[InsulinEvent]:
    """Parse a Nightscout-compatible treatments array into insulin events.

    Accepts either `timestamp` (ms) or `date`/`mills` fields; tolerates entries
    without insulin (skipped). Carbs are handled elsewhere (meal labeling).
    """
    out: list[InsulinEvent] = []
    for it in items or []:
        try:
            units = float(it.get("insulin") or 0)
        except (TypeError, ValueError):
            continue
        if units <= 0:
            continue
        ts_ms = _extract_ts_ms(it)
        if ts_ms is None:
            continue
        itype = it.get("insulinType") or it.get("enteredBy") or None
        out.append(InsulinEvent(ts_ms=ts_ms, units=units, insulin_type=itype))
    return out


def _extract_ts_ms(it: dict) -> int | None:
    for key in ("timestamp", "mills", "date"):
        v = it.get(key)
        if v is None:
            continue
        try:
            return int(v)
        except (TypeError, ValueError):
            continue
    return None
