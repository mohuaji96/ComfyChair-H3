package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

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
        val label: String,
        val value: Any,
        val group: Group,
        val bypassed: Boolean,
        val editable: Boolean,
        val reason: String? = null
    ) {
        val key: String get() = "$nodeId:${this.field}"
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
                        field = field, label = humanLabel(type, title, field), value = value,
                        group = group, bypassed = bypassed,
                        editable = !linked && safelyIndexed,
                        reason = when { linked -> "由上游连线提供，请修改源节点";
                            !safelyIndexed -> "控件位置无法安全定位"; else -> null }
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

    private fun humanLabel(type: String, title: String, field: String): String = when (field.lowercase()) {
        "image" -> "图片"
        "video" -> "视频"
        "audio" -> "音频"
        "text", "prompt" -> "提示词"
        "unet_name", "ckpt_name", "model_name" -> "模型"
        "lora_name" -> "LoRA"
        "strength_model" -> "LoRA 强度"
        "clip_name" -> "文本编码器"
        "vae_name" -> "VAE"
        "aspect_ratio" -> "宽高比"
        "megapixels" -> "像素量 (MP)"
        "width" -> "宽度"
        "height" -> "高度"
        "steps" -> "采样步数"
        "scheduler" -> "调度器"
        "sampler_name" -> "采样器"
        "denoise" -> "去噪强度"
        "value" -> if (title.contains("duration", true)) "时长（秒）" else "值"
        else -> field.replace('_', ' ')
    } + " · $title"
}
