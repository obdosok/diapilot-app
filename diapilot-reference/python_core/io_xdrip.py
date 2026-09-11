"""Reading and normalizing xDrip SQLite export.

Only two tables are consumed:
  BgReadings(timestamp ms epoch UTC, calculated_value mg/dL)
  Treatments(timestamp ms epoch UTC, insulin U, carbs g)

All glucose values are converted to mmol/L on load. Timestamps are kept as
UTC pandas Timestamps; a tz-localized column is added for time-of-day logic.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass

import pandas as pd

MGDL_PER_MMOL = 18.0182


@dataclass
class XDripData:
    readings: pd.DataFrame  # columns: ts (UTC), ts_local, bg (mmol/L)
    treatments: pd.DataFrame  # columns: ts (UTC), ts_local, insulin (U), carbs (g)
    tz: str


def load_data(sqlite_path: str, tz: str = "UTC") -> XDripData:
    """Load BG readings and treatments from an xDrip export.

    `tz` is the IANA zone of the user's local time, used only for the
    time-of-day columns. It defaults to UTC so that results never depend on
    the machine the analysis runs on; pass the user's zone explicitly.
    """
    con = sqlite3.connect(sqlite_path)
    try:
        readings = pd.read_sql_query(
            "SELECT timestamp, calculated_value FROM BgReadings "
            "WHERE calculated_value > 0 ORDER BY timestamp",
            con,
        )
        treatments = pd.read_sql_query(
            "SELECT timestamp, insulin, carbs FROM Treatments ORDER BY timestamp",
            con,
        )
    finally:
        con.close()

    readings["ts"] = pd.to_datetime(readings["timestamp"], unit="ms", utc=True)
    readings["bg"] = readings["calculated_value"] / MGDL_PER_MMOL
    readings["ts_local"] = readings["ts"].dt.tz_convert(tz)
    readings = readings[["ts", "ts_local", "bg"]].reset_index(drop=True)

    treatments["ts"] = pd.to_datetime(treatments["timestamp"], unit="ms", utc=True)
    treatments["insulin"] = treatments["insulin"].fillna(0.0)
    treatments["carbs"] = treatments["carbs"].fillna(0.0)
    treatments["ts_local"] = treatments["ts"].dt.tz_convert(tz)
    treatments = treatments[["ts", "ts_local", "insulin", "carbs"]].reset_index(drop=True)

    return XDripData(readings=readings, treatments=treatments, tz=tz)
