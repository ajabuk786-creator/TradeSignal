package com.tradesignal.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

public final class TradeStore {
    private TradeStore() {}
    private static final String PREFS = "trade_signal";
    private static final long MAX_HOLD_MS = 60L * 60L * 1000L;
    private static final long COOLDOWN_MS = 5L * 60L * 1000L;

    public static final class ActiveTrade {
        public final String marketId, side, setupLabel;
        public final double entry, stopLoss, takeProfit, originalRisk, qty, trailingTrigger;
        public final int confidence;
        public final long openedAt;
        public final boolean trailingActive;

        public ActiveTrade(String marketId, String side, String setupLabel, double entry, double stopLoss,
                           double takeProfit, double originalRisk, double qty, double trailingTrigger,
                           int confidence, long openedAt, boolean trailingActive) {
            this.marketId = marketId; this.side = side; this.setupLabel = setupLabel;
            this.entry = entry; this.stopLoss = stopLoss; this.takeProfit = takeProfit;
            this.originalRisk = originalRisk; this.qty = qty; this.trailingTrigger = trailingTrigger;
            this.confidence = confidence; this.openedAt = openedAt; this.trailingActive = trailingActive;
        }
    }

    public static final class Update {
        public final ActiveTrade trade;
        public final String event;
        public final boolean closed;
        public final double exitPrice;
        public Update(ActiveTrade trade, String event, boolean closed, double exitPrice) {
            this.trade = trade; this.event = event; this.closed = closed; this.exitPrice = exitPrice;
        }
    }

    public static ActiveTrade load(Context ctx, String marketId) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(key(marketId, "active"), false)) return null;
        return new ActiveTrade(
                marketId,
                p.getString(key(marketId, "side"), ""),
                p.getString(key(marketId, "setup"), ""),
                getDouble(p, key(marketId, "entry"), 0),
                getDouble(p, key(marketId, "sl"), 0),
                getDouble(p, key(marketId, "tp"), 0),
                getDouble(p, key(marketId, "risk"), 0),
                getDouble(p, key(marketId, "qty"), 0),
                getDouble(p, key(marketId, "trigger"), 0),
                p.getInt(key(marketId, "confidence"), 0),
                p.getLong(key(marketId, "opened"), 0L),
                p.getBoolean(key(marketId, "trail"), false)
        );
    }

    public static boolean canOpen(Context ctx, String marketId, long signalCandleTs) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(key(marketId, "active"), false)) return false;
        if (System.currentTimeMillis() < p.getLong(key(marketId, "cooldown"), 0L)) return false;
        return signalCandleTs > p.getLong(key(marketId, "last_entry_candle"), 0L);
    }

    public static void open(Context ctx, String marketId, SignalEngine.Signal s) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .putBoolean(key(marketId, "active"), true)
                .putString(key(marketId, "side"), s.side)
                .putString(key(marketId, "setup"), s.confirmationLabel)
                .putLong(key(marketId, "entry"), Double.doubleToRawLongBits(s.entry))
                .putLong(key(marketId, "sl"), Double.doubleToRawLongBits(s.stopLoss))
                .putLong(key(marketId, "tp"), Double.doubleToRawLongBits(s.takeProfit))
                .putLong(key(marketId, "risk"), Double.doubleToRawLongBits(s.riskPerUnit))
                .putLong(key(marketId, "qty"), Double.doubleToRawLongBits(s.qtyAt2Pct))
                .putLong(key(marketId, "trigger"), Double.doubleToRawLongBits(s.trailingTrigger))
                .putInt(key(marketId, "confidence"), s.confidence)
                .putLong(key(marketId, "opened"), s.candleCloseTime)
                .putLong(key(marketId, "last_entry_candle"), s.candleCloseTime)
                .putBoolean(key(marketId, "trail"), false)
                .apply();
    }

    public static boolean inCooldown(Context ctx, String marketId, long now) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return now < p.getLong(key(marketId, "cooldown"), 0L);
    }

    public static String lastResult(Context ctx, String marketId) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(marketId, "last_result"), "");
    }

    public static Update update(Context ctx, String marketId, List<SignalEngine.Candle> m5) {
        ActiveTrade t = load(ctx, marketId);
        if (t == null || m5 == null || m5.isEmpty()) return new Update(t, "", false, 0);

        double sl = t.stopLoss;
        boolean trail = t.trailingActive;
        double atr = atr(m5, 14);
        SignalEngine.Candle latest = m5.get(m5.size() - 1);

        for (SignalEngine.Candle c : m5) {
            if (c.closeTime <= t.openedAt) continue;

            // Conservative paper handling: if one 5m candle touches both stop and target, stop is counted first.
            if ("BUY".equals(t.side)) {
                if (c.low <= sl) return close(ctx, t, sl, "STOP LOSS");
                if (c.high >= t.takeProfit) return close(ctx, t, t.takeProfit, "TAKE PROFIT");
                if (c.high >= t.trailingTrigger) {
                    trail = true;
                    sl = Math.max(sl, t.entry + 0.05 * t.originalRisk);
                }
                if (trail && Double.isFinite(atr) && atr > 0) sl = Math.max(sl, c.close - 0.80 * atr);
            } else {
                if (c.high >= sl) return close(ctx, t, sl, "STOP LOSS");
                if (c.low <= t.takeProfit) return close(ctx, t, t.takeProfit, "TAKE PROFIT");
                if (c.low <= t.trailingTrigger) {
                    trail = true;
                    sl = Math.min(sl, t.entry - 0.05 * t.originalRisk);
                }
                if (trail && Double.isFinite(atr) && atr > 0) sl = Math.min(sl, c.close + 0.80 * atr);
            }
        }

        if (latest.closeTime - t.openedAt >= MAX_HOLD_MS) {
            return close(ctx, t, latest.close, "TIME EXIT (60m max scalp hold)");
        }

        if (sl != t.stopLoss || trail != t.trailingActive) {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putLong(key(marketId, "sl"), Double.doubleToRawLongBits(sl))
                    .putBoolean(key(marketId, "trail"), trail).apply();
            t = new ActiveTrade(t.marketId, t.side, t.setupLabel, t.entry, sl, t.takeProfit,
                    t.originalRisk, t.qty, t.trailingTrigger, t.confidence, t.openedAt, trail);
        }
        return new Update(t, trail ? "Trailing protection active" : "Trade active", false, latest.close);
    }

    private static Update close(Context ctx, ActiveTrade t, double exit, String reason) {
        double pnlPerUnit = "BUY".equals(t.side) ? exit - t.entry : t.entry - exit;
        double pnl = pnlPerUnit * t.qty;
        String result = reason + " @ " + format(exit) + " · paper P/L " + (pnl >= 0 ? "+" : "") + String.format(Locale.US, "%.2f", pnl);
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .putBoolean(key(t.marketId, "active"), false)
                .putString(key(t.marketId, "last_result"), result)
                .putLong(key(t.marketId, "cooldown"), System.currentTimeMillis() + COOLDOWN_MS)
                .apply();
        return new Update(null, result, true, exit);
    }

    private static double atr(List<SignalEngine.Candle> c, int period) {
        if (c.size() <= period) return Double.NaN;
        int start = Math.max(1, c.size() - period);
        double sum = 0; int n = 0;
        for (int i = start; i < c.size(); i++) {
            SignalEngine.Candle cur = c.get(i), prev = c.get(i - 1);
            double tr = Math.max(cur.high - cur.low, Math.max(Math.abs(cur.high - prev.close), Math.abs(cur.low - prev.close)));
            sum += tr; n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static String key(String marketId, String suffix) { return "trade_" + marketId + "_" + suffix; }
    private static double getDouble(SharedPreferences p, String key, double def) {
        return Double.longBitsToDouble(p.getLong(key, Double.doubleToRawLongBits(def)));
    }
    private static String format(double x) {
        return x >= 100 ? String.format(Locale.US, "%.2f", x) : x >= 1 ? String.format(Locale.US, "%.4f", x) : String.format(Locale.US, "%.6f", x);
    }
}
