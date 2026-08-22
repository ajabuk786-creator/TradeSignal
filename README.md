# TradeSignal Pro

Android signal-only / paper-trading app plus a Python quantitative signal engine.

## Android APK — v3 scalp mode

The Android app scans **BTC/USDT**, **XRP/USDT**, and **XAU/USDT** (using PAXG/USDT as the gold market-data proxy when exact XAU/USDT is unavailable).

The v3 Android signal flow is staged:

1. **1h context + 15m setup** establish direction and reject mixed conditions.
2. **5m closed-candle confirmation** must then validate the actual entry.
3. The 5m trigger can be a volume-confirmed breakout, breakout/retest, Bollinger squeeze expansion, or a rule-based AMD sequence: accumulation range → liquidity sweep/manipulation → distribution breakout.
4. CMF, OBV, RSI, EMA9/20, volume/VMA, ATR, candle body and over-extension checks must support the trigger.
5. A confirmed trade becomes **ACTIVE** and is locked. Later neutral candles do not erase it.
6. The active trade remains until **TP, SL, trailing stop, or a 60-minute scalp time exit**.
7. After an exit, the app waits one 5m candle before another trade and will not reuse the same entry candle.

The scalp target is deliberately smaller than the old v2 swing-style 1:2 target: approximately **1.3R–1.5R**, selected from trigger quality. Trailing protection starts around **+0.8R**. Position size still uses at most **2% of paper equity** as the configured risk budget.

The APK never stores exchange API keys and never places real orders.

### Active trade vs current analysis

The app now displays these separately:

- **ACTIVE TRADE** — locked entry, stop, target, size and trailing state.
- **CURRENT MARKET ANALYSIS** — latest 15m/1h setup score, 5m trigger score and AMD state.

A new candle can change CURRENT MARKET ANALYSIS without deleting an already-active trade.

### Background behavior

Android WorkManager performs periodic checks roughly every 15 minutes when networking is available. Because 5m scalp confirmations can occur between background runs, the app also supports manual scanning around each 5m candle close. Stale confirmed entries are rejected if live price has moved too far from the confirmed 5m close.

GitHub Actions automatically builds a debug APK after pushes/PRs. Open **Actions → Build Android APK → latest successful run → TradeSignal-APK**.

## Python signal engine

`backend/signal_engine.py` is the separate server/desktop paper-signal engine using:

- `ccxt` for exchange REST market data
- `pandas` + `pandas-ta` for technical indicators
- `python-telegram-bot` for optional Telegram alerts
- persisted paper-trading state

### Install

```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # Windows: .venv\\Scripts\\activate
pip install -r requirements.txt
```

### Environment variables

```bash
export EXCHANGE_ID=binance
export PAPER_EQUITY=1000
export RISK_PCT=0.02
export CONFIDENCE_THRESHOLD=70
export MAX_SLIPPAGE_BPS=25
export TELEGRAM_BOT_TOKEN=...
export TELEGRAM_CHAT_ID=...
export GOLD_SYMBOL=XAU/USDT
```

Run:

```bash
python signal_engine.py
```

**Confidence is strategy-rule agreement, not a guaranteed win probability.** Judge the strategy from recorded paper results such as win rate, expectancy, drawdown, profit factor, fees/slippage and out-of-sample behavior.
