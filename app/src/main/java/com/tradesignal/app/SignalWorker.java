package com.tradesignal.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.*;

public class SignalWorker extends Worker {
    public static final String UNIQUE_WORK = "TradeSignal-scalp-background-scan";
    public static final String CHANNEL_ID = "trade_signals";
    private static final double MAX_ENTRY_SLIPPAGE_BPS = 20.0;

    public SignalWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }

    @NonNull @Override public Result doWork() {
        Context ctx = getApplicationContext();
        ensureChannel(ctx);
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("trade_signal", Context.MODE_PRIVATE);
        double equity = Double.longBitsToDouble(prefs.getLong("paper_equity_bits", Double.doubleToRawLongBits(1000.0)));
        int threshold = prefs.getInt("threshold", 70);
        boolean hadTransientError = false;

        for (MarketDataClient.Market market : MarketDataClient.MARKETS) {
            try {
                List<SignalEngine.Candle> m5 = MarketDataClient.fetchClosed(market,"5m",240);
                TradeStore.Update tradeUpdate = TradeStore.update(ctx,market.id,m5);
                if (tradeUpdate.closed) notifyText(ctx,market,market.displayName + " scalp closed",tradeUpdate.event);

                if (TradeStore.load(ctx,market.id) != null) continue; // locked trade remains until its exit rule
                if (TradeStore.inCooldown(ctx,market.id,System.currentTimeMillis())) continue;

                List<SignalEngine.Candle> m15 = MarketDataClient.fetchClosed(market,"15m",180);
                List<SignalEngine.Candle> h1 = MarketDataClient.fetchClosed(market,"1h",120);
                SignalEngine.Decision decision = SignalEngine.analyzeScalp(m5,m15,h1,equity,threshold);
                SignalEngine.Signal signal = decision.signal;
                if (signal == null || !TradeStore.canOpen(ctx,market.id,signal.candleCloseTime)) continue;

                double live = MarketDataClient.fetchLastPrice(market);
                double slippageBps = Math.abs(live-signal.entry)/Math.max(signal.entry,1e-12)*10000.0;
                if (slippageBps > MAX_ENTRY_SLIPPAGE_BPS) continue;

                TradeStore.open(ctx,market.id,signal);
                notifySignal(ctx,market,signal);
            } catch (Exception e) {
                hadTransientError = true;
            }
        }
        return hadTransientError ? Result.retry() : Result.success();
    }

    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SignalWorker.class,15,java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(constraints).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE_WORK,ExistingPeriodicWorkPolicy.UPDATE,request);
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,"Trade signals",NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Confirmed scalp entries and active trade exits"); nm.createNotificationChannel(ch);
        }
    }

    private void notifySignal(Context ctx, MarketDataClient.Market market, SignalEngine.Signal s) {
        String title = market.displayName + " " + s.side + " scalp · " + s.confidence + "%";
        String text = String.format(Locale.US,"Entry %.5f | SL %.5f | TP %.5f | R:R 1:%.2f",s.entry,s.stopLoss,s.takeProfit,s.riskReward);
        notifyText(ctx,market,title,text + "\n" + s.confirmationLabel + "\n" + s.amdState);
    }

    private void notifyText(Context ctx, MarketDataClient.Market market, String title, String text) {
        Intent intent = new Intent(ctx,MainActivity.class).putExtra("market",market.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx,market.id.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx,CHANNEL_ID) : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(pi).setPriority(Notification.PRIORITY_HIGH);
        try {
            NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Math.abs((market.id + title + System.currentTimeMillis()).hashCode()),b.build());
        } catch (SecurityException ignored) {}
    }
}
