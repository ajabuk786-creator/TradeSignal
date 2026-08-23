package com.tradesignal.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class SignalWorker extends Worker {
    public static final String UNIQUE_PERIODIC="TradeSignal-v5-periodic";
    public static final String UNIQUE_NOW="TradeSignal-v5-now";
    public static final String CHANNEL_ID="trade_signals";
    private static final long BACKFILL_MS=12L*60L*60L*1000L;
    private static final long FRESH_NOTIFY_MS=25L*60L*1000L;

    public SignalWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    @NonNull @Override public Result doWork(){
        Context ctx=getApplicationContext();ensureChannel(ctx);
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("trade_signal",Context.MODE_PRIVATE);
        double equity=Double.longBitsToDouble(prefs.getLong("paper_equity_bits",Double.doubleToRawLongBits(1000.0)));
        boolean transientError=false;int oldBackfilled=0;

        for(MarketDataClient.Market market:MarketDataClient.MARKETS){
            try{
                List<SignalEngine.Candle> m5=MarketDataClient.fetchClosed(market,"5m",360);
                List<SignalEngine.Candle> m15=MarketDataClient.fetchClosed(market,"15m",240);
                List<SignalEngine.Candle> h1=MarketDataClient.fetchClosed(market,"1h",160);

                TradeStore.Update tradeUpdate=TradeStore.update(ctx,market.id,m5);
                if(tradeUpdate.closed)notifyText(ctx,market,market.displayName+" taken trade closed",tradeUpdate.event);
                SignalHistoryStore.updateUntakenOutcomes(ctx,market.id,m5);

                long latestClose=m5.get(m5.size()-1).closeTime;
                long watermark=prefs.getLong("scan_watermark_v5_"+market.id,Math.max(0,latestClose-BACKFILL_MS));
                int start=Math.max(145,m5.size()-145);
                for(int i=start;i<m5.size();i++){
                    long t=m5.get(i).closeTime;if(t<=watermark)continue;
                    List<SignalEngine.Candle> five=new ArrayList<>(m5.subList(0,i+1));
                    List<SignalEngine.Candle> fifteen=through(m15,t),hour=through(h1,t);
                    if(fifteen.size()<100||hour.size()<80)continue;
                    SignalEngine.Decision d=SignalEngine.analyzeScalp(five,fifteen,hour,equity,0);
                    if(d.signal!=null){
                        boolean added=SignalHistoryStore.add(ctx,market,d.signal,System.currentTimeMillis());
                        if(added){
                            long age=System.currentTimeMillis()-d.signal.candleCloseTime;
                            if(age<=FRESH_NOTIFY_MS)notifySignal(ctx,market,d.signal);else oldBackfilled++;
                        }
                    }
                }
                prefs.edit().putLong("scan_watermark_v5_"+market.id,latestClose).apply();
            }catch(Exception e){transientError=true;}
        }
        prefs.edit().putLong("last_background_scan",System.currentTimeMillis()).apply();
        if(oldBackfilled>0)notifySummary(ctx,oldBackfilled);
        return transientError?Result.retry():Result.success();
    }

    private static List<SignalEngine.Candle> through(List<SignalEngine.Candle> all,long closeTime){
        ArrayList<SignalEngine.Candle> out=new ArrayList<>();for(SignalEngine.Candle c:all){if(c.closeTime<=closeTime)out.add(c);else break;}return out;
    }

    public static void schedule(Context ctx){
        WorkManager wm=WorkManager.getInstance(ctx);
        wm.cancelUniqueWork("TradeSignal-v4-periodic");wm.cancelUniqueWork("TradeSignal-v4-now");
        wm.cancelUniqueWork("TradeSignal-scalp-background-scan");wm.cancelUniqueWork("TradeSignal-15m-background-scan");
        Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest periodic=new PeriodicWorkRequest.Builder(SignalWorker.class,15,TimeUnit.MINUTES,5,TimeUnit.MINUTES).setConstraints(c).build();
        wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,periodic);
    }

    public static void enqueueNow(Context ctx){
        Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest once=new OneTimeWorkRequest.Builder(SignalWorker.class).setConstraints(c).build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_NOW,ExistingWorkPolicy.KEEP,once);
    }

    public static void ensureChannel(Context ctx){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){
            NotificationManager nm=ctx.getSystemService(NotificationManager.class);if(nm==null)return;
            NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"TradeSignal alerts",NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("v5 confirmed scalp signals with TAKE and AWAY actions");nm.createNotificationChannel(ch);
        }
    }

    private void notifySignal(Context ctx,MarketDataClient.Market market,SignalEngine.Signal s){
        String signalId=market.id+"_"+s.candleCloseTime+"_"+s.side+"_v5";
        int notificationId=safeId(signalId.hashCode());

        Intent open=new Intent(ctx,MainActivity.class).putExtra("market",market.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi=PendingIntent.getActivity(ctx,safeId((signalId+"open").hashCode()),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent take=new Intent(ctx,SignalActionReceiver.class).setAction(SignalActionReceiver.ACTION_TAKE)
                .putExtra(SignalActionReceiver.EXTRA_SIGNAL_ID,signalId).putExtra(SignalActionReceiver.EXTRA_MARKET_ID,market.id)
                .putExtra(SignalActionReceiver.EXTRA_NOTIFICATION_ID,notificationId);
        PendingIntent takePi=PendingIntent.getBroadcast(ctx,safeId((signalId+"take").hashCode()),take,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        Intent away=new Intent(ctx,SignalActionReceiver.class).setAction(SignalActionReceiver.ACTION_AWAY)
                .putExtra(SignalActionReceiver.EXTRA_SIGNAL_ID,signalId).putExtra(SignalActionReceiver.EXTRA_MARKET_ID,market.id)
                .putExtra(SignalActionReceiver.EXTRA_NOTIFICATION_ID,notificationId);
        PendingIntent awayPi=PendingIntent.getBroadcast(ctx,safeId((signalId+"away").hashCode()),away,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);

        String title=market.displayName+"  "+s.side+" · Quality "+s.qualityScore+"/10";
        String body=String.format(Locale.US,"Entry %.5f | SL %.5f | TP %.5f | R:R 1:%.2f\n%s · %s",s.entry,s.stopLoss,s.takeProfit,s.riskReward,s.confirmationLabel,s.regime);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(ctx,CHANNEL_ID):new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body+"\nTAKE activates tracking on the main page. AWAY keeps it only in History."))
                .setAutoCancel(false).setContentIntent(openPi).setPriority(Notification.PRIORITY_HIGH)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_input_add,"TAKE",takePi).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"AWAY",awayPi).build());
        try{NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.notify(notificationId,b.build());}catch(SecurityException ignored){}
    }

    private void notifySummary(Context ctx,int n){
        MarketDataClient.Market m=MarketDataClient.MARKETS[0];
        notifyText(ctx,m,"TradeSignal history updated",n+" older confirmed v5 signal"+(n==1?" was":"s were")+" reconstructed from missed 5m closes and saved to History.");
    }

    private void notifyText(Context ctx,MarketDataClient.Market market,String title,String text){
        Intent intent=new Intent(ctx,MainActivity.class).putExtra("market",market.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi=PendingIntent.getActivity(ctx,safeId((market.id+title).hashCode()),intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(ctx,CHANNEL_ID):new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(pi).setPriority(Notification.PRIORITY_HIGH);
        try{NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.notify(safeId((market.id+title+System.currentTimeMillis()).hashCode()),b.build());}catch(SecurityException ignored){}
    }

    private static int safeId(int x){return x==Integer.MIN_VALUE?1:Math.abs(x);}
}
