package com.ereader.app.ui.bookshelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ereader.app.domain.model.SmartShelf

// ─────────────────────────────────────────────────────────────────────────────
// SMART SHELVES — saved Calibre searches shown as shelves.
//   • The chip row above the sort chips: All Books · each shelf · + New Shelf
//   • Tap a shelf to show only its books; tap it again (or All Books) to go back
//   • Long-press a shelf to edit or delete it
// ─────────────────────────────────────────────────────────────────────────────

/** Ready-made searches the user can tap to fill in the search box. */
private val ShelfExamples = listOf(
    "Unread"            to "#read:false",
    "4 stars and up"    to "rating:>=4",
    "Added this month"  to "date:>30daysago",
    "Fantasy"           to "tags:\"=Fantasy\"",
    "In a series"       to "series:true",
    "Saved search"      to "search:\"My saved search\""
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SmartShelfBar(
    shelves: List<SmartShelf>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
    onEdit: (SmartShelf) -> Unit
) {
    LazyRow(
        contentPadding        = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        if (shelves.isNotEmpty()) {
            item(key = "all") {
                ShelfPill(
                    label    = "All Books",
                    selected = selectedId == null,
                    onClick  = { onSelect(null) },
                    onLongClick = null
                )
            }
        }
        items(shelves, key = { it.id }) { shelf ->
            ShelfPill(
                label       = "${shelf.name} (${shelf.calibreIds.size})",
                selected    = shelf.id == selectedId,
                onClick     = { onSelect(shelf.id) },
                onLongClick = { onEdit(shelf) }
            )
        }
        item(key = "new") {
            AssistChip(
                onClick     = onNew,
                label       = { Text(if (shelves.isEmpty()) "New Smart Shelf" else "New Shelf") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }
    }
}

/**
 * A chip that also reacts to long-press (Material's FilterChip only has a tap).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape  = shape,
        color  = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = if (selected) null else
                 androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .height(32.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onLongClick != null) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Shown in place of the grid when a Smart Shelf has no books. */
@Composable
internal fun EmptyShelfPlaceholder(shelf: SmartShelf) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = "No books on \"${shelf.name}\" yet.\n\n" +
                   "Calibre's search \"${shelf.query.ifBlank { "(everything)" }}\" didn't match any " +
                   "books that have synced to OmniTome. Long-press the shelf to change its search.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

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
