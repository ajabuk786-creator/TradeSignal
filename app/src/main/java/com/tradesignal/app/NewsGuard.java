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

public class NewsGuard {
    public static class State {
        public final boolean available;
        public final boolean active;
        public final String label;
        State(boolean available, boolean active, String label) {
            this.available = available;
            this.active = active;
            this.label = label;
        }
    }

    private static volatile long cacheUntil = 0L;
    private static volatile State cached = new State(false, false, "News filter not configured");

    public static synchronized State check(Context context) {
        long now = System.currentTimeMillis();
        if (cacheUntil > now) return cached;

        SharedPreferences p = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = p.getString("trading_economics_key", "").trim();
        if (key.isEmpty()) {
            cached = new State(false, false, "News calendar off");
            cacheUntil = now + 10 * 60_000L;
            return cached;
        }

        try {
            String url = "https://api.tradingeconomics.com/calendar/country/united%20states?importance=3&c="
                    + URLEncoder.encode(key, "UTF-8");
            String body = get(url);
            JSONArray arr = new JSONArray(body);
            long nearest = Long.MAX_VALUE;
            String nearestName = "";
            boolean active = false;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.getJSONObject(i);
                if (e.optInt("Importance", 0) < 3) continue;
                String raw = e.optString("Date", "").replace(' ', 'T');
                if (raw.isEmpty()) continue;
                long eventTime;
                try {
                    eventTime = LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli();
                } catch (Exception parse) {
                    continue;
                }
                long diff = eventTime - now;
                long abs = Math.abs(diff);
                if (abs < nearest) {
                    nearest = abs;
                    nearestName = e.optString("Event", e.optString("Category", "US high-impact news"));
                }
                if (diff >= -15 * 60_000L && diff <= 20 * 60_000L) {
                    active = true;
                    nearestName = e.optString("Event", e.optString("Category", "US high-impact news"));
                    break;
                }
            }

            String label;
            if (active) label = "HIGH-IMPACT NEWS WINDOW: " + nearestName;
            else if (nearest < 90 * 60_000L) label = "Nearest high-impact US event: " + nearestName;
            else label = "No high-impact US event near current candle";
            cached = new State(true, active, label);
        } catch (Exception e) {
            cached = new State(false, false, "News feed unavailable");
        }
        cacheUntil = now + 10 * 60_000L;
        return cached;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("User-Agent", "TradeSignal-Android/2.0");
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] block = new byte[4096];
        int n;
        while ((n = in.read(block)) != -1) buf.write(block, 0, n);
        in.close();
        String body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        if (code < 200 || code >= 300) throw new IllegalStateException("News HTTP " + code);
        return body;
    }
}
