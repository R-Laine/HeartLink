package com.heartlink.phone.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.heartlink.phone.service.Settings
import com.heartlink.shared.CheckIns
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Arrived home: check in on this person's card and message the partner. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val session = Identity.get(context) ?: return

        val now = System.currentTimeMillis()
        if (now - Settings.lastHomeArrival(context) < CheckIns.HOME_COOLDOWN_MS) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(20_000) {
                    HeartRepository.checkIn(session, CheckIns.HOME)
                    // Recorded once the check-in is in, so a slow message isn't sent again (with
                    // a second check-in) on the next arrival.
                    Settings.setLastHomeArrival(context, now)
                    val myName = HeartRepository.observeMembers(session.coupleId).first()[session.role]
                    HeartRepository.sendMessage(session, "Got home safe 🏠", myName)
                }
            } catch (e: Exception) {
                Log.w("GeofenceReceiver", "Couldn't share arrival", e)
            } finally {
                pending.finish()
            }
        }
    }
}
