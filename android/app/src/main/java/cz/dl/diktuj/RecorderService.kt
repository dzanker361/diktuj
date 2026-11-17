package cz.dl.diktuj

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.service.quicksettings.TileService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground služba s mikrofonem: nahrává po segmentech (~8–10 min, řez v tichu) a po zastavení
 * naplánuje odeslání na Arch. Díky foreground službě funguje i při zamčené obrazovce.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "cz.dl.diktuj.START"
        const val ACTION_STOP = "cz.dl.diktuj.STOP"
        private const val NOTIF_ID = 1
        private const val SEG_MIN_MS = 8 * 60_000L
        private const val SEG_MAX_MS = 10 * 60_000L
        private const val SILENCE_AMP = 800

        @Volatile var recording = false
        @Volatile var recordingSince = 0L
        @Volatile var status = ""
    }

    private val lock = Any()
    private var jobs = 0
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var useOpus = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notify.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (enterForeground() && !recording) beginRecording()
            ACTION_STOP -> recording = false
        }
        synchronized(lock) { if (jobs == 0) stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recording = false
        releaseWakeLock()
        refreshTile()
        super.onDestroy()
    }

    // ------------------------------------------------------------ foreground + notifikace

    private fun enterForeground(): Boolean {
        return try {
            synchronized(lock) {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                foreground = true
            }
            true
        } catch (e: Exception) {
            status = "Službu nelze spustit: ${e.message}"
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, Notify.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
            val started = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - recordingSince)
            b.setContentTitle("● Nahrávám")
                .setUsesChronometer(true)
                .setWhen(started)
                .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_mic), "Zastavit", stop).build())
        } else {
            b.setContentTitle("Diktuj").setContentText("Dokončuji nahrávku…")
        }
        return b.build()
    }

    private fun updateNotification() {
        synchronized(lock) {
            if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, DiktujTileService::class.java))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ počítadlo úloh (služba žije, dokud něco běží)

    private fun jobAdd() = synchronized(lock) { jobs++ }

    private fun jobDone() {
        synchronized(lock) {
            jobs--
            if (jobs <= 0) {
                jobs = 0
                foreground = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "diktuj:rec")
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire(5 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ nahrávání

    private fun beginRecording() {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val dir = File(Sessions.root(this), name).apply { mkdirs() }
        Sessions.activeName = name
        status = ""
        recording = true
        recordingSince = SystemClock.elapsedRealtime()
        acquireWakeLock()
        jobAdd()
        updateNotification()
        refreshTile()
        Thread({ recordLoop(dir) }, "diktuj-rec").start()
    }

    private fun recordLoop(dir: File) {
        var index = 0
        var failures = 0
        try {
            while (recording) {
                val seg = startSegment(dir, index)
                if (seg == null) {
                    if (++failures >= 5) break
                    Thread.sleep(1000)
                    continue
                }
                failures = 0
                val (rec, _) = seg
                val segError = AtomicBoolean(false)
                rec.setOnErrorListener { _, _, _ -> segError.set(true) }
                val t0 = SystemClock.elapsedRealtime()
                var quietSince = 0L
                while (recording && !segError.get()) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - t0
                    if (elapsed >= SEG_MIN_MS) {
                        // řez segmentu v pauze řeči, ať se nepřeruší slovo
                        val amp = try { rec.maxAmplitude } catch (_: Exception) { 0 }
                        if (amp < SILENCE_AMP) {
                            if (quietSince == 0L) quietSince = now else if (now - quietSince >= 800) break
                        } else {
                            quietSince = 0L
                        }
                        if (elapsed >= SEG_MAX_MS) break
                    }
                }
                try { rec.stop() } catch (_: Exception) { }
                try { rec.release() } catch (_: Exception) { }
                index++
            }
        } finally {
            recording = false
            Sessions.activeName = null
            refreshTile()
            UploadJobService.schedule(this) // odeslat na Arch, jakmile je Wi-Fi
            jobDone()
        }
    }

    private fun startSegment(dir: File, index: Int): Pair<MediaRecorder, File>? {
        if (useOpus) {
            val f = File(dir, "seg_%03d.ogg".format(index))
            try {
                return newRecorder(f, true) to f
            } catch (e: Exception) {
                useOpus = false // záložní formát AAC, kdyby telefon Opus neuměl
                f.delete()
            }
        }
        val f = File(dir, "seg_%03d.m4a".format(index))
        return try {
            newRecorder(f, false) to f
        } catch (e: Exception) {
            status = "Mikrofon nejde spustit: ${e.message}"
            f.delete()
            null
        }
    }

    private fun newRecorder(f: File, opus: Boolean): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (opus) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioEncodingBitRate(24_000)
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(32_000)
            }
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            return r
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            throw e
        }
    }
}
