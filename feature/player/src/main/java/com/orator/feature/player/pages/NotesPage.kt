package com.orator.feature.player.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.components.SectionLabel
import com.orator.core.designsystem.text.ShowNotes
import com.orator.core.designsystem.theme.OnyxTokens

/**
 * Pager middle page for episodes: show notes with tappable timestamp links, transcript below.
 * Timestamps are original-timeline; the ViewModel subtracts the show's intro clip.
 */
@Composable
fun NotesPage(
    notes: ShowNotes.Rendered?,
    transcript: String?,
    transcriptAvailableButNotFetched: Boolean,
    onTimestampTap: (Long) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp),
    ) {
        SectionLabel("Show notes", Modifier.padding(top = 6.dp))
        if (notes == null) {
            Text("No show notes.", color = OnyxTokens.TextFaint, fontSize = 13.sp)
        } else {
            val annotated = buildAnnotatedString {
                append(notes.text)
                notes.links.forEach { link ->
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "timestamp",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = OnyxTokens.AccentBright,
                                    fontWeight = FontWeight.Bold,
                                ),
                            ),
                            linkInteractionListener = { onTimestampTap(link.positionMs) },
                        ),
                        link.startIndex,
                        link.endIndex,
                    )
                }
            }
            Text(annotated, color = OnyxTokens.TextDim, fontSize = 13.sp, lineHeight = 21.sp)
        }
        when {
            transcript != null -> {
                SectionLabel("Transcript", Modifier.padding(top = 10.dp))
                Text(transcript, color = OnyxTokens.TextDim, fontSize = 12.sp, lineHeight = 19.sp)
            }
            transcriptAvailableButNotFetched -> {
                SectionLabel("Transcript", Modifier.padding(top = 10.dp))
                Text(
                    "Transcript available — download the episode to get it.",
                    color = OnyxTokens.TextFaint,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
