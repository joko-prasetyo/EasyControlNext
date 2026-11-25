package com.shiyunjin.easycontrolnext.app.client;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.shiyunjin.easycontrolnext.app.MainActivity;
import com.shiyunjin.easycontrolnext.app.R;
import com.shiyunjin.easycontrolnext.app.helper.PublicTools;

// Foreground service that runs while at least one session is connected.
//
// In the floating (small/mini) window the app has no activity on screen, so Android treats the process as
// "cached". With battery saving active (measured on an ASUS phone: "Restrict power: true") the system then
// blocks ALL network traffic of the app (netpolicy rule REJECT_ALL): video, heartbeats and every reconnect
// attempt time out until the app comes back to the front. A process with a foreground service is exempt from
// that blocking, which keeps floating sessions connected.
public class StreamService extends Service {
  private static final String CHANNEL = "stream";
  private static final int NOTIFICATION_ID = 1;

  // Start or stop to match the number of connected sessions
  public static void update(Context context, int sessions) {
    Intent intent = new Intent(context, StreamService.class);
    try {
      if (sessions > 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
      } else context.stopService(intent);
    } catch (Exception e) {
      // e.g. ForegroundServiceStartNotAllowedException when started from the background: the session still
      // works, only without the protection described above
      PublicTools.logToast("service", "foreground service not started: " + e, false);
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Notification notification = buildNotification();
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
      else startForeground(NOTIFICATION_ID, notification);
    } catch (Exception e) {
      PublicTools.logToast("service", "startForeground failed: " + e, false);
      stopSelf();
    }
    return START_NOT_STICKY;
  }

  private Notification buildNotification() {
    Notification.Builder builder;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
      if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.notification_stream_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
      }
      builder = new Notification.Builder(this, CHANNEL);
    } else builder = new Notification.Builder(this);
    int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
    return builder
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(getString(R.string.notification_stream_title))
      .setContentText(getString(R.string.notification_stream_text))
      .setContentIntent(open)
      .setOngoing(true)
      .build();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
