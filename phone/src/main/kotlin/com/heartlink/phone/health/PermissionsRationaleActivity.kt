package com.heartlink.phone.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Explains how HeartLink uses Health Connect data. Health Connect requires apps to provide this
 * screen and opens it from its permission dialog and settings.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0E0E12)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("How HeartLink uses your health data", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Paragraph(
                        "HeartLink reads only your heart rate from Health Connect. It doesn't read any other health data.",
                    )
                    Paragraph(
                        "When you don't use the HeartLink watch app, HeartLink takes the most recent heart rate your " +
                            "watch or fitness band saved to Health Connect and shares it with the one partner you linked with.",
                    )
                    Paragraph(
                        "Only the latest reading is stored: one number and the time it was measured. It's replaced " +
                            "by each new reading. HeartLink doesn't keep a history and doesn't share your data with anyone else.",
                    )
                    Paragraph(
                        "If you allow background access, HeartLink checks for a new reading about every 15 minutes. " +
                            "You can turn this off at any time in Health Connect settings, or tap Unlink in HeartLink.",
                    )
                    TextButton(onClick = { finish() }) { Text("Close") }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Paragraph(text: String) {
    Text(text, color = Color.LightGray, fontSize = 16.sp, lineHeight = 22.sp)
}
