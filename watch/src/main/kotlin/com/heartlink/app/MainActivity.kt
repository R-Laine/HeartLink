package com.heartlink.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.UserActivityState
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.heartlink.shared.Battery
import com.heartlink.shared.Devices
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Identity
import com.heartlink.shared.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val session = mutableStateOf<Session?>(null)
    private val myBpm = mutableStateOf<Int?>(null)
    private val crash = mutableStateOf<String?>(null)
    private val setupNote = mutableStateOf<String?>(null)
    private val sleepWarning = mutableStateOf<SleepWarning?>(null)
    private var sleepSupported: Boolean? = null
    private var fixingSleep = false
    private var measuring = false

    private val measureCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

        override fun onDataReceived(data: DataPointContainer) {
            val value = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value ?: return
            if (value <= 0.0) return
            val bpm = value.roundToInt()
            myBpm.value = bpm
            SleepReport.recordReading(this@MainActivity, bpm)
            val current = session.value ?: return
            lifecycleScope.launch { HeartRepository.upload(current, bpm) }
        }
    }

    private val sensorPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) afterSensorPermission() else requestActivityRecognition()
        }

    private val backgroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestActivityRecognition() }

    private val activityPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setupBackground()
            if (fixingSleep) {
                fixingSleep = false
                // Denied for good: Android won't ask again, so open the app's settings instead.
                if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) openAppSettings()
            } else {
                requestNotifications()
            }
            checkSleep()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session.value = Identity.get(this)
        lifecycleScope.launch { runCatching { HeartRepository.ensureSignedIn() } }
        // A link the database no longer accepts (unlinked from the phone, or made in an older
        // Firebase project) would otherwise leave the watch stuck: forget it and pair again.
        session.value?.let { current ->
            lifecycleScope.launch { HeartRepository.isStillLinked(current) }
        }
        lifecycleScope.launch {
            HeartRepository.accessDenied.collectLatest { denied ->
                val current = session.value
                if (!denied || current == null) return@collectLatest
                // One refused read isn't proof the link is gone: only forget it if the database
                // confirms this watch isn't in the couple. If it can't be checked (offline), keep
                // checking, or the flag would never clear.
                while (true) {
                    when (HeartRepository.isStillLinked(current)) {
                        false -> return@collectLatest forgetLink(refused = true)
                        true -> {
                            android.util.Log.w("MainActivity", "Read refused but still linked: ${HeartRepository.lastDenied}")
                            return@collectLatest HeartRepository.resetAccessDenied()
                        }
                        null -> delay(LINK_RECHECK_MS)
                    }
                }
            }
        }
        // Unlinked while open, e.g. because the phone was unlinked.
        lifecycleScope.launch { LinkReset.events.collect { showSetup() } }
        crash.value = CrashLog.read(this)
        setContent {
            val report = crash.value
            if (report != null) {
                CrashReport(report, onDismiss = {
                    CrashLog.clear(this)
                    crash.value = null
                })
            } else {
                HeartLinkApp(
                    session = session.value,
                    myBpm = myBpm.value,
                    setupNote = setupNote.value,
                    sleepWarning = sleepWarning.value,
                    onFixSleep = ::fixSleep,
                    onLinked = ::link,
                    onUnlink = ::unlink,
                )
            }
        }
        if (session.value != null) requestSensors()
    }

    override fun onResume() {
        super.onResume()
        // The link may have been cleared while this screen was in the background.
        if (session.value != null && Identity.get(this) == null) showSetup()
        startLive()
        // Re-register background updates in case Health Services dropped them, and send any
        // sleep change still waiting.
        if (session.value != null) {
            setupBackground()
            SleepReport.retryIfPending(this)
        }
        checkSleep()
        // Using the watch counts as being active.
        session.value?.let { current ->
            lifecycleScope.launch { runCatching { HeartRepository.markActive(current) } }
            lifecycleScope.launch { Battery.reportIfChanged(this@MainActivity, current, Devices.WATCH) }
        }
    }

    override fun onPause() {
        super.onPause()
        stopLive()
    }

    private fun link(newSession: Session) {
        Pairing.setPaused(this, false)
        HeartRepository.resetAccessDenied()
        setupNote.value = null
        Identity.set(this, newSession)
        session.value = newSession
        requestSensors()
    }

    private fun unlink() {
        val old = session.value
        // Unlinked on purpose: don't pair straight back through the phone. Setup offers it instead.
        Pairing.setPaused(this, true)
        forgetLink()
        if (old != null) {
            // Not tied to this screen, so leaving still finishes if the app is closed.
            AppScope.launch {
                runCatching { withTimeout(LEAVE_TIMEOUT_MS) { HeartRepository.leave(old) } }
                    .onFailure { android.util.Log.w("MainActivity", "Couldn't remove device", it) }
            }
        }
    }

    /**
     * Clears the link on this watch only; setup then pairs it again through the phone. When the
     * database [refused] the link, setup explains why and waits before asking the phone again,
     * so a link that keeps failing can't flicker between screens.
     */
    private fun forgetLink(refused: Boolean = false) {
        android.util.Log.w("MainActivity", "Link no longer valid; pairing again")
        setupNote.value = if (refused) {
            listOfNotNull(
                "The database refused this watch's link. Trying again shortly.",
                HeartRepository.currentUid?.let { "Watch ID ${it.take(8)}" },
                HeartRepository.lastDenied,
            ).joinToString("\n")
        } else {
            null
        }
        LinkReset.forget(this)
        showSetup()
    }

    /** Back to setup once the link is gone. */
    private fun showSetup() {
        stopLive()
        session.value = null
        myBpm.value = null
        checkSleep()
    }

    private fun has(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private val heartRatePermission = BackgroundSetup.heartRatePermission

    private fun hasHeartRate() = BackgroundSetup.hasHeartRate(this)

    private fun requestSensors() {
        if (hasHeartRate()) afterSensorPermission()
        else sensorPermission.launch(heartRatePermission)
    }

    private fun afterSensorPermission() {
        startLive()
        setupBackground()
        val backgroundSensors = if (Build.VERSION.SDK_INT >= 36) {
            "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
        } else {
            Manifest.permission.BODY_SENSORS_BACKGROUND
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(backgroundSensors)) {
            backgroundPermission.launch(backgroundSensors)
        } else {
            requestActivityRecognition()
        }
    }

    /** Needed for sleep detection, so the partner can see when this person is asleep. */
    private fun requestActivityRecognition() {
        if (!has(Manifest.permission.ACTIVITY_RECOGNITION)) {
            activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            requestNotifications()
        }
    }

    /** For the notification shown while measuring live, and heartbeats that arrive silently. */
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startLive() {
        if (measuring || session.value == null || !hasHeartRate()) return
        runCatching {
            HealthServices.getClient(this).measureClient
                .registerMeasureCallback(DataType.HEART_RATE_BPM, measureCallback)
            measuring = true
        }.onFailure { android.util.Log.w("MainActivity", "Couldn't start live heart rate", it) }
    }

    private fun stopLive() {
        if (!measuring) return
        runCatching {
            HealthServices.getClient(this).measureClient
                .unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, measureCallback)
        }
        measuring = false
    }

    private fun setupBackground() = BackgroundSetup.register(this)

    /** Works out whether this watch can share sleep, and why not if it can't. */
    private fun checkSleep() {
        if (session.value == null) {
            sleepWarning.value = null
            return
        }
        if (!has(Manifest.permission.ACTIVITY_RECOGNITION)) {
            sleepWarning.value = SleepWarning.NeedsPermission
            return
        }
        sleepWarning.value = if (sleepSupported == false) SleepWarning.Unsupported else null
        if (sleepSupported != null) return
        runCatching {
            val future = HealthServices.getClient(this).passiveMonitoringClient.getCapabilitiesAsync()
            future.addListener({
                val supported = runCatching {
                    UserActivityState.USER_ACTIVITY_ASLEEP in future.get().supportedUserActivityStates
                }.getOrNull() ?: return@addListener
                sleepSupported = supported
                if (!supported && has(Manifest.permission.ACTIVITY_RECOGNITION)) sleepWarning.value = SleepWarning.Unsupported
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun fixSleep() {
        if (sleepWarning.value != SleepWarning.NeedsPermission) return
        fixingSleep = true
        activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    private companion object {
        val LINK_RECHECK_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(30)
        val LEAVE_TIMEOUT_MS = java.util.concurrent.TimeUnit.SECONDS.toMillis(30)
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }
}
