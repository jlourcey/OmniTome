package com.ereader.app.ui.shelves

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ereader.app.domain.model.SmartShelf

// ─────────────────────────────────────────────────────────────────────────────
// SMART SHELVES — saved Calibre searches shown as shelves on the Shelves tab.
// This file holds the New / Edit Smart Shelf popup.
// ─────────────────────────────────────────────────────────────────────────────

/** State of the "New / Edit Smart Shelf" popup. */
data class ShelfEditorState(
    /** null = making a new shelf; otherwise the shelf being changed. */
    val editing: SmartShelf? = null,
    val isSaving: Boolean    = false,
    val error: String?       = null
)

/** Ready-made searches the user can tap to fill in the search box. */
private val ShelfExamples = listOf(
    "Unread"            to "#read:false",
    "4 stars and up"    to "rating:>=4",
    "Added this month"  to "date:>30daysago",
    "Fantasy"           to "tags:\"=Fantasy\"",
    "On a shelf"        to "#shelves:\"=To read\"",
    "In a series"       to "series:true",
    "Saved search"      to "search:\"My saved search\""
)

/** The New / Edit Smart Shelf popup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SmartShelfDialog(
    state: ShelfEditorState,
    onDismiss: () -> Unit,
    onSave: (name: String, query: String) -> Unit,
    onDelete: (SmartShelf) -> Unit
) {
    val editing = state.editing
    var name  by remember(editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
    var query by remember(editing?.id) { mutableStateOf(editing?.query.orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Smart Shelf") },
            text  = { Text("Delete \"${editing.name}\"? Your books stay in your library.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(editing) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!state.isSaving) onDismiss() },
        icon  = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
        title = { Text(if (editing == null) "New Smart Shelf" else "Edit Smart Shelf") },
        text  = {
            Column {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Shelf name") },
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier      = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = query,
                    onValueChange = { query = it },
                    label         = { Text("Calibre search") },
                    placeholder   = { Text("e.g. tags:\"=Fantasy\" and #read:false") },
                    textStyle     = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    modifier      = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Type it the same way as in Calibre's search bar. Tap an example to use it:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // Example chips, wrapped onto as many lines as needed
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ShelfExamples.forEach { (label, example) ->
                        SuggestionChip(
                            onClick = {
                                query = example
                                if (name.isBlank()) name = label
                            },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                state.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { onSave(name, query) },
                enabled  = !state.isSaving && name.isNotBlank()
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Asking Calibre…")
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            Row {
                if (editing != null) {
                    TextButton(onClick = { confirmDelete = true }, enabled = !state.isSaving) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss, enabled = !state.isSaving) { Text("Cancel") }
            }
        }
    )
}
