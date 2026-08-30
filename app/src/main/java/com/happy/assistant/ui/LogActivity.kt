package com.happy.assistant.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.happy.assistant.data.LogDao
import com.happy.assistant.data.LogEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The last few hundred things Happy did, readable on the phone with no cable.
 *
 * Spec section 12: nothing is swallowed silently. This is where the swallowing
 * would otherwise happen, so it is worth having from Phase 0.
 */
@AndroidEntryPoint
class LogActivity : ComponentActivity() {

    @Inject lateinit var dao: LogDao

    // Held once, not rebuilt per recomposition: a fresh Room Flow on every frame
    // would restart the query and re-trigger composition forever.
    private val entries by lazy { dao.recent(LIMIT) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HappyTheme {
                LogScreen(
                    entries = entries,
                    onClear = { lifecycleScope.launch { dao.clear() } },
                    onCopy = ::copyToClipboard,
                )
            }
        }
    }

    private fun copyToClipboard(entries: List<LogEntry>) {
        val text = entries.asReversed().joinToString(System.lineSeparator()) { format(it) }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Happy log", text))
        Toast.makeText(this, "Copied ${entries.size} lines", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val LIMIT = 200
        private val STAMP = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

        fun timeOf(entry: LogEntry): String = STAMP.format(Date(entry.timestamp))

        fun format(entry: LogEntry): String =
            "${timeOf(entry)} ${entry.level.first()} ${entry.tag}: ${entry.message}"
    }
}
