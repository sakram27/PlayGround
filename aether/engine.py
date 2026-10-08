"""AetherSignalBot — BacktestEngine FIXED (freqtrade parity).

Mempertahankan arah/tujuan aplikasi (bot sinyal ala freqtrade: 1 posisi per
sinyal, SL/TP dari RiskEngine, fee+slippage, equity curve, trade list).

Perbaikan vs v3.22-debug (lihat LAPORAN_FIX.md untuk bukti bytecode):
  FIX-01 SL/TP hit logic terbalik -> diperbaiki (BUY: low<=SL loss, high>=TP win).
  FIX-02 warmup hardcode 220 -> startup_candle_count dinamis.
  FIX-03 O(n^2) subList+decide ulang tiap bar -> sinyal di-pass dari luar / precompute.
  FIX-04 fee 1x -> fee 2 sisi (entry+exit); slippage 2 sisi.
  FIX-05 position sizing campur leverage -> stake*leverage dgn cap + guard dana.
  FIX-06 max holding hardcode 200 -> params.max_holding_bars.
  FIX-07 equity hanya saat close -> equity per-bar + maxDD dari equity curve.
  FIX-08 entry tanpa validasi dana/cap -> guard + skip bila size<=0.
  FIX-09 parseTimeframe default 15 & tanpa 'w' -> support w/d/h/m + fallback aman.
  FIX-10 candle tidak di-sort/dedupe -> sort+dedupe berdasarkan timestamp.

Kontrak no-lookahead (ala freqtrade):
  sinyal dihitung dari bar 0..i (close i), entry dieksekusi di OPEN bar i+1.
  SL/TP dicek mulai bar i+1 (tidak termasuk bar sinyal).
"""
from __future__ import annotations
import math
from dataclasses import dataclass
from typing import Callable, List, Optional
from .models import (Candle, BacktestParams, BacktestResult, BacktestTrade,
                     RiskLevels, SignalDirection, SignalResult)
from .metrics import sharpe_ratio, sortino_ratio, calmar_ratio, sqn, kelly_fraction


def parse_timeframe(tf: str) -> int:
    """FIX-09: menit per timeframe. Support m/h/d/w. Fallback 15 (aman)."""
    try:
        s = (tf or "").strip().lower()
        if s.endswith("m"):
            return max(1, int(s[:-1] or 15))
        if s.endswith("h"):
            return max(1, int(s[:-1] or 1)) * 60
        if s.endswith("d"):
            return max(1, int(s[:-1] or 1)) * 1440
        if s.endswith("w"):
            return max(1, int(s[:-1] or 1)) * 10080
    except (ValueError, TypeError):
        pass
    return 15


def sanitize_candles(candles: List[Candle]) -> List[Candle]:
    """FIX-10: sort + dedupe timestamp + buang candle invalid."""
    seen = {}
    for c in candles:
        if c is not None and math.isfinite(c.timestamp):
            # keep terakhir bila duplikat (data terbaru menang)
            seen[int(c.timestamp)] = c
    out = [c for c in seen.values() if c.is_valid()]
    out.sort(key=lambda c: c.timestamp)
    return out


def resolve_exit(direction: SignalDirection, bar: Candle,
                 stop_loss: float, take_profit: float,
                 ambiguous_to_loss: bool = True):
    """FIX-01: logika SL/TP yang benar + same-bar ambiguity konservatif.

    BUY : SL kena bila low  <= SL ; TP kena bila high >= TP.
    SELL: SL kena bila high >= SL ; TP kena bila low  <= TP.
    Bila keduanya kena dalam 1 bar -> LOSS (SL dulu), flag ambiguous=True.
    Return (exit_price|None, result|None, ambiguous).
    """
    if direction == SignalDirection.BUY:
        sl_hit = bar.low <= stop_loss
        tp_hit = bar.high >= take_profit
    else:
        sl_hit = bar.high >= stop_loss
        tp_hit = bar.low <= take_profit
    if sl_hit and tp_hit:
        if ambiguous_to_loss:
            return stop_loss, SignalResult.LOSS, True
        # alternatif (tidak dipakai default): tentukan dari arah close
        mid = (bar.open + bar.close) / 2.0
        if direction == SignalDirection.BUY:
            return (take_profit, SignalResult.WIN, True) if mid >= (stop_loss + take_profit) / 2 else (stop_loss, SignalResult.LOSS, True)
        return (stop_loss, SignalResult.LOSS, True) if mid <= (stop_loss + take_profit) / 2 else (take_profit, SignalResult.WIN, True)
    if sl_hit:
        return stop_loss, SignalResult.LOSS, False
    if tp_hit:
        return take_profit, SignalResult.WIN, False
    return None, None, False


@dataclass
class Signal:
    index: int                  # index bar sinyal (close bar ini)
    direction: SignalDirection
    stop_loss: float
    take_profit: float
    strategy_name: str = "strategy"


SignalFn = Callable[[List[Candle], int], Optional[Signal]]
# SignalFn(candles, i) -> Signal|None memakai data candles[0..i] saja (no lookahead).


def run_backtest(candles: List[Candle], params: BacktestParams,
                 signal_fn: SignalFn) -> BacktestResult:
    data = sanitize_candles(candles)
    if len(data) < 60:
        return BacktestResult.empty("Insufficient data (min 60 candles)")
    if len(data) <= params.startup_candle_count:
        return BacktestResult.empty(
            f"Insufficient data (need >{params.startup_candle_count} warmup candles)")

    capital = float(params.initial_capital)
    if not math.isfinite(capital) or capital <= 0:
        capital = 1000.0
    initial = capital

    trades: List[BacktestTrade] = []
    equity: List[tuple] = [(data[0].timestamp, capital)]
    peak = capital
    max_dd = 0.0
    max_dd_pct = 0.0

    def track_equity(ts: int, cap: float):
        nonlocal peak, max_dd, max_dd_pct
        equity.append((ts, cap))
        if cap > peak:
            peak = cap
        dd = peak - cap
        if dd > max_dd:
            max_dd = dd
        if peak > 0:
            ddp = dd / peak * 100.0
            if ddp > max_dd_pct:
                max_dd_pct = ddp

    i = params.startup_candle_count
    n = len(data)
    while i < n - 1:  # butuh bar i+1 untuk entry (open next bar)
        sig = signal_fn(data, i)
        if sig is None:
            # FIX-07: equity per-bar (mark-to-market flat saat flat = capital tetap)
            track_equity(data[i].timestamp, capital)
            i += 1
            continue

        sl, tp = sig.stop_loss, sig.take_profit
        if not (math.isfinite(sl) and math.isfinite(tp) and sl > 0 and tp > 0):
            track_equity(data[i].timestamp, capital)
            i += 1
            continue
        # validasi arah SL/TP waras (BUY: SL<entry<TP ; SELL: TP<entry<SL)
        entry_ref = data[i].close
        if sig.direction == SignalDirection.BUY and not (sl < entry_ref < tp):
            track_equity(data[i].timestamp, capital)
            i += 1
            continue
        if sig.direction == SignalDirection.SELL and not (tp < entry_ref < sl):
            track_equity(data[i].timestamp, capital)
            i += 1
            continue

        # FIX: entry di OPEN bar berikutnya (freqtrade: tidak bisa entry di bar sinyal).
        entry_bar = data[i + 1]
        raw_entry = entry_bar.open
        # FIX-04: slippage di entry (BUY bayar lebih mahal, SELL terima lebih murah).
        if sig.direction == SignalDirection.BUY:
            entry = raw_entry * (1.0 + params.slippage_percent)
        else:
            entry = raw_entry * (1.0 - params.slippage_percent)
        if not math.isfinite(entry) or entry <= 0:
            track_equity(data[i].timestamp, capital)
            i += 1
            continue

        # FIX-05: sizing freqtrade-style.
        # risk_amount = capital * risk_per_trade (TANPA leverage).
        # risk_fraction = |entry-SL|/entry ; notional = risk_amount/risk_fraction
        # cap notional <= capital*leverage ; guard dana & size.
        risk_amount = capital * params.risk_per_trade
        risk_fraction = abs(entry - sl) / entry
        if not math.isfinite(risk_fraction) or risk_fraction <= 0:
            track_equity(data[i].timestamp, capital)
            i += 1
            continue
        notional = risk_amount / risk_fraction
        max_notional = capital * params.leverage
        if notional > max_notional:
            notional = max_notional
        if notional <= 0 or not math.isfinite(notional):
            track_equity(data[i].timestamp, capital)
            i += 1
            continue
        # FIX-04: fee DUA sisi dihitung dari notional entry & exit.
        fee_entry = notional * params.fee_percent

        exit_price: Optional[float] = None
        result: Optional[SignalResult] = None
        exit_idx = -1
        ambiguous = False
        # SL/TP dicek mulai bar entry (i+1) s/d max holding.
        last = min(n - 1, (i + 1) + params.max_holding_bars)
        j = i + 1
        while j <= last:
            px, res, amb = resolve_exit(sig.direction, data[j], sl, tp,
                                       params.same_bar_ambiguous_to_loss)
            if res is not None:
                exit_price, result, ambiguous = px, res, amb
                exit_idx = j
                break
            j += 1
        if result is None:
            # FIX-06: expired pada close bar terakhir window (sebelumnya hardcode 200).
            exit_price = data[last].close
            result = SignalResult.EXPIRED
            exit_idx = last

        # FIX-04: slippage di exit (BUY jual lebih murah, SELL beli lebih mahal).
        if sig.direction == SignalDirection.BUY:
            exit_adj = exit_price * (1.0 - params.slippage_percent)
            gross = (exit_adj - entry) / entry * notional
        else:
            exit_adj = exit_price * (1.0 + params.slippage_percent)
            gross = (entry - exit_adj) / entry * notional
        fee_exit = notional * params.fee_percent
        fees = fee_entry + fee_exit
        pnl = gross - fees
        capital += pnl
        if capital < 0:
            capital = 0.0  # bangkrut -> floor 0 (sebelumnya bisa negatif tanpa guard)
        track_equity(data[exit_idx].timestamp, capital)

        pnl_pct = pnl / initial * 100.0 if initial > 0 else 0.0
        r_mult = pnl / risk_amount if risk_amount > 0 else 0.0
        trades.append(BacktestTrade(
            direction=sig.direction, asset=params.asset, timeframe=params.timeframe,
            entry=entry, stop_loss=sl, take_profit=tp, exit=exit_adj,
            entry_time=entry_bar.timestamp, exit_time=data[exit_idx].timestamp,
            strategy_name=sig.strategy_name, result=result, pnl=pnl,
            pnl_percent=pnl_pct, fees=fees, risk_amount=risk_amount,
            r_multiple=r_mult, ambiguous_same_bar=ambiguous))
        # lanjut ke bar setelah exit (hindari overlap; freqtrade: max_open_trades=1 di sini)
        i = exit_idx + 1

    return build_result(initial, capital, trades, equity, data)


def build_result(initial: float, final: float, trades: List[BacktestTrade],
                 equity: List[tuple], data: List[Candle]) -> BacktestResult:
    wins = [t for t in trades if t.result == SignalResult.WIN]
    losses = [t for t in trades if t.result == SignalResult.LOSS]
    expired = [t for t in trades if t.result == SignalResult.EXPIRED]
    gross_p = sum(t.pnl for t in wins)
    gross_l = sum(-t.pnl for t in losses)  # positif
    net = final - initial
    net_pct = net / initial * 100.0 if initial > 0 else 0.0
    n = len(trades)
    win_rate = len(wins) / n * 100.0 if n else 0.0
    loss_rate = len(losses) / n * 100.0 if n else 0.0
    # FIX: profit factor edge-case jujur (freqtrade style).
    if gross_l > 0:
        pf = gross_p / gross_l
    else:
        pf = float("inf") if gross_p > 0 else 0.0
    avg_w = gross_p / len(wins) if wins else 0.0
    avg_l = (-sum(t.pnl for t in losses) / len(losses)) if losses else 0.0
    expectancy = net / n if n else 0.0
    avg_r = sum(t.r_multiple for t in trades) / n if n else 0.0
    # streaks
    lws = lls = cws = cls_ = 0
    for t in trades:
        if t.result == SignalResult.WIN:
            cws += 1
            cls_ = 0
            lws = max(lws, cws)
        elif t.result == SignalResult.LOSS:
            cls_ += 1
            cws = 0
            lls = max(lls, cls_)
        else:
            cws = cls_ = 0
    # drawdown dari equity curve (FIX-07).
    peak = initial
    max_dd = 0.0
    max_dd_pct = 0.0
    for _, cap in equity:
        peak = max(peak, cap)
        dd = peak - cap
        max_dd = max(max_dd, dd)
        if peak > 0:
            max_dd_pct = max(max_dd_pct, dd / peak * 100.0)
    # freqtrade parity metrics (FIX: sebelumnya tidak ada).
    per_trade_ret = [(t.pnl / initial) if initial > 0 else 0.0 for t in trades]
    sharpe = sharpe_ratio(per_trade_ret)
    sortino = sortino_ratio(per_trade_ret)
    calmar = calmar_ratio(net_pct, max_dd_pct)
    q = sqn([t.pnl for t in trades])
    kelly = kelly_fraction(len(wins) / n if n else 0.0, avg_w, avg_l)
    bh = 0.0
    if len(data) >= 2 and data[0].close > 0:
        bh = (data[-1].close - data[0].close) / data[0].close * 100.0
    return BacktestResult(
        initial_capital=initial, final_capital=final, net_profit=net,
        net_profit_percent=net_pct, total_trades=n, wins=len(wins),
        losses=len(losses), expired=len(expired), win_rate=win_rate,
        loss_rate=loss_rate, gross_profit=gross_p, gross_loss=gross_l,
        profit_factor=pf, expectancy=expectancy, average_win=avg_w,
        average_loss=avg_l, average_r=avg_r, average_risk_reward=avg_r,
        max_drawdown=max_dd, max_drawdown_percent=max_dd_pct,
        longest_win_streak=lws, longest_loss_streak=lls,
        sharpe=sharpe, sortino=sortino, calmar=calmar, sqn=q,
        kelly_fraction=kelly, buy_and_hold_return=bh,
        trades=trades, equity_curve=equity)
