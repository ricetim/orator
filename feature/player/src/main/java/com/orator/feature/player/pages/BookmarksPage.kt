package com.orator.feature.player.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.database.BookmarkEntity
import com.orator.core.database.ChapterEntity
import com.orator.core.database.SourceKind
import com.orator.core.designsystem.components.SectionLabel
import com.orator.core.designsystem.text.TimeFormats
import com.orator.core.designsystem.theme.OnyxTokens
import com.orator.feature.player.PlayerChapters

/**
 * Pager middle page for books: bookmarks (global positions) with chapter prefix, tap to jump,
 * ✕ to delete, plus an accent "add at current position" row. Mockup #bgbm.
 */
@Composable
fun BookmarksPage(
    bookmarks: List<BookmarkEntity>,
    chapters: List<ChapterEntity>,
    sourceKind: SourceKind,
    currentGlobalMs: Long,
    onBookmarkTap: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onAddBookmark: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 26.dp)) {
        item { SectionLabel("Bookmarks", Modifier.padding(top = 6.dp)) }
        items(bookmarks, key = { it.id }) { bookmark ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onBookmarkTap(bookmark) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        chapterNumberFor(chapters, sourceKind, bookmark.positionMs)?.let {
                            append("Ch $it · ")
                        }
                        append(TimeFormats.clock(bookmark.positionMs))
                        bookmark.note?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                    },
                    color = OnyxTokens.TextDim,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    color = OnyxTokens.TextFaint,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onDeleteBookmark(bookmark.id) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        item {
            Text(
                "＋ Add bookmark at ${TimeFormats.clock(currentGlobalMs)}",
                color = OnyxTokens.AccentBright,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClick = onAddBookmark)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** 1-based chapter number containing a global position; null when chapters are unknown. */
private fun chapterNumberFor(
    chapters: List<ChapterEntity>,
    sourceKind: SourceKind,
    globalMs: Long,
): Int? = PlayerChapters.chapterNumberAt(chapters, sourceKind, globalMs)
