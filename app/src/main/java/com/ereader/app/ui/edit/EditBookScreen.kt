package com.ereader.app.ui.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage

/**
 * Edit Book screen — lets the user correct or enrich the metadata for any book.
 *
 * Fields:
 *  - Cover image (tap to pick from photo library)
 *  - Title (required)
 *  - Author (required)
 *  - Series name (optional)
 *  - Series number (optional; decimal supported for novellas like "2.5")
 *  - Description / synopsis (optional, multiline)
 *
 * Cover images are copied to app private storage so they persist if the
 * original gallery URI becomes invalid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditBookViewModel = hiltViewModel()
) {
    val title       by viewModel.title.collectAsStateWithLifecycle()
    val author      by viewModel.author.collectAsStateWithLifecycle()
    val tags        by viewModel.tags.collectAsStateWithLifecycle()
    val series      by viewModel.series.collectAsStateWithLifecycle()
    val seriesIndex by viewModel.seriesIndex.collectAsStateWithLifecycle()
    val description by viewModel.description.collectAsStateWithLifecycle()
    val coverUrl    by viewModel.coverUrl.collectAsStateWithLifecycle()
    val isLoading   by viewModel.isLoading.collectAsStateWithLifecycle()
    val isSaving    by viewModel.isSaving.collectAsStateWithLifecycle()
    val error       by viewModel.error.collectAsStateWithLifecycle()

    // Navigate back on successful save
    LaunchedEffect(Unit) {
        viewModel.saveComplete.collect { onNavigateBack() }
    }

    // System photo picker — no permissions needed on API 33+; falls back
    // to legacy picker on older devices automatically.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.onCoverPicked(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit Book Info") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier    = Modifier
                                .padding(end = 16.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(
                            onClick  = viewModel::onSave,
                            enabled  = title.isNotBlank() && author.isNotBlank()
                        ) {
                            Text("Save")
                        }
                    }
                }
            )
        },
        snackbarHost = {
            if (error != null) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::onErrorDismissed) { Text("Dismiss") }
                    }
                ) { Text(error!!) }
            }
        }
    ) { innerPadding ->

        if (isLoading) {
            Box(
                modifier         = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Cover image ───────────────────────────────────────────────────
            CoverEditor(
                coverUrl  = coverUrl,
                onPickImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                }
            )

            HorizontalDivider()

            // ── Title ─────────────────────────────────────────────────────────
            OutlinedTextField(
                value         = title,
                onValueChange = viewModel::onTitleChange,
                label         = { Text("Title *") },
                singleLine    = true,
                isError       = title.isBlank(),
                supportingText = if (title.isBlank()) {
                    { Text("Required") }
                } else null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // ── Author ────────────────────────────────────────────────────────
            OutlinedTextField(
                value         = author,
                onValueChange = viewModel::onAuthorChange,
                label         = { Text("Author *") },
                singleLine    = true,
                isError       = author.isBlank(),
                supportingText = if (author.isBlank()) {
                    { Text("Required") }
                } else null,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // ── Tags ──────────────────────────────────────────────────────────
            OutlinedTextField(
                value         = tags,
                onValueChange = viewModel::onTagsChange,
                label         = { Text("Tags") },
                singleLine    = true,
                placeholder   = { Text("Fantasy, Magic, Adventure") },
                supportingText = { Text("Separate multiple tags with commas") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction      = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // ── Series ────────────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value         = series,
                    onValueChange = viewModel::onSeriesChange,
                    label         = { Text("Series") },
                    singleLine    = true,
                    placeholder   = { Text("e.g. Dune") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction      = ImeAction.Next
                    ),
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value         = seriesIndex,
                    onValueChange = viewModel::onSeriesIndexChange,
                    label         = { Text("#") },
                    singleLine    = true,
                    placeholder   = { Text("1") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction    = ImeAction.Next
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            // ── Description ───────────────────────────────────────────────────
            OutlinedTextField(
                value         = description,
                onValueChange = viewModel::onDescriptionChange,
                label         = { Text("Description") },
                minLines      = 4,
                maxLines      = 10,
                placeholder   = { Text("Synopsis or notes…") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Default
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Bottom padding so the last field isn't obscured by the keyboard
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

// ── Cover editor composable ───────────────────────────────────────────────────

@Composable
private fun CoverEditor(
    coverUrl: String?,
    onPickImage: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPickImage),
        contentAlignment = Alignment.Center
    ) {
        if (coverUrl != null) {
            SubcomposeAsyncImage(
                model              = coverUrl,
                contentDescription = "Book cover",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                },
                error = { NoCoverPlaceholder() }
            )

            // Semi-transparent overlay with "Tap to change" hint
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            startY = 80f
                        )
                    )
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.PhotoCamera,
                    contentDescription = null,
                    tint     = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text  = "Tap to change cover",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        } else {
            NoCoverPlaceholder()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text  = "Tap to add a cover image",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoCoverPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}
