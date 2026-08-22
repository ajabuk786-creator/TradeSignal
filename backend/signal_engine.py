#!/usr/bin/env python3
"""TradeSignal Pro - signal-only / paper-trading crypto scalper.

Uses ccxt market data + pandas-ta indicators. It NEVER submits live orders.
Signals are generated only from CLOSED candles and optionally sent to Telegram.
"""
from __future__ import annotations

import asyncio
import json
import logging
import math
import os
import signal
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, Iterable, Optional

import ccxt
import pandas as pd
import pandas_ta as ta
from telegram import Bot

LOG = logging.getLogger("tradesignal")


def env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except ValueError:
        return default


def env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except ValueError:
        return default


@dataclass(frozen=True)
class Config:
    exchange_id: str = os.getenv("EXCHANGE_ID", "binance")
    timeframe: str = "15m"
    higher_timeframe: str = "1h"
    candle_limit_15m: int = 220
    candle_limit_1h: int = 160
    confidence_threshold: int = env_int("CONFIDENCE_THRESHOLD", 70)
    risk_pct: float = env_float("RISK_PCT", 0.02)
    paper_equity: float = env_float("PAPER_EQUITY", 1000.0)
    rr: float = max(2.0, env_float("TARGET_RR", 2.0))
    atr_stop_mult: float = env_float("ATR_STOP_MULT", 1.5)
    max_slippage_bps: float = env_float("MAX_SLIPPAGE_BPS", 25.0)
    poll_seconds: int = max(15, env_int("POLL_SECONDS", 30))
    state_file: str = os.getenv("STATE_FILE", "paper_state.json")
    telegram_token: str = os.getenv("TELEGRAM_BOT_TOKEN", "")
    telegram_chat_id: str = os.getenv("TELEGRAM_CHAT_ID", "")
    gold_symbol: str = os.getenv("GOLD_SYMBOL", "")

    def validate(self) -> None:
        if not 50 <= self.confidence_threshold <= 100:
            raise ValueError("CONFIDENCE_THRESHOLD must be 50..100")
        if not 0 < self.risk_pct <= 0.02:
            raise ValueError("RISK_PCT must be >0 and <=0.02")
        if self.paper_equity <= 0:
            raise ValueError("PAPER_EQUITY must be >0")
        if self.max_slippage_bps <= 0:
            raise ValueError("MAX_SLIPPAGE_BPS must be >0")


@dataclass
class TradeSignal:
    symbol: str
    side: str
    entry: float
    stop_loss: float
    take_profit: float
    trailing_trigger: float
    confidence: int
    votes_for: int
    votes_against: int
    risk_reward: float
    risk_per_unit: float
    risk_budget: float
    position_size: float
    atr: float
    candle_timestamp: int
    rationale: str


@dataclass
class PaperPosition:
    symbol: str
    side: str
    entry: float
    stop_loss: float
    take_profit: float
    original_risk: float
    qty: float
    opened_at: int
    trailing_active: bool = False


class ExchangeClient:
    """Thin, retrying ccxt REST market-data client. No trading methods are exposed."""

    def __init__(self, cfg: Config):
        if not hasattr(ccxt, cfg.exchange_id):
            raise ValueError(f"Unsupported ccxt exchange: {cfg.exchange_id}")
        exchange_cls = getattr(ccxt, cfg.exchange_id)
        self.exchange = exchange_cls({
            "enableRateLimit": True,
            "timeout": 15_000,
            "options": {"defaultType": "spot"},
        })
        self.cfg = cfg
        self.markets = self._retry(self.exchange.load_markets)
        self.symbols = self._resolve_symbols()

    def _resolve_symbols(self) -> Dict[str, str]:
        result = {}
        for label, candidates in {
            "BTC/USDT": ["BTC/USDT"],
            "XRP/USDT": ["XRP/USDT"],
            "XAU/USDT": [self.cfg.gold_symbol, "XAU/USDT", "XAUT/USDT", "PAXG/USDT"],
        }.items():
            found = next((s for s in candidates if s and s in self.markets), None)
            if found:
                result[label] = found
            else:
                LOG.warning("No market found for %s on %s; candidates=%s", label, self.cfg.exchange_id, candidates)
        return result

    def _retry(self, fn, *args, attempts: int = 4, **kwargs):
        delay = 1.0
        last = None
        for n in range(attempts):
            try:
                return fn(*args, **kwargs)
            except (ccxt.RateLimitExceeded, ccxt.DDoSProtection) as exc:
                last = exc
                LOG.warning("Rate limited (%s/%s): %s", n + 1, attempts, exc)
                time.sleep(delay * 2)
            except (ccxt.NetworkError, ccxt.RequestTimeout, ccxt.ExchangeNotAvailable) as exc:
                last = exc
                LOG.warning("Exchange/network error (%s/%s): %s", n + 1, attempts, exc)
                time.sleep(delay)
            delay = min(delay * 2.0, 8.0)
        raise last if last else RuntimeError("exchange operation failed")

    def fetch_closed_ohlcv(self, symbol: str, timeframe: str, limit: int) -> pd.DataFrame:
        rows = self._retry(self.exchange.fetch_ohlcv, symbol, timeframe=timeframe, limit=limit)
        if not rows:
            raise RuntimeError(f"No OHLCV for {symbol} {timeframe}")
        df = pd.DataFrame(rows, columns=["timestamp", "open", "high", "low", "close", "volume"])
        df["timestamp"] = pd.to_numeric(df["timestamp"], errors="coerce").astype("Int64")
        for col in ["open", "high", "low", "close", "volume"]:
            df[col] = pd.to_numeric(df[col], errors="coerce")
        df = df.dropna().reset_index(drop=True)
        tf_ms = int(self.exchange.parse_timeframe(timeframe) * 1000)
        now = int(self.exchange.milliseconds())
        df = df[df["timestamp"] + tf_ms <= now].reset_index(drop=True)
        if len(df) < 80:
            raise RuntimeError(f"Not enough CLOSED {timeframe} candles for {symbol}: {len(df)}")
        return df

    def current_price(self, symbol: str) -> float:
        ticker = self._retry(self.exchange.fetch_ticker, symbol)
        bid, ask, last = ticker.get("bid"), ticker.get("ask"), ticker.get("last")
        if bid and ask:
            return (float(bid) + float(ask)) / 2.0
        if last:
            return float(last)
        raise RuntimeError(f"No usable ticker price for {symbol}")


class MultiFactorEngine:
    """20 equal-weight directional checks => score is consensus, not win probability."""

    def __init__(self, cfg: Config):
        self.cfg = cfg

    @staticmethod
    def _add_indicators(df: pd.DataFrame) -> pd.DataFrame:
        x = df.copy()
        x["ema20"] = ta.ema(x["close"], length=20)
        x["ema50"] = ta.ema(x["close"], length=50)
        x["rsi14"] = ta.rsi(x["close"], length=14)
        x["atr14"] = ta.atr(x["high"], x["low"], x["close"], length=14)
        x["vma20"] = x["volume"].rolling(20).mean()
        bb = ta.bbands(x["close"], length=20, std=2.0)
        if bb is None or bb.empty:
            raise RuntimeError("pandas-ta Bollinger Bands failed")
        low_col = next(c for c in bb.columns if c.startswith("BBL_"))
        mid_col = next(c for c in bb.columns if c.startswith("BBM_"))
        up_col = next(c for c in bb.columns if c.startswith("BBU_"))
        x["bb_lower"], x["bb_mid"], x["bb_upper"] = bb[low_col], bb[mid_col], bb[up_col]
        x["bb_width"] = (x["bb_upper"] - x["bb_lower"]) / x["bb_mid"].replace(0, math.nan)
        return x.dropna().reset_index(drop=True)

    @staticmethod
    def _count(votes: Iterable[bool]) -> int:
        return sum(1 for x in votes if bool(x))

    def analyze(self, public_label: str, df15: pd.DataFrame, df1h: pd.DataFrame, equity: float) -> Optional[TradeSignal]:
        a = self._add_indicators(df15)
        h = self._add_indicators(df1h)
        if len(a) < 30 or len(h) < 30:
            return None
        cur, prev = a.iloc[-1], a.iloc[-2]
        hcur = h.iloc[-1]

        atr_pct = float(cur.atr14 / max(cur.close, 1e-12))
        dynamic_shift = min(10.0, atr_pct * 300.0)
        rsi_upper, rsi_lower = 70.0 + dynamic_shift, 30.0 - dynamic_shift
        candle_range = max(float(cur.high - cur.low), 1e-12)
        body = float(cur.close - cur.open)
        recent_low = float(a.low.iloc[-9:-1].min())
        prior_low = float(a.low.iloc[-17:-9].min())
        recent_high = float(a.high.iloc[-9:-1].max())
        prior_high = float(a.high.iloc[-17:-9].max())
        recent_5_high = float(a.high.iloc[-6:-1].max())
        recent_5_low = float(a.low.iloc[-6:-1].min())

        buy = [
            cur.ema20 > cur.ema50,
            cur.close > cur.ema20,
            cur.ema20 > a.ema20.iloc[-4],
            hcur.ema20 > hcur.ema50,
            hcur.close > hcur.ema20,
            hcur.ema20 > h.ema20.iloc[-3],
            cur.rsi14 >= 50,
            cur.rsi14 > prev.rsi14,
            cur.rsi14 < rsi_upper,
            (prev.rsi14 <= 45 < cur.rsi14) or (prev.rsi14 <= rsi_lower + 5 and cur.rsi14 > prev.rsi14),
            cur.close > cur.bb_mid,
            cur.bb_width > prev.bb_width,
            (cur.close >= cur.bb_upper) or (cur.close > cur.bb_mid and cur.close > prev.close),
            candle_range >= 0.8 * cur.atr14 and body > 0,
            cur.volume > cur.vma20,
            cur.volume > 1.15 * cur.vma20,
            body > 0 and abs(body) / candle_range > 0.35,
            recent_low >= prior_low,
            cur.close > recent_5_high or cur.close > cur.ema20,
            0.0005 < atr_pct < 0.08,
        ]
        sell = [
            cur.ema20 < cur.ema50,
            cur.close < cur.ema20,
            cur.ema20 < a.ema20.iloc[-4],
            hcur.ema20 < hcur.ema50,
            hcur.close < hcur.ema20,
            hcur.ema20 < h.ema20.iloc[-3],
            cur.rsi14 <= 50,
            cur.rsi14 < prev.rsi14,
            cur.rsi14 > rsi_lower,
            (prev.rsi14 >= 55 > cur.rsi14) or (prev.rsi14 >= rsi_upper - 5 and cur.rsi14 < prev.rsi14),
            cur.close < cur.bb_mid,
            cur.bb_width > prev.bb_width,
            (cur.close <= cur.bb_lower) or (cur.close < cur.bb_mid and cur.close < prev.close),
            candle_range >= 0.8 * cur.atr14 and body < 0,
            cur.volume > cur.vma20,
            cur.volume > 1.15 * cur.vma20,
            body < 0 and abs(body) / candle_range > 0.35,
            recent_high <= prior_high,
            cur.close < recent_5_low or cur.close < cur.ema20,
            0.0005 < atr_pct < 0.08,
        ]

        buy_votes, sell_votes = self._count(buy), self._count(sell)
        if buy_votes >= sell_votes + 2:
            side, votes, against = "BUY", buy_votes, sell_votes
        elif sell_votes >= buy_votes + 2:
            side, votes, against = "SELL", sell_votes, buy_votes
        else:
            return None

        confidence = round(votes / 20 * 100)
        if confidence < self.cfg.confidence_threshold:
            return None

        entry, atr = float(cur.close), float(cur.atr14)
        atr_distance = self.cfg.atr_stop_mult * atr
        swing_buffer = 0.10 * atr
        if side == "BUY":
            swing_stop = float(a.low.iloc[-11:].min()) - swing_buffer
            stop = min(entry - atr_distance, swing_stop)
        else:
            swing_stop = float(a.high.iloc[-11:].max()) + swing_buffer
            stop = max(entry + atr_distance, swing_stop)

        risk_per_unit = abs(entry - stop)
        if risk_per_unit <= 0 or not math.isfinite(risk_per_unit):
            return None
        take_profit = entry + self.cfg.rr * risk_per_unit if side == "BUY" else entry - self.cfg.rr * risk_per_unit
        trailing_trigger = entry + risk_per_unit if side == "BUY" else entry - risk_per_unit
        risk_budget = max(0.0, equity) * self.cfg.risk_pct
        position_size = risk_budget / risk_per_unit
        return TradeSignal(
            symbol=public_label,
            side=side,
            entry=entry,
            stop_loss=stop,
            take_profit=take_profit,
            trailing_trigger=trailing_trigger,
            confidence=confidence,
            votes_for=votes,
            votes_against=against,
            risk_reward=self.cfg.rr,
            risk_per_unit=risk_per_unit,
            risk_budget=risk_budget,
            position_size=position_size,
            atr=atr,
            candle_timestamp=int(cur.timestamp),
            rationale=f"{votes}/20 checks agree across 15m/1h trend, ATR-adjusted RSI, Bollinger volatility, volume and structure.",
        )


class PaperBroker:
    def __init__(self, path: str, starting_equity: float):
        self.path = Path(path)
        self.equity = starting_equity
        self.positions: Dict[str, PaperPosition] = {}
        self.last_signal_candle: Dict[str, int] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.exists():
            return
        try:
            data = json.loads(self.path.read_text())
            self.equity = float(data.get("equity", self.equity))
            self.last_signal_candle = {k: int(v) for k, v in data.get("last_signal_candle", {}).items()}
            self.positions = {k: PaperPosition(**v) for k, v in data.get("positions", {}).items()}
        except Exception as exc:
            LOG.warning("Could not load paper state: %s", exc)

    def save(self) -> None:
        tmp = self.path.with_suffix(self.path.suffix + ".tmp")
        payload = {
            "equity": self.equity,
            "last_signal_candle": self.last_signal_candle,
            "positions": {k: asdict(v) for k, v in self.positions.items()},
        }
        tmp.write_text(json.dumps(payload, indent=2, sort_keys=True))
        tmp.replace(self.path)

    def can_alert(self, symbol: str, candle_ts: int) -> bool:
        return candle_ts > self.last_signal_candle.get(symbol, 0)

    def mark_alerted(self, symbol: str, candle_ts: int) -> None:
        self.last_signal_candle[symbol] = candle_ts
        self.save()

    def open_if_flat(self, s: TradeSignal) -> None:
        if s.symbol in self.positions:
            return
        self.positions[s.symbol] = PaperPosition(
            symbol=s.symbol, side=s.side, entry=s.entry, stop_loss=s.stop_loss, take_profit=s.take_profit,
            original_risk=s.risk_per_unit, qty=s.position_size, opened_at=s.candle_timestamp,
        )
        self.save()

    def update_position(self, symbol: str, price: float, atr: float) -> Optional[str]:
        p = self.positions.get(symbol)
        if not p:
            return None
        if p.side == "BUY":
            if price >= p.take_profit:
                pnl = (p.take_profit - p.entry) * p.qty; return self._close(symbol, pnl, "TP")
            if price <= p.stop_loss:
                pnl = (p.stop_loss - p.entry) * p.qty; return self._close(symbol, pnl, "SL")
            if price >= p.entry + p.original_risk:
                p.trailing_active = True
                p.stop_loss = max(p.stop_loss, p.entry, price - atr)
        else:
            if price <= p.take_profit:
                pnl = (p.entry - p.take_profit) * p.qty; return self._close(symbol, pnl, "TP")
            if price >= p.stop_loss:
                pnl = (p.entry - p.stop_loss) * p.qty; return self._close(symbol, pnl, "SL")
            if price <= p.entry - p.original_risk:
                p.trailing_active = True
                p.stop_loss = min(p.stop_loss, p.entry, price + atr)
        self.save()
        return None

    def _close(self, symbol: str, pnl: float, reason: str) -> str:
        self.equity += pnl
        self.positions.pop(symbol, None)
        self.save()
        return f"{symbol} paper {reason}: P/L {pnl:+.2f} USDT | equity {self.equity:.2f}"


class TelegramNotifier:
    def __init__(self, cfg: Config):
        self.bot = Bot(cfg.telegram_token) if cfg.telegram_token else None
        self.chat_id = cfg.telegram_chat_id

    @property
    def enabled(self) -> bool:
        return bool(self.bot and self.chat_id)

    async def send_signal(self, s: TradeSignal) -> None:
        if not self.enabled:
            return
        msg = (
            f"📊 {s.symbol} {s.side}\n"
            f"Entry: {s.entry:.8g}\nSL: {s.stop_loss:.8g}\nTP: {s.take_profit:.8g}\n"
            f"R:R: 1:{s.risk_reward:.2f}\nConfidence: {s.confidence}% ({s.votes_for}/20)\n"
            f"2% paper risk: {s.risk_budget:.2f} USDT | size: {s.position_size:.8g} units\n"
            f"Trail activates at 1R: {s.trailing_trigger:.8g}\n{s.rationale}\n"
            "Signal/paper mode only — no order was placed."
        )
        await self.bot.send_message(chat_id=self.chat_id, text=msg)

    async def send_text(self, text: str) -> None:
        if self.enabled:
            await self.bot.send_message(chat_id=self.chat_id, text=text)


class SignalService:
    def __init__(self, cfg: Config):
        cfg.validate()
        self.cfg = cfg
        self.client = ExchangeClient(cfg)
        self.engine = MultiFactorEngine(cfg)
        self.paper = PaperBroker(cfg.state_file, cfg.paper_equity)
        self.telegram = TelegramNotifier(cfg)
        self.stop_requested = False

    def request_stop(self, *_):
        self.stop_requested = True

    async def scan_once(self) -> None:
        if not self.client.symbols:
            raise RuntimeError("None of the requested markets are available on the configured exchange")
        for public_label, exchange_symbol in self.client.symbols.items():
            try:
                df15 = await asyncio.to_thread(self.client.fetch_closed_ohlcv, exchange_symbol, self.cfg.timeframe, self.cfg.candle_limit_15m)
                df1h = await asyncio.to_thread(self.client.fetch_closed_ohlcv, exchange_symbol, self.cfg.higher_timeframe, self.cfg.candle_limit_1h)
                signal_obj = self.engine.analyze(public_label, df15, df1h, self.paper.equity)

                enriched = self.engine._add_indicators(df15)
                atr = float(enriched.iloc[-1].atr14)
                current = await asyncio.to_thread(self.client.current_price, exchange_symbol)
                paper_event = self.paper.update_position(public_label, current, atr)
                if paper_event:
                    LOG.info(paper_event)
                    await self.telegram.send_text(paper_event)

                if signal_obj is None or not self.paper.can_alert(public_label, signal_obj.candle_timestamp):
                    continue

                slippage_bps = abs(current - signal_obj.entry) / max(signal_obj.entry, 1e-12) * 10_000
                if slippage_bps > self.cfg.max_slippage_bps:
                    LOG.info("Skip stale %s signal: slippage %.1f bps > %.1f", public_label, slippage_bps, self.cfg.max_slippage_bps)
                    self.paper.mark_alerted(public_label, signal_obj.candle_timestamp)
                    continue

                LOG.info("SIGNAL %s", json.dumps(asdict(signal_obj), sort_keys=True))
                await self.telegram.send_signal(signal_obj)
                self.paper.open_if_flat(signal_obj)
                self.paper.mark_alerted(public_label, signal_obj.candle_timestamp)
            except (ccxt.BaseError, RuntimeError, ValueError, OSError) as exc:
                LOG.exception("Scan failed for %s (%s): %s", public_label, exchange_symbol, exc)

    async def run_forever(self) -> None:
        LOG.info("Starting signal-only service on %s. Resolved symbols: %s", self.cfg.exchange_id, self.client.symbols)
        while not self.stop_requested:
            await self.scan_once()
            for _ in range(self.cfg.poll_seconds):
                if self.stop_requested:
                    break
                await asyncio.sleep(1)


def configure_logging() -> None:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
    )


async def amain() -> None:
    configure_logging()
    cfg = Config()
    service = SignalService(cfg)
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, service.request_stop)
        except NotImplementedError:
            pass
    await service.run_forever()


if __name__ == "__main__":
    asyncio.run(amain())
