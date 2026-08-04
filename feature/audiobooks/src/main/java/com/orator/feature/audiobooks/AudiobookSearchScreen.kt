package com.orator.feature.audiobooks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.database.artworkModel
import com.orator.core.designsystem.components.EpisodeRow
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.components.RowArt
import com.orator.core.designsystem.theme.OnyxTokens

// Distinct contentTypes keep the row shapes in separate reuse pools; see AudiobookListScreen.
private const val SEARCH_HEADER = "header"
private const val BOOK_ROW = "book"
private const val SERIES_ROW = "series"
private const val AUTHOR_ROW = "author"

@Composable
fun AudiobookSearchScreen(
    onOpenBook: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: AudiobookSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    // The field drives itself from local state: routing keystrokes through a StateFlow and back
    // costs a frame, which is what garbles IME composition and gesture typing.
    var term by rememberSaveable { mutableStateOf("") }
    // Re-arms the search after process death, where `term` is restored but the ViewModel is new.
    LaunchedEffect(Unit) { if (term.isNotEmpty()) viewModel.onQueryChange(term) }

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = "Search",
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
        )
        OutlinedTextField(
            value = term,
            onValueChange = {
                term = it
                viewModel.onQueryChange(it)
            },
            placeholder = { Text("Search your library", color = OnyxTokens.TextFaint) },
            singleLine = true,
            shape = RoundedCornerShape(11.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            // Results are already live; the search key just gets the keyboard out of the way.
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OnyxTokens.Accent,
                unfocusedBorderColor = OnyxTokens.ChipBorder,
                focusedTextColor = OnyxTokens.Text,
                unfocusedTextColor = OnyxTokens.Text,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        )

        val results = state.results
        if (state.term.isNotBlank() && results.isEmpty) {
            Text(
                "No matches",
                color = OnyxTokens.TextFaint,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        LazyColumn(contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding)) {
            if (results.books.isNotEmpty()) {
                item(key = "h:books", contentType = SEARCH_HEADER) { SearchHeader("Books") }
                items(results.books, key = { "b:${it.id}" }, contentType = { BOOK_ROW }) { book ->
                    EpisodeRow(
                        title = book.title,
                        subLine = book.author.orEmpty(),
                        onClick = { onOpenBook(book.id) },
                        leading = { RowArt(artworkModel = book.artworkModel, title = book.title) },
                    )
                }
            }
            if (results.series.isNotEmpty()) {
                item(key = "h:series", contentType = SEARCH_HEADER) { SearchHeader("Series") }
                items(results.series, key = { "s:${it.name}" }, contentType = { SERIES_ROW }) { hit ->
                    EpisodeRow(
                        title = hit.name,
                        subLine = bookCount(hit.count),
                        onClick = { onOpenSeries(hit.name) },
                    )
                }
            }
            if (results.authors.isNotEmpty()) {
                item(key = "h:authors", contentType = SEARCH_HEADER) { SearchHeader("Authors") }
                items(results.authors, key = { "a:${it.name}" }, contentType = { AUTHOR_ROW }) { hit ->
                    EpisodeRow(
                        title = hit.name,
                        subLine = bookCount(hit.count),
                        onClick = { onOpenAuthor(hit.name) },
                    )
                }
            }
        }
    }
}

private fun bookCount(n: Int) = if (n == 1) "1 book" else "$n books"

@Composable
private fun SearchHeader(text: String) {
    Text(
        text,
        color = OnyxTokens.TextDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}
