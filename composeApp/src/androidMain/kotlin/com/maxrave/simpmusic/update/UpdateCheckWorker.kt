package com.maxrave.simpmusic.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.utils.VersionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.ytm_update_available_body
import simpmusic.composeapp.generated.resources.ytm_update_channel
import simpmusic.composeapp.generated.resources.ytm_update_notification_body
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateCheckWorker"
private const val WORK_NAME = "refine_update_check"
private const val CHANNEL_ID = "app_updates"
private const val NOTIFICATION_ID = 7301

class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val release =
            try {
                withContext(Dispatchers.IO) { fetchLatestAndroidRelease() }
            } catch (e: Exception) {
                Logger.w(TAG, "Background update check failed: ${e.message}")
                return Result.retry()
            } ?: return Result.success()
        VersionManager.initialize()
        if (!UpdateVersion.isNewer(release.version, VersionManager.getVersionName())) return Result.success()
        val prefs = applicationContext.getSharedPreferences(UPDATER_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED_VERSION, null) == release.version) return Result.success()
        if (notify(release)) prefs.edit().putString(KEY_NOTIFIED_VERSION, release.version).apply()
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private suspend fun notify(release: AppRelease): Boolean {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(Res.string.ytm_update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        val tap =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val icon =
            context.resources
                .getIdentifier("mono", "drawable", context.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.stat_sys_download_done
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(getString(Res.string.ytm_update_available_body, release.version))
                .setContentText(getString(Res.string.ytm_update_notification_body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    companion object {
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(1, TimeUnit.HOURS)
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun clear(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
}
