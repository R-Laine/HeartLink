package com.heartlink.phone.health

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.heartlink.shared.Session
import kotlinx.coroutines.delay

private const val FOREGROUND_SYNC_INTERVAL_MS = 60_000L

/**
 * Lets someone without the HeartLink watch app share heart rate from Health Connect instead.
 * While the screen is open it also syncs every minute, on top of the 15-minute background job.
 */
@Composable
fun HealthConnectRow(session: Session) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var availability by remember { mutableStateOf(HealthConnectSync.availability(context)) }
    var granted by remember { mutableStateOf<Boolean?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    LaunchedEffect(refresh) {
        availability = HealthConnectSync.availability(context)
        granted = runCatching { HealthConnectSync.hasPermission(context) }.getOrDefault(false)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { result ->
        if (HealthConnectSync.HEART_RATE_PERMISSION in result) HealthSyncWorker.schedule(context)
        refresh++
    }

    if (granted == true) {
        LaunchedEffect(session) {
            HealthSyncWorker.schedule(context)
            while (true) {
                runCatching { HealthConnectSync.syncOnce(context, session) }
                    .onFailure { Log.w("HealthConnectRow", "Sync failed", it) }
                delay(FOREGROUND_SYNC_INTERVAL_MS)
            }
        }
    }

    when {
        availability == HealthConnectSync.Availability.Unsupported || granted == null -> Unit

        // The sharing card on the home screen already says it's coming from Health Connect.
        granted == true -> Unit

        availability == HealthConnectSync.Availability.NeedsInstall -> TextButton(onClick = {
            try {
                context.startActivity(HealthConnectSync.installIntent())
            } catch (e: ActivityNotFoundException) {
                Log.w("HealthConnectRow", "Play Store not available", e)
            }
        }) {
            Text("No HeartLink watch? Set up Health Connect", color = Color.LightGray)
        }

        else -> TextButton(onClick = {
            permissionLauncher.launch(HealthConnectSync.permissionsToRequest(context))
        }) {
            Text("No HeartLink watch? Share from Health Connect", color = Color.LightGray)
        }
    }
}
