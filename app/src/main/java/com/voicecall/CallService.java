package com.voicecall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class CallService extends Service {

    private static final String CHANNEL_ID = "voicecall_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_END_CALL = "com.voicecall.END_CALL";

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        CallService getService() { return CallService.this; }
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_END_CALL.equals(intent.getAction())) {
            Intent endIntent = new Intent("com.voicecall.END_CALL_ACTION");
            sendBroadcast(endIntent);
            stopSelf();
            return START_NOT_STICKY;
        }
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Call Active", "VoiceCall is running"));
        return START_STICKY;
    }

    public void updateNotification(String title, String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(title, message));
    }

    private Notification buildNotification(String title, String message) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent endIntent = new Intent(this, CallService.class);
        endIntent.setAction(ACTION_END_CALL);
        PendingIntent endPending = PendingIntent.getService(this, 1, endIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_delete, "End Call", endPending)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "VoiceCall", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("VoiceCall active call");
            channel.setSound(null, null);
            channel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
    }
}