package com.orator.feature.podcasts

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.orator.core.designsystem.components.EpisodeRow
import com.orator.core.designsystem.components.OnyxTopBar
import com.orator.core.designsystem.components.RowArt
import com.orator.core.designsystem.theme.OnyxTokens

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var term by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(OnyxTokens.Background)) {
        OnyxTopBar(
            title = "Search",
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
        )
        OutlinedTextField(
            value = term,
            onValueChange = { term = it },
            placeholder = {
                Text("Search Podcast Index… or paste a feed URL", color = OnyxTokens.TextFaint)
            },
            singleLine = true,
            shape = RoundedCornerShape(11.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.onSearch(term) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OnyxTokens.Accent,
                unfocusedBorderColor = OnyxTokens.ChipBorder,
                focusedTextColor = OnyxTokens.Text,
                unfocusedTextColor = OnyxTokens.Text,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        )

        val status = when {
            state.searching -> "Searching…"
            state.error != null -> state.error
            state.provider != null -> "via ${state.provider}"
            else -> null
        }
        if (status != null) {
            Text(
                status,
                color = OnyxTokens.TextFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(contentPadding = PaddingValues(bottom = OnyxTokens.OverlayBottomPadding)) {
            val direct = state.directUrl
            if (direct != null) {
                item {
                    EpisodeRow(
                        title = "Subscribe to feed URL",
                        subLine = direct,
                        onClick = { viewModel.onSubscribeUrl(direct) },
                        trailing = {
                            SubscribePill(
                                subscribed = direct in state.subscribedFeeds,
                                onClick = { viewModel.onSubscribeUrl(direct) },
                            )
                        },
                    )
                }
            }
            items(state.results, key = { it.feedUrl }) { result ->
                EpisodeRow(
                    title = result.title,
                    subLine = result.author.orEmpty(),
                    onClick = { viewModel.onSubscribe(result) },
                    leading = { RowArt(artworkModel = result.artworkUrl, title = result.title) },
                    trailing = {
                        SubscribePill(
                            subscribed = result.feedUrl in state.subscribedFeeds,
                            onClick = { viewModel.onSubscribe(result) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SubscribePill(subscribed: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = !subscribed) {
        Text(
            if (subscribed) "✓ Subscribed" else "Subscribe",
            color = if (subscribed) OnyxTokens.Accent else OnyxTokens.AccentBright,
            fontSize = 12.sp,
        )
    }
}
