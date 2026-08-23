package com.tradesignal.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

public final class SignalHistoryStore {
    private SignalHistoryStore() {}
    private static final String PREFS="trade_signal";
    private static final String KEY="signal_history_v4";
    public static final long TAKE_WINDOW_MS=20L*60L*1000L;
    public static final long PAPER_TRACK_MS=60L*60L*1000L;
    private static final int MAX_RECORDS=500;

    public static final class Record {
        public String id, marketId, symbol, side, confirmation, amdState, status, outcome;
        public double entry, stopLoss, takeProfit, riskPerUnit, qty, trailingTrigger, riskReward, outcomePrice;
        public int confidence, setupConfidence, triggerConfidence;
        public long signalTime, detectedAt, takenAt;

        public boolean isTakeable(long now){ return "NEW".equals(status) && outcome.isEmpty() && now-signalTime<=TAKE_WINDOW_MS; }
        public String statusLabel(long now){
            if("TAKEN".equals(status)) return outcome.isEmpty()?"TAKEN / ACTIVE":"TAKEN / "+outcome;
            if(!outcome.isEmpty()) return "NOT TAKEN / "+outcome;
            if(now-signalTime>TAKE_WINDOW_MS) return "NOT TAKEN / EXPIRED";
            return "NEW / AVAILABLE";
        }
    }

    public static synchronized boolean add(Context ctx, MarketDataClient.Market market, SignalEngine.Signal s, long detectedAt){
        List<Record> all=load(ctx);
        String id=market.id+"_"+s.candleCloseTime+"_"+s.side;
        for(Record r:all) if(id.equals(r.id)) return false;
        Record r=new Record(); r.id=id; r.marketId=market.id; r.symbol=market.displayName; r.side=s.side;
        r.entry=s.entry; r.stopLoss=s.stopLoss; r.takeProfit=s.takeProfit; r.riskPerUnit=s.riskPerUnit; r.qty=s.qtyAt2Pct;
        r.trailingTrigger=s.trailingTrigger; r.riskReward=s.riskReward; r.confidence=s.confidence;
        r.setupConfidence=s.setupConfidence; r.triggerConfidence=s.triggerConfidence; r.confirmation=s.confirmationLabel;
        r.amdState=s.amdState; r.signalTime=s.candleCloseTime; r.detectedAt=detectedAt; r.takenAt=0L;
        r.status="NEW"; r.outcome=""; r.outcomePrice=0;
        all.add(0,r); trim(all); save(ctx,all); return true;
    }

    public static synchronized List<Record> all(Context ctx){
        List<Record> all=load(ctx); expireOldInMemory(all,System.currentTimeMillis()); save(ctx,all); return all;
    }

    public static synchronized Record byId(Context ctx,String id){
        for(Record r:load(ctx)) if(r.id.equals(id)) return r; return null;
    }

    public static synchronized Record latestPending(Context ctx,String marketId){
        List<Record> all=load(ctx); long now=System.currentTimeMillis(); expireOldInMemory(all,now); save(ctx,all);
        for(Record r:all) if(r.marketId.equals(marketId)&&r.isTakeable(now)) return r; return null;
    }

    public static synchronized int newCount(Context ctx){
        List<Record> all=load(ctx); long now=System.currentTimeMillis(); expireOldInMemory(all,now); save(ctx,all);
        int n=0; for(Record r:all) if(r.isTakeable(now)) n++; return n;
    }

    public static synchronized boolean markTaken(Context ctx,String id,long takenAt){
        List<Record> all=load(ctx); boolean changed=false;
        for(Record r:all) if(r.id.equals(id) && r.isTakeable(takenAt)){r.status="TAKEN";r.takenAt=takenAt;changed=true;break;}
        if(changed) save(ctx,all); return changed;
    }

    public static synchronized void markTakenOutcome(Context ctx,String id,String outcome,double price){
        if(id==null||id.isEmpty()) return;
        List<Record> all=load(ctx); boolean changed=false;
        for(Record r:all) if(r.id.equals(id)){r.status="TAKEN";r.outcome=outcome;r.outcomePrice=price;changed=true;break;}
        if(changed) save(ctx,all);
    }

    public static synchronized int updateUntakenOutcomes(Context ctx,String marketId,List<SignalEngine.Candle> m5){
        if(m5==null||m5.isEmpty()) return 0;
        List<Record> all=load(ctx); long now=System.currentTimeMillis(); int changed=0;
        for(Record r:all){
            if(!r.marketId.equals(marketId)||"TAKEN".equals(r.status)||!r.outcome.isEmpty()) continue;
            if(now-r.signalTime>TAKE_WINDOW_MS) r.status="MISSED";
            long end=r.signalTime+PAPER_TRACK_MS;
            for(SignalEngine.Candle c:m5){
                if(c.closeTime<=r.signalTime||c.openTime>end) continue;
                if("BUY".equals(r.side)){
                    if(c.low<=r.stopLoss){r.outcome="SL";r.outcomePrice=r.stopLoss;r.status="MISSED";changed++;break;}
                    if(c.high>=r.takeProfit){r.outcome="TP";r.outcomePrice=r.takeProfit;r.status="MISSED";changed++;break;}
                }else{
                    if(c.high>=r.stopLoss){r.outcome="SL";r.outcomePrice=r.stopLoss;r.status="MISSED";changed++;break;}
                    if(c.low<=r.takeProfit){r.outcome="TP";r.outcomePrice=r.takeProfit;r.status="MISSED";changed++;break;}
                }
            }
            if(r.outcome.isEmpty() && now>end){r.outcome="TIME";r.status="MISSED";changed++;}
        }
        expireOldInMemory(all,now); save(ctx,all); return changed;
    }

    private static void expireOldInMemory(List<Record> all,long now){
        for(Record r:all) if("NEW".equals(r.status) && now-r.signalTime>TAKE_WINDOW_MS) r.status="MISSED";
    }

    private static List<Record> load(Context ctx){
        SharedPreferences p=ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE); String raw=p.getString(KEY,"[]");
        ArrayList<Record> out=new ArrayList<>();
        try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++)out.add(fromJson(a.getJSONObject(i)));}catch(Exception ignored){}
        Collections.sort(out,(a,b)->Long.compare(b.signalTime,a.signalTime)); return out;
    }

    private static void save(Context ctx,List<Record> all){
        trim(all); JSONArray a=new JSONArray(); for(Record r:all) a.put(toJson(r));
        ctx.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(KEY,a.toString()).apply();
    }
    private static void trim(List<Record> all){ while(all.size()>MAX_RECORDS) all.remove(all.size()-1); }

    private static JSONObject toJson(Record r){
        JSONObject o=new JSONObject();
        try{
            o.put("id",r.id).put("marketId",r.marketId).put("symbol",r.symbol).put("side",r.side)
             .put("entry",r.entry).put("sl",r.stopLoss).put("tp",r.takeProfit).put("risk",r.riskPerUnit)
             .put("qty",r.qty).put("trigger",r.trailingTrigger).put("rr",r.riskReward)
             .put("confidence",r.confidence).put("setup",r.setupConfidence).put("triggerConfidence",r.triggerConfidence)
             .put("confirmation",r.confirmation).put("amd",r.amdState).put("signalTime",r.signalTime)
             .put("detectedAt",r.detectedAt).put("takenAt",r.takenAt).put("status",r.status)
             .put("outcome",r.outcome).put("outcomePrice",r.outcomePrice);
        }catch(JSONException ignored){}
        return o;
    }

    private static Record fromJson(JSONObject o){
        Record r=new Record();
        r.id=o.optString("id","");r.marketId=o.optString("marketId","");r.symbol=o.optString("symbol",r.marketId);
        r.side=o.optString("side","");r.entry=o.optDouble("entry",0);r.stopLoss=o.optDouble("sl",0);r.takeProfit=o.optDouble("tp",0);
        r.riskPerUnit=o.optDouble("risk",Math.abs(r.entry-r.stopLoss));r.qty=o.optDouble("qty",0);r.trailingTrigger=o.optDouble("trigger",0);
        r.riskReward=o.optDouble("rr",1.3);r.confidence=o.optInt("confidence",0);r.setupConfidence=o.optInt("setup",0);
        r.triggerConfidence=o.optInt("triggerConfidence",0);r.confirmation=o.optString("confirmation","");r.amdState=o.optString("amd","");
        r.signalTime=o.optLong("signalTime",0);r.detectedAt=o.optLong("detectedAt",0);r.takenAt=o.optLong("takenAt",0);
        r.status=o.optString("status","NEW");r.outcome=o.optString("outcome","");r.outcomePrice=o.optDouble("outcomePrice",0);return r;
    }
}
