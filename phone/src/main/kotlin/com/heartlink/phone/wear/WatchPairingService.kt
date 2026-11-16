package com.heartlink.phone.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.heartlink.shared.BatteryState
import com.heartlink.shared.Devices
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Sources
import com.heartlink.shared.WatchReport
import com.heartlink.shared.Identity
import com.heartlink.shared.WearPaths
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * When the HeartLink watch app has no link yet, it asks the phone it's paired with. If this
 * phone is linked, we let the watch's Firebase user into the couple and send it the session,
 * so the watch never needs a code. The Data Layer only connects apps with the same package
 * name and signing key, so only our own watch app can ask.
 */
class WatchPairingService : WearableListenerService() {

    // Runs on a background thread, so blocking here is fine.
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.PAIR_REQUEST -> pair(event)
            WearPaths.SYNC_REPLY -> relay(event)
        }
    }

    /**
     * The watch answered a sync request: upload what it knows from here, in case its own route
     * to the internet isn't working. Each part only replaces older data.
     */
    private fun relay(event: MessageEvent) {
        val session = Identity.get(this) ?: return
        val report = WatchReport.decode(event.data)
        // Watches linked before their IDs were recorded: remember them now, so unlinking this
        // phone removes them too.
        report.uid?.let { Identity.addWatch(this, it) }
        runBlocking {
            runCatching {
                withTimeout(20_000) {
                    val bpm = report.bpm
                    val bpmAt = report.bpmAt
                    if (bpm != null && bpmAt != null) HeartRepository.uploadSample(session, bpm, bpmAt, Sources.WATCH)
                    report.battery?.let { HeartRepository.reportBattery(session, Devices.WATCH, BatteryState(it, report.charging)) }
                    val asleep = report.asleep
                    val asleepAt = report.asleepAt
                    if (asleep != null && asleepAt != null) {
                        HeartRepository.setAsleep(session, asleep, asleepAt)
                        Wearable.getMessageClient(this@WatchPairingService)
                            .sendMessage(event.sourceNodeId, WearPaths.SLEEP_RELAYED, asleepAt.toString().toByteArray())
                            .await()
                    }
                }
            }.onFailure { Log.w(TAG, "Couldn't relay watch data", it) }
        }
    }

    private fun pair(event: MessageEvent) {
        val watchUid = String(event.data).trim()
        if (watchUid.isEmpty()) return

        runBlocking {
            val (path, payload) = try {
                withTimeout(20_000) {
                    val session = Identity.get(this@WatchPairingService)
                    if (session == null) {
                        WearPaths.NOT_LINKED to ByteArray(0)
                    } else {
                        HeartRepository.addDevice(session, watchUid)
                        Identity.addWatch(this@WatchPairingService, watchUid)
                        WearPaths.SESSION to WearPaths.encode(session)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't link watch", e)
                return@runBlocking
            }
            try {
                Wearable.getMessageClient(this@WatchPairingService)
                    .sendMessage(event.sourceNodeId, path, payload)
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't reply to watch", e)
            }
        }
    }

    private companion object {
        const val TAG = "WatchPairingService"
    }
}
