package com.tradesignal.app;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private static final int SIGNAL_THRESHOLD = 70;
    private LinearLayout root, marketRow;
    private TextView status, marketTitle, side, confidence, entry, sl, tp, risk, trail, rationale, background;
    private EditText equityInput;
    private MarketDataClient.Market selected = MarketDataClient.MARKETS[0];
    private final Map<String, Button> marketButtons = new HashMap<>();

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        SignalWorker.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        buildUi();
        String requested = getIntent().getStringExtra("market");
        if (requested != null) selectMarket(MarketDataClient.byId(requested));
        SignalWorker.schedule(this);
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setPadding(0, 8, 0, 8); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32, 36, 32, 56); root.setBackgroundColor(Color.rgb(8, 11, 16));
        sv.addView(root);

        root.addView(text("20-FACTOR SIGNAL ENGINE", 12, Color.rgb(133, 151, 175), true));
        root.addView(text("TradeSignal Pro", 30, Color.WHITE, true));
        root.addView(text("15m closed candles · 1h higher-timeframe bias · 70% minimum consensus", 13, Color.rgb(165, 178, 196), false));

        marketRow = new LinearLayout(this); marketRow.setOrientation(LinearLayout.HORIZONTAL); marketRow.setPadding(0, 18, 0, 16); root.addView(marketRow);
        for (MarketDataClient.Market m : MarketDataClient.MARKETS) {
            Button b = new Button(this); b.setText(m.id); b.setAllCaps(false); b.setOnClickListener(v -> selectMarket(m));
            marketRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); marketButtons.put(m.id, b);
        }

        marketTitle = text("", 22, Color.WHITE, true); root.addView(marketTitle);
        status = text("Ready. Background scanner is enabled.", 13, Color.rgb(143, 159, 181), false); root.addView(status);

        TextView equityLabel = text("Paper equity used for 2% risk sizing", 13, Color.rgb(180, 191, 207), true); equityLabel.setPadding(0, 20, 0, 2); root.addView(equityLabel);
        equityInput = new EditText(this); equityInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        equityInput.setTextColor(Color.WHITE); equityInput.setHintTextColor(Color.GRAY); equityInput.setHint("1000");
        equityInput.setText(String.format(Locale.US, "%.0f", loadEquity())); root.addView(equityInput);

        Button scan = new Button(this); scan.setText("SCAN LATEST CLOSED CANDLE"); scan.setOnClickListener(v -> scanSelected()); root.addView(scan);

        root.addView(text("Latest qualifying setup", 18, Color.WHITE, true));
        side = text("NO SIGNAL", 30, Color.rgb(175, 185, 198), true); root.addView(side);
        confidence = text("Confidence  —", 16, Color.rgb(210, 219, 232), true); root.addView(confidence);
        entry = text("ENTRY  —", 17, Color.WHITE, true); root.addView(entry);
        sl = text("STOP LOSS  —", 17, Color.WHITE, true); root.addView(sl);
        tp = text("TAKE PROFIT  —", 17, Color.WHITE, true); root.addView(tp);
        risk = text("2% PAPER SIZE  —", 15, Color.rgb(193, 204, 220), false); root.addView(risk);
        trail = text("TRAILING STOP  —", 15, Color.rgb(193, 204, 220), false); root.addView(trail);
        rationale = text("The app waits until at least 14 of 20 directional checks agree.", 14, Color.rgb(184, 196, 213), false); rationale.setPadding(0, 12, 0, 12); root.addView(rationale);

        background = text("Background mode: Android checks roughly every 15 minutes when network is available and only alerts once per closed candle.", 13, Color.rgb(122, 201, 155), false);
        background.setPadding(0, 24, 0, 8); root.addView(background);
        TextView note = text("Signal-only / paper mode. No exchange API keys are stored and the APK never places an order. Confidence is rule consensus, not a guaranteed win rate. Gold uses PAXG/USDT as the market-data proxy when exact XAU/USDT is unavailable.", 12, Color.rgb(222, 190, 105), false);
        root.addView(note);

        setContentView(sv);
        selectMarket(selected);
    }

    private void selectMarket(MarketDataClient.Market m) {
        selected = m;
        for (Map.Entry<String, Button> e : marketButtons.entrySet()) e.getValue().setEnabled(!e.getKey().equals(m.id));
        if (marketTitle != null) marketTitle.setText(m.displayName + "  ·  " + m.note);
        clearSignal("Ready to scan " + m.displayName + ".");
    }

    private void scanSelected() {
        saveEquity();
        final MarketDataClient.Market market = selected;
        final double equity = loadEquity();
        status.setText("Scanning " + market.displayName + " 15m + 1h…");
        new Thread(() -> {
            try {
                List<SignalEngine.Candle> m15 = MarketDataClient.fetchClosed(market, "15m", 180);
                List<SignalEngine.Candle> h1 = MarketDataClient.fetchClosed(market, "1h", 120);
                SignalEngine.Signal s = SignalEngine.analyze(m15, h1, equity, SIGNAL_THRESHOLD);
                runOnUiThread(() -> {
                    if (market != selected) return;
                    if (s == null) clearSignal("No setup reached " + SIGNAL_THRESHOLD + "% consensus on the latest closed candle.");
                    else showSignal(market, s);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Data error: " + safe(e.getMessage())));
            }
        }).start();
    }

    private void clearSignal(String message) {
        if (status == null) return;
        status.setText(message); side.setText("NO SIGNAL"); side.setTextColor(Color.rgb(175, 185, 198));
        confidence.setText("Confidence  —"); entry.setText("ENTRY  —"); sl.setText("STOP LOSS  —"); tp.setText("TAKE PROFIT  —");
        risk.setText("2% PAPER SIZE  —"); trail.setText("TRAILING STOP  —"); rationale.setText("The engine requires strong multi-factor agreement before an alert is produced.");
    }

    private void showSignal(MarketDataClient.Market m, SignalEngine.Signal s) {
        status.setText("Qualified closed-candle signal · " + m.displayName);
        side.setText(s.side); side.setTextColor("BUY".equals(s.side) ? Color.rgb(55, 211, 131) : Color.rgb(255, 104, 124));
        confidence.setText("Confidence  " + s.confidence + "%  (" + s.votesFor + "/20 directional votes)");
        entry.setText("ENTRY  " + price(s.entry)); sl.setText("STOP LOSS  " + price(s.stopLoss)); tp.setText("TAKE PROFIT  " + price(s.takeProfit) + "  ·  R:R 1:2");
        double riskBudget = loadEquity() * 0.02;
        risk.setText("2% PAPER SIZE  " + qty(s.qtyAt2Pct) + " units  ·  risk budget $" + String.format(Locale.US, "%.2f", riskBudget));
        trail.setText("TRAILING STOP  activate at 1R (" + price(s.trailingTrigger) + "), then protect breakeven / trail by volatility");
        rationale.setText(s.rationale);
    }

    private double loadEquity() {
        android.content.SharedPreferences p = getSharedPreferences("trade_signal", MODE_PRIVATE);
        return Double.longBitsToDouble(p.getLong("paper_equity_bits", Double.doubleToRawLongBits(1000.0)));
    }

    private void saveEquity() {
        double value = 1000.0;
        try { value = Double.parseDouble(equityInput.getText().toString().trim()); } catch (Exception ignored) {}
        if (!Double.isFinite(value) || value <= 0) value = 1000.0;
        getSharedPreferences("trade_signal", MODE_PRIVATE).edit()
                .putLong("paper_equity_bits", Double.doubleToRawLongBits(value)).putInt("threshold", SIGNAL_THRESHOLD).apply();
    }

    private static String safe(String s) { return s == null || s.isEmpty() ? "unknown error" : s; }
    private static String price(double x) { return x >= 100 ? String.format(Locale.US, "%.2f", x) : x >= 1 ? String.format(Locale.US, "%.4f", x) : String.format(Locale.US, "%.6f", x); }
    private static String qty(double x) { return x >= 100 ? String.format(Locale.US, "%.2f", x) : x >= 1 ? String.format(Locale.US, "%.4f", x) : String.format(Locale.US, "%.6f", x); }
}
