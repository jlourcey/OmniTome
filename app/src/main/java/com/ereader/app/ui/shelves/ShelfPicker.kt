package com.ereader.app.ui.shelves

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.local.entity.ShelfWithCount
import com.ereader.app.data.repository.ReadPushResult
import com.ereader.app.data.repository.ShelfRepository
import com.ereader.app.domain.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the "Add to Shelf" popup. */
@HiltViewModel
class ShelfPickerViewModel @Inject constructor(
    private val shelves: ShelfRepository
) : ViewModel() {

    val allShelves: StateFlow<List<ShelfWithCount>> = shelves.observeShelves()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun shelfIdsFor(bookId: String): Set<String> = shelves.shelfIdsForBook(bookId)

    suspend fun create(name: String): String? = shelves.createShelf(name)?.id

    /** Saves the ticks. A name typed in "New shelf" but not added yet is created and ticked too. */
    fun save(book: Book, shelfIds: Set<String>, pendingNewName: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val ids = shelfIds + listOfNotNull(pendingNewName.takeIf { it.isNotBlank() }?.let { shelves.createShelf(it)?.id })
            val msg = when (val r = shelves.setBookShelves(book, ids)) {
                ReadPushResult.Synced -> "✓ Shelves for \"${book.title}\" updated and synced to Calibre"
                is ReadPushResult.Failed -> "✓ Shelves for \"${book.title}\" updated. They'll sync to Calibre next time you're home."
                else -> "✓ Shelves for \"${book.title}\" updated"
            }
            onDone(msg)
        }
    }
}

/**
 * "Add to Shelf" popup: tick the shelves this book belongs on, or make a new one.
 * Shelf changes are saved when you tap Save.
 */
@Composable
fun ShelfPickerDialog(
    book: Book,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    viewModel: ShelfPickerViewModel = hiltViewModel()
) {
    val shelves by viewModel.allShelves.collectAsStateWithLifecycle()
    var selected by remember(book.id) { mutableStateOf<Set<String>?>(null) }
    var newName  by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(book.id) { selected = viewModel.shelfIdsFor(book.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon  = { Icon(Icons.Default.CollectionsBookmark, contentDescription = null) },
        title = { Text("Shelves for \"${book.title}\"", maxLines = 2) },
        text  = {
            Column {
                if (shelves.isEmpty()) {
                    Text("You don't have any shelves yet. Name one below.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(shelves, key = { it.id }) { shelf ->
                        val checked = selected?.contains(shelf.id) == true
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = (selected ?: emptySet()).let { if (checked) it - shelf.id else it + shelf.id }
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(shelf.name, modifier = Modifier.weight(1f))
                            Text("${shelf.bookCount}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it.take(60) },
                        label = { Text("New shelf") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        enabled = newName.isNotBlank(),
                        onClick = {
                            scope.launch {
                                viewModel.create(newName)?.let { id -> selected = (selected ?: emptySet()) + id }
                                newName = ""
                            }
                        }
                    ) { Icon(Icons.Default.Add, contentDescription = "Create shelf") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = {
                    selected?.let { viewModel.save(book, it, newName, onMessage) }
                    onDismiss()
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
