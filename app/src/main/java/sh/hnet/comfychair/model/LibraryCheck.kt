package sh.hnet.comfychair.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R

/**
 * 导入前的整份体检。
 *
 * 解析器对超限的东西是「静默截断、静默丢弃」的：格子超过上限就只读前几条，池子空了
 * 整个格子消失。用户导进来看到「成功」，实际少了一半词，还找不到是哪一半。
 * 这里在解析之后、落盘之前把这类损失点出来，宁可整包退回，也不做无声的删减。
 *
 * 同一把尺子同时给词库和剧情库用，两个入口各写一套判定必然走偏。
 */
object LibraryCheck {

    /** 与 TagLibrary 里的上限保持一致，那边改了这里要跟着改。 */
    private const val MAX_SLOTS = 256
    private const val MAX_TAGS_PER_SLOT = 4096

    /**
     * 引擎真正认识的模板占位符。来源是两档内置词库实际用到的全集，
     * 认不出的占位符不会被替换，会原样印在正文里，所以直接拒收。
     */
    private val KNOWN_PLACEHOLDERS = setOf(
        "cast", "chore", "detail", "escalate", "her", "her2", "male", "mark",
        "mood", "acts_dot", "outfit_mid", "place", "time", "wear", "wear2",
        "weather", "world", "female", "light", "pose",
    )

    /** 词库：重复编号、超限会被截断、空格子、同一格里的重复词。 */
    fun tagLibrary(context: Context, raw: String): List<String> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val categories = root.optJSONArray("categories") ?: return emptyList()

        val duplicateCategories = linkedSetOf<String>()
        val duplicateSlots = linkedSetOf<String>()
        val duplicateWords = linkedSetOf<String>()
        val seenCategories = mutableSetOf<String>()
        var truncated = 0
        var emptySlots = 0

        for (i in 0 until categories.length()) {
            val category = categories.optJSONObject(i) ?: continue
            val categoryId = category.optString("id")
            if (categoryId.isNotBlank() && !seenCategories.add(categoryId)) {
                duplicateCategories += categoryId
            }
            val slots = category.optJSONArray("slots") ?: continue
            if (slots.length() > MAX_SLOTS) truncated++
            val seenSlots = mutableSetOf<String>()
            for (j in 0 until slots.length()) {
                val slot = slots.optJSONObject(j) ?: continue
                val slotId = slot.optString("id")
                if (slotId.isNotBlank() && !seenSlots.add(slotId)) {
                    duplicateSlots += "$categoryId.$slotId"
                }
                val pool = slot.optJSONArray("pool") ?: JSONArray()
                if (pool.length() == 0) {
                    emptySlots++
                    continue
                }
                if (pool.length() > MAX_TAGS_PER_SLOT) truncated++
                val seenWords = mutableSetOf<String>()
                for (k in 0 until pool.length()) {
                    val word = pool.optString(k).trim()
                    if (word.isNotEmpty() && !seenWords.add(word)) duplicateWords += word
                }
            }
        }

        val problems = mutableListOf<String>()
        if (duplicateCategories.isNotEmpty()) {
            problems += context.getString(
                R.string.err_lib_dup_category,
                duplicateCategories.joinToString("、")
            )
        }
        if (duplicateSlots.isNotEmpty()) {
            problems += context.getString(
                R.string.err_lib_dup_slot,
                duplicateSlots.take(5).joinToString("、")
            )
        }
        if (duplicateWords.isNotEmpty()) {
            problems += context.getString(
                R.string.err_lib_dup_word,
                duplicateWords.take(5).joinToString("、")
            )
        }
        if (truncated > 0) {
            problems += context.getString(R.string.err_lib_truncated, truncated, MAX_TAGS_PER_SLOT)
        }
        if (emptySlots > 0) {
            problems += context.getString(R.string.err_lib_empty_slot, emptySlots)
        }
        return problems
    }

    /** 剧情库：认不出的占位符、声明了人数却没有模板、空模板。 */
    fun storyLibrary(context: Context, raw: String): List<String> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val templates = root.optJSONObject("templates") ?: return emptyList()

        val unknown = sortedSetOf<String>()
        val missing = linkedSetOf<String>()
        templates.keys().forEach { cast ->
            val lines = linesOf(templates.opt(cast))
            if (lines.isEmpty()) {
                missing += cast
                return@forEach
            }
            lines.forEach { line ->
                Regex("\\{([A-Za-z0-9_]+)}").findAll(line).forEach { match ->
                    val name = match.groupValues[1]
                    if (name !in KNOWN_PLACEHOLDERS) unknown += name
                }
            }
        }
        root.optJSONObject("casts")?.keys()?.forEach { cast ->
            if (linesOf(templates.opt(cast)).isEmpty()) missing += cast
        }

        val problems = mutableListOf<String>()
        if (unknown.isNotEmpty()) {
            problems += context.getString(
                R.string.err_lib_unknown_placeholder,
                unknown.joinToString("、")
            )
        }
        if (missing.isNotEmpty()) {
            problems += context.getString(
                R.string.err_lib_no_template,
                missing.joinToString("、")
            )
        }
        return problems
    }

    /** 模板既可能是字符串数组，也可能是按段分组的对象，两种都摊平成一行一条。 */
    private fun linesOf(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is JSONArray -> (0 until value.length())
            .map { value.optString(it) }
            .filter { it.isNotBlank() }
        is JSONObject -> value.keys().asSequence()
            .flatMap { key -> linesOf(value.opt(key)) }
            .toList()
        is String -> if (value.isBlank()) emptyList() else listOf(value)
        else -> emptyList()
    }
}
