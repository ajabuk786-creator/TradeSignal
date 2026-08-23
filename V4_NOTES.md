# TradeSignal Pro v4

v4 changes the Android app from auto-activating every confirmed signal to a signal-inbox model.

- Confirmed signals are always saved to History with entry, SL, TP, confidence, confirmation type and AMD state.
- A signal becomes ACTIVE only after the user presses TAKE / I'M INTERESTED.
- Fresh signals have a 20-minute take window and live-price/slippage checks to avoid chasing stale entries.
- Active taken trades remain on the main page and show live percentage movement from entry and R progress.
- Untaken signals remain in History and are paper-tracked for TP/SL/time outcome for later strategy evaluation.
- Background work still uses Android WorkManager, but v4 replays up to 12 hours of missed 5-minute candle closes so intermediate scalp signals are not lost if Android delays a periodic job.
- Old v2/v3 unique WorkManager jobs are cancelled during migration.
- The scalp engine keeps 1h context + 15m setup + 5m confirmation, and adds a slightly broader set of confirmed 5m triggers: volume breakout, micro-structure break, breakout retest, EMA pullback continuation, Bollinger squeeze expansion and rule-based AMD sweep/distribution.

Signal-only / paper mode. The APK does not place real orders.
