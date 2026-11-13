package com.heartlink.phone.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.heartlink.phone.service.Settings
import kotlinx.coroutines.tasks.await

/**
 * Tells the partner "Home safe 🏠" when this phone arrives home. The home location is saved only
 * on this phone; Android watches a circle around it (a geofence) and wakes [GeofenceReceiver]
 * on arrival. Needs "Allow all the time" location access.
 */
object HomeArrival {
    private const val TAG = "HomeArrival"
    private const val GEOFENCE_ID = "home"
    private const val RADIUS_METERS = 150f

    fun hasForegroundPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context): Boolean = Settings.home(ctx) != null

    /** Saves the phone's current location as home and starts watching for arrivals. */
    @SuppressLint("MissingPermission") // Checked by hasForegroundPermission().
    suspend fun setHomeToCurrentLocation(ctx: Context): Boolean {
        if (!hasForegroundPermission(ctx)) return false
        val location = LocationServices.getFusedLocationProviderClient(ctx)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .await() ?: return false
        Settings.setHome(ctx, location.latitude to location.longitude)
        register(ctx)
        return true
    }

    fun clear(ctx: Context) {
        Settings.setHome(ctx, null)
        LocationServices.getGeofencingClient(ctx).removeGeofences(pendingIntent(ctx))
    }

    /** (Re)registers the geofence. Android drops geofences on reboot, so this runs at boot too. */
    @SuppressLint("MissingPermission") // Checked below.
    fun register(ctx: Context) {
        val (lat, lng) = Settings.home(ctx) ?: return
        if (!hasForegroundPermission(ctx) || !hasBackgroundPermission(ctx)) return
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(lat, lng, RADIUS_METERS)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        val request = GeofencingRequest.Builder()
            // Don't fire just because we're already home when it's registered.
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()
        LocationServices.getGeofencingClient(ctx)
            .addGeofences(request, pendingIntent(ctx))
            .addOnFailureListener { Log.w(TAG, "Couldn't register home geofence", it) }
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        0,
        Intent(ctx, GeofenceReceiver::class.java),
        // Geofencing fills in the event, so the intent must be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}
