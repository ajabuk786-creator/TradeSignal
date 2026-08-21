package com.tradesignal.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MarketData {
    private static final int LIMIT = 300;

    public static List<Candle> fetch(Context context, String symbol, String timeframe) throws Exception {
        if ("XAUUSD".equals(symbol)) return fetchXau(context, timeframe);
        return fetchBinance(symbol, timeframe);
    }

    private static List<Candle> fetchBinance(String symbol, String timeframe) throws Exception {
        String interval = "5m".equals(timeframe) ? "5m" : "15m";
        String u = "https://api.binance.com/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + LIMIT;
        String json = get(u);
        JSONArray a = new JSONArray(json);
        ArrayList<Candle> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONArray k = a.getJSONArray(i);
            long closeTime = k.getLong(6);
            if (closeTime >= now) continue;
            out.add(new Candle(
                    k.getLong(0), closeTime,
                    k.getDouble(1), k.getDouble(2), k.getDouble(3), k.getDouble(4), k.getDouble(5)
            ));
        }
        return out;
    }

    private static List<Candle> fetchXau(Context context, String timeframe) throws Exception {
        SharedPreferences p = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("twelve_data_key", "").trim();
        if (key.isEmpty()) throw new IllegalStateException("XAUUSD needs a Twelve Data API key. Tap DATA KEYS once to add it.");

        String interval = "5m".equals(timeframe) ? "5min" : "15min";
        String u = "https://api.twelvedata.com/time_series?symbol="
                + URLEncoder.encode("XAU/USD", "UTF-8")
                + "&interval=" + interval
                + "&outputsize=" + LIMIT
                + "&timezone=UTC&apikey=" + URLEncoder.encode(key, "UTF-8");
        JSONObject root = new JSONObject(get(u));
        if ("error".equalsIgnoreCase(root.optString("status"))) {
            throw new IllegalStateException(root.optString("message", "XAU data provider error"));
        }
        JSONArray values = root.optJSONArray("values");
        if (values == null) throw new IllegalStateException(root.optString("message", "No XAUUSD intraday data returned"));

        ArrayList<Candle> out = new ArrayList<>();
        long minutes = "5m".equals(timeframe) ? 5L : 15L;
        long span = minutes * 60_000L;
        long now = System.currentTimeMillis();
        for (int i = 0; i < values.length(); i++) {
            JSONObject v = values.getJSONObject(i);
            String dt = v.getString("datetime").replace(' ', 'T');
            long openTime = LocalDateTime.parse(dt).toInstant(ZoneOffset.UTC).toEpochMilli();
            long closeTime = openTime + span - 1L;
            if (closeTime >= now) continue;
            double volume = 0.0;
            try { volume = Double.parseDouble(v.optString("volume", "0")); } catch (Exception ignored) {}
            out.add(new Candle(
                    openTime, closeTime,
                    Double.parseDouble(v.getString("open")),
                    Double.parseDouble(v.getString("high")),
                    Double.parseDouble(v.getString("low")),
                    Double.parseDouble(v.getString("close")),
                    volume
            ));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.openTime, b.openTime));
        return out;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12_000);
        c.setReadTimeout(12_000);
        c.setRequestProperty("User-Agent", "TradeSignal-Android/2.0");
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int n;
        while ((n = in.read(block)) != -1) buf.write(block, 0, n);
        in.close();
        String body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) throw new IllegalStateException("Market data HTTP " + code + ": " + body);
        return body;
    }
}
