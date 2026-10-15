package com.example.jarvis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class ChatLine(val fromUser: Boolean, val text: String)

private val Navy = Color(0xFF05101B)
private val Panel = Color(0xFF0E2030)
private val Cyan = Color(0xFF48DFFE)
private val TextPale = Color(0xFFE7F7FF)

@Composable
fun JarvisScreen(
    status: String,
    busy: Boolean,
    messages: List<ChatLine>,
    config: JarvisConfig,
    showSettings: Boolean,
    wakeEnabled: Boolean,
    action: ActionTicket?,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onWakeToggle: () -> Unit,
    onSettingsToggle: () -> Unit,
    onSave: (JarvisConfig) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClearMemory: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(config.baseUrl) }
    var token by remember { mutableStateOf(config.appToken) }
    var picovoice by remember { mutableStateOf(config.picovoiceKey) }
    var confirmErase by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size) { scroll.animateScrollTo(scroll.maxValue) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, surface = Navy, onSurface = TextPale)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Navy).verticalScroll(rememberScrollState()).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("STARK SYSTEMS // AI", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("J.A.R.V.I.S.", color = TextPale, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                }
                TextButton(onClick = onSettingsToggle) {
                    Text(if (showSettings) "CLOSE" else "SETTINGS", color = Cyan)
                }
            }

            if (showSettings) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("CONNECTION SETTINGS", color = Cyan, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true,
                        label = { Text("Backend URL") }, placeholder = { Text("http://192.168.1.20:8765") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = token, onValueChange = { token = it }, singleLine = true,
                        label = { Text("App token from backend .env") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = picovoice, onValueChange = { picovoice = it }, singleLine = true,
                        label = { Text("Picovoice AccessKey") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Text("The API secret stays on the Python server. Use HTTP only for private LAN testing.",
                        color = TextPale.copy(alpha = .7f), fontSize = 12.sp)
                    Button(onClick = { onSave(JarvisConfig(url.trim(), token.trim(), picovoice.trim())) }, modifier = Modifier.fillMaxWidth()) {
                        Text("SAVE SETTINGS")
                    }
                    OutlinedButton(onClick = { confirmErase = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("ERASE SERVER MEMORY")
                    }
                }
            }

            HudCore(Modifier.size(226.dp))
            Text(status, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("WAKE WORD", color = TextPale, fontSize = 12.sp)
                    Switch(checked = wakeEnabled, onCheckedChange = { onWakeToggle() })
                    Text(if (wakeEnabled) "JARVIS ON" else "OFF", fontSize = 11.sp, color = Cyan)
                }
                Button(onClick = onMic, enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Navy)) {
                    Text("🎙 SPEAK", fontWeight = FontWeight.Black)
                }
            }

            Column(modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("COMMUNICATION LOG", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 250.dp).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (line in messages) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(if (line.fromUser) "YOU" else "JARVIS", color = if (line.fromUser) Color.White else Cyan,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(line.text, color = TextPale, fontSize = 14.sp)
                        }
                    }
                }
            }
            if (action != null) {
                Column(Modifier.fillMaxWidth().border(1.dp, Cyan, RoundedCornerShape(14.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("APPROVAL REQUIRED", color = Cyan, fontWeight = FontWeight.Bold)
                    Text(action.label, color = TextPale)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onConfirm, enabled = !busy) { Text("CONFIRM") }
                        OutlinedButton(onClick = onCancel, enabled = !busy) { Text("CANCEL") }
                    }
                }
            }
            OutlinedTextField(value = input, onValueChange = { input = it },
                label = { Text("Message JARVIS") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Button(
                onClick = { val v = input.trim(); if (v.isNotEmpty()) { onSend(v); input = "" } },
                modifier = Modifier.fillMaxWidth(), enabled = !busy && input.isNotBlank()
            ) { Text(if (busy) "PROCESSING" else "SEND COMMAND") }
            Text("JARVIS v0.1 • Foreground voice mode • Confirmation before actions",
                color = TextPale.copy(alpha = .48f), fontSize = 10.sp, textAlign = TextAlign.Center)
        }

        if (confirmErase) {
            AlertDialog(onDismissRequest = { confirmErase = false }, title = { Text("Erase all JARVIS memory?") },
                text = { Text("This deletes conversation history and saved facts for this device on your backend. This cannot be undone.") },
                confirmButton = { TextButton(onClick = { confirmErase = false; onClearMemory() }) { Text("ERASE") } },
                dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("CANCEL") } })
        }
    }
}

@Composable
private fun HudCore(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) * .48f
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(color = Cyan.copy(alpha = .12f), radius = r, center = center)
            drawCircle(color = Cyan.copy(alpha = .92f), radius = r * .94f, center = center, style = Stroke(2.5.dp.toPx()))
            drawCircle(color = Cyan.copy(alpha = .35f), radius = r * .82f, center = center, style = Stroke(5.dp.toPx()))
            drawCircle(color = Cyan.copy(alpha = .85f), radius = r * .63f, center = center, style = Stroke(2.dp.toPx()))
            drawCircle(color = Cyan.copy(alpha = .4f), radius = r * .47f, center = center, style = Stroke(3.dp.toPx()))
            for (i in 0 until 40) {
                val angle = (i * 2 * PI / 40).toFloat()
                val inner = r * .85f
                val outer = if (i % 5 == 0) r * 1.0f else r * .94f
                drawLine(Cyan.copy(alpha = if (i % 5 == 0) .95f else .34f),
                    start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                    end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                    strokeWidth = 1.5.dp.toPx())
            }
            drawCircle(Cyan.copy(alpha = .9f), radius = r * .28f, center = center)
            drawCircle(Color(0xFF0B2D42), radius = r * .23f, center = center)
        }
        Text("J", fontSize = 41.sp, fontWeight = FontWeight.Black, color = Cyan)
    }
}
