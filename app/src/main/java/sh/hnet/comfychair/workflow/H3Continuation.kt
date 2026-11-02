package sh.hnet.comfychair.workflow

/**
 * The prompt side of last-frame continuation.
 *
 * Two shapes exist in the wild: the structured prompts the H3 templates ship with
 * (`subject_definitions` / `summary` / `[Shot 1]`) and the free-form descriptions the official
 * examples use. The edit follows whichever one it is given instead of forcing a structure, and it
 * only ever adds the smallest line that names the frame.
 *
 * Ownership is the other half. The declaration this object writes and the one a user writes by
 * hand are the same sentence, so text alone cannot tell them apart: every edit reports what it
 * actually added, and [revert] removes only that. A hand-written line survives a switch-off.
 */
object H3Continuation {

    /** Returned when nothing was written, so the caller knows it owns no line. */
    const val NONE = -1

    private const val SUMMARY_FLAG = "[video continuation]"

    private val DECLARED = Regex(
        """<Picture\s*(\d+)>\s+is\s+the\s+final\s+frame""",
        RegexOption.IGNORE_CASE
    )
    private val OUR_DECLARATION = Regex(
        """(?m)^<Picture\s*(\d+)>\s*is\s+the\s+final\s+frame\s+of\s+the\s+previous\s+segment[^\n]*\n?""",
        RegexOption.IGNORE_CASE
    )
    private val OUR_FREEFORM_ZH = Regex("""(?m)^承接上一段结尾的画面、人物姿势与光线，继续：\n?""")
    private val OUR_FREEFORM_EN = Regex(
        """(?m)^Continue from the last frame of the previous segment[^\n]*\n?""",
        RegexOption.IGNORE_CASE
    )
    private val SUMMARY_PREFIX = Regex("""(?m)^\[video continuation\] """)
    private val SECTION_AFTER_DEFINITIONS = Regex(
        """\n(?:summary|retention_analysis|detailed_description|overall_soundscape|non_diegetic_music):"""
    )

    /** What one edit added: the picture it claimed, and whether it also flagged the summary. */
    data class Applied(
        val prompt: String,
        val declared: Int = NONE,
        val flagged: Boolean = false
    ) {
        val wrote: Boolean get() = declared != NONE || flagged
    }

    /** The declaration used inside `subject_definitions`; wording matches the prompts we run. */
    fun declaration(picture: Int): String =
        "<Picture $picture> is the final frame of the previous segment, " +
            "providing the starting composition, lighting, and pose."

    fun freeformLine(chinese: Boolean): String = if (chinese) {
        "承接上一段结尾的画面、人物姿势与光线，继续："
    } else {
        "Continue from the last frame of the previous segment, " +
            "keeping the same framing, pose and lighting."
    }

    /** First final-frame claim in the prompt, whoever wrote it. */
    fun declaredPicture(prompt: String): Int? =
        DECLARED.find(prompt)?.groupValues?.get(1)?.toIntOrNull()

    fun hasSummaryFlag(prompt: String): Boolean = prompt.contains(SUMMARY_FLAG)

    /**
     * Adds the continuation claim, or leaves the prompt alone when [enabled] is false or a claim
     * for this picture is already there. The shape is decided by the prompt, not by us.
     */
    fun apply(prompt: String, picture: Int, enabled: Boolean): Applied {
        if (!enabled) return Applied(prompt)
        if (declaredPicture(prompt) == picture) return Applied(prompt)

        if (!prompt.contains("subject_definitions:")) {
            val already = prompt.contains("承接上一段结尾的画面、人物姿势与光线，继续：") ||
                prompt.startsWith("Continue from the last frame", ignoreCase = true)
            if (already) return Applied(prompt)
            return Applied(freeformLine(looksChinese(prompt)) + "\n\n" + prompt, picture)
        }

        // Another picture already claimed the final frame: two claims cancel each other out, so a
        // stale one is left for the user to fix rather than silently added to.
        if (declaredPicture(prompt) != null) return Applied(prompt)

        var out = insertInDefinitions(prompt, declaration(picture))
        val flagged = !hasSummaryFlag(out)
        if (flagged) out = flagSummary(out)
        return Applied(out, picture, flagged)
    }

    /** Removes only the lines this object wrote, and nothing else. */
    fun revert(prompt: String, declared: Int, flagged: Boolean): String {
        if (declared == NONE && !flagged) return prompt
        var out = prompt
        if (declared != NONE) {
            out = OUR_DECLARATION.replace(out) { match ->
                if (match.groupValues[1].toIntOrNull() == declared) "" else match.value
            }
            out = OUR_FREEFORM_ZH.replace(out, "")
            out = OUR_FREEFORM_EN.replace(out, "")
        }
        if (flagged) out = SUMMARY_PREFIX.replaceFirst(out, "")
        return out.replace(Regex("\n{3,}"), "\n\n").trim('\n')
    }

    /** Rewrites our declaration's picture number after the reference count changed. */
    fun renumber(prompt: String, from: Int, to: Int): String {
        if (from == to) return prompt
        var done = false
        return OUR_DECLARATION.replace(prompt) { match ->
            val number = match.groupValues[1].toIntOrNull()
            if (!done && number == from) {
                done = true
                match.value.replace("<Picture $from>", "<Picture $to>")
            } else {
                match.value
            }
        }
    }

    private fun insertInDefinitions(body: String, line: String): String {
        val header = body.indexOf("subject_definitions:")
        if (header < 0) return body
        val afterHeader = body.indexOf('\n', header)
        if (afterHeader < 0) return body + "\n" + line
        val nextSection = SECTION_AFTER_DEFINITIONS.find(body, afterHeader)
        val end = nextSection?.range?.first ?: body.length
        val section = body.substring(afterHeader + 1, end).trimEnd('\n')
        val rest = if (end < body.length) body.substring(end) else ""
        return body.substring(0, afterHeader + 1) + section + "\n" + line + rest
    }

    private fun flagSummary(body: String): String {
        val header = body.indexOf("summary:")
        if (header < 0) return body
        val lineEnd = body.indexOf('\n', header)
        if (lineEnd < 0) return body
        val after = body.substring(lineEnd + 1)
        if (after.trimStart().startsWith(SUMMARY_FLAG)) return body
        return body.substring(0, lineEnd + 1) + SUMMARY_FLAG + " " + after
    }

    private fun looksChinese(text: String): Boolean {
        val han = text.count { it in '一'..'鿿' }
        return han > 0 && han * 20 > text.length
    }
}
