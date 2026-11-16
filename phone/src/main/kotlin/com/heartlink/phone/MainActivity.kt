package com.heartlink.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.heartlink.phone.health.HealthConnectSync
import com.heartlink.phone.health.HealthSyncWorker
import com.heartlink.phone.activity.Motion
import com.heartlink.phone.sleep.NightJournal
import com.heartlink.phone.sleep.SleepApi
import com.heartlink.phone.location.HomeArrival
import com.heartlink.phone.service.HeartLinkService
import com.heartlink.shared.Battery
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { HeartLinkService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { runCatching { HeartRepository.ensureSignedIn() } }
        lifecycleScope.launch {
            val hasHealthConnect = runCatching { HealthConnectSync.hasPermission(this@MainActivity) }.getOrDefault(false)
            if (Identity.get(this@MainActivity) != null && hasHealthConnect) HealthSyncWorker.schedule(this@MainActivity)
        }
        setContent {
            PhoneApp(
                initialSession = Identity.get(this),
                onSessionChange = { session ->
                    if (session != null) {
                        Identity.set(this, session)
                        onLinked()
                    } else {
                        HeartLinkService.stop(this)
                        HealthSyncWorker.cancel(this)
                        SleepApi.unregister(this)
                        Motion.unregister(this)
                        NightJournal.clear(this)
                        HomeArrival.clear(this)
                        Battery.reset(this)
                        Identity.clear(this)
                    }
                },
            )
        }
        if (Identity.get(this) != null) {
            onLinked()
            HomeArrival.register(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Also records that this person is active.
        HeartLinkService.start(this)
    }

    private fun onLinked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            HeartLinkService.start(this)
        }
    }
}
