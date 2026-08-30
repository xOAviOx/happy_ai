package com.happy.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.happy.assistant.data.LogEntry
import kotlinx.coroutines.flow.Flow

private val LevelColors = mapOf(
    "ERROR" to Color(0xFFD05353),
    "WARN" to Color(0xFFCC8A00),
    "INFO" to Color(0xFF3E8FB0),
    "DEBUG" to Color(0xFF8A8A8A),
)

@Composable
fun LogScreen(
    entries: Flow<List<LogEntry>>,
    onClear: () -> Unit,
    onCopy: (List<LogEntry>) -> Unit,
) {
    val rows by entries.collectAsStateWithLifecycle(initialValue = emptyList())

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Log (${rows.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Row {
                    TextButton(onClick = { onCopy(rows) }) { Text("Copy") }
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
            HorizontalDivider()
            if (rows.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row {
                            Text(
                                LogActivity.timeOf(entry),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${entry.level.first()} ${entry.tag}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = LevelColors[entry.level] ?: Color.Gray,
                            )
                        }
                        Text(
                            entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                }
            }
        }
    }
}
