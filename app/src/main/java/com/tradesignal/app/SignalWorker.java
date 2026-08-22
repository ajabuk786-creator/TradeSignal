package com.tradesignal.app;

import android.app.*;
import android.content.*;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.*;
import java.util.*;

public class SignalWorker extends Worker {
    public static final String UNIQUE_WORK = "TradeSignal-15m-background-scan";
    public static final String CHANNEL_ID = "trade_signals";

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
                List<SignalEngine.Candle> m15 = MarketDataClient.fetchClosed(market, "15m", 180);
                List<SignalEngine.Candle> h1 = MarketDataClient.fetchClosed(market, "1h", 120);
                SignalEngine.Signal signal = SignalEngine.analyze(m15, h1, equity, threshold);
                if (signal == null) continue;

                long lastSent = prefs.getLong("last_notified_" + market.id, 0L);
                if (signal.candleCloseTime <= lastSent) continue;

                double live = MarketDataClient.fetchLastPrice(market);
                double slippageBps = Math.abs(live - signal.entry) / Math.max(signal.entry, 1e-12) * 10000.0;
                if (slippageBps > 25.0) continue;

                notifySignal(ctx, market, signal);
                prefs.edit().putLong("last_notified_" + market.id, signal.candleCloseTime).apply();
            } catch (Exception e) {
                hadTransientError = true;
            }
        }
        return hadTransientError ? Result.retry() : Result.success();
    }

    public static void schedule(Context ctx) {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SignalWorker.class, 15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(constraints).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Trade signals", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("15-minute closed-candle signal alerts");
            nm.createNotificationChannel(ch);
        }
    }

    private void notifySignal(Context ctx, MarketDataClient.Market market, SignalEngine.Signal s) {
        Intent intent = new Intent(ctx, MainActivity.class).putExtra("market", market.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, market.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = market.displayName + "  " + s.side + "  " + s.confidence + "%";
        String text = String.format(Locale.US, "Entry %.5f | SL %.5f | TP %.5f | R:R 1:2", s.entry, s.stopLoss, s.takeProfit);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n" + s.rationale))
                .setAutoCancel(true).setContentIntent(pi).setPriority(Notification.PRIORITY_HIGH);
        try {
            NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(Math.abs((market.id + s.candleCloseTime).hashCode()), b.build());
        } catch (SecurityException ignored) {}
    }
}
