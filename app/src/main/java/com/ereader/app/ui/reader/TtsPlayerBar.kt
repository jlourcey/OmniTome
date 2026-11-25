package com.ereader.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/**
 * The small read-aloud player shown at the bottom of the page only while
 * reading aloud: play/pause, speed, sleep timer, stop. It doesn't cover the
 * page — taps above it still turn pages and open the reader controls.
 */
@Composable
internal fun TtsPlayerBar(
    theme: ReaderTheme,
    state: TtsState,
    rate: Float,
    sleep: SleepTimer,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onRate: (Float) -> Unit,
    onSleep: (SleepTimer) -> Unit
) {
    if (state == TtsState.IDLE) return
    val fg = readerHudOnSurface(theme)
    Surface(
        color           = readerHudSurface(theme),
        contentColor    = fg,
        shape           = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier        = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play / pause
            if (state == TtsState.PLAYING) {
                IconButton(onClick = onPause) { Icon(Icons.Default.Pause, contentDescription = "Pause reading") }
            } else {
                IconButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, contentDescription = "Resume reading") }
            }

            // Speed
            Box {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = true }) { Text(speedLabel(rate), color = fg) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    SPEEDS.forEach { s ->
                        DropdownMenuItem(text = { Text(speedLabel(s)) }, onClick = { open = false; onRate(s) })
                    }
                }
            }

            // Sleep timer
            Box {
                var open by remember { mutableStateOf(false) }
                TextButton(onClick = { open = true }) {
                    Icon(Icons.Default.Bedtime, contentDescription = "Sleep timer", tint = fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(sleepLabel(sleep), color = fg)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Off") }, onClick = { open = false; onSleep(SleepTimer.Off) })
                    listOf(15, 30, 45, 60).forEach { m ->
                        DropdownMenuItem(text = { Text("$m minutes") }, onClick = {
                            open = false; onSleep(SleepTimer.At(System.currentTimeMillis() + m * 60_000L, m))
                        })
                    }
                    DropdownMenuItem(text = { Text("End of chapter") }, onClick = { open = false; onSleep(SleepTimer.EndOfChapter) })
                }
            }

            Spacer(Modifier.weight(1f))
            IconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = "Stop reading") }
        }
    }
}

private fun speedLabel(rate: Float): String =
    (if (rate == rate.toInt().toFloat()) "${rate.toInt()}" else "$rate".trimEnd('0')) + "×"

/** "Off", "End of chapter", or the minutes left ("24 min"), updated each minute. */
@Composable
private fun sleepLabel(sleep: SleepTimer): String = when (sleep) {
    SleepTimer.Off          -> "Sleep"
    SleepTimer.EndOfChapter -> "End of chapter"
    is SleepTimer.At -> {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(sleep) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
        val mins = ((sleep.endsAt - now + 59_999) / 60_000).coerceAtLeast(0)
        "$mins min"
    }
}
