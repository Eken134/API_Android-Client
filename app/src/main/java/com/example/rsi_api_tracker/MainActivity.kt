package com.example.rsi_api_tracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.rsi_api_tracker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("poll", MODE_PRIVATE) }

    // Broadcast från PollService: "POLL_UPDATE" med extras: "last" (String), "signal" ("1"/"-1"), ev. "changed" (Bool)
    private val pollReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val last = intent?.getStringExtra("last") ?: "–"
            val signal = intent?.getStringExtra("signal")
            val changed = intent?.getBooleanExtra("changed", false) ?: false

            b.textLastResponse.text = "Senaste svar: $last"
            prefs.edit().putString("lastResponse", last).apply()

            when (signal) {
                "1"  -> { setSignalUi(true);  if (changed) pulse() }
                "-1" -> { setSignalUi(false); if (changed) pulse() }
                else -> setNeutralUi()
            }
        }
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore */ }

    // ---------- Färg-hjälpare ----------
    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun setWindowBg(c: Int) {
        window.setBackgroundDrawable(ColorDrawable(c)) // färga hela appfönstret
        b.root.setBackgroundColor(c)                   // färga rotvyn (layouten)
        runCatching { b.statusBar.setBackgroundColor(c) } // ev. statusBar-vy
    }

    private fun setSignalUi(isBuy: Boolean) {
        val bg = if (isBuy) color(R.color.signalGreenBg) else color(R.color.signalRedBg)
        setWindowBg(bg)
        b.textStatus.text = if (isBuy) "Status: KÖP" else "Status: SÄLJ"
        b.textStatus.setTextColor(color(R.color.signalText))
    }

    private fun setNeutralUi() {
        setWindowBg(color(R.color.bgDefault))
        b.textStatus.text = "Status: På"
        b.textStatus.setTextColor(color(R.color.neutralText))
    }

    private fun pulse() {
        b.root.animate().cancel()
        b.root.alpha = 0.92f
        b.root.animate().alpha(1f).setDuration(160).start()
        runCatching {
            b.statusBar.animate().cancel()
            b.statusBar.alpha = 0.7f
            b.statusBar.animate().alpha(1f).setDuration(160).start()
        }
    }

    // ---------- Lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Defaultvärden
        b.editUrl.setText("https://eelde-koivisto.se/_API/mobile/get_latest.php")
        b.editUser.setText("testuser")
        b.editPass.setText("1234567890abcdef1234567890abcdef")
        b.editIntervalSec.setText("10")

        // START
        b.btnStart.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            val url = b.editUrl.text.toString()
            val user = b.editUser.text.toString()
            val pass = b.editPass.text.toString()
            val interval = b.editIntervalSec.text.toString().toLongOrNull() ?: 10L

            prefs.edit()
                .putString("activeUrl", url)
                .putString("activeUser", user)
                .putLong("activeInterval", interval)
                .apply()

            startForegroundService(Intent(this, PollService::class.java).apply {
                putExtra("url", url)
                putExtra("user", user)
                putExtra("pass", pass)
                putExtra("intervalSec", interval)
            })

            b.textStatus.text = "Status: På"
            b.textDetails.text = "🌐 $url\n👤 $user\n⏱️ Var $interval sekunder"
            b.textLastResponse.text = "Senaste svar: –"
            setNeutralUi()
        }

        // STOP
        b.btnStop.setOnClickListener {
            stopService(Intent(this, PollService::class.java))
            prefs.edit()
                .remove("activeUrl")
                .remove("activeUser")
                .remove("activeInterval")
                .apply()

            b.textStatus.text = "Status: Av"
            b.textDetails.text = "Polling stoppad."
            b.textLastResponse.text = "Senaste svar: –"
            setNeutralUi()
        }
    }

    override fun onResume() {
        super.onResume()

        // börja lyssna när skärmen är synlig
        val filter = IntentFilter("POLL_UPDATE")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pollReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pollReceiver, filter)
        }

        // Visa senast kända status direkt
        val lastResponse = prefs.getString("lastResponse", "–")
        val activeUrl = prefs.getString("activeUrl", null)
        val activeUser = prefs.getString("activeUser", null)
        val activeInterval = prefs.getLong("activeInterval", 0L)
        val lastSignal = prefs.getString("lastSignal", null)

        b.textLastResponse.text = "Senaste svar: $lastResponse"
        if (activeUrl != null && activeUser != null && activeInterval > 0) {
            b.textStatus.text = "Status: På"
            b.textDetails.text = "🌐 $activeUrl\n👤 $activeUser\n⏱️ Var $activeInterval sekunder"
        } else {
            b.textStatus.text = "Status: Av"
            b.textDetails.text = "Ingen aktiv polling ännu"
        }
        when (lastSignal) {
            "1"  -> setSignalUi(true)
            "-1" -> setSignalUi(false)
            else -> setNeutralUi()
        }
    }

    override fun onPause() {
        try { unregisterReceiver(pollReceiver) } catch (_: Exception) {}
        super.onPause()
    }
}
