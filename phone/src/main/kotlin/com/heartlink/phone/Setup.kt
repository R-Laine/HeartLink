package com.heartlink.phone

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartlink.shared.HeartLinkColors
import com.heartlink.shared.HeartRepository
import com.heartlink.shared.Invite
import com.heartlink.shared.InviteCode
import com.heartlink.shared.Names
import com.heartlink.shared.Session
import kotlinx.coroutines.launch

private sealed interface Step {
    data object Welcome : Step
    data object Create : Step
    data object EnterCode : Step
    data class Join(val invite: Invite) : Step
}

/** First-run flow: start a new link, or join one with a one-time invite code. */
@Composable
fun SetupFlow(onDone: (Session) -> Unit) {
    var step by remember { mutableStateOf<Step>(Step.Welcome) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun go(next: Step) {
        error = null
        step = next
    }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("SetupFlow", "Setup step failed", e)
                error = "Something went wrong. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = step) {
            Step.Welcome -> {
                Title("HeartLink")
                Subtitle("Feel each other's heartbeat, wherever you are.")
                Spacer(Modifier.height(32.dp))
                BigButton("Start a new link", HeartLinkColors.Pink) { go(Step.Create) }
                Spacer(Modifier.height(12.dp))
                BigButton("I have an invite code", HeartLinkColors.Blue) { go(Step.EnterCode) }
            }

            Step.Create -> {
                var name by remember { mutableStateOf("") }
                Title("What's your name?")
                Subtitle("Your partner will see this next to your heartbeat.")
                Spacer(Modifier.height(24.dp))
                NameField(name) { name = it }
                Spacer(Modifier.height(16.dp))
                BigButton("Create link", HeartLinkColors.Pink, enabled = !busy && Names.clean(name).isNotEmpty()) {
                    run { onDone(HeartRepository.createCouple(Names.clean(name))) }
                }
                BackButton { go(Step.Welcome) }
            }

            Step.EnterCode -> {
                var code by remember { mutableStateOf("") }
                val normalized = InviteCode.normalize(code)
                Title("Enter your invite code")
                Subtitle("Codes work once. Ask your partner to share theirs, or use Add device on another of your devices.")
                Spacer(Modifier.height(24.dp))
                HeartTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(InviteCode.LENGTH + 2) },
                    label = "Invite code",
                    capitalization = KeyboardCapitalization.Characters,
                )
                Spacer(Modifier.height(16.dp))
                BigButton("Next", HeartLinkColors.Blue, enabled = !busy && InviteCode.isValid(normalized)) {
                    run {
                        val invite = HeartRepository.lookupInvite(normalized)
                        if (invite == null) {
                            error = "That code doesn't work. It may have expired or already been used."
                        } else {
                            go(Step.Join(invite))
                        }
                    }
                }
                BackButton { go(Step.Welcome) }
            }

            is Step.Join -> {
                val invite = s.invite
                val color = HeartLinkColors.forPerson(invite.role)
                val seatName = invite.seatName
                if (seatName != null) {
                    // Adding another device for someone who's already in the link.
                    Title("Link this phone as $seatName?")
                    Subtitle("It will share and show heartbeats just like $seatName's other devices.")
                    Spacer(Modifier.height(24.dp))
                    BigButton("Yes, I'm $seatName", color, enabled = !busy) {
                        run { onDone(HeartRepository.joinWithInvite(invite, name = null)) }
                    }
                } else {
                    var name by remember { mutableStateOf("") }
                    Title("${invite.fromName ?: "Your partner"} invited you")
                    Subtitle("What's your name? They'll see it next to your heartbeat.")
                    Spacer(Modifier.height(24.dp))
                    NameField(name) { name = it }
                    Spacer(Modifier.height(16.dp))
                    BigButton("Join", color, enabled = !busy && Names.clean(name).isNotEmpty()) {
                        run { onDone(HeartRepository.joinWithInvite(invite, Names.clean(name))) }
                    }
                }
                BackButton { go(Step.EnterCode) }
            }
        }

        if (busy) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = Color.White)
        }
        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = HeartLinkColors.Pink, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

@Composable
private fun Subtitle(text: String) {
    Spacer(Modifier.height(8.dp))
    Text(text, color = Color.Gray, fontSize = 15.sp, textAlign = TextAlign.Center)
}

@Composable
fun BigButton(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black),
    ) {
        Text(text, fontSize = 16.sp)
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onClick) { Text("Back", color = Color.Gray) }
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit) {
    HeartTextField(
        value = value,
        onValueChange = { onValueChange(it.take(Names.MAX_LENGTH)) },
        label = "Your name",
        capitalization = KeyboardCapitalization.Words,
    )
}

@Composable
private fun HeartTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    capitalization: KeyboardCapitalization,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color.Gray,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = Color.Gray,
            cursorColor = Color.White,
        ),
    )
}
