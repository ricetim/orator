package com.orator.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orator.core.designsystem.theme.OnyxTokens

/** Label row + bar state for one progress bar. */
data class BarSpec(
    val leftLabel: String,
    val rightLabel: String,
    val fraction: Float,
    /** Tick positions as fractions (chapter starts on the whole-item bar). */
    val ticks: List<Float> = emptyList(),
)

/**
 * The player's stacked progress bars (mockup .dual): an optional chapter bar (bright accent,
 * thumb) above the whole-item bar (accent, tick marks). [onChapterSeek]/[onItemSeek] receive
 * the tapped/dragged fraction 0..1.
 */
@Composable
fun DualProgressBars(
    chapter: BarSpec?,
    item: BarSpec,
    onChapterSeek: (Float) -> Unit,
    onItemSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
        if (chapter != null) {
            BarLabels(chapter)
            SeekBar(
                fraction = chapter.fraction,
                ticks = chapter.ticks,
                fillColor = OnyxTokens.AccentBright,
                showThumb = true,
                onSeek = onChapterSeek,
            )
        }
        BarLabels(item)
        SeekBar(
            fraction = item.fraction,
            ticks = item.ticks,
            fillColor = OnyxTokens.Accent,
            showThumb = chapter == null,
            onSeek = onItemSeek,
        )
    }
}

@Composable
private fun BarLabels(spec: BarSpec) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 5.dp)) {
        Text(
            spec.leftLabel.uppercase(),
            color = OnyxTokens.TextFaint,
            fontSize = 10.sp,
            letterSpacing = 0.7.sp,
            modifier = Modifier.weight(1f),
        )
        Text(spec.rightLabel, color = OnyxTokens.TextFaint, fontSize = 10.sp)
    }
}

@Composable
private fun SeekBar(
    fraction: Float,
    ticks: List<Float>,
    fillColor: Color,
    showThumb: Boolean,
    onSeek: (Float) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp) // generous touch target; visual bar is 4dp centered
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val barWidth = maxWidth
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(OnyxTokens.BarTrack),
        ) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxSize().background(fillColor))
        }
        ticks.forEach { t ->
            Box(
                Modifier
                    .offset(x = barWidth * t)
                    .width(2.dp).height(8.dp)
                    .background(OnyxTokens.TextFaint),
            )
        }
        if (showThumb) {
            Box(
                Modifier
                    .offset(x = barWidth * fraction.coerceIn(0f, 1f) - 6.dp)
                    .size(13.dp).clip(CircleShape).background(OnyxTokens.Text),
            )
        }
    }
}
