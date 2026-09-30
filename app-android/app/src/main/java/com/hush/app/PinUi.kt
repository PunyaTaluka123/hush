package com.hush.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

const val SUCCESS_ANIMATION_MS = 1200 // SPEC §7.3
const val SUCCESS_HOLD_MS = 800L

@Composable
fun HushTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
fun PinPad(title: String, length: Int, message: String?, onPin: (String) -> Unit) {
    var entered by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        // Reserved line: an error message never shifts the layout.
        Text(message ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.height(24.dp))
        Row(Modifier.padding(vertical = 16.dp)) {
            repeat(length) { i ->
                Box(Modifier.padding(6.dp).size(14.dp).clip(CircleShape)
                    .background(if (i < entered.length) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        listOf("123", "456", "789", " 0<").forEach { row ->
            Row {
                row.forEach { k ->
                    Key(k) {
                        when (k) {
                            ' ' -> Unit
                            '<' -> entered = entered.dropLast(1)
                            else -> {
                                entered += k
                                if (entered.length == length) {
                                    val pin = entered
                                    entered = ""
                                    onPin(pin)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(k: Char, onClick: () -> Unit) {
    val label = when (k) { '<' -> "⌫"; ' ' -> ""; else -> k.toString() }
    var m = Modifier.padding(8.dp).size(72.dp).clip(CircleShape)
    if (k != ' ') m = m.background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)
        .semantics { contentDescription = if (k == '<') "Delete" else label }
    Box(m, contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.headlineMedium) }
}

/**
 * The wearer's cancel flow (SPEC §7.3). `check` is the local verdict only, so
 * any accepted PIN gives the same screens, the same 1.2 s animation, the same
 * haptic, on the same schedule, without waiting for the server.
 */
@Composable
fun IncidentScreen(length: Int, check: (String) -> Boolean, onAcceptedHaptic: () -> Unit, onFinished: () -> Unit) {
    var done by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    if (!done) {
        PinPad("Enter your PIN to cancel the alarm", length, message) { pin ->
            if (check(pin)) {
                done = true
                onAcceptedHaptic()
            } else {
                message = "Incorrect PIN. Try again."
            }
        }
    } else {
        AlarmCancelled(onFinished)
    }
}

@Composable
fun AlarmCancelled(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(SUCCESS_ANIMATION_MS, easing = LinearEasing))
        delay(SUCCESS_HOLD_MS)
        onFinished()
    }
    val color = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(120.dp)) {
            val w = 8.dp.toPx()
            drawArc(color, -90f, 360f * progress.value, false, style = Stroke(w, cap = StrokeCap.Round))
            if (progress.value > 0.5f) {
                val t = (progress.value - 0.5f) * 2
                val a = Offset(size.width * 0.28f, size.height * 0.52f)
                val b = Offset(size.width * 0.45f, size.height * 0.68f)
                val c = Offset(size.width * 0.74f, size.height * 0.36f)
                drawLine(color, a, a + (b - a) * minOf(1f, t * 2), w, StrokeCap.Round)
                if (t > 0.5f) drawLine(color, b, b + (c - b) * ((t - 0.5f) * 2), w, StrokeCap.Round)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Alarm cancelled", style = MaterialTheme.typography.headlineSmall)
    }
}
