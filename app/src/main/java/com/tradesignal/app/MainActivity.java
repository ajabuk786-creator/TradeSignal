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
    private static final int SIGNAL_THRESHOLD=70;
    private static final double MAX_TAKE_SLIPPAGE_BPS=25.0;

    private LinearLayout root,marketRow;
    private TextView status,backgroundStatus,marketTitle,activeSide,activeMeta,activeMove,entry,sl,tp,risk,trail,lastResult;
    private TextView signalSide,signalMeta,signalEntry,signalSl,signalTp,signalText;
    private TextView analysisDirection,analysisScores,analysisAmd,analysisText;
    private EditText equityInput;
    private Button historyButton,takeButton;
    private MarketDataClient.Market selected=MarketDataClient.MARKETS[0];
    private final Map<String,Button> marketButtons=new HashMap<>();

    @Override public void onCreate(Bundle b){
        super.onCreate(b); SignalWorker.ensureChannel(this);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
        buildUi(); String requested=getIntent().getStringExtra("market"); if(requested!=null) selected=MarketDataClient.byId(requested);
        selectMarket(selected); SignalWorker.schedule(this); SignalWorker.enqueueNow(this);
    }

    @Override protected void onResume(){super.onResume();if(marketTitle!=null){refreshStoredState();refreshHistoryCount();}}

    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,7,0,7);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}

    private void buildUi(){
        ScrollView sv=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,34,30,54);root.setBackgroundColor(Color.rgb(8,11,16));sv.addView(root);
        root.addView(text("CONFIRMED SCALP SIGNAL ENGINE",12,Color.rgb(133,151,175),true));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);
        TextView title=text("TradeSignal Pro v4",29,Color.WHITE,true);top.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        historyButton=new Button(this);historyButton.setText("HISTORY");historyButton.setAllCaps(false);historyButton.setOnClickListener(v->startActivity(new Intent(this,HistoryActivity.class)));top.addView(historyButton);root.addView(top);
        root.addView(text("1h context + 15m setup → 5m confirmation → signal inbox → YOU choose TAKE",13,Color.rgb(165,178,196),false));
        backgroundStatus=text("Background scanner: starting…",12,Color.rgb(122,201,155),false);root.addView(backgroundStatus);

        marketRow=new LinearLayout(this);marketRow.setOrientation(LinearLayout.HORIZONTAL);marketRow.setPadding(0,12,0,12);root.addView(marketRow);
        for(MarketDataClient.Market m:MarketDataClient.MARKETS){Button b=new Button(this);b.setText(m.id);b.setAllCaps(false);b.setOnClickListener(v->selectMarket(m));marketRow.addView(b,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));marketButtons.put(m.id,b);}
        marketTitle=text("",21,Color.WHITE,true);root.addView(marketTitle);status=text("Ready.",13,Color.rgb(143,159,181),false);root.addView(status);

        TextView eq=text("Paper equity — used only for max 2% risk sizing",13,Color.rgb(180,191,207),true);root.addView(eq);
        equityInput=new EditText(this);equityInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);equityInput.setTextColor(Color.WHITE);equityInput.setText(String.format(Locale.US,"%.0f",loadEquity()));root.addView(equityInput);
        Button scan=new Button(this);scan.setText("SCAN NOW / REFRESH");scan.setOnClickListener(v->scanSelected());root.addView(scan);

        root.addView(text("ACTIVE TRADE — ONLY AFTER YOU TAKE IT",18,Color.WHITE,true));
        activeSide=text("NO ACTIVE TRADE",28,Color.rgb(175,185,198),true);root.addView(activeSide);
        activeMeta=text("Signals do not become active automatically.",14,Color.rgb(198,208,222),false);root.addView(activeMeta);
        activeMove=text("MOVE FROM ENTRY  —",16,Color.rgb(151,193,255),true);root.addView(activeMove);
        entry=text("ENTRY  —",16,Color.WHITE,true);root.addView(entry);sl=text("STOP LOSS  —",16,Color.WHITE,true);root.addView(sl);tp=text("TAKE PROFIT  —",16,Color.WHITE,true);root.addView(tp);
        risk=text("2% PAPER SIZE  —",14,Color.rgb(193,204,220),false);root.addView(risk);trail=text("TRAILING  —",14,Color.rgb(193,204,220),false);root.addView(trail);
        lastResult=text("",13,Color.rgb(220,190,105),false);root.addView(lastResult);

        root.addView(text("LATEST CONFIRMED SIGNAL",18,Color.WHITE,true));
        signalSide=text("NO NEW SIGNAL",25,Color.rgb(175,185,198),true);root.addView(signalSide);
        signalMeta=text("Every confirmed signal is saved in History whether you take it or not.",13,Color.rgb(194,205,220),false);root.addView(signalMeta);
        signalEntry=text("ENTRY  —",15,Color.WHITE,true);root.addView(signalEntry);signalSl=text("SL  —",15,Color.WHITE,true);root.addView(signalSl);signalTp=text("TP  —",15,Color.WHITE,true);root.addView(signalTp);
        signalText=text("",13,Color.rgb(184,196,213),false);root.addView(signalText);
        takeButton=new Button(this);takeButton.setText("★ TAKE / I'M INTERESTED");takeButton.setEnabled(false);takeButton.setOnClickListener(v->takeLatestSignal());root.addView(takeButton);

        root.addView(text("CURRENT MARKET ANALYSIS",18,Color.WHITE,true));
        analysisDirection=text("Direction  —",19,Color.WHITE,true);root.addView(analysisDirection);
        analysisScores=text("15m/1h setup  —  ·  5m trigger  —",14,Color.rgb(197,207,223),true);root.addView(analysisScores);
        analysisAmd=text("AMD  —",13,Color.rgb(151,193,255),false);root.addView(analysisAmd);
        analysisText=text("Waiting for scan.",13,Color.rgb(184,196,213),false);root.addView(analysisText);
        root.addView(text("Signal-only / paper mode. No real order is placed. Background timing is controlled by Android, so v4 replays missed 5m candle closes and stores confirmed signals in History instead of losing them.",12,Color.rgb(222,190,105),false));
        setContentView(sv);
    }

    private void selectMarket(MarketDataClient.Market m){selected=m;for(Map.Entry<String,Button> e:marketButtons.entrySet())e.getValue().setEnabled(!e.getKey().equals(m.id));marketTitle.setText(m.displayName+"  ·  "+m.note);status.setText("Ready to scan "+m.displayName+".");refreshStoredState();clearAnalysis();}

    private void refreshStoredState(){
        TradeStore.ActiveTrade t=TradeStore.load(this,selected.id);if(t==null)showNoActive();else showActive(t,Double.NaN,"Stored active trade");
        SignalHistoryStore.Record r=SignalHistoryStore.latestPending(this,selected.id);showLatestSignal(r);
        String res=TradeStore.lastResult(this,selected.id);lastResult.setText(res==null||res.isEmpty()?"":"Last taken-trade result: "+res);
        long last=getSharedPreferences("trade_signal",MODE_PRIVATE).getLong("last_background_scan",0L);
        backgroundStatus.setText(last==0?"Background scanner: scheduled; no completed run recorded yet.":"Background scanner last completed: "+formatTime(last)+" · missed 5m candles are replayed.");
    }

    private void refreshHistoryCount(){historyButton.setText("HISTORY ("+SignalHistoryStore.newCount(this)+")");}

    private void scanSelected(){
        saveEquity();final MarketDataClient.Market market=selected;final double equity=loadEquity();status.setText("Scanning 5m + 15m + 1h closed candles…");
        new Thread(()->{
            try{
                List<SignalEngine.Candle> m5=MarketDataClient.fetchClosed(market,"5m",360),m15=MarketDataClient.fetchClosed(market,"15m",220),h1=MarketDataClient.fetchClosed(market,"1h",150);
                TradeStore.Update u=TradeStore.update(this,market.id,m5);SignalHistoryStore.updateUntakenOutcomes(this,market.id,m5);
                SignalEngine.Decision d=SignalEngine.analyzeScalp(m5,m15,h1,equity,SIGNAL_THRESHOLD);
                boolean added=d.signal!=null&&SignalHistoryStore.add(this,market,d.signal,System.currentTimeMillis());
                double live=MarketDataClient.fetchLastPrice(market);final TradeStore.Update fu=u;final SignalEngine.Decision fd=d;final boolean fadded=added;final double flive=live;
                runOnUiThread(()->{if(market!=selected)return;if(fu.closed)status.setText(fu.event);else if(fadded)status.setText("New confirmed signal saved to History. Take it only if you want this trade.");else status.setText("Scan complete.");
                    TradeStore.ActiveTrade t=TradeStore.load(this,market.id);if(t==null)showNoActive();else showActive(t,flive,fu.event);showDecision(fd);showLatestSignal(SignalHistoryStore.latestPending(this,market.id));refreshHistoryCount();});
            }catch(Exception e){runOnUiThread(()->status.setText("Data error: "+safe(e.getMessage())));}
        }).start();
    }

    private void takeLatestSignal(){
        final SignalHistoryStore.Record r=SignalHistoryStore.latestPending(this,selected.id);if(r==null){Toast.makeText(this,"No fresh signal available to take.",Toast.LENGTH_SHORT).show();refreshStoredState();return;}
        if(TradeStore.load(this,selected.id)!=null){Toast.makeText(this,"This market already has an active taken trade.",Toast.LENGTH_SHORT).show();return;}
        takeButton.setEnabled(false);status.setText("Checking live price before taking signal…");
        final MarketDataClient.Market market=selected;
        new Thread(()->{
            try{
                long now=System.currentTimeMillis();double live=MarketDataClient.fetchLastPrice(market);double bps=Math.abs(live-r.entry)/Math.max(r.entry,1e-12)*10000.0;double riskMove=Math.abs(live-r.entry)/Math.max(r.riskPerUnit,1e-12);
                boolean okay=r.isTakeable(now)&&bps<=MAX_TAKE_SLIPPAGE_BPS&&riskMove<=0.30;
                if(okay){boolean marked=SignalHistoryStore.markTaken(this,r.id,now);if(marked)TradeStore.openFromRecord(this,r,now);}
                runOnUiThread(()->{if(market!=selected)return;if(okay){status.setText("Trade marked TAKEN and is now ACTIVE.");showActive(TradeStore.load(this,market.id),live,"Taken by you");showLatestSignal(SignalHistoryStore.latestPending(this,market.id));}
                    else{status.setText("Signal is no longer a safe fresh entry: price moved too far or the 20-minute take window expired. It remains in History.");Toast.makeText(this,"Signal kept in History; not activated.",Toast.LENGTH_LONG).show();showLatestSignal(SignalHistoryStore.latestPending(this,market.id));}refreshHistoryCount();});
            }catch(Exception e){runOnUiThread(()->{status.setText("Could not verify live price: "+safe(e.getMessage()));takeButton.setEnabled(true);});}
        }).start();
    }

    private void showActive(TradeStore.ActiveTrade t,double live,String event){
        if(t==null){showNoActive();return;}activeSide.setText(t.side+" · ACTIVE");activeSide.setTextColor("BUY".equals(t.side)?Color.rgb(55,211,131):Color.rgb(255,104,124));
        activeMeta.setText(t.confidence+"% entry confidence · "+t.setupLabel+(event==null||event.isEmpty()?"":" · "+event));entry.setText("ENTRY  "+price(t.entry));sl.setText("STOP LOSS  "+price(t.stopLoss));tp.setText("TAKE PROFIT  "+price(t.takeProfit));risk.setText("2% PAPER SIZE  "+qty(t.qty)+" units");
        trail.setText(t.trailingActive?"TRAILING ACTIVE · protected stop "+price(t.stopLoss):"TRAILING activates near +0.8R: "+price(t.trailingTrigger));
        if(Double.isFinite(live)){double raw=(live-t.entry)/Math.max(t.entry,1e-12)*100.0;double favorable="BUY".equals(t.side)?raw:-raw;double rmove=("BUY".equals(t.side)?live-t.entry:t.entry-live)/Math.max(t.originalRisk,1e-12);activeMove.setText("LIVE "+price(live)+" · market "+signed(raw)+"% · trade "+signed(favorable)+"% · "+signed(rmove)+"R");activeMove.setTextColor(favorable>=0?Color.rgb(55,211,131):Color.rgb(255,104,124));}
        else activeMove.setText("MOVE FROM ENTRY  refresh/scan for live %");
    }

    private void showNoActive(){activeSide.setText("NO ACTIVE TRADE");activeSide.setTextColor(Color.rgb(175,185,198));activeMeta.setText("A signal becomes active only after you press TAKE / I'M INTERESTED.");activeMove.setText("MOVE FROM ENTRY  —");activeMove.setTextColor(Color.rgb(151,193,255));entry.setText("ENTRY  —");sl.setText("STOP LOSS  —");tp.setText("TAKE PROFIT  —");risk.setText("2% PAPER SIZE  —");trail.setText("TRAILING  —");}

    private void showLatestSignal(SignalHistoryStore.Record r){
        if(r==null){signalSide.setText("NO NEW SIGNAL");signalSide.setTextColor(Color.rgb(175,185,198));signalMeta.setText("Confirmed signals remain in History even when you sleep, ignore them, or have no budget.");signalEntry.setText("ENTRY  —");signalSl.setText("SL  —");signalTp.setText("TP  —");signalText.setText("");takeButton.setEnabled(false);return;}
        signalSide.setText(r.side+" · "+r.confidence+"%");signalSide.setTextColor("BUY".equals(r.side)?Color.rgb(55,211,131):Color.rgb(255,104,124));signalMeta.setText(formatTime(r.signalTime)+" · setup "+r.setupConfidence+"% · trigger "+r.triggerConfidence+"% · "+r.confirmation);
        signalEntry.setText("ENTRY  "+price(r.entry));signalSl.setText("SL  "+price(r.stopLoss));signalTp.setText("TP  "+price(r.takeProfit)+" · R:R 1:"+String.format(Locale.US,"%.2f",r.riskReward));signalText.setText(r.amdState);takeButton.setEnabled(r.isTakeable(System.currentTimeMillis())&&TradeStore.load(this,r.marketId)==null);
    }

    private void showDecision(SignalEngine.Decision d){if(d==null){clearAnalysis();return;}analysisDirection.setText("Direction  "+d.direction);analysisDirection.setTextColor("BUY".equals(d.direction)?Color.rgb(55,211,131):"SELL".equals(d.direction)?Color.rgb(255,104,124):Color.WHITE);analysisScores.setText("15m/1h setup  "+d.setupConfidence+"%  ·  5m trigger  "+d.triggerConfidence+"%");analysisAmd.setText("AMD  "+d.amdState);analysisText.setText(d.summary);}
    private void clearAnalysis(){analysisDirection.setText("Direction  —");analysisDirection.setTextColor(Color.WHITE);analysisScores.setText("15m/1h setup  —  ·  5m trigger  —");analysisAmd.setText("AMD  —");analysisText.setText("Waiting for scan.");}

    private double loadEquity(){android.content.SharedPreferences p=getSharedPreferences("trade_signal",MODE_PRIVATE);return Double.longBitsToDouble(p.getLong("paper_equity_bits",Double.doubleToRawLongBits(1000.0)));}
    private void saveEquity(){double v=1000;try{v=Double.parseDouble(equityInput.getText().toString().trim());}catch(Exception ignored){}if(!Double.isFinite(v)||v<=0)v=1000;getSharedPreferences("trade_signal",MODE_PRIVATE).edit().putLong("paper_equity_bits",Double.doubleToRawLongBits(v)).putInt("threshold",SIGNAL_THRESHOLD).apply();}
    private static String safe(String s){return s==null||s.isEmpty()?"unknown error":s;}private static String price(double x){return x>=100?String.format(Locale.US,"%.2f",x):x>=1?String.format(Locale.US,"%.4f",x):String.format(Locale.US,"%.6f",x);}private static String qty(double x){return x>=100?String.format(Locale.US,"%.2f",x):x>=1?String.format(Locale.US,"%.4f",x):String.format(Locale.US,"%.6f",x);}private static String signed(double x){return (x>=0?"+":"")+String.format(Locale.US,"%.2f",x);}private static String formatTime(long ms){return new SimpleDateFormat("dd MMM HH:mm",Locale.getDefault()).format(new Date(ms));}
}
