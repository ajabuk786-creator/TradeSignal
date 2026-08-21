package com.tradesignal.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private TextView status, side, entry, sl, tp, confidence, reason, monitorState;
    private Button symbolButton, timeframeButton;
    private final String[] symbols = {"BTCUSDT", "XRPUSDT", "XAUUSD"};
    private final String[] timeframes = {"5m", "15m"};
    private int symbolIndex = 0, timeframeIndex = 0;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        buildUi();
        loadLatestSignal();
    }

    @Override protected void onResume() {
        super.onResume();
        updateMonitorState();
        loadLatestSignal();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setPadding(0, 10, 0, 10);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 42, 36, 60);
        root.setBackgroundColor(Color.rgb(8, 11, 16));
        sv.addView(root);

        root.addView(text("SIGNAL ASSISTANT", 12, Color.rgb(142, 155, 173), true));
        root.addView(text("TradeSignal", 32, Color.WHITE, true));
        status = text("Ready", 14, Color.rgb(142, 155, 173), false);
        root.addView(status);

        LinearLayout picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.HORIZONTAL);
        symbolButton = new Button(this);
        symbolButton.setText(symbols[symbolIndex]);
        symbolButton.setOnClickListener(v -> {
            symbolIndex = (symbolIndex + 1) % symbols.length;
            symbolButton.setText(symbols[symbolIndex]);
            clearDisplay("Selected " + symbols[symbolIndex] + " " + timeframes[timeframeIndex]);
        });
        timeframeButton = new Button(this);
        timeframeButton.setText(timeframes[timeframeIndex]);
        timeframeButton.setOnClickListener(v -> {
            timeframeIndex = (timeframeIndex + 1) % timeframes.length;
            timeframeButton.setText(timeframes[timeframeIndex]);
            clearDisplay("Selected " + symbols[symbolIndex] + " " + timeframes[timeframeIndex]);
        });
        picker.addView(symbolButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        picker.addView(timeframeButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(picker);

        root.addView(text("Latest setup", 20, Color.WHITE, true));
        side = text("NO SIGNAL", 28, Color.rgb(174, 184, 197), true); root.addView(side);
        confidence = text("CONFIDENCE   —", 15, Color.rgb(142, 155, 173), true); root.addView(confidence);
        entry = text("ENTRY   —", 18, Color.WHITE, true); root.addView(entry);
        sl = text("STOP LOSS   —", 18, Color.WHITE, true); root.addView(sl);
        tp = text("TAKE PROFIT   —", 18, Color.WHITE, true); root.addView(tp);
        reason = text("The engine waits for strong multi-factor confluence on a closed candle.", 14, Color.rgb(187, 197, 209), false);
        root.addView(reason);

        Button scan = new Button(this); scan.setText("SCAN NOW"); scan.setOnClickListener(v -> scanSelected()); root.addView(scan);
        Button start = new Button(this); start.setText("START BACKGROUND"); start.setOnClickListener(v -> startMonitor()); root.addView(start);
        Button stop = new Button(this); stop.setText("STOP BACKGROUND"); stop.setOnClickListener(v -> stopMonitor()); root.addView(stop);
        Button keys = new Button(this); keys.setText("DATA KEYS"); keys.setOnClickListener(v -> showKeysDialog()); root.addView(keys);

        monitorState = text("Background: OFF", 14, Color.rgb(142, 155, 173), true);
        monitorState.setPadding(0, 20, 0, 10); root.addView(monitorState);

        TextView warning = text(
                "Engine: EMA trend + multi-timeframe bias + market structure/BOS + liquidity sweeps + FVG + order blocks + supply/demand + support/resistance + premium/discount + OTE + AMD phase + RSI/ATR + rejection + volume/volatility breakout + optional high-impact news guard.\n\nSignals are probabilistic and can lose. Background mode never places trades automatically.",
                13, Color.rgb(220, 190, 100), false);
        warning.setPadding(0, 30, 0, 10); root.addView(warning);
        setContentView(sv);
    }

    private void scanSelected() {
        final String symbol = symbols[symbolIndex];
        final String tf = timeframes[timeframeIndex];
        status.setText("Scanning " + symbol + " " + tf + "…");
        new Thread(() -> {
            try {
                List<Candle> candles = MarketData.fetch(this, symbol, tf);
                List<Candle> higher = null;
                if ("5m".equals(tf)) {
                    try { higher = MarketData.fetch(this, symbol, "15m"); } catch (Exception ignored) {}
                }
                NewsGuard.State news = NewsGuard.check(this);
                StrategyEngine.Analysis a = StrategyEngine.analyze(symbol, tf, candles, higher, news);
                runOnUiThread(() -> {
                    if (a.signal == null) {
                        status.setText(symbol + " · " + tf + " · scan complete");
                        side.setText("NO SIGNAL");
                        side.setTextColor(Color.rgb(174, 184, 197));
                        confidence.setText("BUY " + a.buyScore + "/100   ·   SELL " + a.sellScore + "/100");
                        entry.setText("ENTRY   —"); sl.setText("STOP LOSS   —"); tp.setText("TAKE PROFIT   —");
                        reason.setText(a.summary);
                    } else {
                        showSignal(a.signal);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Cannot scan " + symbol + " " + tf);
                    reason.setText(e.getMessage() == null ? "Market data error" : e.getMessage());
                });
            }
        }).start();
    }

    private void startMonitor() {
        SharedPreferences p = getSharedPreferences("settings", Context.MODE_PRIVATE);
        p.edit().putBoolean("monitor_enabled", true).apply();
        Intent i = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("Background monitoring started");
        updateMonitorState();
    }

    private void stopMonitor() {
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("monitor_enabled", false).apply();
        stopService(new Intent(this, MonitorService.class));
        status.setText("Background monitoring stopped");
        updateMonitorState();
    }

    private void updateMonitorState() {
        if (monitorState == null) return;
        boolean enabled = getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("monitor_enabled", false);
        monitorState.setText("Background: " + (MonitorService.running ? "RUNNING" : enabled ? "ENABLED — tap START after reboot if needed" : "OFF"));
        monitorState.setTextColor(MonitorService.running ? Color.rgb(53, 208, 127) : Color.rgb(142, 155, 173));
    }

    private void showKeysDialog() {
        SharedPreferences p = getSharedPreferences("settings", Context.MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(36, 12, 36, 0);
        EditText td = new EditText(this); td.setHint("Twelve Data API key — required for XAUUSD"); td.setText(p.getString("twelve_data_key", ""));
        td.setSingleLine(true); td.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); box.addView(td);
        EditText te = new EditText(this); te.setHint("Trading Economics API key — optional news guard"); te.setText(p.getString("trading_economics_key", ""));
        te.setSingleLine(true); te.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD); box.addView(te);
        new AlertDialog.Builder(this)
                .setTitle("Market data settings")
                .setMessage("BTCUSDT and XRPUSDT need no key. XAUUSD uses Twelve Data. High-impact US calendar filtering is optional.")
                .setView(box)
                .setPositiveButton("SAVE", (d, w) -> {
                    p.edit().putString("twelve_data_key", td.getText().toString().trim())
                            .putString("trading_economics_key", te.getText().toString().trim()).apply();
                    status.setText("Data keys saved on this phone");
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void loadLatestSignal() {
        String raw = getSharedPreferences("monitor", Context.MODE_PRIVATE).getString("latest_signal", "");
        if (raw.isEmpty()) return;
        Signal s = Signal.fromJson(raw);
        if (s != null) showSignal(s);
    }

    private void showSignal(Signal s) {
        status.setText(s.symbol + " · " + s.timeframe + " · signal ready");
        side.setText(s.side);
        side.setTextColor("BUY".equals(s.side) ? Color.rgb(53, 208, 127) : Color.rgb(255, 100, 120));
        confidence.setText("CONFIDENCE   " + s.confidence + "/100");
        entry.setText("ENTRY   " + fmt(s.symbol, s.entry));
        sl.setText("STOP LOSS   " + fmt(s.symbol, s.sl));
        tp.setText("TAKE PROFIT   " + fmt(s.symbol, s.tp));
        reason.setText(s.reason + String.format(Locale.US, " · R:R %.2f", s.rr));
    }

    private void clearDisplay(String message) {
        status.setText(message); side.setText("NO SIGNAL"); side.setTextColor(Color.rgb(174, 184, 197));
        confidence.setText("CONFIDENCE   —"); entry.setText("ENTRY   —"); sl.setText("STOP LOSS   —"); tp.setText("TAKE PROFIT   —");
        reason.setText("Tap SCAN NOW for the selected market and timeframe.");
    }

    private static String fmt(String symbol, double x) {
        if ("XRPUSDT".equals(symbol)) return String.format(Locale.US, "%.5f", x);
        return String.format(Locale.US, "%.2f", x);
    }
}
