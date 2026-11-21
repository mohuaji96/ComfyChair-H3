package sh.hnet.comfychair.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Seed handling for the H3 templates.
 *
 * The bundled templates carry a literal seed (757358688076805 and 261662374822964), so the same
 * prompt produced the same clip every time. ComfyUI's own "control after generate" is a LiteGraph
 * frontend behaviour and never runs through the API, so the value has to be rolled here.
 *
 * The switch is global on purpose: it answers "I want a different one every run", not "this
 * template is random and that one is fixed".
 */
object H3Seed {

    /** The two names ComfyUI sampling nodes use for the seed input. */
    val INPUTS = setOf("seed", "noise_seed")

    private const val PREFS = "h3_seed"
    private const val KEY_AUTO = "auto_random"

    /** On by default — a fixed seed is the surprising case, not the expected one. */
    fun autoRandom(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(KEY_AUTO, true)

    fun setAutoRandom(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_AUTO, on).apply()
    }

    /**
     * ComfyUI's own KSampler takes a full 64-bit seed, but Easy-Use 的 easy seed 节点 caps it at
     * 2^50: "Value … bigger than max of 1125899906842624: seed", and the whole output is dropped.
     * Staying under the smallest cap in the graph is the only value every node accepts.
     */
    const val MAX = 1_125_899_906_842_624L

    fun next(): Long = Random.nextLong(0L, MAX + 1L)

    /**
     * Returns a replacement when [raw] cannot be sent, null when it is already fine.
     *
     * The working copy still holds rolls from the previous build, which used the full 64-bit
     * range, so a pinned seed can be out of bounds without the user touching anything.
     */
    fun fit(raw: String): String? {
        val value = raw.trim().toLongOrNull() ?: return next().toString()
        return if (value in 0L..MAX) null else next().toString()
    }

    /**
     * Replaces every seed literal in an API-format graph, leaving everything else untouched.
     *
     * Returns null when the graph has no seed to roll, so callers can skip rewriting the file
     * instead of saving an identical copy. Wires (JSONArray) are never replaced — a linked seed
     * comes from another node and is that node's business.
     */
    fun randomize(json: String, auto: Boolean = true): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        // LiteGraph keeps seeds inside widgets_values arrays, where the index depends on the
        // node's own widget order. Guessing at it would rewrite the wrong number, so decline.
        if (root.has("nodes")) return null
        var changed = false
        root.keys().asSequence().toList().forEach { id ->
            val inputs = root.optJSONObject(id)?.optJSONObject("inputs") ?: return@forEach
            INPUTS.forEach { key ->
                if (inputs.has(key) && inputs.opt(key) !is JSONArray) {
                    // Pinned mode still repairs an out-of-range leftover; it just keeps a seed
                    // that is already valid.
                    val replacement = if (auto) next().toString() else fit(inputs.opt(key).toString())
                    if (replacement != null) {
                        inputs.put(key, replacement.toLong())
                        changed = true
                    }
                }
            }
        }
        return if (changed) root.toString(2) else null
    }
}
