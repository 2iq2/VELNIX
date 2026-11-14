package com.velnix.livingroom

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

private object VelnixNotifications {
    private const val CHANNEL_ID = "velnix_sports"
    private const val CHANNEL_NAME = "تنبيهات الرياضة"

    fun show(context: Context, title: String, message: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "تذكيرات المباريات وتحديثات النتائج"
                    enableVibration(true)
                }
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID) else Notification.Builder(context)
        manager.notify(id, builder.setSmallIcon(R.drawable.ic_velnix).setContentTitle(title)
            .setContentText(message).setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true).build())
    }
}

object SportsReminderScheduler {
    fun schedule(context: Context, match: TvMatch, minutesBefore: Int = 15) {
        val start = runCatching { Instant.parse(match.startsAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        val minutes = minutesBefore.coerceIn(1, 180)
        val delay = (start - System.currentTimeMillis() - minutes * 60_000L).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<SportsReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                "title" to "تذكير مباراة",
                "message" to "${match.home} × ${match.away} · تبدأ بعد ${minutes} دقيقة",
                "notification_id" to match.id.hashCode()
            )).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "velnix-match-reminder-${match.id}", ExistingWorkPolicy.REPLACE, request
        )
    }
}

class SportsReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        VelnixNotifications.show(applicationContext,
            inputData.getString("title") ?: "تذكير مباراة",
            inputData.getString("message") ?: "ستبدأ المباراة قريباً.",
            inputData.getInt("notification_id", System.currentTimeMillis().toInt()))
        Result.success()
    }
}

class SportsScoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val api = VelnixApi(applicationContext)
        if (api.session() == null) return@withContext Result.success()
        try {
            api.syncSportsFeed()
            val matches = api.upcomingMatches()
            val prefs = applicationContext.getSharedPreferences("velnix_sports_state", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            matches.forEach { match ->
                val scoreKey = "score_${match.id}"
                val currentScore = "${match.homeScore}:${match.awayScore}"
                val currentStatus = match.status.lowercase()
                val previousScore = prefs.getString(scoreKey, null)
                val previousStatus = prefs.getString("status_${match.id}", null)
                if (previousScore != null && previousScore != currentScore && currentStatus == "live") {
                    val homeIncreased = previousScore.substringBefore(":").toIntOrNull()?.let { match.homeScore > it } == true
                    val awayIncreased = previousScore.substringAfter(":").toIntOrNull()?.let { match.awayScore > it } == true
                    val label = if (homeIncreased || awayIncreased) "تحديث النتيجة · احتمال تسجيل هدف" else "تم تحديث نتيجة المباراة"
                    VelnixNotifications.show(applicationContext, label,
                        "${match.home} ${match.homeScore} - ${match.awayScore} ${match.away}", match.id.hashCode())
                } else if (previousStatus != null && previousStatus != "finished" && currentStatus == "finished") {
                    VelnixNotifications.show(applicationContext, "انتهت المباراة",
                        "${match.home} ${match.homeScore} - ${match.awayScore} ${match.away}", match.id.hashCode())
                }
                editor.putString(scoreKey, currentScore).putString("status_${match.id}", currentStatus)
            }
            editor.apply()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}

object SportsScoreScheduler {
    fun start(context: Context) {
        val request = PeriodicWorkRequestBuilder<SportsScoreWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "velnix-sports-score-updates", ExistingWorkPolicy.UPDATE, request)
    }
    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("velnix-sports-score-updates")
    }
}
