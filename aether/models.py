"""AetherSignalBot — model data backtest (freqtrade-parity, fixed).

Mirror 1:1 dari kelas Kotlin di APK v3.22-debug:
  com.aether.signal.core.model.{Candle, BacktestParams, ...}
  com.aether.signal.core.backtest.{BacktestTrade, BacktestResult}
  com.aether.signal.core.risk.RiskLevels

Tujuan: referensi fixed yang bisa di-port 1:1 ke Kotlin.
"""
from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum
import math


class SignalDirection(Enum):
    BUY = "BUY"
    SELL = "SELL"


class SignalResult(Enum):
    WIN = "WIN"
    LOSS = "LOSS"
    EXPIRED = "EXPIRED"


@dataclass
class Candle:
    timestamp: int  # ms epoch
    open: float
    high: float
    low: float
    close: float
    volume: float = 0.0

    def is_valid(self) -> bool:
        for v in (self.open, self.high, self.low, self.close):
            if not math.isfinite(v) or v <= 0:
                return False
        if self.high < max(self.open, self.close) - 1e-12:
            return False
        if self.low > min(self.open, self.close) + 1e-12:
            return False
        return True


@dataclass
class RiskLevels:
    entry: float
    stop_loss: float
    take_profit: float
    risk_percent: float = 0.0   # |entry-SL| / entry
    risk_reward: float = 0.0    # |TP-entry| / |entry-SL|


@dataclass
class BacktestParams:
    asset: str = "BTC/USDT"
    timeframe: str = "15m"
    initial_capital: float = 1000.0
    risk_per_trade: float = 0.01      # fraksi modal per trade (0.01 = 1%)
    fee_percent: float = 0.0005       # per sisi (0.05% ala Binance spot)
    slippage_percent: float = 0.0002  # per sisi
    leverage: float = 1.0
    # FIX (freqtrade parity): warmup dinamis + max holding konfig, bukan hardcode 220/200.
    startup_candle_count: int = 50
    max_holding_bars: int = 200
    # konserfatif: bila satu bar menyentuh SL & TP sekaligus -> anggap SL (LOSS).
    same_bar_ambiguous_to_loss: bool = True


@dataclass
class BacktestTrade:
    direction: SignalDirection
    asset: str
    timeframe: str
    entry: float
    stop_loss: float
    take_profit: float
    exit: float
    entry_time: int
    exit_time: int
    strategy_name: str
    result: SignalResult
    pnl: float
    pnl_percent: float
    fees: float
    risk_amount: float
    r_multiple: float
    ambiguous_same_bar: bool = False


@dataclass
class BacktestResult:
    initial_capital: float
    final_capital: float
    net_profit: float
    net_profit_percent: float
    total_trades: int
    wins: int
    losses: int
    expired: int
    win_rate: float
    loss_rate: float
    gross_profit: float
    gross_loss: float
    profit_factor: float
    expectancy: float
    average_win: float
    average_loss: float
    average_r: float
    average_risk_reward: float
    max_drawdown: float
    max_drawdown_percent: float
    longest_win_streak: int
    longest_loss_streak: int
    # freqtrade parity (tidak ada di v3.22):
    sharpe: float = 0.0
    sortino: float = 0.0
    calmar: float = 0.0
    sqn: float = 0.0
    kelly_fraction: float = 0.0
    buy_and_hold_return: float = 0.0
    trades: list = field(default_factory=list)
    equity_curve: list = field(default_factory=list)  # [(timestamp, capital)]

    @staticmethod
    def empty(reason: str = "") -> "BacktestResult":
        r = BacktestResult(1000, 1000, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                           0, 0, 0, 0, 0, 0, 0, 0)
        r.error = reason
        return r
