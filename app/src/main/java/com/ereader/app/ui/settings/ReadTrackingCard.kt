package com.ereader.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ereader.app.data.preferences.UserPreferences
import com.ereader.app.data.remote.calibre.CalibreColumnInfo
import com.ereader.app.data.remote.calibre.CalibreSyncService
import com.ereader.app.data.remote.calibre.ReadColumnChoice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// SETTINGS → READ TRACKING
//
// Lets the user pick which of their own Calibre columns OmniTome uses for
// "Read" (Yes/No), "Date Read" (Date) and "Times Read" (Integers).
// Automatic (the default) finds them by name, as before.
// Picks are saved per Calibre library.
// ─────────────────────────────────────────────────────────────────────────────

private val CardBg        = Color(0xFF0D1848)
private val Gold          = Color(0xFFDDBB3A)
private val TextMain      = Color.White
private val TextDim       = Color.White.copy(alpha = 0.6f)

/** What the Read Tracking card is showing. */
sealed class ReadColumnsLoad {
    data object Idle    : ReadColumnsLoad()
    data object Loading : ReadColumnsLoad()
    data class  Loaded(val columns: List<CalibreColumnInfo>, val automatic: Triple<String?, String?, String?>) : ReadColumnsLoad()
    data class  Error(val message: String) : ReadColumnsLoad()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReadTrackingViewModel @Inject constructor(
    private val prefs: UserPreferences,
    private val calibreSync: CalibreSyncService
) : ViewModel() {

    /** The library the picks apply to (the one selected on the bookshelf). */
    val libraryId: StateFlow<String?> = prefs.effectiveCalibreLibraryId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Display name of that library, for the card's subtitle. */
    val libraryName: StateFlow<String?> = combine(
        prefs.effectiveCalibreLibraryId, prefs.knownCalibreLibraries
    ) { id, libs -> libs.firstOrNull { it.first == id }?.second ?: id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The saved picks for that library. */
    val choice: StateFlow<ReadColumnChoice> = prefs.effectiveCalibreLibraryId
        .flatMapLatest { id ->
            if (id == null) flowOf(ReadColumnChoice.DEFAULT) else prefs.observeReadColumnChoice(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadColumnChoice.DEFAULT)

    private val _load = MutableStateFlow<ReadColumnsLoad>(ReadColumnsLoad.Idle)
    val load: StateFlow<ReadColumnsLoad> = _load.asStateFlow()

    /** Asks Calibre for the library's custom columns. */
    fun loadColumns() {
        if (_load.value is ReadColumnsLoad.Loading) return
        _load.value = ReadColumnsLoad.Loading
        viewModelScope.launch {
            _load.value = runCatching { calibreSync.fetchLibraryColumns(libraryId.value) }
                .fold(
                    onSuccess = {
                        ReadColumnsLoad.Loaded(
                            it.columns,
                            Triple(it.automatic.readKey, it.automatic.dateKey, it.automatic.timesKey)
                        )
                    },
                    onFailure = {
                        ReadColumnsLoad.Error(
                            when (it) {
                                is java.net.UnknownHostException,
                                is java.net.ConnectException,
                                is java.net.SocketTimeoutException ->
                                    "Can't reach your Calibre server. Make sure you're on your home network."
                                else -> it.localizedMessage ?: "Couldn't read the columns from Calibre."
                            }
                        )
                    }
                )
        }
    }

    fun save(newChoice: ReadColumnChoice) {
        val lib = libraryId.value ?: return
        viewModelScope.launch { prefs.saveReadColumnChoice(lib, newChoice) }
    }
}

@Composable
fun ReadTrackingCard(viewModel: ReadTrackingViewModel = hiltViewModel()) {
    val libraryId   by viewModel.libraryId.collectAsStateWithLifecycle()
    val libraryName by viewModel.libraryName.collectAsStateWithLifecycle()
    val choice      by viewModel.choice.collectAsStateWithLifecycle()
    val load        by viewModel.load.collectAsStateWithLifecycle()

    // Load the columns once when the card first appears (if a library is known).
    LaunchedEffect(libraryId) { if (libraryId != null) viewModel.loadColumns() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text     = "Choose which of your Calibre columns OmniTome uses. " +
                       "Automatic finds them by name (\"Read\", \"Date Read\", \"Times Read\").",
            color    = TextDim,
            fontSize = 12.sp
        )
        if (libraryName != null) {
            Spacer(Modifier.height(4.dp))
            Text("Library: $libraryName", color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        when (val l = load) {
            ReadColumnsLoad.Idle -> {
                Text(
                    "Sync your Calibre library once, then come back here.",
                    color = TextDim, fontSize = 13.sp
                )
            }
            ReadColumnsLoad.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Gold)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading columns from Calibre…", color = TextDim, fontSize = 13.sp)
                }
            }
            is ReadColumnsLoad.Error -> {
                Text(l.message, color = Color(0xFFFF8A80), fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = viewModel::loadColumns) { Text("Try Again", color = Gold) }
            }
            is ReadColumnsLoad.Loaded -> {
                ColumnPicker(
                    label     = "Read  (Yes/No column)",
                    columns   = l.columns.filter { it.datatype == "bool" },
                    allColumns = l.columns,
                    selected  = choice.read,
                    automatic = l.automatic.first,
                    onSelect  = { viewModel.save(choice.copy(read = it)) }
                )
                Spacer(Modifier.height(10.dp))
                ColumnPicker(
                    label     = "Date Read  (Date column)",
                    columns   = l.columns.filter { it.datatype == "datetime" },
                    allColumns = l.columns,
                    selected  = choice.date,
                    automatic = l.automatic.second,
                    onSelect  = { viewModel.save(choice.copy(date = it)) }
                )
                Spacer(Modifier.height(10.dp))
                ColumnPicker(
                    label     = "Times Read  (Integers column)",
                    columns   = l.columns.filter { it.datatype == "int" },
                    allColumns = l.columns,
                    selected  = choice.times,
                    automatic = l.automatic.third,
                    onSelect  = { viewModel.save(choice.copy(times = it)) }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Changes take effect at the next sync (tap ↻ on the Library screen).\n\n" +
                    "No column yet? In Calibre: Preferences → Add your own columns → " +
                    "the green + button. Use \"Yes/No\" for Read, \"Date\" for Date Read " +
                    "and \"Integers\" for Times Read. Restart Calibre, then tap Refresh Columns.",
                    color = TextDim, fontSize = 12.sp
                )
                TextButton(onClick = viewModel::loadColumns) { Text("Refresh Columns", color = Gold) }
            }
        }
    }
}

/**
 * One "label + dropdown" row. Options:
 *   Automatic (shows what it currently finds) · Don't use · each matching column.
 */
@Composable
private fun ColumnPicker(
    label: String,
    columns: List<CalibreColumnInfo>,
    allColumns: List<CalibreColumnInfo>,
    selected: String,
    automatic: String?,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    fun nameOf(key: String?): String =
        allColumns.firstOrNull { it.key == key }?.name ?: key.orEmpty()

    val automaticLabel = if (automatic != null) "Automatic (finds \"${nameOf(automatic)}\")"
                         else "Automatic (none found)"
    val current = when (selected) {
        ReadColumnChoice.AUTO -> automaticLabel
        ReadColumnChoice.OFF  -> "Don't use"
        else                  -> columns.firstOrNull { it.key == selected }
                                     ?.let { "${it.name}  (${it.key})" }
                                     ?: "$selected (not in this library — using Automatic)"
    }

    Text(label, color = TextDim, fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A2560))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(current, color = TextMain, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose column", tint = Gold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text    = { Text(automaticLabel) },
                onClick = { open = false; onSelect(ReadColumnChoice.AUTO) }
            )
            DropdownMenuItem(
                text    = { Text("Don't use") },
                onClick = { open = false; onSelect(ReadColumnChoice.OFF) }
            )
            if (columns.isEmpty()) {
                DropdownMenuItem(
                    text    = { Text("No columns of this type in Calibre", color = TextDim) },
                    onClick = { open = false },
                    enabled = false
                )
            }
            columns.forEach { col ->
                DropdownMenuItem(
                    text    = { Text("${col.name}  (${col.key})") },
                    onClick = { open = false; onSelect(col.key) }
                )
            }
        }
    }
}
