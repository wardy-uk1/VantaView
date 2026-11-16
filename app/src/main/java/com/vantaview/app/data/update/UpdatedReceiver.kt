// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vantaview.app.MainActivity
import com.vantaview.app.R

/**
 * Opens VantaView again straight after it's updated, as installing an update closes it. Android 9 and older
 * (Fire TV, many TV boxes) allow that; newer Android doesn't let an app open itself from the background, so
 * there a notification offers to open it instead.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Only after an update started from inside the app, not one from a store or a file manager.
        val sp = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val asked = sp.getLong("installing_at", 0)
        sp.edit().remove("installing_at").apply()
        if (System.currentTimeMillis() - asked > 30 * 60_000L) return

        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT < 29 && runCatching { context.startActivity(open) }.isSuccess) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_HIGH))
        }
        val tap = PendingIntent.getActivity(context, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(com.vantaview.app.ui.tr("VantaView is updated"))
            .setContentText(com.vantaview.app.ui.tr("Tap to open it again."))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val allowed = Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(context).notify(7001, n) }
        // Without notifications, it still tries: some devices allow it.
        else runCatching { context.startActivity(open) }
    }

    companion object { const val CHANNEL = "updates" }
}
