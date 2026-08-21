# TradeSignal Android v2

A small Android signal assistant that keeps the screen simple while running a broader rule engine underneath.

## Markets and timeframes
- BTCUSDT: Binance public candles
- XRPUSDT: Binance public candles
- XAUUSD: Twelve Data intraday candles (API key required in DATA KEYS)
- 5 minute and 15 minute

## Confluence engine
The score combines EMA trend, 15m alignment for 5m setups, structure/BOS, liquidity sweeps, fair value gaps, order blocks, supply/demand, support/resistance, premium/discount, OTE retracement, AMD-style accumulation/manipulation/distribution, RSI/ATR, rejection candles, volume/volatility breakout and an optional Trading Economics high-impact US news guard.

Signals require a high score and directional margin. SL is structure/ATR based and TP targets approximately 2R unless nearby liquidity provides a suitable target. The app never places trades automatically.

## Background mode
Tap START BACKGROUND in the app. Android runs a user-enabled foreground service that scans BTC, XRP and XAU on 5m and 15m and sends Entry / SL / TP notifications for qualifying closed-candle setups. Android displays a small persistent monitoring notification while this service is running.

## Data keys
BTC/XRP require no API key. XAUUSD requires a Twelve Data API key. Trading Economics is optional and is only used to block entries around high-impact US calendar events.

## Important
No strategy can guarantee profitable or perfect entries. Backtest and demo-test the rules before risking real money.
