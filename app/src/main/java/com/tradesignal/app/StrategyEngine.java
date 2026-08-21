package com.tradesignal.app;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StrategyEngine {
    public static final int DEFAULT_THRESHOLD = 70;

    public static class Analysis {
        public final Signal signal;
        public final int buyScore;
        public final int sellScore;
        public final String summary;

        Analysis(Signal signal, int buyScore, int sellScore, String summary) {
            this.signal = signal;
            this.buyScore = buyScore;
            this.sellScore = sellScore;
            this.summary = summary;
        }
    }

    public static Analysis analyze(String symbol, String timeframe, List<Candle> c,
                                   List<Candle> higher, NewsGuard.State news) {
        if (c == null || c.size() < 210) return new Analysis(null, 0, 0, "Need more closed candles");
        int i = c.size() - 1;
        Candle last = c.get(i);
        double[] e20 = ema(c, 20), e50 = ema(c, 50), e200 = ema(c, 200);
        double[] rsi = rsi(c, 14), atr = atr(c, 14);
        double a = atr[i];
        if (Double.isNaN(a) || a <= 0) return new Analysis(null, 0, 0, "ATR unavailable");

        double priorHigh = rangeHigh(c, Math.max(0, i - 20), i - 1);
        double priorLow = rangeLow(c, Math.max(0, i - 20), i - 1);
        double dealHigh = rangeHigh(c, Math.max(0, i - 50), i - 1);
        double dealLow = rangeLow(c, Math.max(0, i - 50), i - 1);
        double midpoint = (dealHigh + dealLow) / 2.0;
        double dealRange = Math.max(1e-9, dealHigh - dealLow);

        boolean trendUp = e20[i] > e50[i] && last.close > e20[i];
        boolean trendDown = e20[i] < e50[i] && last.close < e20[i];
        boolean macroUp = e50[i] > e200[i];
        boolean macroDown = e50[i] < e200[i];
        boolean htfUp = higher == null || higherTrend(higher, true);
        boolean htfDown = higher == null || higherTrend(higher, false);

        boolean bosUp = last.close > priorHigh;
        boolean bosDown = last.close < priorLow;
        boolean sweepLow = bullSweep(c, i);
        boolean sweepHigh = bearSweep(c, i);
        boolean bullFvg = hasBullFvg(c, i, a);
        boolean bearFvg = hasBearFvg(c, i, a);
        boolean bullOb = hasBullOrderBlock(c, i, a);
        boolean bearOb = hasBearOrderBlock(c, i, a);
        boolean nearSupport = Math.abs(last.close - priorLow) <= 0.65 * a || last.low <= priorLow + 0.25 * a;
        boolean nearResistance = Math.abs(last.close - priorHigh) <= 0.65 * a || last.high >= priorHigh - 0.25 * a;
        boolean discount = last.close <= midpoint;
        boolean premium = last.close >= midpoint;

        double bullOteLow = dealHigh - 0.79 * dealRange;
        double bullOteHigh = dealHigh - 0.62 * dealRange;
        boolean bullOte = last.close >= bullOteLow && last.close <= bullOteHigh;
        double bearOteLow = dealLow + 0.62 * dealRange;
        double bearOteHigh = dealLow + 0.79 * dealRange;
        boolean bearOte = last.close >= bearOteLow && last.close <= bearOteHigh;

        double body = Math.abs(last.close - last.open);
        double range = Math.max(1e-9, last.high - last.low);
        boolean displacementUp = last.close > last.open && body >= 1.05 * a && range >= 1.35 * a;
        boolean displacementDown = last.close < last.open && body >= 1.05 * a && range >= 1.35 * a;
        boolean volUp = volumeConfirm(c, i);
        boolean breakoutUp = bosUp && displacementUp && volUp;
        boolean breakoutDown = bosDown && displacementDown && volUp;
        boolean bullReject = lowerWick(last) > Math.max(body, 0.05 * a) * 1.2 && last.close > last.open;
        boolean bearReject = upperWick(last) > Math.max(body, 0.05 * a) * 1.2 && last.close < last.open;
        boolean bullMomentum = rsi[i] >= 45 && rsi[i] <= 68 && rsi[i] > rsi[i - 1];
        boolean bearMomentum = rsi[i] <= 55 && rsi[i] >= 32 && rsi[i] < rsi[i - 1];
        boolean accumulation = compressed(c, i, a);
        boolean amdBull = accumulation && sweepLow && (displacementUp || last.close > e20[i]);
        boolean amdBear = accumulation && sweepHigh && (displacementDown || last.close < e20[i]);
        boolean activeSession = institutionalSession(last.closeTime);

        int buy = 0, sell = 0;
        List<String> br = new ArrayList<>(), sr = new ArrayList<>();

        if (trendUp) { buy += 12; br.add("EMA trend up"); }
        if (trendDown) { sell += 12; sr.add("EMA trend down"); }
        if (macroUp) { buy += 5; br.add("50/200 macro bias"); }
        if (macroDown) { sell += 5; sr.add("50/200 macro bias"); }
        if (htfUp) { buy += 10; br.add("15m aligned"); }
        if (htfDown) { sell += 10; sr.add("15m aligned"); }
        if (bosUp) { buy += 10; br.add("bullish structure break"); }
        if (bosDown) { sell += 10; sr.add("bearish structure break"); }
        if (sweepLow) { buy += 12; br.add("sell-side liquidity sweep"); }
        if (sweepHigh) { sell += 12; sr.add("buy-side liquidity sweep"); }
        if (bullFvg) { buy += 8; br.add("bullish FVG"); }
        if (bearFvg) { sell += 8; sr.add("bearish FVG"); }
        if (bullOb) { buy += 8; br.add("bullish order block/demand"); }
        if (bearOb) { sell += 8; sr.add("bearish order block/supply"); }
        if (nearSupport) { buy += 6; br.add("support/demand reaction"); }
        if (nearResistance) { sell += 6; sr.add("resistance/supply reaction"); }
        if (discount) { buy += 5; br.add("discount pricing"); }
        if (premium) { sell += 5; sr.add("premium pricing"); }
        if (bullOte) { buy += 6; br.add("OTE retracement zone"); }
        if (bearOte) { sell += 6; sr.add("OTE retracement zone"); }
        if (amdBull) { buy += 9; br.add("AMD accumulation→sweep→expansion"); }
        if (amdBear) { sell += 9; sr.add("AMD accumulation→sweep→expansion"); }
        if (bullMomentum) { buy += 5; br.add("RSI confirmation"); }
        if (bearMomentum) { sell += 5; sr.add("RSI confirmation"); }
        if (bullReject) { buy += 5; br.add("bullish rejection"); }
        if (bearReject) { sell += 5; sr.add("bearish rejection"); }
        if (breakoutUp) { buy += 9; br.add("confirmed volatility/news-style breakout"); }
        if (breakoutDown) { sell += 9; sr.add("confirmed volatility/news-style breakout"); }
        if (activeSession) {
            buy += 3; sell += 3;
            br.add("London/NY session"); sr.add("London/NY session");
        }

        buy = Math.min(100, buy);
        sell = Math.min(100, sell);

        if (news != null && news.active) {
            return new Analysis(null, buy, sell, news.label + " — entries blocked around high-impact news");
        }

        boolean wantBuy = buy >= DEFAULT_THRESHOLD && buy - sell >= 12;
        boolean wantSell = sell >= DEFAULT_THRESHOLD && sell - buy >= 12;
        if (!wantBuy && !wantSell) {
            String bias = buy >= sell ? "BUY " + buy + "/100" : "SELL " + sell + "/100";
            return new Analysis(null, buy, sell, "Waiting — best confluence " + bias + ". Need " + DEFAULT_THRESHOLD + "+ and clear directional edge.");
        }

        String side = wantBuy ? "BUY" : "SELL";
        int score = wantBuy ? buy : sell;
        double entry = last.close;
        double anchor;
        double rawRisk;
        if (wantBuy) {
            anchor = rangeLow(c, Math.max(0, i - 14), i);
            rawRisk = entry - anchor + 0.15 * a;
        } else {
            anchor = rangeHigh(c, Math.max(0, i - 14), i);
            rawRisk = anchor - entry + 0.15 * a;
        }
        if (rawRisk > 3.4 * a) {
            return new Analysis(null, buy, sell, "High confluence, but structure-based stop is too wide. No trade.");
        }
        double risk = Math.max(rawRisk, 0.90 * a);
        double sl = wantBuy ? entry - risk : entry + risk;
        double tp = wantBuy ? entry + 2.0 * risk : entry - 2.0 * risk;

        double liquidity = wantBuy
                ? rangeHigh(c, Math.max(0, i - 60), i - 1)
                : rangeLow(c, Math.max(0, i - 60), i - 1);
        double liqR = wantBuy ? (liquidity - entry) / risk : (entry - liquidity) / risk;
        if (liqR >= 1.6 && liqR <= 3.0) tp = liquidity;
        double rr = wantBuy ? (tp - entry) / risk : (entry - tp) / risk;
        if (rr < 1.55) return new Analysis(null, buy, sell, "Setup rejected because available target gives poor risk/reward.");

        List<String> reasons = wantBuy ? br : sr;
        String reason = "Confluence " + score + "/100: " + joinTop(reasons, 7);
        if (news != null && news.available) reason += ". " + news.label;
        Signal signal = new Signal(symbol, timeframe, side, entry, sl, tp, rr, score, reason, last.closeTime);
        return new Analysis(signal, buy, sell, reason);
    }

    private static boolean higherTrend(List<Candle> c, boolean up) {
        if (c == null || c.size() < 60) return true;
        int i = c.size() - 1;
        double[] e20 = ema(c, 20), e50 = ema(c, 50);
        return up ? e20[i] > e50[i] && c.get(i).close > e20[i]
                  : e20[i] < e50[i] && c.get(i).close < e20[i];
    }

    private static double[] ema(List<Candle> c, int p) {
        double[] o = new double[c.size()]; Arrays.fill(o, Double.NaN);
        if (c.size() < p) return o;
        double s = 0; for (int i = 0; i < p; i++) s += c.get(i).close;
        double cur = s / p, m = 2.0 / (p + 1); o[p - 1] = cur;
        for (int i = p; i < c.size(); i++) { cur = (c.get(i).close - cur) * m + cur; o[i] = cur; }
        return o;
    }

    private static double[] rsi(List<Candle> c, int p) {
        double[] o = new double[c.size()]; Arrays.fill(o, Double.NaN);
        if (c.size() <= p) return o;
        double g = 0, l = 0;
        for (int i = 1; i <= p; i++) { double d = c.get(i).close - c.get(i - 1).close; g += Math.max(d, 0); l += Math.max(-d, 0); }
        g /= p; l /= p; o[p] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
        for (int i = p + 1; i < c.size(); i++) {
            double d = c.get(i).close - c.get(i - 1).close;
            g = (g * (p - 1) + Math.max(d, 0)) / p; l = (l * (p - 1) + Math.max(-d, 0)) / p;
            o[i] = l == 0 ? 100 : 100 - 100 / (1 + g / l);
        }
        return o;
    }

    private static double[] atr(List<Candle> c, int p) {
        double[] o = new double[c.size()]; Arrays.fill(o, Double.NaN);
        if (c.size() <= p) return o;
        double[] tr = new double[c.size()]; tr[0] = c.get(0).high - c.get(0).low;
        for (int i = 1; i < c.size(); i++) {
            Candle x = c.get(i); double prev = c.get(i - 1).close;
            tr[i] = Math.max(x.high - x.low, Math.max(Math.abs(x.high - prev), Math.abs(x.low - prev)));
        }
        double cur = 0; for (int i = 1; i <= p; i++) cur += tr[i]; cur /= p; o[p] = cur;
        for (int i = p + 1; i < c.size(); i++) { cur = (cur * (p - 1) + tr[i]) / p; o[i] = cur; }
        return o;
    }

    private static boolean bullSweep(List<Candle> c, int i) {
        for (int j = Math.max(10, i - 2); j <= i; j++) {
            double prev = rangeLow(c, j - 10, j - 1);
            if (c.get(j).low < prev && c.get(j).close > prev) return true;
        }
        return false;
    }

    private static boolean bearSweep(List<Candle> c, int i) {
        for (int j = Math.max(10, i - 2); j <= i; j++) {
            double prev = rangeHigh(c, j - 10, j - 1);
            if (c.get(j).high > prev && c.get(j).close < prev) return true;
        }
        return false;
    }

    private static boolean hasBullFvg(List<Candle> c, int i, double a) {
        double price = c.get(i).close;
        for (int j = Math.max(2, i - 7); j <= i; j++) {
            double lo = c.get(j - 2).high, hi = c.get(j).low;
            if (hi > lo && price >= lo - 0.35 * a && price <= hi + 0.55 * a) return true;
        }
        return false;
    }

    private static boolean hasBearFvg(List<Candle> c, int i, double a) {
        double price = c.get(i).close;
        for (int j = Math.max(2, i - 7); j <= i; j++) {
            double lo = c.get(j).high, hi = c.get(j - 2).low;
            if (hi > lo && price >= lo - 0.55 * a && price <= hi + 0.35 * a) return true;
        }
        return false;
    }

    private static boolean hasBullOrderBlock(List<Candle> c, int i, double a) {
        double price = c.get(i).close;
        for (int j = i - 1; j >= Math.max(1, i - 9); j--) {
            Candle base = c.get(j), next = c.get(j + 1);
            if (base.close < base.open && next.close > next.open && Math.abs(next.close - next.open) > 0.8 * a && next.close > base.high) {
                if (price >= base.low - 0.25 * a && price <= base.high + 0.70 * a) return true;
            }
        }
        return false;
    }

    private static boolean hasBearOrderBlock(List<Candle> c, int i, double a) {
        double price = c.get(i).close;
        for (int j = i - 1; j >= Math.max(1, i - 9); j--) {
            Candle base = c.get(j), next = c.get(j + 1);
            if (base.close > base.open && next.close < next.open && Math.abs(next.close - next.open) > 0.8 * a && next.close < base.low) {
                if (price <= base.high + 0.25 * a && price >= base.low - 0.70 * a) return true;
            }
        }
        return false;
    }

    private static boolean compressed(List<Candle> c, int i, double a) {
        if (i < 16) return false;
        double hi = rangeHigh(c, i - 14, i - 3), lo = rangeLow(c, i - 14, i - 3);
        return hi - lo <= 4.0 * a;
    }

    private static boolean volumeConfirm(List<Candle> c, int i) {
        if (c.get(i).volume <= 0) return true;
        int s = Math.max(0, i - 20); double sum = 0; int n = 0;
        for (int j = s; j < i; j++) if (c.get(j).volume > 0) { sum += c.get(j).volume; n++; }
        if (n == 0) return true;
        return c.get(i).volume >= (sum / n) * 1.20;
    }

    private static boolean institutionalSession(long millis) {
        int h = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).getHour();
        return (h >= 7 && h <= 10) || (h >= 12 && h <= 16);
    }

    private static double upperWick(Candle c) { return c.high - Math.max(c.open, c.close); }
    private static double lowerWick(Candle c) { return Math.min(c.open, c.close) - c.low; }

    private static double rangeHigh(List<Candle> c, int s, int e) {
        s = Math.max(0, s); e = Math.min(c.size() - 1, e); double x = -Double.MAX_VALUE;
        for (int i = s; i <= e; i++) x = Math.max(x, c.get(i).high); return x;
    }

    private static double rangeLow(List<Candle> c, int s, int e) {
        s = Math.max(0, s); e = Math.min(c.size() - 1, e); double x = Double.MAX_VALUE;
        for (int i = s; i <= e; i++) x = Math.min(x, c.get(i).low); return x;
    }

    private static String joinTop(List<String> a, int max) {
        if (a.isEmpty()) return "rule alignment";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.size() && i < max; i++) { if (i > 0) b.append(" + "); b.append(a.get(i)); }
        return b.toString();
    }
}
