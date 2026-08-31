package com.arabvpn.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.arabvpn.app.MainActivity
import com.vibe.app.R
import java.io.File

object UpdateNotifications {
    private const val CHANNEL_ID = "arab_vpn_updates_v2"
    private const val AVAILABLE_ID = 8101
    private const val DOWNLOAD_ID = 8102
    private const val READY_ID = 8103

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "تحديثات Arab VPN",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "تنبيه عند توفر إصدار جديد من Arab VPN"
                enableVibration(true)
            }
        )
    }

    fun showAvailable(context: Context, manifest: UpdateManifest) {
        createChannel(context)
        if (!canPostNotifications(context)) return

        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val updateIntent = PendingIntent.getActivity(
            context,
            manifest.versionCode,
            UpdateInstallActivity.downloadAndInstallIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arab_vpn)
            .setContentTitle("⬆ تحديث جديد لـ Arab VPN")
            .setContentText("الإصدار ${manifest.versionName} جاهز. اضغط للتحديث الآن.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "يتوفر إصدار جديد ${manifest.versionName}. اضغط «تحديث الآن»؛ سيُنزل التطبيق التحديث ثم يفتح مثبت Android تلقائياً."
                )
            )
            .setContentIntent(openIntent)
            .addAction(0, "تحديث الآن", updateIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        notifySafely(context, AVAILABLE_ID, notification)
    }

    fun downloadingForeground(context: Context, progress: Int): ForegroundInfo {
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arab_vpn)
            .setContentTitle("جاري تنزيل تحديث Arab VPN")
            .setContentText(if (progress >= 0) "$progress%" else "جاري تجهيز التحديث…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .build()
        return ForegroundInfo(
            DOWNLOAD_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    fun showReady(context: Context, apk: File, manifest: UpdateManifest, usedDelta: Boolean) {
        createChannel(context)
        if (!canPostNotifications(context)) return

        val installIntent = PendingIntent.getActivity(
            context,
            manifest.versionCode,
            Intent(context, UpdateInstallActivity::class.java).apply {
                putExtra(UpdateInstallActivity.EXTRA_APK_PATH, apk.absolutePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val mode = if (usedDelta) "تم تنزيل فرق التحديث فقط" else "تم تنزيل الحزمة الكاملة"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arab_vpn)
            .setContentTitle("✓ التحديث جاهز للتثبيت")
            .setContentText("$mode · الإصدار ${manifest.versionName}")
            .setContentIntent(installIntent)
            .addAction(0, "تثبيت الآن", installIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        notifySafely(context, READY_ID, notification)
    }

    fun showFailure(context: Context, message: String) {
        createChannel(context)
        if (!canPostNotifications(context)) return
        val signatureMismatch = message.contains("توقيع", ignoreCase = true)
        val text = if (signatureMismatch) {
            "نسخة الاختبار الحالية موقعة بمفتاح قديم. احذف Arab VPN وثبّت النسخة الجديدة مرة واحدة؛ بعدها تعمل التحديثات مباشرة."
        } else {
            message
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arab_vpn)
            .setContentTitle(if (signatureMismatch) "يلزم تثبيت يدوي مرة واحدة" else "تعذر تحديث Arab VPN")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notifySafely(context, READY_ID, notification)
    }

    private fun canPostNotifications(context: Context): Boolean {
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimePermissionGranted &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun notifySafely(
        context: Context,
        id: Int,
        notification: android.app.Notification,
    ) {
        if (!canPostNotifications(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and the notify call.
        }
    }
}
