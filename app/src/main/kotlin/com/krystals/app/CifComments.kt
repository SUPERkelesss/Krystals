package com.krystals.app

/**
 * Per v0.7.0: extract/inject user comments from/to CIF text.
 *
 * Comments are stored as a `#`-prefixed block in the CIF source:
 * ```
 * # Krystals Comments
 * # line 1
 * # line 2
 * ```
 * This is pure app-layer text processing — CifCodec is not modified.
 */
object CifComments {
    private const val MARKER = "# Krystals Comments"

    /** Extract user comments from CIF source text. Returns "" if none found. */
    fun extract(cifText: String): String {
        val markerIndex = cifText.indexOf(MARKER)
        if (markerIndex < 0) return ""
        // Start collecting from the line after the marker.
        val afterMarker = markerIndex + MARKER.length
        // Skip the newline after the marker.
        var pos = afterMarker
        if (pos < cifText.length && cifText[pos] == '\r') pos++
        if (pos < cifText.length && cifText[pos] == '\n') pos++
        val lines = mutableListOf<String>()
        while (pos < cifText.length) {
            val lineEnd = cifText.indexOf('\n', pos)
            val rawLine = if (lineEnd < 0) cifText.substring(pos) else cifText.substring(pos, lineEnd)
            val trimmed = rawLine.trimEnd('\r')
            if (!trimmed.startsWith("#")) break
            // Strip "# " or "#" prefix.
            val content = if (trimmed.startsWith("# ")) trimmed.substring(2) else trimmed.substring(1)
            lines.add(content)
            if (lineEnd < 0) break
            pos = lineEnd + 1
        }
        return lines.joinToString("\n")
    }

    /** Inject (or replace) the comments block into CIF source text. Returns the modified text. */
    fun inject(cifText: String, comments: String): String {
        // First remove any existing comments block.
        var text = removeExistingBlock(cifText)
        // If comments are blank, we're done.
        if (comments.isBlank()) return text
        // Build the new block.
        val lines = comments.split("\n")
        val block = buildString {
            append(MARKER)
            append("\n")
            lines.forEach { line ->
                if (line.isEmpty()) append("#\n") else append("# ").append(line).append("\n")
            }
        }
        // Append at the end of the file.
        if (text.isNotEmpty() && !text.endsWith("\n")) text += "\n"
        return text + block
    }

    private fun removeExistingBlock(cifText: String): String {
        val markerIndex = cifText.indexOf(MARKER)
        if (markerIndex < 0) return cifText
        // Find the start of the marker line.
        var blockStart = markerIndex
        // Include the preceding newline if any.
        if (blockStart > 0 && cifText[blockStart - 1] == '\n') blockStart--
        if (blockStart > 0 && cifText[blockStart - 1] == '\r') blockStart--
        // Find the end of the block (first non-# line after the marker).
        var pos = markerIndex + MARKER.length
        // Skip the newline after the marker.
        if (pos < cifText.length && cifText[pos] == '\r') pos++
        if (pos < cifText.length && cifText[pos] == '\n') pos++
        while (pos < cifText.length) {
            val lineEnd = cifText.indexOf('\n', pos)
            val rawLine = if (lineEnd < 0) cifText.substring(pos) else cifText.substring(pos, lineEnd)
            if (!rawLine.trimEnd('\r').startsWith("#")) {
                // This line is not part of the comment block — end before it.
                break
            }
            if (lineEnd < 0) {
                pos = cifText.length
                break
            }
            pos = lineEnd + 1
        }
        return cifText.substring(0, blockStart) + cifText.substring(pos)
    }
}
