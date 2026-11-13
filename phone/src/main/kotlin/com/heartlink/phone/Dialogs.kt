package com.heartlink.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.heartlink.shared.Messages
import com.heartlink.shared.PartnerStatus

@Composable
fun StatusLabel(status: PartnerStatus, text: String = status.label) {
    val color = when (status) {
        PartnerStatus.Online -> Color(0xFF4CD964)
        PartnerStatus.Inactive -> Color(0xFFFFCC00)
        PartnerStatus.Away -> Color(0xFFFF9F43)
        PartnerStatus.Asleep -> Color(0xFF8E8EF0)
        PartnerStatus.NoConnection, PartnerStatus.Unknown -> Color.Gray
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MessageDialog(partnerName: String, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message $partnerName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(Messages.MAX_LENGTH) },
                    placeholder = { Text("A short note…") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )
                Text("Or send one of these:", fontSize = 13.sp)
                Messages.quickReplies.forEach { reply ->
                    TextButton(onClick = { onSend(reply) }) { Text(reply) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(Messages.clean(text)) },
                enabled = Messages.clean(text).isNotEmpty(),
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
