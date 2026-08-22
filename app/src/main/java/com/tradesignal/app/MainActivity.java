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
    private static final double MAX_ENTRY_SLIPPAGE_BPS = 20.0;

    private LinearLayout root, marketRow;
    private TextView status, marketTitle, activeSide, activeMeta, entry, sl, tp, risk, trail, lastResult;
    private TextView analysisDirection, analysisScores, analysisAmd, analysisText, background;
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

    @Override protected void onResume() {
        super.onResume();
        if (marketTitle != null) refreshStoredTrade();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setPadding(0, 8, 0, 8); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32, 36, 32, 56); root.setBackgroundColor(Color.rgb(8, 11, 16));
        sv.addView(root);

        root.addView(text("SCALP CONFIRMATION ENGINE", 12, Color.rgb(133,151,175), true));
        root.addView(text("TradeSignal Pro v3", 30, Color.WHITE, true));
        root.addView(text("15m setup + 1h context → 5m confirmation → locked active trade", 13, Color.rgb(165,178,196), false));

        marketRow = new LinearLayout(this); marketRow.setOrientation(LinearLayout.HORIZONTAL); marketRow.setPadding(0,18,0,16); root.addView(marketRow);
        for (MarketDataClient.Market m : MarketDataClient.MARKETS) {
            Button b = new Button(this); b.setText(m.id); b.setAllCaps(false); b.setOnClickListener(v -> selectMarket(m));
            marketRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); marketButtons.put(m.id,b);
        }

        marketTitle = text("",22,Color.WHITE,true); root.addView(marketTitle);
        status = text("Ready.",13,Color.rgb(143,159,181),false); root.addView(status);

        TextView equityLabel = text("Paper equity used only for max 2% risk sizing",13,Color.rgb(180,191,207),true); equityLabel.setPadding(0,18,0,2); root.addView(equityLabel);
        equityInput = new EditText(this); equityInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        equityInput.setTextColor(Color.WHITE); equityInput.setHintTextColor(Color.GRAY); equityInput.setHint("1000"); equityInput.setText(String.format(Locale.US,"%.0f",loadEquity())); root.addView(equityInput);

        Button scan = new Button(this); scan.setText("SCAN / UPDATE SCALP"); scan.setOnClickListener(v -> scanSelected()); root.addView(scan);

        root.addView(text("ACTIVE TRADE",18,Color.WHITE,true));
        activeSide = text("NO ACTIVE TRADE",29,Color.rgb(175,185,198),true); root.addView(activeSide);
        activeMeta = text("A confirmed entry will stay here until TP, SL, trailing stop, or the 60-minute time exit.",14,Color.rgb(198,208,222),false); root.addView(activeMeta);
        entry = text("ENTRY  —",17,Color.WHITE,true); root.addView(entry);
        sl = text("STOP LOSS  —",17,Color.WHITE,true); root.addView(sl);
        tp = text("TAKE PROFIT  —",17,Color.WHITE,true); root.addView(tp);
        risk = text("2% PAPER SIZE  —",14,Color.rgb(193,204,220),false); root.addView(risk);
        trail = text("TRAILING  —",14,Color.rgb(193,204,220),false); root.addView(trail);
        lastResult = text("",13,Color.rgb(220,190,105),false); root.addView(lastResult);

        root.addView(text("CURRENT MARKET ANALYSIS",18,Color.WHITE,true));
        analysisDirection = text("Direction  —",20,Color.WHITE,true); root.addView(analysisDirection);
        analysisScores = text("15m/1h setup  —  ·  5m trigger  —",14,Color.rgb(197,207,223),true); root.addView(analysisScores);
        analysisAmd = text("AMD  —",13,Color.rgb(151,193,255),false); root.addView(analysisAmd);
        analysisText = text("Waiting for scan.",14,Color.rgb(184,196,213),false); root.addView(analysisText);

        background = text("Background mode checks roughly every 15 minutes. For 5m scalping, scan manually around each 5m candle close so the app can use the newest confirmation and reject stale entries.",13,Color.rgb(122,201,155),false);
        background.setPadding(0,24,0,8); root.addView(background);
        root.addView(text("Signal-only / paper mode. The app does not place real orders. A new 5m candle may change current analysis, but it does not erase an already-active trade.",12,Color.rgb(222,190,105),false));

        setContentView(sv);
        selectMarket(selected);
    }

    private void selectMarket(MarketDataClient.Market m) {
        selected = m;
        for (Map.Entry<String,Button> e : marketButtons.entrySet()) e.getValue().setEnabled(!e.getKey().equals(m.id));
        marketTitle.setText(m.displayName + "  ·  " + m.note);
        status.setText("Ready to scan " + m.displayName + ".");
        refreshStoredTrade();
        clearAnalysis();
    }

    private void refreshStoredTrade() {
        TradeStore.ActiveTrade t = TradeStore.load(this, selected.id);
        if (t == null) showNoActiveTrade(); else showActiveTrade(t,"Stored active trade");
        String result = TradeStore.lastResult(this,selected.id);
        lastResult.setText(result == null || result.isEmpty() ? "" : "Last result: " + result);
    }

    private void scanSelected() {
        saveEquity();
        final MarketDataClient.Market market = selected;
        final double equity = loadEquity();
        status.setText("Fetching closed 5m + 15m + 1h candles…");
        new Thread(() -> {
            try {
                List<SignalEngine.Candle> m5 = MarketDataClient.fetchClosed(market,"5m",240);
                List<SignalEngine.Candle> m15 = MarketDataClient.fetchClosed(market,"15m",180);
                List<SignalEngine.Candle> h1 = MarketDataClient.fetchClosed(market,"1h",120);

                TradeStore.Update update = TradeStore.update(this,market.id,m5);
                SignalEngine.Decision decision = SignalEngine.analyzeScalp(m5,m15,h1,equity,SIGNAL_THRESHOLD);
                double live = MarketDataClient.fetchLastPrice(market);

                if (update.trade == null && !update.closed && decision.signal != null && TradeStore.canOpen(this,market.id,decision.signal.candleCloseTime)) {
                    double slippageBps = Math.abs(live-decision.signal.entry)/Math.max(decision.signal.entry,1e-12)*10000.0;
                    if (slippageBps <= MAX_ENTRY_SLIPPAGE_BPS) {
                        TradeStore.open(this,market.id,decision.signal);
                        update = new TradeStore.Update(TradeStore.load(this,market.id),"New confirmed scalp opened",false,live);
                    } else {
                        decision = new SignalEngine.Decision(null,decision.direction,decision.setupConfidence,decision.triggerConfidence,decision.amdState,
                                "Confirmation existed, but live price moved " + String.format(Locale.US,"%.1f",slippageBps) + " bps from the 5m entry. Stale entry skipped.");
                    }
                }

                final TradeStore.Update finalUpdate = update;
                final SignalEngine.Decision finalDecision = decision;
                runOnUiThread(() -> {
                    if (market != selected) return;
                    if (finalUpdate.closed) {
                        status.setText(finalUpdate.event);
                        showNoActiveTrade();
                        lastResult.setText("Last result: " + finalUpdate.event);
                    } else if (finalUpdate.trade != null) {
                        status.setText(finalUpdate.event);
                        showActiveTrade(finalUpdate.trade,finalUpdate.event);
                    } else {
                        status.setText(TradeStore.inCooldown(this,market.id,System.currentTimeMillis()) ? "Trade closed. One 5m cooldown candle before next entry." : "No active trade.");
                        showNoActiveTrade();
                    }
                    showDecision(finalDecision);
                });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Data error: " + safe(e.getMessage())));
            }
        }).start();
    }

    private void showActiveTrade(TradeStore.ActiveTrade t,String event) {
        activeSide.setText(t.side + " · ACTIVE");
        activeSide.setTextColor("BUY".equals(t.side) ? Color.rgb(55,211,131) : Color.rgb(255,104,124));
        activeMeta.setText("Locked entry · " + t.confidence + "% entry confidence · " + t.setupLabel + " · " + event);
        entry.setText("ENTRY  " + price(t.entry));
        sl.setText("STOP LOSS  " + price(t.stopLoss));
        tp.setText("TAKE PROFIT  " + price(t.takeProfit));
        risk.setText("2% PAPER SIZE  " + qty(t.qty) + " units");
        trail.setText(t.trailingActive ? "TRAILING  ACTIVE · protected stop " + price(t.stopLoss) : "TRAILING  activates at +0.8R: " + price(t.trailingTrigger));
    }

    private void showNoActiveTrade() {
        activeSide.setText("NO ACTIVE TRADE"); activeSide.setTextColor(Color.rgb(175,185,198));
        activeMeta.setText("Waiting for a confirmed 5m entry after the 15m/1h setup.");
        entry.setText("ENTRY  —"); sl.setText("STOP LOSS  —"); tp.setText("TAKE PROFIT  —"); risk.setText("2% PAPER SIZE  —"); trail.setText("TRAILING  —");
    }

    private void showDecision(SignalEngine.Decision d) {
        if (d == null) { clearAnalysis(); return; }
        analysisDirection.setText("Direction  " + d.direction);
        analysisDirection.setTextColor("BUY".equals(d.direction) ? Color.rgb(55,211,131) : "SELL".equals(d.direction) ? Color.rgb(255,104,124) : Color.WHITE);
        analysisScores.setText("15m/1h setup  " + d.setupConfidence + "%  ·  5m trigger  " + d.triggerConfidence + "%");
        analysisAmd.setText("AMD  " + d.amdState);
        analysisText.setText(d.summary);
        if (d.signal != null) {
            analysisText.setText(d.summary + "\nConfirmed entry: " + d.signal.confirmationLabel + " · target R:R 1:" + String.format(Locale.US,"%.2f",d.signal.riskReward));
        }
    }

    private void clearAnalysis() {
        analysisDirection.setText("Direction  —"); analysisDirection.setTextColor(Color.WHITE);
        analysisScores.setText("15m/1h setup  —  ·  5m trigger  —"); analysisAmd.setText("AMD  —"); analysisText.setText("Waiting for scan.");
    }

    private double loadEquity() {
        android.content.SharedPreferences p = getSharedPreferences("trade_signal",MODE_PRIVATE);
        return Double.longBitsToDouble(p.getLong("paper_equity_bits",Double.doubleToRawLongBits(1000.0)));
    }

    private void saveEquity() {
        double value=1000.0; try { value=Double.parseDouble(equityInput.getText().toString().trim()); } catch(Exception ignored) {}
        if(!Double.isFinite(value)||value<=0) value=1000.0;
        getSharedPreferences("trade_signal",MODE_PRIVATE).edit().putLong("paper_equity_bits",Double.doubleToRawLongBits(value)).putInt("threshold",SIGNAL_THRESHOLD).apply();
    }

    private static String safe(String s) { return s==null||s.isEmpty()?"unknown error":s; }
    private static String price(double x) { return x>=100?String.format(Locale.US,"%.2f",x):x>=1?String.format(Locale.US,"%.4f",x):String.format(Locale.US,"%.6f",x); }
    private static String qty(double x) { return x>=100?String.format(Locale.US,"%.2f",x):x>=1?String.format(Locale.US,"%.4f",x):String.format(Locale.US,"%.6f",x); }
}
