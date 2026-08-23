package com.tradesignal.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import java.util.*;

/** Handles TAKE and AWAY buttons directly from a signal notification. */
public class SignalActionReceiver extends BroadcastReceiver {
    public static final String ACTION_TAKE="com.tradesignal.app.action.TAKE_SIGNAL";
    public static final String ACTION_AWAY="com.tradesignal.app.action.AWAY_SIGNAL";
    public static final String EXTRA_SIGNAL_ID="signal_id";
    public static final String EXTRA_MARKET_ID="market_id";
    public static final String EXTRA_NOTIFICATION_ID="notification_id";
    private static final double MAX_TAKE_SLIPPAGE_BPS=25.0;

    @Override public void onReceive(Context context,Intent intent){
        if(intent==null)return;
        String action=intent.getAction();
        String signalId=intent.getStringExtra(EXTRA_SIGNAL_ID);
        String marketId=intent.getStringExtra(EXTRA_MARKET_ID);
        int notificationId=intent.getIntExtra(EXTRA_NOTIFICATION_ID,0);
        if(signalId==null||marketId==null)return;

        if(ACTION_AWAY.equals(action)){
            SignalHistoryStore.markAway(context,signalId);
            cancel(context,notificationId);
            postInfo(context,marketId,"Signal moved to History","AWAY selected. The signal will not become active, but its paper outcome will still be tracked.");
            return;
        }

        if(!ACTION_TAKE.equals(action))return;
        final PendingResult pending=goAsync();
        new Thread(()->{
            try{
                SignalHistoryStore.Record r=SignalHistoryStore.byId(context,signalId);
                if(r==null){postInfo(context,marketId,"Could not take signal","Signal record was not found. It remains in History.");return;}
                if(TradeStore.load(context,marketId)!=null){postInfo(context,marketId,"Trade not activated","There is already an active taken trade for this market.");return;}
                long now=System.currentTimeMillis();
                MarketDataClient.Market market=MarketDataClient.byId(marketId);
                double live=MarketDataClient.fetchLastPrice(market);
                double bps=Math.abs(live-r.entry)/Math.max(r.entry,1e-12)*10000.0;
                double riskMove=Math.abs(live-r.entry)/Math.max(r.riskPerUnit,1e-12);
                boolean okay=r.isTakeable(now)&&bps<=MAX_TAKE_SLIPPAGE_BPS&&riskMove<=0.30;
                if(!okay){
                    SignalHistoryStore.markAway(context,signalId);
                    postInfo(context,marketId,"Signal kept in History","TAKE was too late or price had moved too far from the confirmed entry, so it was not activated.");
                    return;
                }
                boolean marked=SignalHistoryStore.markTaken(context,signalId,now);
                boolean opened=marked&&TradeStore.openFromRecord(context,r,now);
                if(opened){
                    postInfo(context,marketId,market.displayName+" "+r.side+" · ACTIVE","Taken from notification. Entry "+price(r.entry)+" · SL "+price(r.stopLoss)+" · TP "+price(r.takeProfit)+" · Quality "+r.qualityScore+"/10");
                }else{
                    postInfo(context,marketId,"Trade not activated","The signal could not be activated and remains recorded in History.");
                }
            }catch(Exception e){
                postInfo(context,marketId,"Could not verify TAKE","Network/live-price verification failed. The signal remains in History and was not activated.");
            }finally{
                cancel(context,notificationId);
                pending.finish();
            }
        },"signal-action").start();
    }

    private static void cancel(Context ctx,int id){
        if(id==0)return;NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.cancel(id);
    }

    private static void postInfo(Context ctx,String marketId,String title,String text){
        MarketDataClient.Market market=MarketDataClient.byId(marketId);
        Intent open=new Intent(ctx,MainActivity.class).putExtra("market",market.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(ctx,Math.abs((marketId+title).hashCode()),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(ctx,SignalWorker.CHANNEL_ID):new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi).setPriority(Notification.PRIORITY_HIGH);
        try{NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.notify(Math.abs((title+System.currentTimeMillis()).hashCode()),b.build());}catch(SecurityException ignored){}
    }

    private static String price(double x){return x>=100?String.format(Locale.US,"%.2f",x):x>=1?String.format(Locale.US,"%.4f",x):String.format(Locale.US,"%.6f",x);}
}
