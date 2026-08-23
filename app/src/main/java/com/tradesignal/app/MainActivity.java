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
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final double MAX_TAKE_SLIPPAGE_BPS=25.0;

    private LinearLayout root,marketRow;
    private TextView status,backgroundStatus,marketTitle,activeSide,activeMeta,activeMove,entry,sl,tp,risk,trail,lastResult;
    private TextView signalSide,signalMeta,signalEntry,signalSl,signalTp,signalText;
    private TextView analysisDirection,analysisScores,analysisAmd,analysisText;
    private EditText equityInput;
    private Button historyButton,takeButton,awayButton;
    private MarketDataClient.Market selected=MarketDataClient.MARKETS[0];
    private final Map<String,Button> marketButtons=new HashMap<>();

    @Override public void onCreate(Bundle b){
        super.onCreate(b);SignalWorker.ensureChannel(this);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
        buildUi();String requested=getIntent().getStringExtra("market");if(requested!=null)selected=MarketDataClient.byId(requested);
        selectMarket(selected);SignalWorker.schedule(this);SignalWorker.enqueueNow(this);
    }

    @Override protected void onResume(){super.onResume();if(marketTitle!=null){refreshStoredState();refreshHistoryCount();refreshLiveActive();}}

    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,7,0,7);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}

    private void buildUi(){
        ScrollView sv=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,34,30,54);root.setBackgroundColor(Color.rgb(8,11,16));sv.addView(root);
        root.addView(text("STRUCTURE-FIRST SCALP ENGINE",12,Color.rgb(133,151,175),true));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
        TextView title=text("TradeSignal Pro v5",29,Color.WHITE,true);top.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        historyButton=new Button(this);historyButton.setText("HISTORY");historyButton.setAllCaps(false);historyButton.setOnClickListener(v->startActivity(new Intent(this,HistoryActivity.class)));top.addView(historyButton);root.addView(top);
        root.addView(text("1h context → 15m structure → ONE complete 5m setup → notification TAKE / AWAY",13,Color.rgb(165,178,196),false));
        backgroundStatus=text("Background scanner: starting…",12,Color.rgb(122,201,155),false);root.addView(backgroundStatus);

        marketRow=new LinearLayout(this);marketRow.setOrientation(LinearLayout.HORIZONTAL);marketRow.setPadding(0,12,0,12);root.addView(marketRow);
        for(MarketDataClient.Market m:MarketDataClient.MARKETS){Button b=new Button(this);b.setText(m.id);b.setAllCaps(false);b.setOnClickListener(v->selectMarket(m));marketRow.addView(b,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));marketButtons.put(m.id,b);}
        marketTitle=text("",21,Color.WHITE,true);root.addView(marketTitle);status=text("Ready.",13,Color.rgb(143,159,181),false);root.addView(status);

        root.addView(text("Paper equity — only for maximum 2% position-risk sizing",13,Color.rgb(180,191,207),true));
        equityInput=new EditText(this);equityInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);equityInput.setTextColor(Color.WHITE);equityInput.setText(String.format(Locale.US,"%.0f",loadEquity()));root.addView(equityInput);
        Button scan=new Button(this);scan.setText("SCAN NOW / REFRESH");scan.setOnClickListener(v->scanSelected());root.addView(scan);

        root.addView(text("ACTIVE TRADE — ONLY AFTER TAKE",18,Color.WHITE,true));
        activeSide=text("NO ACTIVE TRADE",28,Color.rgb(175,185,198),true);root.addView(activeSide);
        activeMeta=text("A notification signal remains only an opportunity until you choose TAKE.",14,Color.rgb(198,208,222),false);root.addView(activeMeta);
        activeMove=text("MOVE FROM ENTRY  —",16,Color.rgb(151,193,255),true);root.addView(activeMove);
        entry=text("ENTRY  —",16,Color.WHITE,true);root.addView(entry);sl=text("STOP LOSS  —",16,Color.WHITE,true);root.addView(sl);tp=text("TAKE PROFIT  —",16,Color.WHITE,true);root.addView(tp);
        risk=text("2% PAPER SIZE  —",14,Color.rgb(193,204,220),false);root.addView(risk);trail=text("TRAILING  —",14,Color.rgb(193,204,220),false);root.addView(trail);
        lastResult=text("",13,Color.rgb(220,190,105),false);root.addView(lastResult);

        root.addView(text("LATEST AVAILABLE SIGNAL",18,Color.WHITE,true));
        signalSide=text("NO NEW SIGNAL",25,Color.rgb(175,185,198),true);root.addView(signalSide);
        signalMeta=text("Every confirmed signal is stored in History. Quality is a rule-quality score, not win probability.",13,Color.rgb(194,205,220),false);root.addView(signalMeta);
        signalEntry=text("ENTRY  —",15,Color.WHITE,true);root.addView(signalEntry);signalSl=text("SL  —",15,Color.WHITE,true);root.addView(signalSl);signalTp=text("TP  —",15,Color.WHITE,true);root.addView(signalTp);
        signalText=text("",13,Color.rgb(184,196,213),false);root.addView(signalText);
        LinearLayout signalActions=new LinearLayout(this);signalActions.setOrientation(LinearLayout.HORIZONTAL);
        takeButton=new Button(this);takeButton.setText("★ TAKE");takeButton.setEnabled(false);takeButton.setOnClickListener(v->takeLatestSignal());signalActions.addView(takeButton,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        awayButton=new Button(this);awayButton.setText("AWAY / SKIP");awayButton.setEnabled(false);awayButton.setOnClickListener(v->awayLatestSignal());signalActions.addView(awayButton,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));root.addView(signalActions);

        root.addView(text("CURRENT MARKET ANALYSIS",18,Color.WHITE,true));
        analysisDirection=text("Regime / direction  —",19,Color.WHITE,true);root.addView(analysisDirection);
        analysisScores=text("Context strength  —  ·  trigger strength  —",14,Color.rgb(197,207,223),true);root.addView(analysisScores);
        analysisAmd=text("AMD  —",13,Color.rgb(151,193,255),false);root.addView(analysisAmd);
        analysisText=text("Waiting for scan.",13,Color.rgb(184,196,213),false);root.addView(analysisText);
        root.addView(text("v5 does not combine many correlated indicators into a fake probability. It accepts only a complete Trend Pullback, Breakout Retest, or completed AMD Sweep + Distribution setup. Signal-only / paper mode; no real order is placed.",12,Color.rgb(222,190,105),false));
        setContentView(sv);
    }

    private void selectMarket(MarketDataClient.Market m){selected=m;for(Map.Entry<String,Button> e:marketButtons.entrySet())e.getValue().setEnabled(!e.getKey().equals(m.id));marketTitle.setText(m.displayName+"  ·  "+m.note);status.setText("Ready to scan "+m.displayName+".");refreshStoredState();clearAnalysis();refreshLiveActive();}

    private void refreshStoredState(){
        TradeStore.ActiveTrade t=TradeStore.load(this,selected.id);if(t==null)showNoActive();else showActive(t,Double.NaN,"Stored active trade");
        showLatestSignal(SignalHistoryStore.latestPending(this,selected.id));
        String res=TradeStore.lastResult(this,selected.id);lastResult.setText(res==null||res.isEmpty()?"":"Last taken-trade result: "+res);
        long last=getSharedPreferences("trade_signal",MODE_PRIVATE).getLong("last_background_scan",0L);
        backgroundStatus.setText(last==0?"Background scanner: scheduled; no completed run recorded yet.":"Background scanner last completed: "+formatTime(last)+" · missed 5m closes are replayed.");
    }

    private void refreshHistoryCount(){historyButton.setText("HISTORY ("+SignalHistoryStore.newCount(this)+")");}

    private void refreshLiveActive(){
        final MarketDataClient.Market market=selected;final TradeStore.ActiveTrade t=TradeStore.load(this,market.id);if(t==null)return;
        new Thread(()->{try{double live=MarketDataClient.fetchLastPrice(market);runOnUiThread(()->{if(market==selected){TradeStore.ActiveTrade current=TradeStore.load(this,market.id);if(current!=null)showActive(current,live,"Live refresh");}});}catch(Exception ignored){}},"live-refresh").start();
    }

    private void scanSelected(){
        saveEquity();final MarketDataClient.Market market=selected;final double equity=loadEquity();status.setText("Scanning closed 5m + 15m + 1h structure…");
        new Thread(()->{
            try{
                List<SignalEngine.Candle> m5=MarketDataClient.fetchClosed(market,"5m",360),m15=MarketDataClient.fetchClosed(market,"15m",240),h1=MarketDataClient.fetchClosed(market,"1h",160);
                TradeStore.Update u=TradeStore.update(this,market.id,m5);SignalHistoryStore.updateUntakenOutcomes(this,market.id,m5);
                SignalEngine.Decision d=SignalEngine.analyzeScalp(m5,m15,h1,equity,0);
                boolean added=d.signal!=null&&SignalHistoryStore.add(this,market,d.signal,System.currentTimeMillis());
                double live=MarketDataClient.fetchLastPrice(market);final TradeStore.Update fu=u;final SignalEngine.Decision fd=d;final boolean fadded=added;final double flive=live;
                runOnUiThread(()->{if(market!=selected)return;if(fu.closed)status.setText(fu.event);else if(fadded)status.setText("New v5 signal saved. Choose TAKE or AWAY.");else status.setText("Scan complete — no new independent setup.");
                    TradeStore.ActiveTrade t=TradeStore.load(this,market.id);if(t==null)showNoActive();else showActive(t,flive,fu.event);showDecision(fd);showLatestSignal(SignalHistoryStore.latestPending(this,market.id));refreshHistoryCount();});
            }catch(Exception e){runOnUiThread(()->status.setText("Data error: "+safe(e.getMessage())));}
        },"manual-scan").start();
    }

    private void takeLatestSignal(){
        final SignalHistoryStore.Record r=SignalHistoryStore.latestPending(this,selected.id);if(r==null){Toast.makeText(this,"No fresh signal available.",Toast.LENGTH_SHORT).show();refreshStoredState();return;}
        if(TradeStore.load(this,selected.id)!=null){Toast.makeText(this,"This market already has an active taken trade.",Toast.LENGTH_SHORT).show();return;}
        takeButton.setEnabled(false);awayButton.setEnabled(false);status.setText("Checking live price before TAKE…");final MarketDataClient.Market market=selected;
        new Thread(()->{
            try{
                long now=System.currentTimeMillis();double live=MarketDataClient.fetchLastPrice(market);double bps=Math.abs(live-r.entry)/Math.max(r.entry,1e-12)*10000.0;double riskMove=Math.abs(live-r.entry)/Math.max(r.riskPerUnit,1e-12);
                boolean okay=r.isTakeable(now)&&bps<=MAX_TAKE_SLIPPAGE_BPS&&riskMove<=0.30;
                boolean opened=false;if(okay){boolean marked=SignalHistoryStore.markTaken(this,r.id,now);opened=marked&&TradeStore.openFromRecord(this,r,now);}final boolean fopened=opened;
                runOnUiThread(()->{if(market!=selected)return;if(fopened){status.setText("TAKEN — now active and tracked on the main page.");showActive(TradeStore.load(this,market.id),live,"Taken by you");}
                    else{SignalHistoryStore.markAway(this,r.id);status.setText("Not activated: price moved too far or the take window expired. Kept in History.");Toast.makeText(this,"Signal kept in History only.",Toast.LENGTH_LONG).show();}showLatestSignal(SignalHistoryStore.latestPending(this,market.id));refreshHistoryCount();});
            }catch(Exception e){runOnUiThread(()->{status.setText("Could not verify live price: "+safe(e.getMessage()));showLatestSignal(SignalHistoryStore.latestPending(this,market.id));});}
        },"take-signal").start();
    }

    private void awayLatestSignal(){
        SignalHistoryStore.Record r=SignalHistoryStore.latestPending(this,selected.id);if(r==null)return;
        SignalHistoryStore.markAway(this,r.id);status.setText("AWAY — signal moved out of the inbox. It remains in History for paper evaluation.");showLatestSignal(SignalHistoryStore.latestPending(this,selected.id));refreshHistoryCount();
    }

    private void showActive(TradeStore.ActiveTrade t,double live,String event){
        if(t==null){showNoActive();return;}activeSide.setText(t.side+" · ACTIVE");activeSide.setTextColor("BUY".equals(t.side)?Color.rgb(55,211,131):Color.rgb(255,104,124));
        activeMeta.setText("Quality "+t.qualityScore+"/10 · "+t.setupLabel+(event==null||event.isEmpty()?"":" · "+event));entry.setText("ENTRY  "+price(t.entry));sl.setText("STOP LOSS  "+price(t.stopLoss));tp.setText("TAKE PROFIT  "+price(t.takeProfit));risk.setText("2% PAPER SIZE  "+qty(t.qty)+" units");
        trail.setText(t.trailingActive?"TRAILING ACTIVE · protected stop "+price(t.stopLoss):"TRAILING activates near +0.8R: "+price(t.trailingTrigger));
        if(Double.isFinite(live)){double raw=(live-t.entry)/Math.max(t.entry,1e-12)*100.0;double favorable="BUY".equals(t.side)?raw:-raw;double rmove=("BUY".equals(t.side)?live-t.entry:t.entry-live)/Math.max(t.originalRisk,1e-12);activeMove.setText("LIVE "+price(live)+" · market "+signed(raw)+"% · trade "+signed(favorable)+"% · "+signed(rmove)+"R");activeMove.setTextColor(favorable>=0?Color.rgb(55,211,131):Color.rgb(255,104,124));}
        else activeMove.setText("MOVE FROM ENTRY  refreshing live price…");
    }

    private void showNoActive(){activeSide.setText("NO ACTIVE TRADE");activeSide.setTextColor(Color.rgb(175,185,198));activeMeta.setText("Use TAKE only on a signal you actually want to track.");activeMove.setText("MOVE FROM ENTRY  —");activeMove.setTextColor(Color.rgb(151,193,255));entry.setText("ENTRY  —");sl.setText("STOP LOSS  —");tp.setText("TAKE PROFIT  —");risk.setText("2% PAPER SIZE  —");trail.setText("TRAILING  —");}

    private void showLatestSignal(SignalHistoryStore.Record r){
        if(r==null){signalSide.setText("NO NEW SIGNAL");signalSide.setTextColor(Color.rgb(175,185,198));signalMeta.setText("New independent v5 setups will appear here and in notifications.");signalEntry.setText("ENTRY  —");signalSl.setText("SL  —");signalTp.setText("TP  —");signalText.setText("");takeButton.setEnabled(false);awayButton.setEnabled(false);return;}
        signalSide.setText(r.side+" · Quality "+r.qualityScore+"/10");signalSide.setTextColor("BUY".equals(r.side)?Color.rgb(55,211,131):Color.rgb(255,104,124));
        signalMeta.setText(formatTime(r.signalTime)+" · "+r.confirmation+" · "+r.regime);
        signalEntry.setText("ENTRY  "+price(r.entry));signalSl.setText("SL  "+price(r.stopLoss));signalTp.setText("TP  "+price(r.takeProfit)+" · R:R 1:"+String.format(Locale.US,"%.2f",r.riskReward));
        signalText.setText(r.amdState+"\nContext strength "+r.setupConfidence+"/100 · trigger strength "+r.triggerConfidence+"/100");
        boolean can=r.isTakeable(System.currentTimeMillis())&&TradeStore.load(this,r.marketId)==null;takeButton.setEnabled(can);awayButton.setEnabled(r.isTakeable(System.currentTimeMillis()));
    }

    private void showDecision(SignalEngine.Decision d){
        if(d==null){clearAnalysis();return;}analysisDirection.setText("Regime  "+d.regime+"  ·  direction "+d.direction);
        analysisDirection.setTextColor("BUY".equals(d.direction)?Color.rgb(55,211,131):"SELL".equals(d.direction)?Color.rgb(255,104,124):Color.WHITE);
        analysisScores.setText("Context strength  "+d.setupConfidence+"/100  ·  trigger strength  "+d.triggerConfidence+"/100"+(d.qualityScore>0?"  ·  quality "+d.qualityScore+"/10":""));
        analysisAmd.setText("AMD  "+d.amdState);analysisText.setText(d.summary);
    }

    private void clearAnalysis(){analysisDirection.setText("Regime / direction  —");analysisDirection.setTextColor(Color.WHITE);analysisScores.setText("Context strength  —  ·  trigger strength  —");analysisAmd.setText("AMD  —");analysisText.setText("Waiting for scan.");}
    private double loadEquity(){android.content.SharedPreferences p=getSharedPreferences("trade_signal",MODE_PRIVATE);return Double.longBitsToDouble(p.getLong("paper_equity_bits",Double.doubleToRawLongBits(1000.0)));}
    private void saveEquity(){double v=1000;try{v=Double.parseDouble(equityInput.getText().toString().trim());}catch(Exception ignored){}if(!Double.isFinite(v)||v<=0)v=1000;getSharedPreferences("trade_signal",MODE_PRIVATE).edit().putLong("paper_equity_bits",Double.doubleToRawLongBits(v)).apply();}
    private static String safe(String s){return s==null||s.isEmpty()?"unknown error":s;}
    private static String price(double x){return x>=100?String.format(Locale.US,"%.2f",x):x>=1?String.format(Locale.US,"%.4f",x):String.format(Locale.US,"%.6f",x);}
    private static String qty(double x){return x>=100?String.format(Locale.US,"%.2f",x):x>=1?String.format(Locale.US,"%.4f",x):String.format(Locale.US,"%.6f",x);}
    private static String signed(double x){return String.format(Locale.US,"%+.2f",x);}
    private static String formatTime(long ms){return new SimpleDateFormat("dd MMM HH:mm",Locale.getDefault()).format(new Date(ms));}
}
