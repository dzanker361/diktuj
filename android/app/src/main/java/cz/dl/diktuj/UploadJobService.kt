package cz.dl.diktuj

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/**
 * Odešle nahrávky na Arch, jakmile je telefon na neměřené Wi-Fi. Když Arch zrovna není dosažitelný,
 * zkusí to za pár minut znovu (dokud je nějaká nahrávka neodeslaná).
 */
class UploadJobService : JobService() {
    @Volatile
    private var cancelled = false

    override fun onStartJob(params: JobParameters): Boolean {
        cancelled = false
        Thread({
            val r = Uploader.uploadAll(this) { cancelled }
            when (r) {
                Uploader.Result.AUTH_ERROR -> Notify.post(this, 3, "Diktuj", "Arch odmítl token – zkontroluj ho v aplikaci")
                Uploader.Result.NO_CONFIG -> Notify.post(this, 3, "Diktuj", "Vyplň v aplikaci adresu Archu a token")
                else -> {}
            }
            jobFinished(params, false)
            if (r == Uploader.Result.UNREACHABLE || r == Uploader.Result.ERROR) schedule(this, RETRY_MS)
        }, "diktuj-upload").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelled = true
        return true // systém ji naplánuje znovu
    }

    companion object {
        private const val JOB_ID = 1001
        private const val RETRY_MS = 5 * 60_000L

        fun schedule(ctx: Context, delayMs: Long = 0L) {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val b = JobInfo.Builder(JOB_ID, ComponentName(ctx, UploadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            if (delayMs > 0) b.setMinimumLatency(delayMs)
            js.schedule(b.build())
        }
    }
}
