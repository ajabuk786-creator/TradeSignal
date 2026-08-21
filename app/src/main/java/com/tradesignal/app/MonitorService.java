package com.tradesignal.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {
    public static volatile boolean running = false;
    private static final int FOREGROUND_ID = 1001;
    private static final String CH_MONITOR = "monitor";
    private static final String CH_SIGNALS = "signals";
    private ScheduledExecutorService scheduler;
    private volatile boolean scanning = false;

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        createChannels();
        Notification n = monitorNotification("Monitoring BTC, XRP and XAU · 5m/15m");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FOREGROUND_ID, n);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::scanAllSafely, 2, 60, TimeUnit.SECONDS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void scanAllSafely() {
        if (scanning) return;
        scanning = true;
        try {
            NewsGuard.State news = NewsGuard.check(this);
            String[] symbols = {"BTCUSDT", "XRPUSDT", "XAUUSD"};
            String[] frames = {"5m", "15m"};
            for (String symbol : symbols) {
                for (String tf : frames) {
                    try { scanOne(symbol, tf, news); } catch (Exception ignored) {}
                }
            }
            updateMonitorNotification("Monitoring active · last cycle complete");
        } finally {
            scanning = false;
        }
    }

    private void scanOne(String symbol, String tf, NewsGuard.State news) throws Exception {
        List<Candle> candles = MarketData.fetch(this, symbol, tf);
        if (candles.size() < 210) return;
        Candle last = candles.get(candles.size() - 1);
        SharedPreferences p = getSharedPreferences("monitor", Context.MODE_PRIVATE);
        String checkedKey = "checked_" + symbol + "_" + tf;
        long alreadyChecked = p.getLong(checkedKey, 0L);
        if (last.closeTime <= alreadyChecked) return;

        List<Candle> higher = null;
        if ("5m".equals(tf)) {
            try { higher = MarketData.fetch(this, symbol, "15m"); } catch (Exception ignored) {}
        }
        StrategyEngine.Analysis a = StrategyEngine.analyze(symbol, tf, candles, higher, news);
        p.edit().putLong(checkedKey, last.closeTime).apply();

        if (a.signal == null) return;
        Signal s = a.signal;
        String notifyKey = "notified_" + symbol + "_" + tf;
        long lastNotified = p.getLong(notifyKey, 0L);
        if (s.candleCloseTime <= lastNotified) return;

        p.edit()
                .putLong(notifyKey, s.candleCloseTime)
                .putString("latest_signal", s.toJson())
                .apply();
        sendSignalNotification(s);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel monitor = new NotificationChannel(CH_MONITOR, "Background monitoring", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Keeps TradeSignal scanning selected markets in the foreground.");
        nm.createNotificationChannel(monitor);

        NotificationChannel signals = new NotificationChannel(CH_SIGNALS, "Trading signals", NotificationManager.IMPORTANCE_HIGH);
        signals.setDescription("High-confluence Entry / SL / TP alerts.");
        signals.enableVibration(true);
        signals.enableLights(true);
        signals.setLightColor(Color.WHITE);
        nm.createNotificationChannel(signals);
    }

    private Notification monitorNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_MONITOR) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("TradeSignal background monitor")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateMonitorNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(FOREGROUND_ID, monitorNotification(text));
    }

    private void sendSignalNotification(Signal s) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, Math.abs((s.symbol + s.timeframe).hashCode()), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = "Entry " + fmt(s.symbol, s.entry) + " · SL " + fmt(s.symbol, s.sl) + " · TP " + fmt(s.symbol, s.tp);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_SIGNALS) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(s.side + " " + s.symbol + " " + s.timeframe + " · " + s.confidence + "/100")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n" + s.reason))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffff), n);
    }

    private static String fmt(String symbol, double x) {
        if ("XRPUSDT".equals(symbol)) return String.format(Locale.US, "%.5f", x);
        return String.format(Locale.US, "%.2f", x);
    }
}
