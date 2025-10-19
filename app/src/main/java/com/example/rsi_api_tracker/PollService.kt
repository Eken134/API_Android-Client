package com.example.rsi_api_tracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PollService : Service() {

    // ---- state ----
    private val prefs by lazy { getSharedPreferences("poll", Context.MODE_PRIVATE) }
    private var lastSignal: String? = null
    private var lastNotifiedAt: Long = 0L
    private var running = false

    private val COOLDOWN_MS = 2 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val CHANNEL_FOREGROUND = "poll_foreground"
    private val CHANNEL_ALERTS = "poll_alerts"
    private val FG_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createChannel(CHANNEL_FOREGROUND, "Polling Service", "Håller appen vid liv")
        createChannel(CHANNEL_ALERTS, "Signalnotiser", "Notifierar vid 1 eller -1")
        startForeground(FG_ID, foregroundNotification("Startar…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true

        val url = intent?.getStringExtra("url")
            ?: "https://eelde-koivisto.se/_API/mobile/get_latest.php"
        val user = intent?.getStringExtra("user") ?: "testuser"
        val pass = intent?.getStringExtra("pass")
            ?: "1234567890abcdef1234567890abcdef"
        val intervalSec = intent?.getLongExtra("intervalSec", 10L) ?: 10L

        updateForeground("Polling ${url.substringAfter("//")}")

        scope.launch {
            val tf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            while (running) {
                val now = System.currentTimeMillis()
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Authorization", Credentials.basic(user, pass))
                        .get()
                        .build()

                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string()?.trim().orEmpty()
                        val ts = tf.format(Date())
                        updateForeground("[$ts] Svar: $body")

                        val signal = extractSignal(body)                      // "1", "-1" eller null
                        val changed = signal != null && signal != lastSignal  // för ev. UI-pulse

                        // Skicka ALLTID broadcast till UI (explicit + foreground)
                        sendUiUpdate(last = body, signal = signal, changed = changed)

                        // Spara senaste signal (så onResume kan rita rätt direkt)
                        if (signal != null && signal != lastSignal) {
                            lastSignal = signal
                            prefs.edit().putString("lastSignal", lastSignal).apply()
                        }

                        // Larma endast vid ny signal eller efter cooldown
                        if (signal != null) {
                            val snoozedUntil = prefs.getLong("snoozedUntil", 0L)
                            val snoozed = now < snoozedUntil
                            val shouldNotify = (!snoozed) &&
                                    (changed || (now - lastNotifiedAt) >= COOLDOWN_MS)

                            if (shouldNotify) {
                                val title = if (signal == "1") "KÖP-signal" else "SÄLJ-signal"
                                sendAlert(title, "Ny signal: $signal")
                                lastNotifiedAt = now
                                prefs.edit().putLong("lastNotifiedAt", lastNotifiedAt).apply()
                            }
                        }
                    }
                } catch (e: Exception) {
                    updateForeground("Fel: ${e.localizedMessage ?: e.javaClass.simpleName}")
                    sendUiUpdate(
                        last = "Fel: ${e.localizedMessage ?: e.javaClass.simpleName}",
                        signal = null,
                        changed = false
                    )
                }

                delay(intervalSec.coerceAtLeast(5L) * 1000)
            }

            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- helpers ----

    /** Läs "data" ur JSON, annars använd råtext; returnera "1"/"-1" eller null. */
    private fun extractSignal(body: String): String? {
        try {
            val json = JSONObject(body)
            val data = if (json.has("data")) json.get("data") else null
            val s = when (data) {
                is Number -> data.toInt().toString()
                is String -> data.trim()
                else -> null
            }
            if (s == "1" || s == "-1") return s
        } catch (_: Exception) { /* inte JSON */ }

        val trimmed = body.trim()
        return if (trimmed == "1" || trimmed == "-1") trimmed else null
    }

    private fun createChannel(id: String, name: String, desc: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(id) == null) {
                val importance = if (id == CHANNEL_FOREGROUND)
                    NotificationManager.IMPORTANCE_MIN
                else
                    NotificationManager.IMPORTANCE_HIGH
                val ch = NotificationChannel(id, name, importance).apply {
                    description = desc
                    enableVibration(true)
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun foregroundNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("API-Polling körs")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateForeground(text: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(FG_ID, foregroundNotification(text))
    }

    /** Skicka status till UI (explicit + foreground så den levereras direkt). */
    private fun sendUiUpdate(last: String, signal: String?, changed: Boolean) {
        val i = Intent("POLL_UPDATE")
            .setPackage(packageName)                       // endast din app
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)     // leverera direkt

        i.putExtra("last", last)
        i.putExtra("signal", signal)       // kan vara null
        i.putExtra("changed", changed)

        sendBroadcast(i)
    }

    private fun sendAlert(title: String, msg: String) {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val notif = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(id, notif)
    }
}
