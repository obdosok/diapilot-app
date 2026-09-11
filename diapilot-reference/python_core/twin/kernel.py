"""Personal insulin-response kernel G(tau).

G(tau) = median over clean correction episodes of (BG(t0+tau) - BG(t0)) per
unit dose, for tau in [0, T_kernel]. G <= 0, decreasing, flattening at the end
of the analog's action. This is exactly the median curve of episodes_overlay:
it already fuses ISF and the activity shape, learned on meal-free episodes.

The kernel MUST be built on the training window only (no look-ahead).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from ..episodes import Episode


@dataclass
class Kernel:
    tau: np.ndarray        # minutes, 0..T_kernel, step 5
    g: np.ndarray          # median dBG per unit (<=0), monotone non-increasing
    g_q1: np.ndarray       # 25th pct band (for the chart)
    g_q3: np.ndarray       # 75th pct band
    n_episodes: int

    def value(self, tau_min: float) -> float:
        """G at arbitrary tau: 0 before bolus, flat tail after T_kernel."""
        if tau_min <= 0:
            return 0.0
        if tau_min >= self.tau[-1]:
            return float(self.g[-1])
        return float(np.interp(tau_min, self.tau, self.g))


def build_kernel(episodes: list[Episode], t_kernel_min: float = 240.0,
                 step_min: float = 5.0) -> Kernel:
    """Aggregate per-episode dose-normalized dBG curves into G(tau).

    Only non-anomalous episodes contribute (a post-correction rise is not
    insulin action). Each episode curve is dose-normalized and resampled onto
    a common grid; the median across episodes is the kernel.
    """
    valid = [e for e in episodes if not e.anomaly]
    grid = np.arange(0.0, t_kernel_min + step_min, step_min)
    stacked = []
    for e in valid:
        c = e.curve
        y = np.interp(grid, c["minutes"].to_numpy(), (c["delta_bg"] / e.dose).to_numpy())
        stacked.append(y)
    mat = np.vstack(stacked)
    g = np.median(mat, axis=0)
    # Enforce monotone non-increasing (insulin action only lowers BG cumulatively).
    g = np.minimum.accumulate(g)
    return Kernel(
        tau=grid,
        g=g,
        g_q1=np.percentile(mat, 25, axis=0),
        g_q3=np.percentile(mat, 75, axis=0),
        n_episodes=len(valid),
    )
