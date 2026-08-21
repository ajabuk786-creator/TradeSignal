package com.tradesignal.app;

import org.json.JSONObject;

public class Signal {
    public final String symbol;
    public final String timeframe;
    public final String side;
    public final double entry;
    public final double sl;
    public final double tp;
    public final double rr;
    public final int confidence;
    public final String reason;
    public final long candleCloseTime;

    public Signal(String symbol, String timeframe, String side, double entry, double sl, double tp,
                  double rr, int confidence, String reason, long candleCloseTime) {
        this.symbol = symbol;
        this.timeframe = timeframe;
        this.side = side;
        this.entry = entry;
        this.sl = sl;
        this.tp = tp;
        this.rr = rr;
        this.confidence = confidence;
        this.reason = reason;
        this.candleCloseTime = candleCloseTime;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("symbol", symbol);
            o.put("timeframe", timeframe);
            o.put("side", side);
            o.put("entry", entry);
            o.put("sl", sl);
            o.put("tp", tp);
            o.put("rr", rr);
            o.put("confidence", confidence);
            o.put("reason", reason);
            o.put("candleCloseTime", candleCloseTime);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static Signal fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new Signal(
                    o.getString("symbol"),
                    o.getString("timeframe"),
                    o.getString("side"),
                    o.getDouble("entry"),
                    o.getDouble("sl"),
                    o.getDouble("tp"),
                    o.optDouble("rr", 2.0),
                    o.optInt("confidence", 0),
                    o.optString("reason", ""),
                    o.optLong("candleCloseTime", 0L)
            );
        } catch (Exception e) {
            return null;
        }
    }
}
