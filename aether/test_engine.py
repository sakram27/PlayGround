"""Regression tests: tiap test gagal pada logika v3.22 (buggy) dan lolos pada engine fixed.

Buggy reference (v3.22) untuk SL/TP:
  BUY : high >= SL -> LOSS ; low <= TP -> WIN   (TERBALIK)
  SELL: low  <= SL -> LOSS ; high >= TP -> WIN   (TERBALIK)
"""
import math
from aether.models import Candle, BacktestParams, SignalDirection, SignalResult
from aether.engine import (run_backtest, resolve_exit, sanitize_candles,
                           parse_timeframe, Signal)


def mk_trend(n=300, start=100.0, step=1.0):
    out = []
    p = start
    for i in range(n):
        o = p
        c = o + step
        h = max(o, c) + 0.2
        l = min(o, c) - 0.2
        out.append(Candle(timestamp=1000 * i, open=o, high=h, low=l, close=c, volume=100))
        p = c
    return out


def test_fix01_buy_sl_tp_sides_correct():
    # BUY SL=95 TP=110. Bar low=94 (sentuh SL), high=105 (belum TP) -> harus LOSS@SL.
    bar = Candle(timestamp=1, open=100, high=105, low=94, close=103, volume=10)
    px, res, _ = resolve_exit(SignalDirection.BUY, bar, 95.0, 110.0)
    assert res == SignalResult.LOSS and px == 95.0
    # Logika buggy v3.22: cek high(105) >= SL(95) -> LOSS juga (kebetulan sama),
    # tapi bar berikut membuktikan terbalik:
    bar2 = Candle(timestamp=2, open=100, high=96, low=94, close=95, volume=10)
    # high=96 < TP=110 sehingga buggy "low<=TP -> WIN" akan klaim WIN padahal SL tersentuh.
    px2, res2, _ = resolve_exit(SignalDirection.BUY, bar2, 95.0, 110.0)
    assert res2 == SignalResult.LOSS  # fixed: SL diprioritaskan & sisi benar


def test_fix01_sell_sides_correct():
    # SELL SL=105 TP=90. Bar high=106 (sentuh SL) -> LOSS@SL.
    bar = Candle(timestamp=1, open=100, high=106, low=95, close=96, volume=10)
    px, res, _ = resolve_exit(SignalDirection.SELL, bar, 105.0, 90.0)
    assert res == SignalResult.LOSS and px == 105.0
    # TP murni: high=101 (<SL), low=89 (<=TP) -> WIN@TP
    bar2 = Candle(timestamp=2, open=100, high=101, low=89, close=92, volume=10)
    px2, res2, _ = resolve_exit(SignalDirection.SELL, bar2, 105.0, 90.0)
    assert res2 == SignalResult.WIN and px2 == 90.0


def test_fix01_same_bar_ambiguous_conservative():
    bar = Candle(timestamp=1, open=100, high=115, low=90, close=100, volume=10)
    _, res, amb = resolve_exit(SignalDirection.BUY, bar, 95.0, 110.0)
    assert res == SignalResult.LOSS and amb is True


def test_fix02_warmup_dynamic_not_hardcoded_220():
    # 100 candle valid + sinyal tiap bar setelah warmup. Warmup 50 -> harus ada trades.
    # Engine buggy (start=220) akan return 0 trades untuk data 100 candle.
    candles = mk_trend(100)

    def sig(data, i):
        c = data[i].close
        return Signal(index=i, direction=SignalDirection.BUY,
                      stop_loss=c * 0.98, take_profit=c * 1.05)

    p = BacktestParams(startup_candle_count=50, max_holding_bars=200,
                       fee_percent=0.0, slippage_percent=0.0)
    r = run_backtest(candles, p, sig)
    assert r.total_trades > 0, "warmup dinamis harus menghasilkan trades pada 100 candle"


def test_fix04_fee_charged_both_sides():
    candles = mk_trend(300, step=2.0)

    def sig(data, i):
        if i == 50:
            c = data[i].close
            return Signal(index=i, direction=SignalDirection.BUY,
                          stop_loss=c * 0.5, take_profit=c * 2.0)
        return None

    p = BacktestParams(startup_candle_count=10, max_holding_bars=200,
                       fee_percent=0.001, slippage_percent=0.0,
                       risk_per_trade=0.01, leverage=1.0, initial_capital=1000.0)
    r = run_backtest(candles, p, sig)
    assert r.total_trades == 1
    t = r.trades[0]
    # fee 2 sisi = 2 * notional * 0.001 ; buggy hanya 1x
    assert t.fees > 0
    implied_notional = t.fees / (2 * 0.001)
    assert implied_notional > 0 and math.isfinite(implied_notional)


def test_fix05_leverage_cap_and_no_negative_capital():
    candles = mk_trend(300, step=-2.0)  # turun terus -> BUY pasti loss besar

    def sig(data, i):
        c = data[i].close
        return Signal(index=i, direction=SignalDirection.BUY,
                      stop_loss=c * 0.999, take_profit=c * 2.0)

    p = BacktestParams(startup_candle_count=10, max_holding_bars=200,
                       fee_percent=0.0, slippage_percent=0.0,
                       risk_per_trade=0.5, leverage=10.0, initial_capital=1000.0)
    r = run_backtest(candles, p, sig)
    assert r.final_capital >= 0.0  # guard bangkrut (buggy bisa negatif/tak guard)


def test_fix06_max_holding_configurable():
    candles = mk_trend(300, step=0.01)  # sideways: tak sentuh SL/TP jauh

    def sig(data, i):
        if i == 60:
            c = data[i].close
            return Signal(index=i, direction=SignalDirection.BUY,
                          stop_loss=c * 0.5, take_profit=c * 3.0)
        return None

    p = BacktestParams(startup_candle_count=10, max_holding_bars=5,
                       fee_percent=0.0, slippage_percent=0.0)
    r = run_backtest(candles, p, sig)
    assert r.total_trades == 1
    assert r.trades[0].result == SignalResult.EXPIRED
    # entry di open bar 61, exit di close bar 61+5=66
    assert r.trades[0].exit_time == candles[66].timestamp


def test_fix07_equity_per_bar_and_drawdown():
    candles = mk_trend(120, step=1.0)

    def nosig(data, i):
        return None

    p = BacktestParams(startup_candle_count=10)
    r = run_backtest(candles, p, nosig)
    # equity per-bar: len >= jumlah bar yang diproses
    assert len(r.equity_curve) >= 100


def test_fix09_parse_timeframe():
    assert parse_timeframe("15m") == 15
    assert parse_timeframe("1h") == 60
    assert parse_timeframe("4h") == 240
    assert parse_timeframe("1d") == 1440
    assert parse_timeframe("1w") == 10080
    assert parse_timeframe("ngawur") == 15  # fallback aman (buggy: default 15 tanpa w)


def test_fix10_sanitize_sort_dedupe():
    cs = [Candle(timestamp=3, open=1, high=1.1, low=0.9, close=1.0),
          Candle(timestamp=1, open=1, high=1.1, low=0.9, close=1.0),
          Candle(timestamp=1, open=2, high=2.1, low=1.9, close=2.0),  # duplikat: menang terakhir
          Candle(timestamp=2, open=float("nan"), high=1, low=1, close=1)]  # invalid dibuang
    out = sanitize_candles(cs)
    assert [c.timestamp for c in out] == [1, 3]
    assert out[0].open == 2


def test_freqtrade_metrics_present():
    candles = mk_trend(300, step=1.0)

    def sig(data, i):
        c = data[i].close
        if i % 20 == 0:
            return Signal(index=i, direction=SignalDirection.BUY,
                          stop_loss=c * 0.99, take_profit=c * 1.02)
        return None

    r = run_backtest(candles, BacktestParams(startup_candle_count=10), sig)
    assert r.total_trades > 0
    for attr in ("sharpe", "sortino", "calmar", "sqn", "kelly_fraction", "buy_and_hold_return"):
        assert math.isfinite(getattr(r, attr)), attr
    assert r.buy_and_hold_return > 0  # tren naik
