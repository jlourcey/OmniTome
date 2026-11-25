package com.ereader.app.ui.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ereader.app.data.dictionary.DictionaryResult
import com.ereader.app.data.dictionary.WikipediaSummary

/** What the definition card is showing. */
data class DictionaryCardState(
    val selection: String,
    val result: DictionaryResult? = null,       // null = still looking up
    val wiki: WikiState = WikiState.NotAsked
)

sealed class WikiState {
    data object NotAsked : WikiState()
    data object Loading  : WikiState()
    data class  Loaded(val summary: WikipediaSummary) : WikiState()
    data object NotFound : WikiState()
    data object Offline  : WikiState()
}

/**
 * The "Define" card: slides up over the page so you never leave the book.
 * Dictionary meanings come from the offline WordNet database; the Wikipedia
 * button is optional and is the only part that needs internet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DictionarySheet(
    state: DictionaryCardState,
    onWikipedia: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            val result = state.result
            // ── Headword ──────────────────────────────────────────────────────
            Text(
                text       = result?.headword ?: state.selection,
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (result?.headword != null && result.headword != result.query) {
                Text(
                    "from “${result.query}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            // ── Meanings ──────────────────────────────────────────────────────
            when {
                result == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                !result.found -> Text(
                    "No definition found in the offline dictionary.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> result.entries.forEach { entry ->
                    Text(
                        // "verb · run" when this part comes from the base word
                        if (entry.headword != result.headword) "${entry.partOfSpeech} \u00B7 ${entry.headword}"
                        else entry.partOfSpeech,
                        style      = MaterialTheme.typography.labelLarge,
                        fontStyle  = FontStyle.Italic,
                        color      = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    entry.senses.forEachIndexed { i, sense ->
                        Row(Modifier.padding(bottom = 8.dp)) {
                            Text("${i + 1}.", modifier = Modifier.width(22.dp),
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Column {
                                Text(sense.definition, style = MaterialTheme.typography.bodyMedium)
                                sense.examples.forEach {
                                    Text("“$it”", style = MaterialTheme.typography.bodySmall,
                                        fontStyle = FontStyle.Italic,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (sense.synonyms.isNotEmpty()) {
                                    Text("Similar: " + sense.synonyms.joinToString(", "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ── Wikipedia (optional, online) ──────────────────────────────────
            when (val wiki = state.wiki) {
                WikiState.NotAsked -> OutlinedButton(onClick = onWikipedia) {
                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Look up on Wikipedia")
                }
                WikiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking Wikipedia…", style = MaterialTheme.typography.bodySmall)
                }
                WikiState.NotFound -> Text("Wikipedia has no article for this.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WikiState.Offline -> Text("Wikipedia needs an internet connection. The dictionary above works offline.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is WikiState.Loaded -> Column {
                    Text(wiki.summary.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(wiki.summary.extract, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("From Wikipedia (CC BY-SA)", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        wiki.summary.url?.let { url ->
                            TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            }) { Text("Open article") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Dictionary: WordNet® 3.1, © Princeton University",
                fontSize = 10.sp,
                color    = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
