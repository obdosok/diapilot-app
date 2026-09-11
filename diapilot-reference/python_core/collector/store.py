"""Local SQLite store: hard core (glucose/insulin) + flexible context (annotations,
meal events, meal labels). Idempotent writes (dedup by timestamp)."""

from __future__ import annotations

import sqlite3
from dataclasses import dataclass

SCHEMA = """
CREATE TABLE IF NOT EXISTS glucose_readings (
    ts_ms INTEGER PRIMARY KEY, mgdl REAL, mmol REAL, trend TEXT, source TEXT
);
CREATE TABLE IF NOT EXISTS insulin_events (
    ts_ms INTEGER PRIMARY KEY, units REAL, insulin_type TEXT, source TEXT
);
CREATE TABLE IF NOT EXISTS annotations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_ms INTEGER, kind TEXT, content TEXT, media_ref TEXT
);
CREATE TABLE IF NOT EXISTS meal_events (
    onset_ms INTEGER PRIMARY KEY, peak_ms INTEGER, pre_bg REAL, peak_bg REAL,
    rise REAL, time_to_peak_min REAL, bolus_units REAL, kind TEXT, label_id INTEGER
);
CREATE TABLE IF NOT EXISTS meal_labels (
    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, use_count INTEGER DEFAULT 0
);
"""


@dataclass
class Store:
    con: sqlite3.Connection

    @classmethod
    def open(cls, path: str) -> "Store":
        con = sqlite3.connect(path)
        con.executescript(SCHEMA)
        con.commit()
        return cls(con)

    # --- hard core -------------------------------------------------------
    def upsert_reading(self, ts_ms: int, mgdl: float, mmol: float,
                       trend: str | None, source: str = "xdrip_broadcast") -> None:
        self.con.execute(
            "INSERT INTO glucose_readings VALUES (?,?,?,?,?) "
            "ON CONFLICT(ts_ms) DO UPDATE SET mgdl=excluded.mgdl, mmol=excluded.mmol",
            (ts_ms, mgdl, mmol, trend, source),
        )
        self.con.commit()

    def upsert_insulin(self, ts_ms: int, units: float,
                       insulin_type: str | None, source: str = "xdrip_treatments") -> None:
        self.con.execute(
            "INSERT INTO insulin_events VALUES (?,?,?,?) "
            "ON CONFLICT(ts_ms) DO UPDATE SET units=excluded.units",
            (ts_ms, units, insulin_type, source),
        )
        self.con.commit()

    # --- flexible context ------------------------------------------------
    def add_annotation(self, ts_ms: int, kind: str, content: str,
                       media_ref: str | None = None) -> int:
        cur = self.con.execute(
            "INSERT INTO annotations (ts_ms, kind, content, media_ref) VALUES (?,?,?,?)",
            (ts_ms, kind, content, media_ref),
        )
        self.con.commit()
        return cur.lastrowid

    def get_or_create_label(self, name: str) -> int:
        self.con.execute("INSERT OR IGNORE INTO meal_labels (name) VALUES (?)", (name,))
        self.con.execute("UPDATE meal_labels SET use_count = use_count + 1 WHERE name = ?", (name,))
        self.con.commit()
        return self.con.execute("SELECT id FROM meal_labels WHERE name = ?", (name,)).fetchone()[0]

    def add_meal_event(self, ev) -> None:
        self.con.execute(
            "INSERT OR REPLACE INTO meal_events "
            "(onset_ms, peak_ms, pre_bg, peak_bg, rise, time_to_peak_min, bolus_units, kind, label_id)"
            " VALUES (?,?,?,?,?,?,?,?,?)",
            (ev.onset_ms, ev.peak_ms, ev.pre_bg, ev.peak_bg, ev.rise,
             ev.time_to_peak_min, ev.bolus_units, ev.kind, None),
        )
        self.con.commit()

    def count(self, table: str) -> int:
        return self.con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
