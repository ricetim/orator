package com.orator.core.designsystem.text

import org.json.JSONObject

/**
 * Best-effort plain-text rendering of Podcasting-2.0 transcript files for the placeholder
 * viewer. Lossy by design (drops timing/speakers); real transcript UX is a UI-phase concern.
 */
object TranscriptText {

    private val TAGS = Regex("<[^>]+>")

    fun render(raw: String, type: String?): String = when {
        type?.contains("vtt") == true || raw.trimStart().startsWith("WEBVTT") -> cues(raw)
        type?.contains("srt") == true || type?.contains("subrip") == true -> cues(raw)
        type?.contains("json") == true || raw.trimStart().startsWith("{") -> json(raw)
        else -> raw.trim()
    }

    /**
     * Shared VTT/SRT walk: a "-->" line opens a cue; following non-blank lines are its text;
     * blank closes it. Everything outside cues (headers, indices, NOTE blocks) is dropped.
     */
    private fun cues(raw: String): String {
        val out = StringBuilder()
        var inCue = false
        var lineHasText = false
        for (line in raw.lines()) {
            val text = line.trim()
            when {
                text.contains("-->") -> {
                    if (lineHasText) out.append('\n')
                    inCue = true
                    lineHasText = false
                }
                text.isEmpty() -> inCue = false
                inCue -> {
                    if (lineHasText) out.append(' ')
                    out.append(TAGS.replace(text, ""))
                    lineHasText = true
                }
            }
        }
        return out.toString().trim()
    }

    private fun json(raw: String): String = try {
        val segments = JSONObject(raw).optJSONArray("segments")
        if (segments == null) {
            raw.trim()
        } else {
            buildString {
                for (i in 0 until segments.length()) {
                    val body = segments.getJSONObject(i).optString("body").trim()
                    if (body.isNotEmpty()) {
                        if (isNotEmpty()) append(' ')
                        append(body)
                    }
                }
            }
        }
    } catch (_: Exception) {
        raw.trim()
    }
}
