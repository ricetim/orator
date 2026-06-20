package com.orator.feature.player.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.database.ChapterEntity
import com.orator.core.database.ChapterTimeline
import com.orator.core.database.SourceKind
import com.orator.core.designsystem.components.SectionLabel
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens

/** Pager chapters page: current highlighted with ▶, tap to jump. Mockup #pgch/#bgch. */
@Composable
fun ChaptersPage(
    chapters: List<ChapterEntity>,
    sourceKind: SourceKind,
    currentChapterIndex: Int?,
    onChapterTap: (Int) -> Unit,
) {
    val sorted = if (sourceKind == SourceKind.SINGLE_FILE) chapters.sortedBy { it.startMs } else chapters
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 26.dp)) {
        item { SectionLabel("Chapters", Modifier.padding(top = 6.dp)) }
        itemsIndexed(sorted, key = { _, c -> c.chapterIndex }) { i, chapter ->
            val isCurrent = i == currentChapterIndex
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onChapterTap(i) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = (if (isCurrent) "▶ " else "") + "${i + 1} · ${chapter.title}",
                    color = if (isCurrent) OnyxTokens.AccentBright else OnyxTokens.TextDim,
                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Normal,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    // Chapter start position on the book timeline (global), for both kinds.
                    text = when (sourceKind) {
                        SourceKind.SINGLE_FILE -> TimeFormats.clock(chapter.startMs)
                        SourceKind.MULTI_FILE -> TimeFormats.clock(ChapterTimeline.globalStartOf(sorted, i))
                    },
                    color = OnyxTokens.TextFaint,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
