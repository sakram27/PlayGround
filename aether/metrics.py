"""Metrik freqtrade-parity yang hilang di v3.22 (Sharpe/Sortino/Calmar/SQN/Kelly)."""
from __future__ import annotations
import math


def mean(xs: list) -> float:
    return sum(xs) / len(xs) if xs else 0.0


def sharpe_ratio(returns: list, risk_free: float = 0.0) -> float:
    """Sharpe ala freqtrade: mean(excess)/stdev(excess). Return 0 bila <2 sampel."""
    if len(returns) < 2:
        return 0.0
    ex = [r - risk_free for r in returns]
    m = mean(ex)
    var = sum((x - m) ** 2 for x in ex) / (len(ex) - 1)
    sd = math.sqrt(var)
    return m / sd if sd > 0 else 0.0


def sortino_ratio(returns: list, target: float = 0.0) -> float:
    if not returns:
        return 0.0
    m = mean(returns)
    downside = [min(0.0, r - target) for r in returns]
    dd = math.sqrt(sum(x * x for x in downside) / len(downside)) if downside else 0.0
    return (m - target) / dd if dd > 0 else 0.0


def calmar_ratio(net_profit_pct: float, max_dd_pct: float) -> float:
    if max_dd_pct <= 0:
        return 0.0
    return net_profit_pct / max_dd_pct


def sqn(trades_pnl: list) -> float:
    """System Quality Number = sqrt(N) * mean / stdev (Van Tharp, dipakai freqtrade)."""
    n = len(trades_pnl)
    if n < 2:
        return 0.0
    m = mean(trades_pnl)
    var = sum((x - m) ** 2 for x in trades_pnl) / (n - 1)
    sd = math.sqrt(var)
    return math.sqrt(n) * m / sd if sd > 0 else 0.0


def kelly_fraction(win_rate: float, avg_win: float, avg_loss_abs: float) -> float:
    """Kelly f* = W - (1-W)/R. avg_loss_abs > 0. Negatif => 0 (jangan trade)."""
    if avg_loss_abs <= 0 or avg_win <= 0:
        return 0.0
    r = avg_win / avg_loss_abs
    if r <= 0:
        return 0.0
    f = win_rate - (1.0 - win_rate) / r
    return max(0.0, f)
