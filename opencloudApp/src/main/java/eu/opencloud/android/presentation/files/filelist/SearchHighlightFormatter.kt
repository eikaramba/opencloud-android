package eu.opencloud.android.presentation.files.filelist

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan

/**
 * Turns the snippet the server sends for a content search hit into styled text: the matched terms
 * arrive wrapped in `<mark>` tags and are shown in bold, line breaks are folded into spaces.
 * The snippet is plain text, so it is parsed by hand instead of being handed to an HTML parser.
 */
object SearchHighlightFormatter {

    private const val MARK_OPEN = "<mark>"
    private const val MARK_CLOSE = "</mark>"
    private const val SOFT_HYPHEN = "\u00AD"
    private val WHITESPACE = Regex("""\s+""")

    fun format(raw: String): CharSequence {
        val builder = SpannableStringBuilder()
        var markStart = -1
        var cursor = 0
        while (cursor < raw.length) {
            when {
                raw.startsWith(MARK_OPEN, cursor) -> {
                    markStart = builder.length
                    cursor += MARK_OPEN.length
                }
                raw.startsWith(MARK_CLOSE, cursor) -> {
                    if (markStart >= 0 && builder.length > markStart) {
                        builder.setSpan(StyleSpan(Typeface.BOLD), markStart, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    markStart = -1
                    cursor += MARK_CLOSE.length
                }
                else -> {
                    val next = listOf(MARK_OPEN, MARK_CLOSE)
                        .map { raw.indexOf(it, cursor) }
                        .filter { it >= 0 }
                        .minOrNull() ?: raw.length
                    builder.append(raw.substring(cursor, next).replace(SOFT_HYPHEN, "").replace(WHITESPACE, " "))
                    cursor = next
                }
            }
        }
        return builder
    }
}
