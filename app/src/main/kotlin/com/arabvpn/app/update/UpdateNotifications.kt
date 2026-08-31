package com.arabvpn.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.arabvpn.app.MainActivity
import com.vibe.app.R
import java.io.File

object UpdateNotifications {
    private const val CHANNEL_ID = "arab_vpn_updates"
    private const val AVAILABLE_ID = 8101
    private const val DOWNLOAD_ID = 8102
    private const val READY_ID = 8103

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "تحديثات Arab VPN",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "إشعارات توفر تحديث جديد للتطبيق"
            }
        )
    }

    fun showAvailable(context: Context, manifest: UpdateManifest) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val openIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val downloadIntent = PendingIntent.getBroadcast(
            context,
            manifest.versionCode,
            Intent(context, UpdateDownloadReceiver::class.java).apply {
                action = UpdateDownloadReceiver.ACTION_DOWNLOAD_UPDATE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arab_vpn)
            .setContentTitle("تحديث جديد لـ Arab VPN")
            .setContentText("الإصدار ${manifest.versionName} متاح. سيتم تنزيل الفرق فقط عندما يكون متوفراً.")
            .setContentIntent(openIntent)
            .addAction(0, "تنزيل التحديث", downloadIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(context).notify(AVAILABLE_ID, notification)
    }

    fun downloadingForeground(context: Context, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arab_vpn)
            .setContentTitle("جاري تنزيل تحديث Arab VPN")
            .setContentText(if (progress >= 0) "$progress%" else "جاري تجهيز التحديث…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .build()
        return ForegroundInfo(DOWNLOAD_ID, notification)
    }

    fun showReady(context: Context, apk: File, manifest: UpdateManifest, usedDelta: Boolean) {
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
            .setContentTitle("التحديث جاهز للتثبيت")
            .setContentText("$mode · الإصدار ${manifest.versionName}")
            .setContentIntent(installIntent)
            .addAction(0, "تثبيت", installIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(context).notify(READY_ID, notification)
    }

    fun showFailure(context: Context, message: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arab_vpn)
            .setContentTitle("تعذر تحديث Arab VPN")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(READY_ID, notification)
    }
}
