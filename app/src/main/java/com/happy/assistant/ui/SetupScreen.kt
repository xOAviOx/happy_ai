package com.happy.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.happy.assistant.service.HappyService
import com.happy.assistant.service.HappyState

private val Green = Color(0xFF2E9E6B)
private val Amber = Color(0xFFCC8A00)
private val Grey = Color(0xFF8A8A8A)

@Composable
fun SetupScreen(
    refreshTick: Int,
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    onFix: (CheckItem) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val context = LocalContext.current
    val state by HappyService.state.collectAsStateWithLifecycle()

    // refreshTick is read here on purpose: it is what makes the ticks re-evaluate
    // after the user comes back from a Settings screen.
    val rows = remember(refreshTick) {
        happyChecklist().map { it to statusOf(context, it) }
    }
    val outstanding = rows.count { (_, status) -> status == CheckStatus.MISSING }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Spacer(Modifier.height(20.dp))
                Text("Happy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Phase 1 - listening for the wake phrase, hey Jarvis.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))
            }

            item {
                ServiceCard(
                    state = state,
                    outstanding = outstanding,
                    onStart = onStart,
                    onStop = onStop,
                    onOpenLog = onOpenLog,
                )
                Spacer(Modifier.height(12.dp))
                WakeWordCard(threshold = threshold, onThresholdChange = onThresholdChange)
                Spacer(Modifier.height(12.dp))
                Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            items(rows, key = { it.first.id }) { (item, status) ->
                CheckRow(item = item, status = status, onFix = { onFix(item) })
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ServiceCard(
    state: HappyState,
    outstanding: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val running = state != HappyState.OFF
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (running) "Running" else "Stopped",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (running) Green else Grey,
            )
            Text(state.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                SetupActivity.deviceLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (outstanding > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "$outstanding item(s) still need granting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Amber,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = onStop) { Text("Stop") }
                } else {
                    Button(onClick = onStart) { Text("Start Happy") }
                }
                TextButton(onClick = onOpenLog) { Text("View log") }
            }
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem, status: CheckStatus, onFix: () -> Unit) {
    val (label, color) = when (status) {
        CheckStatus.GRANTED -> "Granted" to Green
        CheckStatus.MISSING -> "Needed" to Amber
        CheckStatus.UNKNOWN -> "Check by hand" to Amber
        CheckStatus.LATER -> "Phase ${item.phase}" to Grey
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                if (status == CheckStatus.LATER) {
                    Text(
                        "Happy will not appear in this Settings list until phase ${item.phase} adds the service.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Grey,
                    )
                }
                if (item.id == "accessibility") {
                    Text(
                        "Note: this toggle resets on every reinstall.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
            }
            if (status != CheckStatus.GRANTED) {
                TextButton(onClick = onFix, enabled = status != CheckStatus.LATER) { Text("Fix") }
            }
        }
    }
}

/**
 * Spec section 4: the threshold has to be tunable, because the right value is a
 * property of the room and the phone, not of the model. Lower catches more and
 * false-fires more. The log records the peak score each minute, which is what to
 * tune against.
 */
@Composable
private fun WakeWordCard(threshold: Float, onThresholdChange: (Float) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Wake word sensitivity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(String.format("%.2f", threshold), style = MaterialTheme.typography.titleSmall, color = Green)
            }
            Slider(
                value = threshold,
                onValueChange = onThresholdChange,
                valueRange = 0.2f..0.8f,
                steps = 11,
            )
            Text(
                "Lower hears you more often and false-fires more. Start at 0.50 and tune between 0.30 and 0.70.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
