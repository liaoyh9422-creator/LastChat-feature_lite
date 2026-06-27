package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "auto backup has been removed")
        return Result.success()
    }

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val WORK_NAME = "auto_backup"
    }
}
