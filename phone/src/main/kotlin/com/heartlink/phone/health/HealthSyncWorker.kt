package com.heartlink.phone.health

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartlink.shared.Identity
import java.util.concurrent.TimeUnit

/** Shares the latest Health Connect heart rate every 15 minutes (the shortest WorkManager allows). */
class HealthSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val session = Identity.get(applicationContext) ?: return Result.success()
        return try {
            HealthConnectSync.syncOnce(applicationContext, session)
            Result.success()
        } catch (e: SecurityException) {
            // Background reading not granted; we'll sync whenever the app is open instead.
            Log.i(TAG, "No background Health Connect access", e)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Health Connect sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HealthSyncWorker"
        private const val WORK_NAME = "health-connect-sync"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
