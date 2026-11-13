package com.heartlink.phone.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.heartlink.phone.location.HomeArrival

/** Restarts the background connection after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                HeartLinkService.start(context)
                // Android forgets geofences on reboot.
                HomeArrival.register(context)
            }
        }
    }
}
