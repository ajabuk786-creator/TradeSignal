package com.tradesignal.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Background XAU/USD scanner. Android schedules roughly every 15m; missed 5m closes are replayed. */
public class SignalWorker extends Worker {
    public static final String UNIQUE_PERIODIC="TradeSignal-XAU-v6-periodic";
    public static final String UNIQUE_NOW="TradeSignal-XAU-v6-now";
    public static final String CHANNEL_ID="trade_signals";
    private static final long BACKFILL_MS=6L*60L*60L*1000L;
    private static final long FRESH_NOTIFY_MS=22L*60L*1000L;

    public SignalWorker(@NonNull Context context,@NonNull WorkerParameters params){super(context,params);}

    @NonNull @Override public Result doWork(){
        Context ctx=getApplicationContext();ensureChannel(ctx);MarketDataClient.init(ctx);MarketDataClient.Market market=MarketDataClient.MARKETS[0];
        try{
            List<SignalEngine.Candle> m5=MarketDataClient.fetchClosed(market,"5m",240);List<SignalEngine.Candle> m15=MarketDataClient.fetchClosed(market,"15m",180);
            TradeStore.Update u=TradeStore.update(ctx,market.id,m5);if(u.closed)notifyInfo(ctx,"XAU/USD taken trade closed",u.event);
            SignalHistoryStore.updateUntakenOutcomes(ctx,market.id,m5);
            android.content.SharedPreferences prefs=ctx.getSharedPreferences("trade_signal",Context.MODE_PRIVATE);
            long latest=m5.get(m5.size()-1).closeTime;long watermark=prefs.getLong("xau_v6_watermark",Math.max(0,latest-BACKFILL_MS));
            int start=Math.max(90,m5.size()-80);
            for(int i=start;i<m5.size();i++){
                long t=m5.get(i).closeTime;if(t<=watermark)continue;
                List<SignalEngine.Candle> five=new ArrayList<>(m5.subList(0,i+1));List<SignalEngine.Candle> fifteen=through(m15,t);if(fifteen.size()<80)continue;
                SignalEngine.Decision d=SignalEngine.analyzeGold(five,fifteen);if(d.signal==null)continue;
                boolean added=SignalHistoryStore.add(ctx,market,d.signal,System.currentTimeMillis());if(added&&System.currentTimeMillis()-d.signal.candleCloseTime<=FRESH_NOTIFY_MS)notifySignal(ctx,market,d.signal);
            }
            prefs.edit().putLong("xau_v6_watermark",latest).putLong("last_background_scan",System.currentTimeMillis()).apply();return Result.success();
        }catch(Exception e){ctx.getSharedPreferences("trade_signal",Context.MODE_PRIVATE).edit().putLong("last_background_scan",System.currentTimeMillis()).apply();return Result.retry();}
    }

    private static List<SignalEngine.Candle> through(List<SignalEngine.Candle> all,long t){ArrayList<SignalEngine.Candle> out=new ArrayList<>();for(SignalEngine.Candle c:all){if(c.closeTime<=t)out.add(c);else break;}return out;}

    public static void schedule(Context ctx){
        WorkManager wm=WorkManager.getInstance(ctx);wm.cancelUniqueWork("TradeSignal-v5-periodic");wm.cancelUniqueWork("TradeSignal-v4-periodic");wm.cancelUniqueWork("TradeSignal-scalp-background-scan");wm.cancelUniqueWork("TradeSignal-15m-background-scan");
        Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();PeriodicWorkRequest p=new PeriodicWorkRequest.Builder(SignalWorker.class,15,TimeUnit.MINUTES,5,TimeUnit.MINUTES).setConstraints(c).build();wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,p);
    }
    public static void enqueueNow(Context ctx){Constraints c=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();OneTimeWorkRequest r=new OneTimeWorkRequest.Builder(SignalWorker.class).setConstraints(c).build();WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_NOW,ExistingWorkPolicy.REPLACE,r);}

    public static void ensureChannel(Context ctx){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O){NotificationManager nm=ctx.getSystemService(NotificationManager.class);if(nm==null)return;NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"XAU/USD scalp signals",NotificationManager.IMPORTANCE_HIGH);ch.setDescription("Gold support/resistance and supply/demand scalp signals");nm.createNotificationChannel(ch);}}

    private void notifySignal(Context ctx,MarketDataClient.Market market,SignalEngine.Signal s){
        int notificationId=Math.abs((market.id+s.candleCloseTime+s.side).hashCode());
        Intent open=new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);PendingIntent openPi=PendingIntent.getActivity(ctx,notificationId,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent take=new Intent(ctx,SignalActionReceiver.class).setAction(SignalActionReceiver.ACTION_TAKE).putExtra(SignalActionReceiver.EXTRA_SIGNAL_ID,market.id+"_"+s.candleCloseTime+"_"+s.side+"_v6").putExtra(SignalActionReceiver.EXTRA_MARKET_ID,market.id).putExtra(SignalActionReceiver.EXTRA_NOTIFICATION_ID,notificationId);
        Intent away=new Intent(ctx,SignalActionReceiver.class).setAction(SignalActionReceiver.ACTION_AWAY).putExtra(SignalActionReceiver.EXTRA_SIGNAL_ID,market.id+"_"+s.candleCloseTime+"_"+s.side+"_v6").putExtra(SignalActionReceiver.EXTRA_MARKET_ID,market.id).putExtra(SignalActionReceiver.EXTRA_NOTIFICATION_ID,notificationId);
        PendingIntent takePi=PendingIntent.getBroadcast(ctx,notificationId+1,take,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);PendingIntent awayPi=PendingIntent.getBroadcast(ctx,notificationId+2,away,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String title="XAU/USD "+s.side+" · Quality "+s.qualityScore+"/10";String body=String.format(Locale.US,"Entry %.2f | SL %.2f | TP %.2f | %s",s.entry,s.stopLoss,s.takeProfit,s.confirmationLabel);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(ctx,CHANNEL_ID):new Notification.Builder(ctx);b.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body+"\n"+s.amdState)).setContentIntent(openPi).setAutoCancel(true).setPriority(Notification.PRIORITY_HIGH).addAction(new Notification.Action.Builder(null,"TAKE",takePi).build()).addAction(new Notification.Action.Builder(null,"AWAY",awayPi).build());
        try{NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.notify(notificationId,b.build());}catch(SecurityException ignored){}
    }
    private void notifyInfo(Context ctx,String title,String text){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(ctx,CHANNEL_ID):new Notification.Builder(ctx);b.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true);try{NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);if(nm!=null)nm.notify(Math.abs((title+System.currentTimeMillis()).hashCode()),b.build());}catch(SecurityException ignored){}}
}
