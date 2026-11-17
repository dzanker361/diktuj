package cz.dl.diktuj

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val REQ_PERMS = 1
    }

    private lateinit var hostEdit: EditText
    private lateinit var tokenEdit: EditText
    private lateinit var mainBtn: Button
    private lateinit var statusView: TextView
    private lateinit var sendBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var closeAfterStart = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Thread { Sessions.cleanup(this) }.start()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            i.removeExtra(EXTRA_AUTOSTART)
            if (!RecorderService.recording) {
                closeAfterStart = true
                tryStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        // neodeslané nahrávky (např. po pádu nebo mimo domov) se pošlou, jakmile je Wi-Fi
        if (Sessions.unsent(this).isNotEmpty() && Prefs.host(this).isNotEmpty()) UploadJobService.schedule(this)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        saveSettings()
        super.onPause()
    }

    private fun saveSettings() = Prefs.save(this, hostEdit.text.toString(), tokenEdit.text.toString())

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun text(t: String, sp: Float, bold: Boolean = false) = TextView(this).apply {
            text = t
            textSize = sp
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        col.addView(text("Diktuj", 28f, true))
        col.addView(text("Nahraje diktát a pošle ho přes Wi-Fi na Arch k přepisu", 13f).apply { setPadding(0, 0, 0, dp(16)) })

        mainBtn = Button(this).apply {
            textSize = 20f
            setOnClickListener { if (RecorderService.recording) stopRec() else tryStart() }
        }
        col.addView(mainBtn, LinearLayout.LayoutParams(-1, dp(88)))

        statusView = text("", 16f).apply { setPadding(0, dp(12), 0, dp(8)) }
        col.addView(statusView)

        sendBtn = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { sendNow() }
        }
        col.addView(sendBtn)

        col.addView(text("Arch (stejná Wi-Fi)", 13f, true).apply { setPadding(0, dp(20), 0, dp(4)) })
        hostEdit = EditText(this).apply {
            setText(Prefs.host(this@MainActivity))
            hint = "Adresa, např. 192.168.1.50:8765"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        col.addView(hostEdit)
        tokenEdit = EditText(this).apply {
            setText(Prefs.token(this@MainActivity))
            hint = "Token (z příkazu: diktuj token)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        col.addView(tokenEdit)
        col.addView(Button(this).apply {
            text = "Test spojení s Archem"
            setOnClickListener { testConnection() }
        })

        col.addView(text("Aby nahrávání nepřežilo zamčení (Xiaomi)", 13f, true).apply { setPadding(0, dp(20), 0, dp(4)) })
        col.addView(Button(this).apply {
            text = "1) Výjimka z úspory baterie"
            setOnClickListener { openBatterySettings() }
        })
        col.addView(Button(this).apply {
            text = "2) Autostart (Xiaomi)"
            setOnClickListener { openAutostart() }
        })
        col.addView(text(
            "V přehledu posledních aplikací můžeš Diktuj zamknout (přidržet kartu → zámek), aby ho systém neukončoval.",
            12f
        ).apply { setPadding(0, dp(4), 0, 0) })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun refresh() {
        val rec = RecorderService.recording
        mainBtn.text = if (rec) "■  Zastavit" else "●  Začít nahrávat"
        val unsent = Sessions.unsent(this).size
        statusView.text = when {
            rec -> {
                val s = (SystemClock.elapsedRealtime() - RecorderService.recordingSince) / 1000
                "Nahrává se  %02d:%02d".format(s / 60, s % 60)
            }
            RecorderService.status.isNotEmpty() -> RecorderService.status
            unsent > 0 -> "Čeká na odeslání: $unsent"
            else -> "Připraveno, vše odesláno ✓"
        }
        sendBtn.visibility = if (unsent > 0 && !rec) View.VISIBLE else View.GONE
        sendBtn.text = "Odeslat na Arch teď ($unsent)"
    }

    // ------------------------------------------------------------------ akce

    private fun tryStart() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) {
            requestPermissions(need.toTypedArray(), REQ_PERMS)
            return
        }
        startRec()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRec()
        } else {
            closeAfterStart = false
            toast("Bez oprávnění k mikrofonu to nejde")
        }
    }

    private fun startRec() {
        saveSettings()
        startForegroundService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_START))
        if (closeAfterStart) {
            closeAfterStart = false
            finish() // appka se zavře, nahrávání běží dál ve službě
        }
    }

    private fun stopRec() {
        startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
    }

    private fun sendNow() {
        saveSettings()
        toast("Odesílám…")
        Thread {
            val r = Uploader.uploadAll(this) { false }
            runOnUiThread { toast(Uploader.message(r)) }
            if (r == Uploader.Result.UNREACHABLE || r == Uploader.Result.ERROR) UploadJobService.schedule(this, 60_000L)
        }.start()
    }

    private fun testConnection() {
        saveSettings()
        Thread {
            val r = Uploader.ping(this)
            runOnUiThread { toast(if (r == Uploader.Result.DONE) "Spojení s Archem funguje ✓" else Uploader.message(r)) }
        }.start()
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Výjimka už je nastavená")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openAutostart() {
        try {
            startActivity(
                Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                )
            )
        } catch (e: Exception) {
            toast("Otevírám nastavení aplikace – povol Autostart a Bez omezení baterie")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
