package com.rishat.fileshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * সংযোগ থাকাকালীন ফোরগ্রাউন্ড সার্ভিস: অ্যাপ ব্যাকগ্রাউন্ডে গেলে বা স্ক্রিন বন্ধ হলেও ট্রান্সফার চলে।
 * লজিক Engine-এ; এই সার্ভিস শুধু প্রসেস বাঁচিয়ে রাখে ও নোটিফিকেশনে অগ্রগতি দেখায়।
 */
class TransferService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL_ID, "ফাইল ট্রান্সফার", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Engine.disconnect()
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val live = Engine.transfers.filter { it.state == TState.ACTIVE || it.state == TState.QUEUED }
        val title = if (Engine.session != null) "সংযুক্ত: ${Engine.peerName}" else "Rishat File Share"
        var text = "ফাইল পাঠাতে/নিতে প্রস্তুত"
        var percent = -1
        if (live.isNotEmpty()) {
            var done = 0L
            var total = 0L
            var speed = 0.0
            for (t in live) {
                t.sampleSpeed()
                done += t.bytes
                total += t.size
                speed += t.speed
            }
            percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            text = "${live.size}টি ফাইল • $percent% • ${fmtSpeed(speed)}"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "বিচ্ছিন্ন করুন", stop)
        if (percent >= 0) b.setProgress(100, percent, false)
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "transfer"
        private const val NOTIF_ID = 77
        const val ACTION_DISCONNECT = "com.rishat.fileshare.DISCONNECT"
    }
}
