package com.tradesignal.app;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    private LinearLayout root;
    private TextView status, side, entry, sl, tp, reason;
    private static final String URL_KLINES = "https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=15m&limit=150";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        buildUi();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setPadding(0,10,0,10);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this); root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(36,42,36,60); root.setBackgroundColor(Color.rgb(8,11,16)); sv.addView(root);
        root.addView(text("SIGNAL ASSISTANT", 12, Color.rgb(142,155,173), true));
        root.addView(text("TradeSignal", 32, Color.WHITE, true));
        status = text("BTCUSDT · 15m · ready",14,Color.rgb(142,155,173),false); root.addView(status);
        root.addView(text("Latest setup",20,Color.WHITE,true));
        side = text("NO SIGNAL",28,Color.rgb(174,184,197),true); root.addView(side);
        entry = text("ENTRY   —",18,Color.WHITE,true); root.addView(entry);
        sl = text("STOP LOSS   —",18,Color.WHITE,true); root.addView(sl);
        tp = text("TAKE PROFIT   —",18,Color.WHITE,true); root.addView(tp);
        reason = text("The strategy waits for a strict closed-candle setup.",14,Color.rgb(187,197,209),false); root.addView(reason);
        Button scan = new Button(this); scan.setText("SCAN NOW"); scan.setOnClickListener(v -> scan()); root.addView(scan);
        Button demo = new Button(this); demo.setText("DEMO SIGNAL"); demo.setOnClickListener(v -> showSignal("BUY", 65000, 64500, 66000, "Demo only — not a real recommendation.")); root.addView(demo);
        TextView warning = text("Risk control\nSignals are rule-based and can lose. Demo-test them first. This app never places trades automatically.",13,Color.rgb(220,190,100),false); warning.setPadding(0,30,0,10); root.addView(warning);
        setContentView(sv);
    }

    private void scan() {
        status.setText("Scanning BTCUSDT 15m…");
        new Thread(() -> {
            try {
                HttpURLConnection c=(HttpURLConnection)new URL(URL_KLINES).openConnection(); c.setConnectTimeout(12000); c.setReadTimeout(12000);
                String json; try(InputStream in=c.getInputStream()){ json=new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                JSONArray a=new JSONArray(json); List<Double> closes=new ArrayList<>(), highs=new ArrayList<>(), lows=new ArrayList<>();
                long now=System.currentTimeMillis();
                for(int x=0;x<a.length();x++){ JSONArray k=a.getJSONArray(x); if(k.getLong(6)>=now) continue; closes.add(k.getDouble(4)); highs.add(k.getDouble(2)); lows.add(k.getDouble(3)); }
                Signal s=strategy(closes,highs,lows);
                runOnUiThread(() -> { if(s==null){ status.setText("Scan complete · no valid setup"); side.setText("NO SIGNAL"); reason.setText("No EMA/RSI pullback confirmation on the latest closed candle."); } else showSignal(s.side,s.entry,s.sl,s.tp,s.reason); });
            } catch(Exception e){ runOnUiThread(() -> status.setText("Network error: "+e.getMessage())); }
        }).start();
    }

    static double[] ema(List<Double> v,int p){ double[] o=new double[v.size()]; Arrays.fill(o,Double.NaN); if(v.size()<p)return o; double s=0; for(int i=0;i<p;i++)s+=v.get(i); double cur=s/p; o[p-1]=cur; double m=2.0/(p+1); for(int i=p;i<v.size();i++){cur=(v.get(i)-cur)*m+cur;o[i]=cur;}return o; }
    static double[] rsi(List<Double> v,int p){ double[] o=new double[v.size()];Arrays.fill(o,Double.NaN);if(v.size()<=p)return o;double g=0,l=0;for(int i=1;i<=p;i++){double d=v.get(i)-v.get(i-1);g+=Math.max(d,0);l+=Math.max(-d,0);}g/=p;l/=p;o[p]=l==0?100:100-100/(1+g/l);for(int i=p+1;i<v.size();i++){double d=v.get(i)-v.get(i-1);g=(g*(p-1)+Math.max(d,0))/p;l=(l*(p-1)+Math.max(-d,0))/p;o[i]=l==0?100:100-100/(1+g/l);}return o;}
    static double[] atr(List<Double> h,List<Double> l,List<Double> c,int p){double[] o=new double[c.size()];Arrays.fill(o,Double.NaN);if(c.size()<=p)return o;double[] tr=new double[c.size()];tr[0]=h.get(0)-l.get(0);for(int i=1;i<c.size();i++)tr[i]=Math.max(h.get(i)-l.get(i),Math.max(Math.abs(h.get(i)-c.get(i-1)),Math.abs(l.get(i)-c.get(i-1))));double cur=0;for(int i=1;i<=p;i++)cur+=tr[i];cur/=p;o[p]=cur;for(int i=p+1;i<c.size();i++){cur=(cur*(p-1)+tr[i])/p;o[i]=cur;}return o;}
    static class Signal {String side,reason;double entry,sl,tp;Signal(String a,double b,double c,double d,String e){side=a;entry=b;sl=c;tp=d;reason=e;}}
    static Signal strategy(List<Double> c,List<Double> h,List<Double> l){if(c.size()<60)return null;double[] e20=ema(c,20),e50=ema(c,50),r=rsi(c,14),a=atr(h,l,c,14);int i=c.size()-1,p=i-1;double cn=c.get(i),cp=c.get(p);boolean buy=e20[i]>e50[i]&&cp<=e20[p]&&cn>e20[i]&&r[p]<48&&r[i]>=48&&r[i]<68;boolean sell=e20[i]<e50[i]&&cp>=e20[p]&&cn<e20[i]&&r[p]>52&&r[i]<=52&&r[i]>32;if(!buy&&!sell)return null;double risk=1.5*a[i];if(buy)return new Signal("BUY",cn,cn-risk,cn+2*risk,"Uptrend + EMA20 reclaim + RSI confirmation.");return new Signal("SELL",cn,cn+risk,cn-2*risk,"Downtrend + EMA20 rejection + RSI confirmation.");}

    private void showSignal(String s,double e,double stop,double take,String why){status.setText("BTCUSDT · 15m · signal ready");side.setText(s);side.setTextColor(s.equals("BUY")?Color.rgb(53,208,127):Color.rgb(255,100,120));entry.setText(String.format(Locale.US,"ENTRY   %.2f",e));sl.setText(String.format(Locale.US,"STOP LOSS   %.2f",stop));tp.setText(String.format(Locale.US,"TAKE PROFIT   %.2f",take));reason.setText(why+"  R:R 1:2");}
}
