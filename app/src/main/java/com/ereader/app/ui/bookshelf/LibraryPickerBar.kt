package com.ereader.app.ui.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ereader.app.data.repository.CalibreLibraryChoices

/**
 * The library switch at the top of the bookshelf.
 *
 * Lists every Calibre library the signed-in Calibre user is allowed to open
 * (Calibre decides this per user). Picking one shows that library's books and
 * syncs it. Only shown when the login can open two or more libraries.
 */
@Composable
internal fun LibraryPickerBar(
    choices: CalibreLibraryChoices,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val activeName = choices.libraries.firstOrNull { it.first == choices.activeId }?.second
        ?: choices.libraries.firstOrNull()?.second
        ?: return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        AssistChip(
            onClick      = { expanded = true },
            label        = { Text("Library: $activeName", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon  = { Icon(Icons.Default.LocalLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose library") }
        )
        DropdownMenu(
            expanded         = expanded,
            onDismissRequest = { expanded = false }
        ) {
            choices.libraries.forEach { (id, name) ->
                val isActive = id == choices.activeId
                DropdownMenuItem(
                    text        = { Text(name) },
                    onClick     = {
                        expanded = false
                        if (!isActive) onSelect(id)
                    },
                    leadingIcon = {
                        if (isActive) Icon(Icons.Default.Check, contentDescription = "Showing now")
                        else Spacer(Modifier.size(24.dp))
                    }
                )
            }
        }
    }
}
