package com.heartlink.phone.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.heartlink.shared.HeartRepository
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
        if (event.path != WearPaths.PAIR_REQUEST) return
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
