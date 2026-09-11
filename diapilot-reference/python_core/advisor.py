"""Correction "second opinion" calculator (personal, non-prescriptive).

Given a current BG, a target, recent boluses (for IOB) and one or more ISF
estimates, it reports a *range* of correction doses with explicit assumptions.
It never issues a single "inject X" command; every number carries its source.

Design note: a data-driven clean-episode ISF is a LOWER bound (unlogged meals
raise end-BG, shrinking the apparent drop), so it yields an UPPER bound on the
dose. The user's configured ISF and lived experience anchor the safe end.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from .episodes import Config, Episode, detect_episodes
from .io_xdrip import XDripData
from .twin.kernel import Kernel


@dataclass
class ISFEstimate:
    label: str
    isf: float           # mmol/L per unit
    source: str          # 'configured' | 'data'
    confidence: str = "" # for data-derived
    n: int = 0


@dataclass
class CorrectionOption:
    estimate: ISFEstimate
    units: float


@dataclass
class Suggestion:
    bg: float
    target: float
    raw_drop_needed: float      # bg - target
    iob_drop: float             # expected further drop from insulin still acting
    net_drop_needed: float      # raw - iob
    options: list[CorrectionOption]
    dose_cap: float
    over_cap: bool              # any option exceeds the cap


def estimate_isf_from_window(
    data: XDripData,
    since: pd.Timestamp,
    max_dose: float | None,
    tz: str,
) -> ISFEstimate:
    """Median clean-correction ISF within [since, end]; confidence by count."""
    from .analysis import _confidence, CONF_LABELS_RU
    reads = data.readings[data.readings["ts"] >= since]
    treat = data.treatments[data.treatments["ts"] >= since]
    sub = XDripData(reads.reset_index(drop=True), treat.reset_index(drop=True), tz)
    res = detect_episodes(sub, Config(max_dose=max_dose, tz=tz))
    vals = [e.isf for e in res.episodes if not e.anomaly]
    if not vals:
        return ISFEstimate("недавние коррекции", float("nan"), "data", "нет данных", 0)
    conf = _confidence(len(vals))
    return ISFEstimate(
        label="недавние чистые коррекции",
        isf=float(np.median(vals)),
        source="data",
        confidence=CONF_LABELS_RU[conf],
        n=len(vals),
    )


def iob_remaining_drop(kernel: Kernel, recent_boluses: list[tuple[float, float]]) -> float:
    """Expected further BG drop (mmol/L, positive) from insulin still acting.

    recent_boluses: list of (minutes_ago, dose_units). For each, the drop still
    to come is D * (G(T_kernel) - G(minutes_ago)) -- the part of the kernel not
    yet realized. G is <=0 and non-increasing, so this is >= 0.
    """
    total = 0.0
    for minutes_ago, dose in recent_boluses:
        already = kernel.value(minutes_ago)          # <= 0, realized so far
        eventual = kernel.value(kernel.tau[-1])      # <= 0, full effect
        remaining = eventual - already               # <= 0 (more negative to come)
        total += dose * (-remaining)                 # positive further drop
    return total


def suggest_correction(
    bg: float,
    target: float,
    estimates: list[ISFEstimate],
    iob_drop: float = 0.0,
    dose_cap: float = 5.0,
) -> Suggestion:
    raw = bg - target
    net = raw - iob_drop
    options = []
    over = False
    for est in estimates:
        if np.isnan(est.isf) or est.isf <= 0:
            continue
        units = max(0.0, net / est.isf)
        if units > dose_cap:
            over = True
        options.append(CorrectionOption(est, units))
    return Suggestion(
        bg=bg, target=target, raw_drop_needed=raw, iob_drop=iob_drop,
        net_drop_needed=net, options=options, dose_cap=dose_cap, over_cap=over,
    )


def format_suggestion(s: Suggestion) -> str:
    L = []
    L.append(f"Текущий сахар: {s.bg:.1f} → цель {s.target:.1f} ммоль/л "
             f"(снизить на {s.raw_drop_needed:.1f})")
    if s.iob_drop > 0.01:
        L.append(f"Активный инсулин (IOB): ожидается ещё падение на "
                 f"~{s.iob_drop:.1f} ммоль/л → остаётся снизить {s.net_drop_needed:.1f}")
    if s.net_drop_needed <= 0:
        L.append("Активного инсулина уже достаточно для достижения цели — "
                 "коррекция не требуется по расчёту.")
        return "\n".join(L)
    L.append("")
    L.append("Расчётная коррекция по разным оценкам ISF (это наблюдение, не назначение):")
    # Sort by units descending so the "more insulin" (lower ISF) options are visible on top.
    for opt in sorted(s.options, key=lambda o: o.units, reverse=True):
        e = opt.estimate
        src = (f"вбитый в xDrip" if e.source == "configured"
               else f"{e.label}, n={e.n}, {e.confidence}")
        tag = ""
        if e.source == "data":
            tag = "  ⟵ нижняя граница ISF → верхняя граница дозы (еда не учтена)"
        cap = "  ⚠️ выше вашего порога" if opt.units > s.dose_cap else ""
        L.append(f"  ISF {e.isf:.2f} ({src}): ~{opt.units:.1f} ед{cap}{tag}")
    if s.over_cap:
        L.append("")
        L.append(f"⚠️ Часть оценок предлагает > {s.dose_cap:.0f} ед. По вашему опыту такие дозы "
                 f"единовременно уводят в гипогликемию — разумно дробить с временным лагом.")
    L.append("")
    L.append("Это второе мнение по вашим данным, а не руководство к действию. "
             "Расчётный ISF из данных занижен из-за незаписанной еды; ваш опыт — сильнее расчёта.")
    return "\n".join(L)


# --------------------------------------------------------------- retrospective

@dataclass
class ReviewRow:
    when: pd.Timestamp
    tod: str
    dose_actual: float
    bg_start: float
    bg_end: float
    dose_calc: float        # what the reference ISF would have suggested
    delta: float            # actual - calc (>0 = you injected more than calc)
    outcome: str            # where BG settled vs target band


@dataclass
class ReviewResult:
    target: float
    isf_ref: ISFEstimate
    rows: list[ReviewRow]
    n: int
    median_delta: float     # systematic over(+)/under(-) dosing vs calc
    n_over: int
    n_under: int


def review_corrections(
    episodes: list[Episode],
    isf_ref: ISFEstimate,
    target: float,
    low: float = 3.9,
    high: float = 10.0,
) -> ReviewResult:
    """Compare each clean correction's actual dose to what isf_ref would suggest,
    and classify the realized outcome. Anomalies (BG rose) are skipped."""
    rows: list[ReviewRow] = []
    for e in sorted((e for e in episodes if not e.anomaly), key=lambda e: e.t0):
        calc = max(0.0, (e.bg_start - target) / isf_ref.isf)
        if e.bg_end < low:
            outcome = "гипо (<3.9)"
        elif e.bg_end > high:
            outcome = "остался высоким"
        else:
            outcome = "в диапазоне"
        rows.append(ReviewRow(
            when=e.t0_local, tod=e.tod_bucket, dose_actual=e.dose,
            bg_start=e.bg_start, bg_end=e.bg_end, dose_calc=calc,
            delta=e.dose - calc, outcome=outcome,
        ))
    deltas = [r.delta for r in rows]
    return ReviewResult(
        target=target, isf_ref=isf_ref, rows=rows, n=len(rows),
        median_delta=float(np.median(deltas)) if deltas else 0.0,
        n_over=sum(1 for d in deltas if d > 0.5),
        n_under=sum(1 for d in deltas if d < -0.5),
    )
