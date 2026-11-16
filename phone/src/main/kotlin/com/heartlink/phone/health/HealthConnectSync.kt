package com.heartlink.phone.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import com.heartlink.shared.FinishedSleep
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Session
import com.heartlink.shared.Sources
import java.time.Duration
import java.time.Instant

/**
 * Fallback heart-rate source for people without the HeartLink watch app: reads the most recent
 * heart rate that their watch or band's own app (Fitbit, Samsung Health, Garmin, …) synced into
 * Health Connect, and shares it. These readings arrive whenever that app syncs, so they show up
 * as "x min ago" rather than live.
 */
object HealthConnectSync {
    private const val TAG = "HealthConnectSync"
    private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    private val LOOKBACK: Duration = Duration.ofHours(24)

    // Stored in the same prefs file as the session, so unlinking clears it too.
    private const val PREFS = "heartlink"
    private const val KEY_LAST_SYNCED = "hc_last_synced_ts"

    val HEART_RATE_PERMISSION = HealthPermission.getReadPermission(HeartRateRecord::class)
    val SLEEP_PERMISSION = HealthPermission.getReadPermission(SleepSessionRecord::class)

    enum class Availability { Available, NeedsInstall, Unsupported }

    fun availability(ctx: Context): Availability =
        when (HealthConnectClient.getSdkStatus(ctx, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.Available
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NeedsInstall
            else -> Availability.Unsupported
        }

    /** Opens Play Store to install or update Health Connect (needed on Android 13 and older). */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"))
            .setPackage("com.android.vending")
            .putExtra("overlay", true)

    private fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    /**
     * Sleep sessions, plus background reading where supported, so HeartLink knows when you woke up
     * even if the watch never noticed.
     */
    fun sleepPermissionsToRequest(ctx: Context): Set<String> =
        permissionsToRequest(ctx) - HEART_RATE_PERMISSION + SLEEP_PERMISSION

    suspend fun hasSleepPermission(ctx: Context): Boolean =
        availability(ctx) == Availability.Available &&
            SLEEP_PERMISSION in client(ctx).permissionController.getGrantedPermissions()

    /**
     * The sleeps another app (Samsung Health, Fitbit, …) saved in the last day, oldest first. Those
     * apps save a night once it's over, which can be a while after waking, and some split a night
     * in two around a wake in the middle, so all of them go to the sleep model.
     */
    suspend fun recentSleeps(ctx: Context): List<FinishedSleep> {
        if (!hasSleepPermission(ctx)) return emptyList()
        val response = client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(Instant.now().minus(LOOKBACK)),
                ascendingOrder = false,
                pageSize = 10,
            ),
        )
        return response.records
            .map { FinishedSleep(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
            .sortedBy { it.start }
    }

    /** Heart rate, plus background reading where this device's Health Connect supports it. */
    fun permissionsToRequest(ctx: Context): Set<String> = buildSet {
        add(HEART_RATE_PERMISSION)
        val background = client(ctx).features
            .getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)
        if (background == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }

    suspend fun hasPermission(ctx: Context): Boolean =
        availability(ctx) == Availability.Available &&
            HEART_RATE_PERMISSION in client(ctx).permissionController.getGrantedPermissions()

    /**
     * Uploads the newest heart-rate sample from the last 24 h if it hasn't been shared yet.
     * Returns true if something was uploaded.
     */
    suspend fun syncOnce(ctx: Context, session: Session): Boolean {
        if (!hasPermission(ctx)) return false

        val response = client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.after(Instant.now().minus(LOOKBACK)),
                ascendingOrder = false,
                pageSize = 20,
            ),
        )
        val latest = response.records.flatMap { it.samples }.maxByOrNull { it.time } ?: return false
        val timestamp = latest.time.toEpochMilli()

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (timestamp <= prefs.getLong(KEY_LAST_SYNCED, 0L)) return false

        val uploaded = HeartRepository.uploadSample(
            session = session,
            bpm = latest.beatsPerMinute.toInt(),
            timestampMs = timestamp,
            source = Sources.HEALTH_CONNECT,
        )
        // Remember it even if a newer watch reading won, so we don't retry the same sample.
        prefs.edit().putLong(KEY_LAST_SYNCED, timestamp).apply()
        if (uploaded) Log.d(TAG, "Shared Health Connect reading from $timestamp")
        return uploaded
    }
}
