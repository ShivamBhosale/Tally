package com.shivam.tally.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.shivam.tally.R
import com.shivam.tally.core.TrackedApp
import com.shivam.tally.data.CountStore

/** Optional low-priority ongoing notification: "Reels 12 · Shorts 8". Silently does nothing without permission. */
class OngoingNotifier(private val ctx: Context, private val store: CountStore) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    fun update(enabled: Boolean) {
        if (!enabled || !canPost()) { nm.cancel(ID); return }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Today's count", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val ig = store.todayCount(TrackedApp.INSTAGRAM); val yt = store.todayCount(TrackedApp.YOUTUBE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tally).setContentTitle("Today: ${ig + yt}")
            .setContentText("Reels $ig · Shorts $yt").setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build()
        nm.notify(ID, n)
    }

    fun cancel() = nm.cancel(ID)

    private fun canPost() = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object { private const val CHANNEL = "tally_count"; private const val ID = 1 }
}
