package sh.hnet.comfychair.workflow

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R

/** A conservative, workflow-independent discovery index for LiteGraph widget values.
 *
 * Discovery never mutates the graph. Only literal, unconnected top-level widgets with
 * a provable positional mapping are offered as editable parameters. Values inside a
 * nested subgraph and dynamically structured widgets remain in the professional editor.
 */
object QuickParameterCatalog {
    enum class Group { MEDIA, PROMPT, MODEL, LORA, RESOLUTION, TIMING, SAMPLING, OTHER }

    data class Entry(
        val nodeId: Int,
        val nodeType: String,
        val nodeTitle: String,
        val field: String,
        /** Field name resource; 0 when the field has no friendly name and [field] is shown as is. */
        @StringRes val labelRes: Int,
        val value: Any,
        val group: Group,
        val bypassed: Boolean,
        val editable: Boolean,
        /** Why the entry is not editable, as a resource; null when it is editable. */
        @StringRes val reasonRes: Int? = null
    ) {
        val key: String get() = "$nodeId:${this.field}"

        /** `name · node title`, the form the quick panel shows. */
        fun displayLabel(context: Context): String {
            val name = if (labelRes != 0) context.getString(labelRes) else field.replace('_', ' ')
            return "$name · $nodeTitle"
        }
    }

    fun discover(source: String): List<Entry> {
        val root = JSONObject(source)
        val nodes = root.optJSONArray("nodes") ?: return emptyList()
        return buildList {
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                val nodeId = node.optInt("id", -1)
                if (nodeId < 0) continue
                val type = node.optString("type")
                val title = node.optString("title").takeIf { it.isNotBlank() } ?: type
                val named = node.optJSONObject("widgets_values_named") ?: continue
                val positional = node.opt("widgets_values")
                val inputs = node.optJSONArray("inputs") ?: JSONArray()
                val indexed = (0 until inputs.length()).mapNotNull { inputs.optJSONObject(it) }
                    .filter { it.has("widget") && !it.isNull("widget") }
                    .map { it.optString("name") }
                val bypassed = node.optInt("mode", 0) != 0
                for (field in named.keys()) {
                    val value = named.opt(field) ?: continue
                    if (value == JSONObject.NULL || value is JSONObject || value is JSONArray) continue
                    if (value !is String && value !is Number && value !is Boolean) continue
                    val group = classify(type, title, field) ?: continue
                    val input = (0 until inputs.length()).mapNotNull { inputs.optJSONObject(it) }
                        .firstOrNull { it.optString("name") == field }
                    val linked = input != null && !input.isNull("link")
                    val safelyIndexed = when (positional) {
                        is JSONObject -> positional.has(field)
                        is JSONArray -> {
                            val index = indexed.indexOf(field).takeIf { it in 0 until positional.length() }
                                ?: if (positional.length() == 1 && named.length() == 1) 0 else -1
                            if (index < 0) false else {
                                val positionalValue = positional.opt(index)
                                when {
                                    value is Number && positionalValue is Number -> value.toDouble() == positionalValue.toDouble()
                                    else -> value == positionalValue
                                }
                            }
                        }
                        null, JSONObject.NULL -> true
                        else -> false
                    }
                    add(Entry(
                        nodeId = nodeId, nodeType = type, nodeTitle = title,
                        field = field, labelRes = humanLabel(type, title, field), value = value,
                        group = group, bypassed = bypassed,
                        editable = !linked && safelyIndexed,
                        reasonRes = when { linked -> R.string.qp_reason_linked;
                            !safelyIndexed -> R.string.qp_reason_unindexed; else -> null }
                    ))
                }
            }
        }.sortedWith(compareBy<Entry> { it.group.ordinal }.thenBy { it.nodeId }.thenBy { it.field })
    }

    private fun classify(type: String, title: String, field: String): Group? {
        val t = type.lowercase()
        val name = field.lowercase()
        val label = title.lowercase()
        return when {
            name in setOf("image", "video", "audio", "file", "video_path", "audio_path") ||
                (t.contains("load") && (name.contains("image") || name.contains("video") || name.contains("audio"))) -> Group.MEDIA
            name in setOf("text", "prompt", "positive", "negative", "value") &&
                (t.contains("string") || t.contains("textencode") || label.contains("prompt")) -> Group.PROMPT
            name.contains("lora") || t.contains("lora") && name.contains("strength") -> Group.LORA
            name in setOf("unet_name", "ckpt_name", "model_name", "clip_name", "vae_name", "weight_dtype") ||
                (t.contains("loader") && name.endsWith("_name")) -> Group.MODEL
            name in setOf("width", "height", "aspect_ratio", "megapixels", "multiple") -> Group.RESOLUTION
            name in setOf("fps", "frame_rate", "frame_count", "num_frames", "length", "duration", "duration_seconds") ||
                (label.contains("duration") && name == "value") -> Group.TIMING
            name in setOf("steps", "cfg", "denoise", "seed", "noise_seed", "sampler_name", "scheduler", "shift_video", "shift_audio") ||
                (t.contains("seed") && name == "value") -> Group.SAMPLING
            else -> null
        }
    }

    /** Field name resource; 0 means no friendly name (the raw field is shown). */
    @StringRes
    private fun humanLabel(type: String, title: String, field: String): Int = when (field.lowercase()) {
        "image" -> R.string.qp_image
        "video" -> R.string.qp_video
        "audio" -> R.string.qp_audio
        "text", "prompt" -> R.string.qp_prompt
        "unet_name", "ckpt_name", "model_name" -> R.string.qp_model
        "lora_name" -> R.string.qp_lora
        "strength_model" -> R.string.qp_lora_strength
        "clip_name" -> R.string.qp_clip
        "vae_name" -> R.string.qp_vae
        "aspect_ratio" -> R.string.qp_aspect_ratio
        "megapixels" -> R.string.qp_megapixels
        "width" -> R.string.qp_width
        "height" -> R.string.qp_height
        "steps" -> R.string.qp_steps
        "scheduler" -> R.string.qp_scheduler
        "sampler_name" -> R.string.qp_sampler
        "denoise" -> R.string.qp_denoise
        "value" -> if (title.contains("duration", true)) R.string.qp_duration else R.string.qp_value
        else -> 0
    }
}
