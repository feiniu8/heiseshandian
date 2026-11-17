package com.heiseidian.lightning;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Keeps downloads alive while the app is in the background and shows progress. */
public class DownloadService extends Service {

    private static final int NID = 1001;
    private static final String CHANNEL = "blitz_downloads";

    private Handler handler;
    private Runnable ticker;

    public static void start(Context ctx) {
        try {
            Intent intent = new Intent(ctx, DownloadService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(intent);
            else ctx.startService(intent);
        } catch (Exception ignored) {
        }
    }

    public static void stopIfIdle(Context ctx) {
        try {
            if (!Downloader.get(ctx).hasActive()) ctx.stopService(new Intent(ctx, DownloadService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        handler = new Handler(Looper.getMainLooper());
        ticker = new Runnable() {
            @Override
            public void run() {
                refresh();
                if (handler != null) handler.postDelayed(this, 1000);
            }
        };
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "下载任务", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("黑色闪电下载进度");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NID, build());
        if (handler != null && ticker != null) {
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }
        return START_STICKY;
    }

    private void refresh() {
        Downloader d = Downloader.get(this);
        if (d.hasActive()) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NID, build());
        } else {
            try {
                stopForeground(true);
            } catch (Exception ignored) {
            }
            stopSelf();
        }
    }

    private Notification build() {
        Downloader d = Downloader.get(this);
        Downloader.Task t = d.firstActive();
        String text = t == null ? "下载完成" : ("正在下载 " + t.name + "  " + (t.total > 0 ? (int) (t.soFar * 100 / t.total) : 0) + "%");
        int pct = (t != null && t.total > 0) ? (int) (t.soFar * 100 / t.total) : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, CHANNEL);
        else b = new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("黑色闪电")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi);
        if (t != null) b.setProgress(100, pct, t.total <= 0);
        return b.build();
    }

    @Override
    public void onDestroy() {
        if (handler != null && ticker != null) handler.removeCallbacks(ticker);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
