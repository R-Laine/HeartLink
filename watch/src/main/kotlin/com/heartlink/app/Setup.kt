package com.heartlink.app

import android.app.RemoteInput
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import androidx.wear.input.RemoteInputIntentHelper
import androidx.wear.input.WearableRemoteInputExtender
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.heartlink.shared.HeartLinkColors
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Invite
import com.heartlink.shared.Names
import com.heartlink.shared.Session
import com.heartlink.shared.WearPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val PAIR_RETRY_MS = 5_000L

/**
 * Watch setup. Normally automatic: the watch asks its phone (over the Wear OS Data Layer) and,
 * if HeartLink on the phone is linked, the phone adds this watch and sends back the session.
 * A one-time invite code is the fallback, e.g. if the phone app isn't installed.
 */
@Composable
fun WatchSetup(onDone: (Session) -> Unit) {
    val context = LocalContext.current
    val latestOnDone by rememberUpdatedState(onDone)
    var status by remember { mutableStateOf("Looking for your phone…") }
    var invite by remember { mutableStateOf<Invite?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun finish(session: Session) {
        if (done) return
        done = true
        latestOnDone(session)
    }

    // Replies from the phone.
    DisposableEffect(Unit) {
        val messages = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { event ->
            when (event.path) {
                WearPaths.SESSION -> WearPaths.decode(event.data)?.let(::finish)
                WearPaths.NOT_LINKED -> status = "Set up HeartLink on your phone first"
            }
        }
        messages.addListener(listener)
        onDispose { messages.removeListener(listener) }
    }

    // Keep asking the phone until it answers with a session.
    LaunchedEffect(Unit) {
        val messages = Wearable.getMessageClient(context)
        val nodes = Wearable.getNodeClient(context)
        while (!done) {
            try {
                val uid = HeartRepository.ensureSignedIn()
                val connected = nodes.connectedNodes.await()
                if (connected.isEmpty()) {
                    status = "Phone not connected"
                } else {
                    connected.forEach { node ->
                        messages.sendMessage(node.id, WearPaths.PAIR_REQUEST, uid.toByteArray()).await()
                    }
                }
            } catch (e: Exception) {
                Log.w("WatchSetup", "Pair request failed", e)
                status = "Couldn't reach your phone"
            }
            delay(PAIR_RETRY_MS)
        }
    }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("WatchSetup", "Setup step failed", e)
                error = "Couldn't connect. Try again."
            } finally {
                busy = false
            }
        }
    }

    val enterCode = rememberTextInput("Invite code") { input ->
        run {
            val found = HeartRepository.lookupInvite(input)
            if (found == null) error = "That code doesn't work" else invite = found
        }
    }

    val enterName = rememberTextInput("Your name") { input ->
        val current = invite ?: return@rememberTextInput
        val name = Names.clean(input)
        if (name.isNotEmpty()) run { finish(HeartRepository.joinWithInvite(current, name)) }
    }

    ScalingLazyColumn(modifier = Modifier.fillMaxWidth()) {
        val current = invite
        if (current == null) {
            item { Text("HeartLink", fontSize = 18.sp) }
            item { Text(status, color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center) }
            item { CircularProgressIndicator() }
            item {
                Text(
                    "Open HeartLink on your phone to link this watch automatically",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
            item { SetupChip("Use a code instead", Color.DarkGray, enabled = !busy, contentColor = Color.White, onClick = enterCode) }
        } else {
            val seatName = current.seatName
            val color = HeartLinkColors.forPerson(current.role)
            if (seatName != null) {
                item { Text("Link this watch as", textAlign = TextAlign.Center) }
                item {
                    SetupChip(seatName, color, enabled = !busy) {
                        run { finish(HeartRepository.joinWithInvite(current, name = null)) }
                    }
                }
            } else {
                item { Text("${current.fromName ?: "Your partner"} invited you", textAlign = TextAlign.Center) }
                item { SetupChip("Enter your name", color, enabled = !busy, onClick = enterName) }
            }
            item {
                SetupChip("Back", Color.DarkGray, enabled = !busy, contentColor = Color.White) {
                    error = null
                    invite = null
                }
            }
        }
        if (busy) item { CircularProgressIndicator() }
        error?.let { message ->
            item { Text(message, color = HeartLinkColors.Pink, fontSize = 12.sp, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun SetupChip(
    label: String,
    color: Color,
    enabled: Boolean,
    contentColor: Color = Color.Black,
    onClick: () -> Unit,
) {
    Chip(
        label = { Text(label) },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.primaryChipColors(backgroundColor = color, contentColor = contentColor),
    )
}

/** Returns a function that opens the Wear OS keyboard / voice input and passes the text to [onResult]. */
@Composable
private fun rememberTextInput(label: String, onResult: (String) -> Unit): () -> Unit {
    val latestOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(INPUT_KEY)
            ?.toString()
            ?.trim()
        if (!text.isNullOrEmpty()) latestOnResult(text)
    }
    return {
        val remoteInput = WearableRemoteInputExtender(RemoteInput.Builder(INPUT_KEY).setLabel(label))
            .setEmojisAllowed(false)
            .setInputActionType(EditorInfo.IME_ACTION_DONE)
            .get()
            .build()
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        launcher.launch(intent)
    }
}

private const val INPUT_KEY = "heartlink_text"
