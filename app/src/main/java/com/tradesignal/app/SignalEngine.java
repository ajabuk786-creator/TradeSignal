package com.tradesignal.app;

import java.util.*;

public final class SignalEngine {
    private SignalEngine() {}

    public static final class Candle {
        public final long openTime, closeTime;
        public final double open, high, low, close, volume;
        public Candle(long openTime, double open, double high, double low, double close, double volume, long closeTime) {
            this.openTime = openTime; this.open = open; this.high = high; this.low = low;
            this.close = close; this.volume = volume; this.closeTime = closeTime;
        }
    }

    public static final class Signal {
        public final String side;
        public final double entry, stopLoss, takeProfit, riskPerUnit, qtyAt2Pct, trailingTrigger;
        public final int confidence, votesFor, votesAgainst;
        public final String rationale;
        public final long candleCloseTime;
        public Signal(String side, double entry, double stopLoss, double takeProfit, double riskPerUnit,
                      double qtyAt2Pct, double trailingTrigger, int confidence, int votesFor, int votesAgainst,
                      String rationale, long candleCloseTime) {
            this.side = side; this.entry = entry; this.stopLoss = stopLoss; this.takeProfit = takeProfit;
            this.riskPerUnit = riskPerUnit; this.qtyAt2Pct = qtyAt2Pct; this.trailingTrigger = trailingTrigger;
            this.confidence = confidence; this.votesFor = votesFor; this.votesAgainst = votesAgainst;
            this.rationale = rationale; this.candleCloseTime = candleCloseTime;
        }
    }

    public static Signal analyze(List<Candle> m15, List<Candle> h1, double paperEquity, int thresholdPct) {
        if (m15 == null || h1 == null || m15.size() < 80 || h1.size() < 80) return null;
        int i = m15.size() - 1;
        int p = i - 1;
        int h = h1.size() - 1;

        double[] c15 = series(m15, 'c'), o15 = series(m15, 'o'), hi15 = series(m15, 'h'), lo15 = series(m15, 'l'), v15 = series(m15, 'v');
        double[] c1h = series(h1, 'c'), hi1h = series(h1, 'h'), lo1h = series(h1, 'l');
        double[] e20 = ema(c15, 20), e50 = ema(c15, 50), e20h = ema(c1h, 20), e50h = ema(c1h, 50);
        double[] rsi = rsi(c15, 14), atr = atr(hi15, lo15, c15, 14), atr1h = atr(hi1h, lo1h, c1h, 14);
        double[] vma = sma(v15, 20), bbMid = sma(c15, 20), bbStd = rollingStd(c15, 20);

        if (!finite(e20[i], e50[i], e20h[h], e50h[h], rsi[i], atr[i], vma[i], bbMid[i], bbStd[i], atr1h[h])) return null;

        double upper = bbMid[i] + 2.0 * bbStd[i];
        double lower = bbMid[i] - 2.0 * bbStd[i];
        double prevUpper = bbMid[p] + 2.0 * bbStd[p];
        double prevLower = bbMid[p] - 2.0 * bbStd[p];
        double width = (upper - lower) / Math.max(bbMid[i], 1e-12);
        double prevWidth = (prevUpper - prevLower) / Math.max(bbMid[p], 1e-12);
        double atrPct = atr[i] / Math.max(c15[i], 1e-12);
        double dynamicShift = Math.min(10.0, atrPct * 300.0);
        double rsiUpper = 70.0 + dynamicShift;
        double rsiLower = 30.0 - dynamicShift;
        double body = c15[i] - o15[i];
        double range = Math.max(hi15[i] - lo15[i], 1e-12);

        boolean[] buy = new boolean[20];
        boolean[] sell = new boolean[20];

        buy[0] = e20[i] > e50[i];                         sell[0] = e20[i] < e50[i];
        buy[1] = c15[i] > e20[i];                        sell[1] = c15[i] < e20[i];
        buy[2] = e20[i] > e20[Math.max(0, i - 3)];       sell[2] = e20[i] < e20[Math.max(0, i - 3)];
        buy[3] = e20h[h] > e50h[h];                      sell[3] = e20h[h] < e50h[h];
        buy[4] = c1h[h] > e20h[h];                       sell[4] = c1h[h] < e20h[h];
        buy[5] = e20h[h] > e20h[Math.max(0, h - 2)];     sell[5] = e20h[h] < e20h[Math.max(0, h - 2)];

        buy[6] = rsi[i] >= 50.0;                         sell[6] = rsi[i] <= 50.0;
        buy[7] = rsi[i] > rsi[p];                        sell[7] = rsi[i] < rsi[p];
        buy[8] = rsi[i] < rsiUpper;                      sell[8] = rsi[i] > rsiLower;
        buy[9] = rsi[p] <= 45.0 && rsi[i] > 45.0 || rsi[p] <= rsiLower + 5.0 && rsi[i] > rsi[p];
        sell[9] = rsi[p] >= 55.0 && rsi[i] < 55.0 || rsi[p] >= rsiUpper - 5.0 && rsi[i] < rsi[p];

        buy[10] = c15[i] > bbMid[i];                     sell[10] = c15[i] < bbMid[i];
        buy[11] = width > prevWidth;                     sell[11] = width > prevWidth;
        buy[12] = c15[i] >= upper || (c15[i] > bbMid[i] && c15[i] > c15[p]);
        sell[12] = c15[i] <= lower || (c15[i] < bbMid[i] && c15[i] < c15[p]);
        buy[13] = range >= 0.8 * atr[i] && body > 0;      sell[13] = range >= 0.8 * atr[i] && body < 0;

        buy[14] = v15[i] > vma[i];                       sell[14] = v15[i] > vma[i];
        buy[15] = v15[i] > 1.15 * vma[i];                sell[15] = v15[i] > 1.15 * vma[i];
        buy[16] = body > 0 && Math.abs(body) / range > 0.35;
        sell[16] = body < 0 && Math.abs(body) / range > 0.35;

        double recentLow = min(lo15, Math.max(0, i - 8), i - 1);
        double priorLow = min(lo15, Math.max(0, i - 16), Math.max(0, i - 9));
        double recentHigh = max(hi15, Math.max(0, i - 8), i - 1);
        double priorHigh = max(hi15, Math.max(0, i - 16), Math.max(0, i - 9));
        buy[17] = recentLow >= priorLow;                  sell[17] = recentHigh <= priorHigh;
        buy[18] = c15[i] > max(hi15, Math.max(0, i - 5), i - 1) || c15[i] > e20[i];
        sell[18] = c15[i] < min(lo15, Math.max(0, i - 5), i - 1) || c15[i] < e20[i];
        buy[19] = atrPct > 0.0005 && atrPct < 0.08 && atr1h[h] > 0;
        sell[19] = buy[19];

        int buyVotes = count(buy), sellVotes = count(sell);
        String side;
        int votes, against;
        if (buyVotes >= sellVotes + 2) { side = "BUY"; votes = buyVotes; against = sellVotes; }
        else if (sellVotes >= buyVotes + 2) { side = "SELL"; votes = sellVotes; against = buyVotes; }
        else return null;

        int confidence = (int)Math.round(votes * 100.0 / 20.0);
        if (confidence < thresholdPct) return null;

        double entry = c15[i];
        double atrDistance = 1.5 * atr[i];
        double swingBuffer = 0.10 * atr[i];
        double sl;
        if ("BUY".equals(side)) {
            double swingStop = min(lo15, Math.max(0, i - 10), i) - swingBuffer;
            sl = Math.min(entry - atrDistance, swingStop);
        } else {
            double swingStop = max(hi15, Math.max(0, i - 10), i) + swingBuffer;
            sl = Math.max(entry + atrDistance, swingStop);
        }
        double risk = Math.abs(entry - sl);
        if (risk <= 0 || !Double.isFinite(risk)) return null;
        double tp = "BUY".equals(side) ? entry + 2.0 * risk : entry - 2.0 * risk;
        double trailTrigger = "BUY".equals(side) ? entry + risk : entry - risk;
        double riskBudget = Math.max(0.0, paperEquity) * 0.02;
        double qty = riskBudget / risk;
        String rationale = votes + "/20 factors agree: 15m + 1h trend, ATR-adjusted RSI, Bollinger/volatility, volume and structure.";
        return new Signal(side, entry, sl, tp, risk, qty, trailTrigger, confidence, votes, against, rationale, m15.get(i).closeTime);
    }

    private static int count(boolean[] a) { int n = 0; for (boolean x : a) if (x) n++; return n; }
    private static boolean finite(double... xs) { for (double x : xs) if (!Double.isFinite(x)) return false; return true; }

    private static double[] series(List<Candle> c, char field) {
        double[] out = new double[c.size()];
        for (int i = 0; i < c.size(); i++) {
            Candle x = c.get(i);
            switch (field) {
                case 'o': out[i] = x.open; break;
                case 'h': out[i] = x.high; break;
                case 'l': out[i] = x.low; break;
                case 'v': out[i] = x.volume; break;
                default: out[i] = x.close;
            }
        }
        return out;
    }

    private static double[] ema(double[] v, int p) {
        double[] out = new double[v.length]; Arrays.fill(out, Double.NaN);
        if (v.length < p) return out;
        double sum = 0; for (int i = 0; i < p; i++) sum += v[i];
        double cur = sum / p; out[p - 1] = cur; double k = 2.0 / (p + 1.0);
        for (int i = p; i < v.length; i++) { cur = (v[i] - cur) * k + cur; out[i] = cur; }
        return out;
    }

    private static double[] sma(double[] v, int p) {
        double[] out = new double[v.length]; Arrays.fill(out, Double.NaN);
        if (v.length < p) return out;
        double sum = 0; for (int i = 0; i < v.length; i++) {
            sum += v[i]; if (i >= p) sum -= v[i - p]; if (i >= p - 1) out[i] = sum / p;
        }
        return out;
    }

    private static double[] rollingStd(double[] v, int p) {
        double[] out = new double[v.length]; Arrays.fill(out, Double.NaN);
        if (v.length < p) return out;
        for (int i = p - 1; i < v.length; i++) {
            double m = 0; for (int j = i - p + 1; j <= i; j++) m += v[j]; m /= p;
            double s = 0; for (int j = i - p + 1; j <= i; j++) { double d = v[j] - m; s += d * d; }
            out[i] = Math.sqrt(s / p);
        }
        return out;
    }

    private static double[] rsi(double[] v, int p) {
        double[] out = new double[v.length]; Arrays.fill(out, Double.NaN);
        if (v.length <= p) return out;
        double gain = 0, loss = 0;
        for (int i = 1; i <= p; i++) { double d = v[i] - v[i - 1]; gain += Math.max(d, 0); loss += Math.max(-d, 0); }
        gain /= p; loss /= p; out[p] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss);
        for (int i = p + 1; i < v.length; i++) {
            double d = v[i] - v[i - 1]; gain = (gain * (p - 1) + Math.max(d, 0)) / p; loss = (loss * (p - 1) + Math.max(-d, 0)) / p;
            out[i] = loss == 0 ? 100 : 100 - 100 / (1 + gain / loss);
        }
        return out;
    }

    private static double[] atr(double[] h, double[] l, double[] c, int p) {
        double[] out = new double[c.length]; Arrays.fill(out, Double.NaN);
        if (c.length <= p) return out;
        double[] tr = new double[c.length]; tr[0] = h[0] - l[0];
        for (int i = 1; i < c.length; i++) tr[i] = Math.max(h[i] - l[i], Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
        double cur = 0; for (int i = 1; i <= p; i++) cur += tr[i]; cur /= p; out[p] = cur;
        for (int i = p + 1; i < c.length; i++) { cur = (cur * (p - 1) + tr[i]) / p; out[i] = cur; }
        return out;
    }

    private static double min(double[] a, int from, int to) {
        from = Math.max(0, from); to = Math.min(a.length - 1, to); if (from > to) return a[Math.max(0, Math.min(a.length - 1, to))];
        double x = Double.POSITIVE_INFINITY; for (int i = from; i <= to; i++) x = Math.min(x, a[i]); return x;
    }
    private static double max(double[] a, int from, int to) {
        from = Math.max(0, from); to = Math.min(a.length - 1, to); if (from > to) return a[Math.max(0, Math.min(a.length - 1, to))];
        double x = Double.NEGATIVE_INFINITY; for (int i = from; i <= to; i++) x = Math.max(x, a[i]); return x;
    }
}
