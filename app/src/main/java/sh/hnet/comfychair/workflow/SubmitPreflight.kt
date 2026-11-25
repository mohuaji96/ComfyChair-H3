package sh.hnet.comfychair.workflow

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R

/**
 * Pre-submit normalisation for the exact failures observed on a real server:
 *
 * - `#66 UNETLoader.unet_name` was sent with `/` separators while the server's COMBO only
 *   accepts `\` (Windows folder separators). The value must be replaced by the server's own
 *   string, never by a global search/replace over the whole prompt.
 * - `#83 LoraLoaderModelOnly.lora_name` reached the server as the literal `{{lora_name}}`
 *   because the placeholder had no default. An unresolved placeholder must never be posted.
 */
object SubmitPreflight {

    /**
     * A refusal carries the resource id and its arguments rather than a finished sentence: the
     * preflight runs without a context, and the same issue has to read in whichever language the
     * interface is currently in.
     */
    data class Issue(
        val nodeId: String,
        val classType: String,
        val input: String,
        @StringRes val detailRes: Int,
        val args: List<Any> = emptyList()
    ) {
        fun format(context: Context): String {
            val detail = if (args.isEmpty()) context.getString(detailRes)
            else context.getString(detailRes, *args.toTypedArray())
            // A JSON-level refusal has no node to point at, so the locator is dropped.
            val where = if (nodeId.isBlank()) "" else "#$nodeId $classType.$input: "
            return where + detail
        }
    }

    data class Result(val prompt: String, val issues: List<Issue>, val rewrites: List<String>) {
        val ok: Boolean get() = issues.isEmpty()
    }

    /** Inputs whose COMBO is a live folder listing, not a fixed enum. */
    private val UPLOAD_INPUTS = mapOf(
        "LoadImage" to setOf("image"),
        "LoadImageMask" to setOf("image"),
        "LoadImageOutput" to setOf("image"),
        "VHS_LoadVideo" to setOf("video"),
        "VHS_LoadVideoPath" to setOf("video"),
        "VHS_LoadAudioUpload" to setOf("audio")
    )

    /** Compares names ignoring the folder separator only; case and unicode are preserved. */
    private fun key(name: String): String = name.replace('\\', '/').trim()

    /** The server string that [value] denotes, or null when it is not in [options]. */
    fun resolveCombo(value: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        if (value in options) return value
        val k = key(value)
        return options.firstOrNull { key(it) == k }
    }

    fun isPlaceholder(value: Any?): Boolean {
        if (value !is String) return false
        val t = value.trim()
        return t.length > 4 && t.startsWith("{{") && t.endsWith("}}")
    }

    /**
     * @param lookup returns the legal COMBO options for (classType, input) or null when the
     * server definition is unknown. Unknown definitions are left untouched rather than guessed.
     */
    fun prepare(promptJson: String, lookup: (classType: String, input: String) -> List<String>?): Result {
        val issues = mutableListOf<Issue>()
        val rewrites = mutableListOf<String>()
        val root = runCatching { JSONObject(promptJson) }.getOrElse {
            return Result(
                promptJson,
                listOf(Issue("", "", "", R.string.pf_bad_json, listOf(it.message.orEmpty()))),
                emptyList()
            )
        }
        for (nodeId in root.keys()) {
            val node = root.optJSONObject(nodeId) ?: continue
            val classType = node.optString("class_type")
            val inputs = node.optJSONObject("inputs") ?: continue
            for (input in inputs.keys()) {
                val current = inputs.opt(input)
                if (isPlaceholder(current)) {
                    issues += Issue(nodeId, classType, input, R.string.pf_unresolved, listOf(current))
                    continue
                }
                if (current !is String) continue
                // Uploaded media cannot be validated against /object_info: that snapshot lists the
                // input folder as it was when the definitions were fetched, so a file uploaded
                // afterwards is always "not in the options". Rejecting it blocked every real
                // submission. The server still rejects a name that does not exist.
                if (UPLOAD_INPUTS[classType]?.contains(input) == true) continue
                val options = lookup(classType, input) ?: continue
                if (options.isEmpty() || current in options) continue
                val resolved = resolveCombo(current, options)
                if (resolved == null) {
                    issues += Issue(
                        nodeId, classType, input,
                        R.string.pf_not_in_options, listOf(current, options.size)
                    )
                } else {
                    inputs.put(input, resolved)
                    rewrites += "#$nodeId $classType.$input: $current → $resolved"
                }
            }
        }
        return Result(root.toString(), issues, rewrites)
    }
}
