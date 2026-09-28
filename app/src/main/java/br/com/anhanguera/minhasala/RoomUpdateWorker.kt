package br.com.anhanguera.minhasala

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RoomUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        try {
            RoomWidgetProvider.updateAllWidgets(appContext)
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}
