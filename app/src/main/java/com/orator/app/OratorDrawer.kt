package com.orator.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.components.SectionLabel
import com.orator.core.designsystem.components.SettingsRow
import com.orator.core.designsystem.theme.OnyxTokens

/** Shell drawer (mockup .drawer): search, add feed, app pages. No ABS/Stats/OPML yet. */
@Composable
fun OratorDrawer(
    counts: ShellViewModel.LibraryCounts,
    onSearch: () -> Unit,
    onAddFeed: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.width(300.dp).fillMaxHeight().background(OnyxTokens.NavBackground),
    ) {
        Column(Modifier.padding(start = 18.dp, top = 24.dp, end = 18.dp, bottom = 12.dp)) {
            Text(
                "ORATOR",
                color = OnyxTokens.Accent,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(
                "${counts.podcasts} podcasts · ${counts.books} books",
                color = OnyxTokens.TextDim,
                fontSize = 11.sp,
            )
        }
        Text(
            "⌕  Search Podcast Index…",
            color = OnyxTokens.TextDim,
            fontSize = 13.5.sp,
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .background(OnyxTokens.Surface)
                .clickable(onClick = onSearch)
                .padding(13.dp),
        )
        SettingsRow(glyph = "＋", label = "Add RSS feed", onClick = onAddFeed)
        SectionLabel("App")
        SettingsRow(glyph = "🕓", label = "History", onClick = onHistory)
        SettingsRow(glyph = "⚙", label = "Settings", onClick = onSettings)
    }
}
