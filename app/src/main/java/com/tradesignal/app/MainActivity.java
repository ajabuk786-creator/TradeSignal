package com.tradesignal.app;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final double MAX_TAKE_SLIPPAGE_BPS=25.0;
    private final MarketDataClient.Market market=MarketDataClient.MARKETS[0];
    private LinearLayout root;
    private EditText apiKeyInput;
    private TextView status,backgroundStatus,activeSide,activeMove,activeMeta,analysisTitle,analysisZones,analysisText;
    private TextView sigSide,sigMeta,sigEntry,sigSl,sigTp,sigWhy;
    private Button historyButton,takeButton,awayButton;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);MarketDataClient.init(this);SignalWorker.ensureChannel(this);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);
        buildUi();SignalWorker.schedule(this);SignalWorker.enqueueNow(this);
    }

    @Override protected void onResume(){super.onResume();MarketDataClient.init(this);if(root!=null){refreshStored();refreshHistoryCount();}}

    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,7,0,7);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}

    private void buildUi(){
        ScrollView sv=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(30,34,30,54);root.setBackgroundColor(Color.rgb(8,11,16));sv.addView(root);
        root.addView(text("XAU/USD FOREX SCALPER",12,Color.rgb(133,151,175),true));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);TextView title=text("Gold Scalper v6",29,Color.WHITE,true);top.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        historyButton=new Button(this);historyButton.setText("HISTORY");historyButton.setAllCaps(false);historyButton.setOnClickListener(v->startActivity(new Intent(this,HistoryActivity.class)));top.addView(historyButton);root.addView(top);
        root.addView(text("15m support/resistance + supply/demand → 5m rejection / sweep / breakout-retest → signal",13,Color.rgb(165,178,196),false));
        backgroundStatus=text("Background scanner: starting…",12,Color.rgb(122,201,155),false);root.addView(backgroundStatus);

        root.addView(text("Market data key (Twelve Data — NOT a broker key)",13,Color.rgb(190,201,218),true));
        apiKeyInput=new EditText(this);apiKeyInput.setTextColor(Color.WHITE);apiKeyInput.setHintTextColor(Color.GRAY);apiKeyInput.setHint("Leave blank to try demo access");apiKeyInput.setSingleLine(true);apiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);apiKeyInput.setText(MarketDataClient.storedApiKey(this));root.addView(apiKeyInput);
        Button save=new Button(this);save.setText("SAVE DATA KEY");save.setOnClickListener(v->{MarketDataClient.saveApiKey(this,apiKeyInput.getText().toString());Toast.makeText(this,"Market-data key saved locally.",Toast.LENGTH_SHORT).show();SignalWorker.enqueueNow(this);});root.addView(save);

        status=text("Ready to scan XAU/USD.",13,Color.rgb(143,159,181),false);root.addView(status);
        Button scan=new Button(this);scan.setText("SCAN XAU/USD NOW");scan.setOnClickListener(v->scanNow());root.addView(scan);

        root.addView(text("ACTIVE TRADE — ONLY IF YOU PRESS TAKE",18,Color.WHITE,true));
        activeSide=text("NO ACTIVE TRADE",27,Color.rgb(175,185,198),true);root.addView(activeSide);activeMeta=text("",13,Color.rgb(198,208,222),false);root.addView(activeMeta);activeMove=text("MOVE FROM ENTRY  —",16,Color.rgb(151,193,255),true);root.addView(activeMove);

        root.addView(text("BEST CONFIRMED SIGNAL",18,Color.WHITE,true));
        sigSide=text("NO SIGNAL",27,Color.rgb(175,185,198),true);root.addView(sigSide);sigMeta=text("A trade is shown only after a 5m confirmation at a mapped 15m zone.",13,Color.rgb(194,205,220),false);root.addView(sigMeta);
        sigEntry=text("ENTRY  —",16,Color.WHITE,true);root.addView(sigEntry);sigSl=text("STOP LOSS  —",16,Color.WHITE,true);root.addView(sigSl);sigTp=text("TAKE PROFIT  —",16,Color.WHITE,true);root.addView(sigTp);sigWhy=text("",13,Color.rgb(184,196,213),false);root.addView(sigWhy);
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);takeButton=new Button(this);takeButton.setText("TAKE");takeButton.setEnabled(false);takeButton.setOnClickListener(v->takeLatest());awayButton=new Button(this);awayButton.setText("AWAY");awayButton.setEnabled(false);awayButton.setOnClickListener(v->awayLatest());actions.addView(takeButton,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));actions.addView(awayButton,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));root.addView(actions);

        root.addView(text("CURRENT XAU/USD MAP",18,Color.WHITE,true));analysisTitle=text("Price / bias  —",18,Color.WHITE,true);root.addView(analysisTitle);analysisZones=text("Support — · Resistance —\nDemand — · Supply —",14,Color.rgb(151,193,255),true);root.addView(analysisZones);analysisText=text("Press SCAN to analyse the latest closed 5m and 15m candles.",13,Color.rgb(184,196,213),false);root.addView(analysisText);
        root.addView(text("Signal-only / paper tracking. The app cannot place forex orders and does not ask for MT5 or broker credentials.",12,Color.rgb(222,190,105),false));
        setContentView(sv);refreshStored();
    }

    private void scanNow(){
        MarketDataClient.saveApiKey(this,apiKeyInput.getText().toString());MarketDataClient.init(this);status.setText("Scanning XAU/USD 5m + 15m structure…");
        new Thread(()->{
            try{
                List<SignalEngine.Candle> m5=MarketDataClient.fetchClosed(market,"5m",220);List<SignalEngine.Candle> m15=MarketDataClient.fetchClosed(market,"15m",180);
                TradeStore.Update u=TradeStore.update(this,market.id,m5);SignalHistoryStore.updateUntakenOutcomes(this,market.id,m5);
                SignalEngine.Decision d=SignalEngine.analyzeGold(m5,m15);boolean added=d.signal!=null&&SignalHistoryStore.add(this,market,d.signal,System.currentTimeMillis());double live=MarketDataClient.fetchLastPrice(market);
                runOnUiThread(()->{if(u.closed)status.setText(u.event);else if(added)status.setText("New XAU/USD signal confirmed and saved to History.");else status.setText("Scan complete.");showDecision(d);showLatest(SignalHistoryStore.latestPending(this,market.id));TradeStore.ActiveTrade t=TradeStore.load(this,market.id);if(t==null)showNoActive();else showActive(t,live);refreshHistoryCount();});
            }catch(Exception e){runOnUiThread(()->{status.setText("Data error: "+safe(e.getMessage()));analysisText.setText("If demo access does not support XAU/USD intraday data, enter a Twelve Data API key above and scan again.");});}
        },"xau-manual-scan").start();
    }

    private void showDecision(SignalEngine.Decision d){
        if(d==null)return;analysisTitle.setText("Price "+price(d.lastPrice)+" · "+d.regime+" · "+d.direction);
        analysisZones.setText("Support "+price(d.support)+" · Resistance "+price(d.resistance)+"\nDemand "+zone(d.demandLow,d.demandHigh)+" · Supply "+zone(d.supplyLow,d.supplyHigh));analysisText.setText(d.summary);
    }

    private void showLatest(SignalHistoryStore.Record r){
        long now=System.currentTimeMillis();if(r==null){sigSide.setText("NO SIGNAL");sigSide.setTextColor(Color.rgb(175,185,198));sigMeta.setText("No fresh confirmed signal is waiting.");sigEntry.setText("ENTRY  —");sigSl.setText("STOP LOSS  —");sigTp.setText("TAKE PROFIT  —");sigWhy.setText("");takeButton.setEnabled(false);awayButton.setEnabled(false);return;}
        sigSide.setText(r.side+" · Quality "+r.qualityScore+"/10");sigSide.setTextColor("BUY".equals(r.side)?Color.rgb(55,211,131):Color.rgb(255,104,124));sigMeta.setText(formatTime(r.signalTime)+" · "+r.confirmation+" · "+r.regime);sigEntry.setText("ENTRY  "+price(r.entry));sigSl.setText("STOP LOSS  "+price(r.stopLoss));sigTp.setText("TAKE PROFIT  "+price(r.takeProfit)+" · R:R 1:"+String.format(Locale.US,"%.2f",r.riskReward));sigWhy.setText(r.amdState);boolean ok=r.isTakeable(now)&&TradeStore.load(this,r.marketId)==null;takeButton.setEnabled(ok);awayButton.setEnabled("NEW".equals(r.status));
    }

    private void takeLatest(){
        SignalHistoryStore.Record r=SignalHistoryStore.latestPending(this,market.id);if(r==null)return;if(TradeStore.load(this,market.id)!=null){Toast.makeText(this,"An XAU trade is already active.",Toast.LENGTH_SHORT).show();return;}takeButton.setEnabled(false);status.setText("Checking live XAU/USD price…");
        new Thread(()->{try{MarketDataClient.init(this);long now=System.currentTimeMillis();double live=MarketDataClient.fetchLastPrice(market);double bps=Math.abs(live-r.entry)/Math.max(r.entry,1e-12)*10000.0;double riskMove=Math.abs(live-r.entry)/Math.max(r.riskPerUnit,1e-12);boolean ok=r.isTakeable(now)&&bps<=MAX_TAKE_SLIPPAGE_BPS&&riskMove<=0.30;if(ok){boolean marked=SignalHistoryStore.markTaken(this,r.id,now);if(marked)TradeStore.openFromRecord(this,r,now);}runOnUiThread(()->{if(ok){status.setText("TAKEN — now tracking this XAU/USD signal on the main page.");showActive(TradeStore.load(this,market.id),live);}else status.setText("Entry moved too far or signal expired. It stays in History.");showLatest(SignalHistoryStore.latestPending(this,market.id));refreshHistoryCount();});}catch(Exception e){runOnUiThread(()->{status.setText("Could not verify live price: "+safe(e.getMessage()));takeButton.setEnabled(true);});}},"xau-take").start();
    }

    private void awayLatest(){SignalHistoryStore.Record r=SignalHistoryStore.latestPending(this,market.id);if(r==null)return;SignalHistoryStore.markAway(this,r.id);status.setText("AWAY — signal moved to History for paper evaluation.");showLatest(SignalHistoryStore.latestPending(this,market.id));refreshHistoryCount();}

    private void refreshStored(){MarketDataClient.init(this);TradeStore.ActiveTrade t=TradeStore.load(this,market.id);if(t==null)showNoActive();else showActive(t,Double.NaN);showLatest(SignalHistoryStore.latestPending(this,market.id));long last=getSharedPreferences("trade_signal",MODE_PRIVATE).getLong("last_background_scan",0L);backgroundStatus.setText(last==0?"Background scanner: scheduled.":"Background scanner last completed: "+formatTime(last));}
    private void refreshHistoryCount(){historyButton.setText("HISTORY ("+SignalHistoryStore.newCount(this)+")");}
    private void showNoActive(){activeSide.setText("NO ACTIVE TRADE");activeSide.setTextColor(Color.rgb(175,185,198));activeMeta.setText("Press TAKE on a signal only when you want to track that trade.");activeMove.setText("MOVE FROM ENTRY  —");}
    private void showActive(TradeStore.ActiveTrade t,double live){if(t==null){showNoActive();return;}activeSide.setText(t.side+" XAU/USD · ACTIVE");activeSide.setTextColor("BUY".equals(t.side)?Color.rgb(55,211,131):Color.rgb(255,104,124));activeMeta.setText("Entry "+price(t.entry)+" · SL "+price(t.stopLoss)+" · TP "+price(t.takeProfit)+" · "+t.setupLabel);if(Double.isFinite(live)){double raw=(live-t.entry)/Math.max(t.entry,1e-12)*100.0;double trade="BUY".equals(t.side)?raw:-raw;double r=("BUY".equals(t.side)?live-t.entry:t.entry-live)/Math.max(t.originalRisk,1e-12);activeMove.setText("LIVE "+price(live)+" · trade "+signed(trade)+"% · "+signed(r)+"R");activeMove.setTextColor(trade>=0?Color.rgb(55,211,131):Color.rgb(255,104,124));}else activeMove.setText("Refresh/scan to update live move.");}

    private static String zone(double a,double b){return price(a)+"–"+price(b);}private static String price(double x){if(!Double.isFinite(x))return"—";return String.format(Locale.US,"%.2f",x);}private static String safe(String s){return s==null||s.isEmpty()?"unknown error":s;}private static String signed(double x){return(String.format(Locale.US,x>=0?"+%.2f":"%.2f",x));}private static String formatTime(long ms){return new SimpleDateFormat("dd MMM HH:mm",Locale.getDefault()).format(new Date(ms));}
}
