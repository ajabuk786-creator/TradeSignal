# TradeSignal Pro

Android signal-only / paper-trading app plus a Python quantitative signal engine.

## Android APK

The Android app scans **BTC/USDT**, **XRP/USDT**, and **XAU/USDT** (using PAXG/USDT as a gold market-data proxy when exact XAU/USDT is unavailable). It evaluates only **closed 15-minute candles**, confirms the direction with a **1-hour higher timeframe**, and requires at least **70% (14/20) multi-factor consensus** before showing or notifying a signal.

The APK never stores exchange API keys and never places orders. It provides entry, ATR/swing stop, 1:2 take profit, 2% paper-risk position sizing, confidence, and a 1R trailing-stop trigger. Android WorkManager performs periodic background scans when networking is available.

GitHub Actions automatically builds a debug APK after pushes to `main`. Open **Actions → Build Android APK → latest successful run → TradeSignal-APK**.

## Python signal engine

`backend/signal_engine.py` uses:

- `ccxt` for exchange REST market data
- `pandas` + `pandas-ta` for EMA, RSI, ATR, Bollinger Bands, volume moving average
- `python-telegram-bot` for optional Telegram alerts
- a persisted paper-trading state file with 2% maximum risk sizing and a trailing stop after +1R

### Install

```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\\Scripts\\activate
pip install -r requirements.txt
```

### Environment variables

```bash
export EXCHANGE_ID=binance          # or bybit
export PAPER_EQUITY=1000
export RISK_PCT=0.02
export CONFIDENCE_THRESHOLD=70
export MAX_SLIPPAGE_BPS=25
export TELEGRAM_BOT_TOKEN=...
export TELEGRAM_CHAT_ID=...
# Optional when your exchange lists another gold symbol:
export GOLD_SYMBOL=XAU/USDT
```

Run:

```bash
python signal_engine.py
```

Base `ccxt` uses REST. If you later require exchange WebSockets, add a supported streaming client such as `ccxt.pro`; the signal logic remains the same.

## 20-factor consensus

The score combines 15m EMA direction/price/slope, 1h EMA direction/price/slope, ATR-adjusted RSI conditions, Bollinger position/expansion/breakout, candle range, VMA confirmation, directional candle body, recent swing structure, local breakout context, and an ATR sanity filter.

**Confidence is the percentage of strategy checks agreeing with the direction; it is not a guaranteed win probability.** Measure actual precision, win rate, drawdown, profit factor and out-of-sample performance before relying on any strategy.
