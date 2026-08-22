package com.tradesignal.app;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MarketDataClient {
    private MarketDataClient() {}

    public static final class Market {
        public final String id, displayName, apiSymbol, note;
        public Market(String id, String displayName, String apiSymbol, String note) {
            this.id = id; this.displayName = displayName; this.apiSymbol = apiSymbol; this.note = note;
        }
    }

    public static final Market[] MARKETS = new Market[] {
        new Market("BTC", "BTC/USDT", "BTCUSDT", "Bitcoin spot"),
        new Market("XRP", "XRP/USDT", "XRPUSDT", "XRP spot"),
        new Market("XAU", "XAU/USDT", "PAXGUSDT", "Gold proxy via PAXG/USDT")
    };

    public static Market byId(String id) {
        for (Market m : MARKETS) if (m.id.equals(id)) return m;
        return MARKETS[0];
    }

    public static List<SignalEngine.Candle> fetchClosed(Market market, String interval, int limit) throws Exception {
        String endpoint = "https://api.binance.com/api/v3/klines?symbol=" + URLEncoder.encode(market.apiSymbol, "UTF-8")
                + "&interval=" + URLEncoder.encode(interval, "UTF-8") + "&limit=" + limit;
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpURLConnection c = (HttpURLConnection)new URL(endpoint).openConnection();
                c.setRequestMethod("GET"); c.setConnectTimeout(12000); c.setReadTimeout(12000);
                c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "TradeSignal/2.0");
                int code = c.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String body = readAll(stream);
                if (code == 418 || code == 429) throw new IOException("Rate limited by market-data API (HTTP " + code + ")");
                if (code < 200 || code >= 300) throw new IOException("Market-data HTTP " + code + ": " + trim(body, 160));
                JSONArray a = new JSONArray(body); ArrayList<SignalEngine.Candle> out = new ArrayList<>();
                long now = System.currentTimeMillis();
                for (int i = 0; i < a.length(); i++) {
                    JSONArray k = a.getJSONArray(i);
                    long closeTime = k.getLong(6);
                    if (closeTime >= now) continue;
                    out.add(new SignalEngine.Candle(k.getLong(0), k.getDouble(1), k.getDouble(2), k.getDouble(3), k.getDouble(4), k.getDouble(5), closeTime));
                }
                if (out.size() < 60) throw new IOException("Not enough closed candles returned");
                return out;
            } catch (Exception e) {
                last = e;
                if (attempt < 3) Thread.sleep(800L * attempt);
            }
        }
        throw last == null ? new IOException("Unknown market-data error") : last;
    }

    public static double fetchLastPrice(Market market) throws Exception {
        String endpoint = "https://api.binance.com/api/v3/ticker/price?symbol=" + URLEncoder.encode(market.apiSymbol, "UTF-8");
        HttpURLConnection c = (HttpURLConnection)new URL(endpoint).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestProperty("User-Agent", "TradeSignal/2.0");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("Ticker HTTP " + code);
        JSONObject o = new JSONObject(readAll(c.getInputStream()));
        return o.getDouble("price");
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream x = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = x.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String trim(String s, int n) { return s == null ? "" : (s.length() <= n ? s : s.substring(0, n)); }
}
