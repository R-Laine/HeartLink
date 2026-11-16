package com.heartlink.phone.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Watches this phone can reach that have the HeartLink watch app. Counting every connected node
 * treated a watch without HeartLink as connected.
 */
object WatchNodes {
    /** Declared by the watch app in res/values/wear.xml. */
    private const val CAPABILITY = "heartlink_watch"
    private const val TAG = "WatchNodes"

    suspend fun reachable(ctx: Context): Set<Node> =
        Wearable.getCapabilityClient(ctx)
            .getCapability(CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes

    /** Sends to every reachable HeartLink watch. Returns true if at least one received it. */
    suspend fun send(ctx: Context, path: String, payload: ByteArray = ByteArray(0)): Boolean = try {
        reachable(ctx).map { node ->
            runCatching { Wearable.getMessageClient(ctx).sendMessage(node.id, path, payload).await() }.isSuccess
        }.any { it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't reach watch", e)
        false
    }
}
