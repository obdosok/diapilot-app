"""Synthetic xDrip-like SQLite database generator for tests.

Creates a minimal DB with the two tables the tool reads:
  BgReadings(timestamp INTEGER ms epoch, calculated_value REAL mg/dL)
  Treatments(timestamp INTEGER ms epoch, insulin REAL, carbs REAL)

All helper times are in minutes relative to an arbitrary base timestamp,
so tests can express scenarios compactly.
"""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass, field

MGDL_PER_MMOL = 18.0182
MS_PER_MIN = 60_000

# Base timestamp: an arbitrary fixed point used as the "day" bucket boundary.
BASE_TS_MS = 1_748_768_400_000


def mmol_to_mgdl(v: float) -> float:
    return v * MGDL_PER_MMOL


@dataclass
class SynthDB:
    """Accumulates readings/treatments, then writes them to a SQLite file."""

    readings: list[tuple[int, float]] = field(default_factory=list)  # (ts_ms, mg/dL)
    treatments: list[tuple[int, float, float]] = field(default_factory=list)  # (ts_ms, insulin, carbs)

    def add_reading(self, minute: float, bg_mmol: float) -> None:
        self.readings.append((BASE_TS_MS + int(minute * MS_PER_MIN), mmol_to_mgdl(bg_mmol)))

    def add_bolus(self, minute: float, units: float) -> None:
        self.treatments.append((BASE_TS_MS + int(minute * MS_PER_MIN), units, 0.0))

    def add_carbs(self, minute: float, grams: float) -> None:
        self.treatments.append((BASE_TS_MS + int(minute * MS_PER_MIN), 0.0, grams))

    def add_clean_episode(
        self,
        t0_minute: float,
        dose: float,
        isf_mmol: float,
        bg_start: float = 12.0,
        step_min: float = 5.0,
        drop_start_min: float = 15.0,
        drop_end_min: float = 150.0,
    ) -> None:
        """A textbook correction: flat BG before bolus, linear drop, flat tail.

        BG settles at bg_start - dose*isf well before the [t0+3h, t0+4h]
        measurement window, so the per-episode ISF equals `isf_mmol` exactly.
        Readings cover [t0-30min, t0+245min] with full coverage.
        """
        bg_end = bg_start - dose * isf_mmol
        m = -30.0
        while m <= 245.0:
            if m <= drop_start_min:
                bg = bg_start
            elif m >= drop_end_min:
                bg = bg_end
            else:
                frac = (m - drop_start_min) / (drop_end_min - drop_start_min)
                bg = bg_start + (bg_end - bg_start) * frac
            self.add_reading(t0_minute + m, bg)
            m += step_min
        self.add_bolus(t0_minute, dose)

    def write(self, path: str) -> str:
        import os
        if os.path.exists(path):
            os.remove(path)
        con = sqlite3.connect(path)
        cur = con.cursor()
        cur.execute(
            "CREATE TABLE BgReadings (_id INTEGER PRIMARY KEY, timestamp INTEGER, calculated_value REAL)"
        )
        cur.execute(
            "CREATE TABLE Treatments (_id INTEGER PRIMARY KEY, timestamp INTEGER, insulin REAL, carbs REAL)"
        )
        cur.executemany(
            "INSERT INTO BgReadings (timestamp, calculated_value) VALUES (?, ?)", self.readings
        )
        cur.executemany(
            "INSERT INTO Treatments (timestamp, insulin, carbs) VALUES (?, ?, ?)", self.treatments
        )
        con.commit()
        con.close()
        return path


def kernel_from_episodes_synth(dose_units: float = 2.0, isf_mmol: float = 2.5,
                               drop_start_min: float = 15.0, drop_end_min: float = 150.0):
    """Build a SynthDB with N clean episodes sharing one known response shape,
    so build_kernel recovers a G(tau) whose end value == -isf_mmol.
    Returns the SynthDB (caller writes + detects)."""
    db = SynthDB()
    for i in range(20):
        db.add_clean_episode(
            t0_minute=i * 24 * 60, dose=dose_units, isf_mmol=isf_mmol,
            drop_start_min=drop_start_min, drop_end_min=drop_end_min,
        )
    return db
