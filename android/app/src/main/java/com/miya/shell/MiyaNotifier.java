package com.miya.shell;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** 本地通知：渠道创建、权限检查、展示（点击回到对应聊天）。 */
public final class MiyaNotifier {

    public static final String CHANNEL_ID = "miya-local";
    public static final String EXTRA_LINK = "miya_link";
    public static final int REQ_NOTIFICATION = 4002;

    private MiyaNotifier() {
    }

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Miya 消息", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("聊天、日程提醒与后台消息");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ch);
    }

    public static boolean isEnabled(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled();
    }

    public static void requestPermissionIfNeeded(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (isEnabled(activity)) return;
        try {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        } catch (Exception ignored) {
        }
    }

    public static void show(Context ctx, String title, String body, String tag, String chatId) {
        ensureChannel(ctx);
        if (!isEnabled(ctx)) return;

        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (chatId != null && !chatId.isEmpty()) {
            intent.putExtra(EXTRA_LINK, "miya-open-chat=" + Uri.encode(chatId));
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        int requestCode = (tag == null || tag.isEmpty())
                ? (int) (System.currentTimeMillis() & 0xFFFFFF)
                : (tag.hashCode() & 0xFFFFFF);
        PendingIntent pi = PendingIntent.getActivity(ctx, requestCode, intent, flags);

        String safeTitle = (title == null || title.isEmpty()) ? "Miya" : title;
        String safeBody = body == null ? "" : body;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_miya)
                .setContentTitle(safeTitle)
                .setContentText(safeBody)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(safeBody))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pi);

        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            int id = (tag == null || tag.isEmpty())
                    ? (int) (System.currentTimeMillis() & 0x7FFFFFFF)
                    : tag.hashCode();
            nm.notify(id, builder.build());
        } catch (Exception ignored) {
        }
    }
}
