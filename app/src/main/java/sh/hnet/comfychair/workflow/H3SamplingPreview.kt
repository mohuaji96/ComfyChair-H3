package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Attaches or detaches the step-by-step preview node on a prompt that is about to be submitted.
 *
 * Two of the built-in H3 graphs already carry the node, two do not, and the switch on the H3 page has
 * to work for all four without the user opening a node editor. So the decision is made here, on the
 * exported prompt, once per submission:
 *
 * - off  – an existing node is taken out and the guider is pointed back at the model it wrapped, so
 *          the run costs nothing extra;
 * - on   – an existing node is pinned to one still per step (the template wires its frame count from
 *          a math node and would otherwise encode a clip for every step), and a graph without one
 *          gets a node inserted between the guider and its model.
 *
 * Rewiring by node id is safe because the exported prompt is a flat map of node id to inputs.
 */
object H3SamplingPreview {

    const val NODE_TYPE = "ModelPreviewOverrideKJ"

    /** Guiders whose `model` input is the thing the preview node has to sit in front of. */
    private val GUIDERS = listOf("BasicGuider", "CFGGuider")

    private const val INJECTED_ID = "999001"
    /**
     * Frames per step. Above one the node encodes an animated WebP of that many frames, which is what
     * makes the preview look like the clip rather than a still. Sixteen at eight frames a second is
     * roughly two seconds of motion: enough to judge movement, and a small enough payload to decode
     * on a phone for every step of a run.
     */
    const val ANIMATED_FRAMES = 16
    const val PREVIEW_FPS = 8
    /** Bounds the page allows; the node itself accepts anything, the phone has to decode it. */
    const val MIN_FRAMES = 4
    const val MAX_FRAMES = 48
    const val MIN_FPS = 2
    const val MAX_FPS = 24
    /**
     * The node's own default is 1024/q80; the phone used to ask for 512/q78 to keep every step cheap,
     * which is what made the picture look soft next to the desktop client's preview. Stills are one
     * JPEG per step, so the larger side costs bytes but nothing in decode time.
     */
    private const val MAX_RESOLUTION = 1024
    private const val JPEG_QUALITY = 85

    data class Result(val json: String, val attached: Boolean)

    /**
     * [frames] and [fps] come from the page's own preview settings. They used to be constants, so the
     * "preview frame rate" field under advanced settings could be edited and changed nothing: this
     * object overwrote it on every submission.
     */
    fun apply(
        promptJson: String,
        enabled: Boolean,
        nodeAvailable: Boolean,
        tinyVae: String? = null,
        frames: Int = ANIMATED_FRAMES,
        fps: Int = PREVIEW_FPS
    ): Result {
        val root = runCatching { JSONObject(promptJson) }.getOrNull()
            ?: return Result(promptJson, false)
        val nFrames = frames.coerceIn(MIN_FRAMES, MAX_FRAMES)
        val nFps = fps.coerceIn(MIN_FPS, MAX_FPS)
        val ids = nodeIds(root)
        val existing = ids.filter { root.optJSONObject(it)?.optString("class_type") == NODE_TYPE }

        if (!enabled) {
            if (existing.isEmpty()) return Result(promptJson, false)
            existing.forEach { bypass(root, it) }
            return Result(root.toString(), false)
        }

        if (existing.isNotEmpty()) {
            existing.forEach { stillOnly(root.optJSONObject(it), tinyVae, nFrames, nFps) }
            val first = existing.first()
            val source = link(root.optJSONObject(first)?.optJSONObject("inputs")?.opt("model"))
            if (source != null) bindGuiders(root, first, source)
            return Result(root.toString(), true)
        }

        // A graph without the node can only gain one when the server actually knows the type;
        // otherwise the whole submission is rejected for an unknown node class.
        if (!nodeAvailable) return Result(promptJson, false)
        // One node per distinct model, and every guider on that model is rewired to read it. The
        // two-pass H3 templates give both passes their own guider over the same model, so attaching
        // to a single guider — whichever came first in the file — left the pass people actually
        // watch with no preview at all.
        val sources = guiderIds(root).mapNotNull { guiderModel(root, it) }.distinct()
        if (sources.isEmpty()) return Result(promptJson, false)
        for (source in sources) {
            val id = freeId(root)
            root.put(id, previewNode(source, tinyVae, nFrames, nFps))
            bindGuiders(root, id, source)
        }
        return Result(root.toString(), true)
    }

    private fun previewNode(source: Pair<String, Int>, tinyVae: String?, frames: Int, fps: Int): JSONObject = JSONObject()
        .put("class_type", NODE_TYPE)
        .put(
            "inputs",
            JSONObject()
                .put("model", JSONArray().put(source.first).put(source.second))
                .put("preview", "")
                .put("preview_frames", frames)
                .put("preview_fps", fps)
                .put("jpeg_quality", JPEG_QUALITY)
                .put("max_resolution", MAX_RESOLUTION)
                .put("tiny_vae", tinyVae ?: "none")
                .put("suppress_default_preview", true)
        )

    /** Points every guider that reads [source] at [previewId], so each pass reports its own steps. */
    private fun bindGuiders(root: JSONObject, previewId: String, source: Pair<String, Int>) {
        for (id in nodeIds(root)) {
            val node = root.optJSONObject(id) ?: continue
            if (node.optString("class_type") !in GUIDERS) continue
            val inputs = node.optJSONObject("inputs") ?: continue
            if (link(inputs.opt("model")) != source) continue
            inputs.put("model", JSONArray().put(previewId).put(0))
        }
    }

    private fun guiderIds(root: JSONObject): List<String> =
        nodeIds(root).filter { root.optJSONObject(it)?.optString("class_type") in GUIDERS }

    private fun guiderModel(root: JSONObject, guiderId: String): Pair<String, Int>? =
        link(root.optJSONObject(guiderId)?.optJSONObject("inputs")?.opt("model"))

    /**
     * Replaces the template's own frame count with ours. The templates wire it from the clip's true
     * length, which the desktop UI can afford and a phone cannot: that is a hundred-odd frames
     * encoded and shipped for every single step.
     */
    private fun stillOnly(node: JSONObject?, tinyVae: String?, frames: Int, fps: Int) {
        val inputs = node?.optJSONObject("inputs") ?: return
        inputs.put("preview", "")
        inputs.put("preview_frames", frames)
        if (tinyVae != null) inputs.put("tiny_vae", tinyVae)
        inputs.put("preview_fps", fps)
        inputs.put("jpeg_quality", JPEG_QUALITY)
        inputs.put("max_resolution", MAX_RESOLUTION)
        inputs.put("suppress_default_preview", true)
    }

    /**
     * Drops a preview node, pointing every reference to it back at the model it wrapped. A node whose
     * own model input is not a link is left alone rather than cut, which would leave its readers
     * pointing at nothing.
     */
    private fun bypass(root: JSONObject, id: String) {
        val node = root.optJSONObject(id) ?: return
        val model = link(node.optJSONObject("inputs")?.opt("model")) ?: return
        for (otherId in nodeIds(root)) {
            if (otherId == id) continue
            val inputs = root.optJSONObject(otherId)?.optJSONObject("inputs") ?: continue
            for (key in inputs.keys().asSequence().toList()) {
                val ref = link(inputs.opt(key)) ?: continue
                if (ref.first == id) inputs.put(key, JSONArray().put(model.first).put(model.second))
            }
        }
        root.remove(id)
    }

    private fun nodeIds(root: JSONObject): List<String> =
        root.keys().asSequence().filter { root.optJSONObject(it) != null }.toList()

    private fun link(value: Any?): Pair<String, Int>? {
        if (value !is JSONArray || value.length() < 2 || value.opt(0) !is String) return null
        return value.optString(0) to value.optInt(1, 0)
    }

    private fun freeId(root: JSONObject): String {
        if (root.optJSONObject(INJECTED_ID) == null) return INJECTED_ID
        var candidate = 999002
        while (root.optJSONObject(candidate.toString()) != null) candidate++
        return candidate.toString()
    }

    /**
     * The decoder the node needs for an animated preview.
     *
     * MiniMax H3 packs its latent as 3-D with 24 channels, which the node's other two decoders cannot
     * read: the LTX path is LTX-only and the latent-RGB path needs a 5-D latent. Without a tiny VAE
     * the node silently falls back to a single JPEG per step, so `preview_frames` never mattered and
     * the strip stayed still. H3's latent format names its decoder `taeh3`, so that is what we look
     * for in the node's own option list — no guessing at file names the server may not have.
     */
    fun pickTinyVae(schemaJson: String?): String? {
        if (schemaJson.isNullOrBlank()) return null
        val root = runCatching { JSONObject(schemaJson) }.getOrNull() ?: return null
        val node = root.optJSONObject(NODE_TYPE) ?: root
        val input = node.optJSONObject("input")
        val entry = (input?.optJSONObject("optional")?.opt("tiny_vae")
            ?: input?.optJSONObject("required")?.opt("tiny_vae")) as? JSONArray ?: return null
        val options = when (val first = entry.opt(0)) {
            is JSONArray -> (0 until first.length()).map { first.optString(it) }
            is String -> {
                val listed = entry.optJSONObject(1)?.optJSONArray("options")
                if (listed != null) (0 until listed.length()).map { listed.optString(it) } else listOf(first)
            }
            else -> emptyList()
        }.filter { it.isNotBlank() && it != "none" }
        fun base(name: String) = name.substringBeforeLast('.').lowercase()
        return options.firstOrNull { base(it) == "taeh3" }
            ?: options.firstOrNull { base(it).startsWith("tae") && base(it).contains("h3") }
    }
}
