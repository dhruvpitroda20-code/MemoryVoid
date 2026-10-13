package com.example.essentialspace

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {

    private const val CHANNEL_SILENT_ID = "memory_void_silent"
    private const val CHANNEL_ALERTS_ID = "memory_void_alerts"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val silentChannel = NotificationChannel(CHANNEL_SILENT_ID, "Recording Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Silent background recording indicators"
                setShowBadge(false)
            }
            
            val alertsChannel = NotificationChannel(CHANNEL_ALERTS_ID, "Note Saved Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts for newly saved memories"
                setShowBadge(true)
            }
            
            manager.createNotificationChannel(silentChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    @SuppressLint("MissingPermission")
    fun showRecordingStarted(context: Context) {
        try {
            createChannels(context)
            if (!hasPermission(context)) return

            val builder = NotificationCompat.Builder(context, CHANNEL_SILENT_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Recording Memory...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)

            NotificationManagerCompat.from(context).notify(1, builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hideRecordingNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(1)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    fun showNoteSaved(context: Context, title: String, noteIndex: Int) {
        try {
            createChannels(context)
            if (!hasPermission(context)) return

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("open_note_index", noteIndex)
            }
            val pendingIntent = PendingIntent.getActivity(context, noteIndex, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
                .setSmallIcon(R.drawable.ic_custom_void_no_bg)
                .setContentTitle("Memory Saved")
                .setContentText(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }
}