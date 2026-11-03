package sh.hnet.comfychair.workflow

import org.json.JSONObject

/**
 * Names the files of a 连拍 chain.
 *
 * The template writes every clip as `h3_00001.mp4`, so a chain of eight segments is eight
 * indistinguishable files. The save node carries a `filename_prefix` input; rewriting just that
 * value keeps the graph intact and makes the chain obvious on the phone and on the PC.
 */
object H3Naming {

    private const val INPUT = "filename_prefix"
    private const val MAX_PREFIX = 40

    /** Keeps a user-typed prefix to characters every filesystem accepts. */
    fun sanitize(raw: String): String = raw
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .trim('_', '.')
        .take(MAX_PREFIX)

    /** Identity of a chain: the user's own prefix, or the default one so recordings still group. */
    fun chainName(custom: String, auto: Boolean): String {
        val clean = sanitize(custom)
        return clean.ifBlank { if (auto) "h3_seq" else "h3" }
    }

    /** `p1_` guard: a numeric-only prefix would make the server name it `1_00001.mp4`. */
    fun prefixFor(name: String, position: Int, auto: Boolean): String {
        val base = sanitize(name)
        if (!auto) return base.ifBlank { "h3" }
        val numbered = String.format("%02d", position.coerceIn(1, 99))
        return if (base.isBlank()) "h3_seq_$numbered" else "${base}_$numbered"
    }

    /**
     * Rewrites every save node's prefix, returns the same JSON when there is nothing to change.
     * A workflow whose save node uses a different input name is left untouched rather than broken.
     */
    fun applyPrefix(promptJson: String, prefix: String): String {
        val clean = sanitize(prefix)
        if (clean.isBlank()) return promptJson
        return try {
            val root = JSONObject(promptJson)
            var changed = false
            for (nodeId in root.keys()) {
                val inputs = root.optJSONObject(nodeId)?.optJSONObject("inputs") ?: continue
                if (inputs.has(INPUT)) {
                    inputs.put(INPUT, clean)
                    changed = true
                }
            }
            if (changed) root.toString() else promptJson
        } catch (_: Exception) {
            promptJson
        }
    }
}
