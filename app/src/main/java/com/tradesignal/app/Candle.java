package com.tradesignal.app;

public class Candle {
    public final long openTime;
    public final long closeTime;
    public final double open;
    public final double high;
    public final double low;
    public final double close;
    public final double volume;

    public Candle(long openTime, long closeTime, double open, double high, double low, double close, double volume) {
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
    }
}
