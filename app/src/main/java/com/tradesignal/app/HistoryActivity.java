package com.tradesignal.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class HistoryActivity extends Activity {
    private LinearLayout root;
    private static final double MAX_TAKE_SLIPPAGE_BPS=25.0;

    @Override public void onCreate(Bundle b){super.onCreate(b);build();}
    @Override protected void onResume(){super.onResume();if(root!=null)render();}

    private TextView text(String s,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,6,0,6);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}

    private void build(){ScrollView sv=new ScrollView(this);root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(28,32,28,50);root.setBackgroundColor(Color.rgb(8,11,16));sv.addView(root);setContentView(sv);render();}

    private void render(){
        root.removeAllViews();
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);TextView title=text("Signal History",28,Color.WHITE,true);top.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));Button back=new Button(this);back.setText("BACK");back.setOnClickListener(v->finish());top.addView(back);root.addView(top);
        root.addView(text("Every confirmed signal is stored here whether you were sleeping, skipped it, had no budget, or later chose TAKE. Untaken signals are paper-tracked for TP/SL/time outcome so you can evaluate the strategy.",13,Color.rgb(174,188,207),false));
        List<SignalHistoryStore.Record> all=SignalHistoryStore.all(this);if(all.isEmpty()){root.addView(text("No confirmed signals recorded yet.",18,Color.rgb(175,185,198),true));return;}
        int shown=0;
        for(SignalHistoryStore.Record r:all){if(shown++>=200)break;addRecord(r);}
    }

    private void addRecord(SignalHistoryStore.Record r){
        long now=System.currentTimeMillis();int sideColor="BUY".equals(r.side)?Color.rgb(55,211,131):Color.rgb(255,104,124);
        TextView head=text(r.symbol+"  "+r.side+"  "+r.confidence+"%",19,sideColor,true);head.setPadding(0,18,0,3);root.addView(head);
        root.addView(text(time(r.signalTime)+"  ·  "+r.statusLabel(now),13,Color.rgb(205,214,229),true));
        root.addView(text("Entry "+price(r.entry)+"   SL "+price(r.stopLoss)+"   TP "+price(r.takeProfit)+"   R:R 1:"+String.format(Locale.US,"%.2f",r.riskReward),14,Color.WHITE,true));
        root.addView(text("15m/1h "+r.setupConfidence+"% · 5m "+r.triggerConfidence+"% · "+r.confirmation,13,Color.rgb(185,197,214),false));
        if(r.amdState!=null&&!r.amdState.isEmpty())root.addView(text(r.amdState,12,Color.rgb(151,193,255),false));
        if(r.outcome!=null&&!r.outcome.isEmpty())root.addView(text("Paper outcome: "+r.outcome+(r.outcomePrice>0?" @ "+price(r.outcomePrice):""),13,"TP".equals(r.outcome)?Color.rgb(55,211,131):"SL".equals(r.outcome)?Color.rgb(255,104,124):Color.rgb(220,190,105),true));
        if(r.isTakeable(now)&&TradeStore.load(this,r.marketId)==null){Button take=new Button(this);take.setText("★ TAKE / I'M INTERESTED");take.setOnClickListener(v->tryTake(r,take));root.addView(take);}
        View sep=new View(this);sep.setBackgroundColor(Color.rgb(37,43,52));root.addView(sep,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,2));
    }

    private void tryTake(SignalHistoryStore.Record r,Button button){
        button.setEnabled(false);Toast.makeText(this,"Checking live price…",Toast.LENGTH_SHORT).show();
        MarketDataClient.Market market=MarketDataClient.byId(r.marketId);
        new Thread(()->{
            try{
                long now=System.currentTimeMillis();double live=MarketDataClient.fetchLastPrice(market);double bps=Math.abs(live-r.entry)/Math.max(r.entry,1e-12)*10000.0;double riskMove=Math.abs(live-r.entry)/Math.max(r.riskPerUnit,1e-12);
                boolean ok=r.isTakeable(now)&&bps<=MAX_TAKE_SLIPPAGE_BPS&&riskMove<=0.30&&TradeStore.load(this,r.marketId)==null;
                if(ok){boolean marked=SignalHistoryStore.markTaken(this,r.id,now);if(marked)TradeStore.openFromRecord(this,r,now);}
                runOnUiThread(()->{if(ok){Toast.makeText(this,"Marked TAKEN. It is now active on the main page.",Toast.LENGTH_LONG).show();Intent i=new Intent(this,MainActivity.class).putExtra("market",r.marketId);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);finish();}else{Toast.makeText(this,"Too old or price moved too far. Signal stays in History only.",Toast.LENGTH_LONG).show();render();}});
            }catch(Exception e){runOnUiThread(()->{Toast.makeText(this,"Could not verify live price.",Toast.LENGTH_LONG).show();button.setEnabled(true);});}
        }).start();
    }

    private static String time(long ms){return new SimpleDateFormat("dd MMM yyyy HH:mm",Locale.getDefault()).format(new Date(ms));}
    private static String price(double x){return x>=100?String.format(Locale.US,"%.2f",x):x>=1?String.format(Locale.US,"%.4f",x):String.format(Locale.US,"%.6f",x);}
}
