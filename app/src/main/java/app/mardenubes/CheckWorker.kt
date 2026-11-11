package app.mardenubes

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class CheckWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        Logic.checkAndNotify(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
