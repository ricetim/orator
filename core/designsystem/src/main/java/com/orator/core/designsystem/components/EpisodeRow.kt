package com.orator.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.theme.OnyxTokens

/**
 * List row used for episodes, queue items, series books, history. Leading slot is either
 * a date block ([DateBlock]) or small artwork ([RowArt]); trailing slot is free (download
 * button etc). Sub-line is an [AnnotatedString] so callers can accent-color inline markers
 * ("↓ downloaded"). Mockup .eprow.
 */
@Composable
fun EpisodeRow(
    title: String,
    subLine: AnnotatedString,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    titleColor: Color = OnyxTokens.Text,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                title,
                color = titleColor,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subLine,
                color = OnyxTokens.TextDim,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/** Convenience overload for plain-text sub-lines. */
@Composable
fun EpisodeRow(
    title: String,
    subLine: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    titleColor: Color = OnyxTokens.Text,
) = EpisodeRow(title, AnnotatedString(subLine), onClick, modifier, leading, trailing, titleColor)

/** "11 / JUN" leading date block for episode rows. Mockup .eprow .d. */
@Composable
fun DateBlock(day: String, month: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.width(42.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(day, color = OnyxTokens.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(month.uppercase(), color = OnyxTokens.TextFaint, fontSize = 10.sp)
    }
}

/** 44dp artwork for queue/history rows. Mockup .qart. */
@Composable
fun RowArt(artworkModel: Any?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.size(44.dp)) {
        ArtworkImage(
            model = artworkModel,
            title = title,
            modifier = Modifier.size(44.dp),
            cornerRadius = 9.dp,
            initialsSize = 14.sp,
        )
    }
}
