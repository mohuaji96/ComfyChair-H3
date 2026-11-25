package sh.hnet.comfychair.workflow

import android.content.Context
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R

/**
 * A user-facing message: the string resource plus the arguments for its placeholders.
 *
 * Workflow code has no Context, so it carries the resource and the interface resolves it —
 * the same contract [H3Plan.Extra] uses for its labels.
 */
data class H3Message(@StringRes val resId: Int, val args: List<Any>) {
    constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())

    /** Resolve against [context]; [H3Res] arguments are resolved first. */
    fun format(context: Context): String {
        if (args.isEmpty()) return context.getString(resId)
        val resolved = args.map { if (it is H3Res) context.getString(it.resId) else it }
        return context.getString(resId, *resolved.toTypedArray())
    }
}

/** A string resource passed as a placeholder argument of another [H3Message]. */
@JvmInline
value class H3Res(@StringRes val resId: Int)

/** An edit that cannot be applied; the interface renders [notice]. */
class H3EditException(val notice: H3Message) : IllegalArgumentException()

/**
 * Mode-driven binding plan for one H3 workflow.
 *
 * Everything is discovered from the file itself: node ids are never hardcoded, and a parameter is
 * only exposed when a unique, editable source can be proven by following real links. The two
 * shipped templates are therefore just data, and a user workflow with different ids resolves the
 * same way.
 *
 * T2V / I2V / FL2V share one template: the mode decides which optional frame inputs are wired
 * into the *submission copy*. The stored workflow is never rewritten by a mode switch, so images
 * uploaded for another mode stay available.
 */
object H3Plan {

    /**
     * Workspace grouping. T2V / I2V / FL2V are one window because the model treats them as a single
     * node with two optional keyframes (`MiniMaxH3ImageToVideo`), so the concrete mode is a
     * submission-time decision, never a separate file or a separate tab.
     */
    enum class Area(val label: String, @StringRes val labelRes: Int) {
        FRAMES("文生 · 图生 · 首尾帧", R.string.h3_area_frames),
        R2V("参考生视频", R.string.h3_area_r2v)
    }

    enum class Mode(val label: String, @StringRes val labelRes: Int) {
        T2V("文生视频", R.string.h3_mode_t2v),
        I2V("图生视频", R.string.h3_mode_i2v),
        FL2V("首尾帧视频", R.string.h3_mode_fl2v),
        R2V("参考生视频", R.string.h3_mode_r2v);

        val area: Area get() = if (this == R2V) Area.R2V else Area.FRAMES

        companion object {
            /** The frames window only has to look at which keyframes are filled. */
            fun forFrames(first: String?, last: String?): Mode = when {
                !last.isNullOrBlank() -> FL2V
                !first.isNullOrBlank() -> I2V
                else -> T2V
            }
        }
    }

    data class TextTarget(val nodeId: String, val input: String, val value: String)
    data class ResolutionTarget(
        val nodeId: String,
        val aspectRatio: String,
        val megapixels: Double,
        val multiple: Int
    )
    /** [expression] is the ComfyMathExpression that turns seconds into frames, when present. */
    data class DurationTarget(val nodeId: String, val input: String, val seconds: Double, val expression: String?)
    data class StepsTarget(val nodeId: String, val input: String, val steps: Int)

    /**
     * The template's own standalone-audio socket: `ref_audios.ref_audio_<n>` fed by one loader.
     * [fileInput] is the loader's filename cell (`VHS_LoadAudioUpload.audio`) — the only thing an
     * upload replaces; the link into the core node is already there and never changes.
     */
    data class AudioRef(
        val coreInput: String,
        val nodeId: String,
        val nodeClass: String,
        val fileInput: String,
        val output: Int
    )

    /** A model file cell (`UNETLoader.unet_name`, `CLIPLoader.clip_name`, `VAELoader.vae_name`). */
    data class ModelRef(val nodeId: String, val input: String, val value: String) {
        val key: String get() = "$nodeId:$input"
        val fileName: String get() = value.substringAfterLast('\\').substringAfterLast('/')
    }

    /**
     * One independently adjustable LoRA.
     * [entryKey] is null for a plain loader and set (e.g. `lora_1`) for a multi-slot loader.
     */
    data class LoraSlot(
        val nodeId: String,
        val entryKey: String?,
        val file: String,
        val strength: Double,
        val enabled: Boolean,
        val clipStrength: Double? = null
    ) {
        val id: String get() = if (entryKey == null) nodeId else "$nodeId/$entryKey"
        val isPowerSlot: Boolean get() = entryKey != null
    }

    /**
     * A parameter the workflow really exposes but that is not part of the everyday page:
     * scheduler, sampler, denoise, seed, output fps and so on. Values are read from the file and
     * written back to the same cell, never merged into another sampling path.
     */
    data class Extra(
        val nodeId: String,
        val input: String,
        val label: String,
        val value: Any,
        val combo: Boolean,
        val hidden: Boolean = false,
        @StringRes val labelRes: Int = 0
    ) {
        val key: String get() = "$nodeId:$input"
    }

    data class Spec(
        val coreId: String,
        val coreClass: String,
        val modes: List<Mode>,
        val prompt: TextTarget?,
        val resolution: ResolutionTarget?,
        val duration: DurationTarget?,
        val steps: StepsTarget?,
        val unet: ModelRef?,
        val clip: ModelRef?,
        val videoVae: ModelRef?,
        val audioVae: ModelRef?,
        val loras: List<LoraSlot>,
        val refIndices: List<Int>,
        val maxRefSlots: Int,
        val audioSlots: List<AudioRef>,
        val fps: Int,
        val outputNodeId: String?,
        val format: String,
        val extras: List<Extra>,
        val warnings: List<H3Message>
    ) {
        val supportsFrames: Boolean get() = Mode.T2V in modes
        val supportsReferences: Boolean get() = Mode.R2V in modes
        /** True only when the file itself wires a standalone `ref_audios` socket. */
        val supportsAudio: Boolean get() = audioSlots.isNotEmpty()
        val frameCount: Int? get() = duration?.let { ComfyExpr.frames(it.expression, it.seconds) }
    }

    data class Request(
        val mode: Mode,
        val prompt: String? = null,
        /** `16:9` style label; matched against the server's own COMBO strings. */
        val ratio: String? = null,
        val megapixels: Double? = null,
        val customWidth: Int? = null,
        val customHeight: Int? = null,
        val seconds: Double? = null,
        val steps: Int? = null,
        /** Ordered reference images, index 0 is `ref_image_0`. */
        val references: List<String?> = emptyList(),
        /** Uploaded standalone reference audio, in slot order; each one not given is pruned. */
        val refAudios: List<String?> = emptyList(),
        val firstFrame: String? = null,
        val lastFrame: String? = null,
        val clearFirstFrame: Boolean = false,
        val clearLastFrame: Boolean = false,
        val loras: List<LoraEdit> = emptyList(),
        /** `nodeId:input` -> literal, for parameters shown under advanced settings. */
        val extras: Map<String, Any> = emptyMap(),
        /** Reference ceiling read live from the server; null falls back to the workflow's own slots. */
        val maxReferences: Int? = null,
        /** `ResolutionSelector.multiple`; null keeps the workflow's value. */
        val multiple: Int? = null,
        /** Extra LoRA chain appended after the workflow's own loaders (0–5 entries). */
        val extraLoras: List<LoraAdd> = emptyList()
    )

    /** One entry of the extra LoRA chain; same shape as ComfyChair's own `LoraSelection`. */
    data class LoraAdd(val name: String, val strength: Double) {
        companion object {
            const val MAX_ENTRIES = 5
            const val MIN_STRENGTH = 0.0
            const val MAX_STRENGTH = 2.0
        }
    }

    data class LoraEdit(val id: String, val file: String? = null, val strength: Double? = null,
                        val enabled: Boolean? = null)

    data class Result(val json: String, val notes: List<H3Message>, val errors: List<H3Message>) {
        val ok: Boolean get() = errors.isEmpty()
    }

    private val CORES = setOf("MiniMaxH3ImageToVideo", "MiniMaxH3ReferenceToVideo")
    private val REF_PREFIX = "ref_images.ref_image_"
    /** The core that owns the reference sockets; a file may keep them while running first/last-frame. */
    private const val REF_CORE = "MiniMaxH3ReferenceToVideo"
    private const val REF_RE = "^ref_images\\.ref_image_(\\d{1,2})$"
    private const val AUDIO_RE = "^ref_audios\\.ref_audio_(\\d{1,2})$"

    /** Loader classes that carry a filename cell, and the name of that cell. */
    private val AUDIO_FILE_INPUTS = mapOf(
        "VHS_LoadAudioUpload" to "audio",
        "LoadAudio" to "audio",
        "VHS_LoadVideo" to "video"
    )

    // ---------------------------------------------------------------- discovery

    fun nodes(document: JSONObject): JSONObject = document.optJSONObject("nodes") ?: document

    /** API JSON, or LiteGraph flattened through the existing converter. */
    private fun apiNodes(source: String): Pair<JSONObject, String>? {
        var doc = runCatching { JSONObject(source) }.getOrNull() ?: return null
        while (doc.optJSONObject("nodes") != null && doc.length() == 1) {
            val inner = doc.getJSONObject("nodes")
            if (inner.has("nodes") || inner.has("links") || inner.has("last_node_id")) {
                doc = inner
            } else {
                break
            }
        }
        if (doc.optJSONArray("nodes") != null || doc.has("links") || doc.has("last_node_id")) {
            val api = runCatching { H3LiteGraphAdapter.convert(doc) }.getOrNull() ?: return null
            return nodes(api) to "litegraph"
        }
        if (doc.optJSONObject("nodes") != null) return nodes(doc) to "api"
        // A bare API map also has no `nodes` array; accept it only when it holds an H3 core.
        return doc to "api"
    }

    fun resolve(source: String): Spec? {
        val rawDoc = runCatching { JSONObject(source) }.getOrNull()
        var rawCursor = rawDoc
        while (rawCursor != null && !rawCursor.has("modes") && rawCursor.optJSONObject("nodes") != null) {
            rawCursor = rawCursor.optJSONObject("nodes")
        }
        val declaredModes = rawCursor?.optJSONArray("modes")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                runCatching { Mode.valueOf(arr.getString(i)) }.getOrNull()
            }
        }?.takeIf { it.isNotEmpty() }
        val (n, format) = apiNodes(source) ?: return null
        val coreId = n.keys().asSequence().firstOrNull { n.optJSONObject(it)?.optString("class_type") in CORES }
            ?: return null
        val core = n.getJSONObject(coreId)
        val coreClass = core.optString("class_type")
        val ci = core.optJSONObject("inputs") ?: JSONObject()
        val warnings = mutableListOf<H3Message>()

        fun link(id: String, input: String): String? =
            (n.optJSONObject(id)?.optJSONObject("inputs")?.opt(input) as? JSONArray)
                ?.optString(0)?.takeIf { it.isNotEmpty() && n.has(it) }

        /** All distinct nodes of [target] classes reachable upstream from [id].[input]. */
        fun upTargets(id: String, input: String, targets: Set<String>): List<String> {
            val out = LinkedHashSet<String>()
            val seen = HashSet<String>()
            fun walk(nodeId: String, field: String) {
                val src = link(nodeId, field) ?: return
                if (!seen.add("$src|$field") && !seen.add(src)) return
                val node = n.optJSONObject(src) ?: return
                if (node.optString("class_type") in targets) { out.add(src); return }
                node.optJSONObject("inputs")?.keys()?.forEach { k -> walk(src, k) }
            }
            walk(id, input)
            return out.toList()
        }

        /** The first node reached from [id].[input], or null. */
        fun direct(id: String, input: String): String? = link(id, input)

        /** The literal string carried by a text-ish node, following one link deep. */
        fun textLiteral(id: String, input: String): TextTarget? {
            val node = n.optJSONObject(id) ?: return null
            val ins = node.optJSONObject("inputs") ?: return null
            val raw = ins.opt(input)
            if (raw is String) return TextTarget(id, input, raw)
            val nested = link(id, input) ?: return null
            val child = n.optJSONObject(nested) ?: return null
            val childInputs = child.optJSONObject("inputs") ?: return null
            for (key in listOf("text", "value", "string", "prompt")) {
                val v = childInputs.opt(key)
                if (v is String) return TextTarget(nested, key, v)
            }
            return null
        }

        // ---- prompt
        val promptTarget = run {
            val src = link(coreId, "prompt")
            if (src == null) {
                val v = ci.opt("prompt")
                if (v is String) TextTarget(coreId, "prompt", v) else null
            } else {
                val cands = mutableListOf<TextTarget>()
                fun walk(id: String, depth: Int) {
                    if (depth > 4) return
                    val node = n.optJSONObject(id) ?: return
                    val ins = node.optJSONObject("inputs") ?: return
                    var found = false
                    for (key in listOf("text", "value", "string", "prompt")) {
                        val v = ins.opt(key)
                        if (v is String) { cands.add(TextTarget(id, key, v)); found = true }
                    }
                    if (!found) ins.keys().forEach { k -> link(id, k)?.let { walk(it, depth + 1) } }
                }
                walk(src, 0)
                cands.distinctBy { it.nodeId to it.input }.singleOrNull() ?: run {
                    warnings.add(H3Message(R.string.plan_prompt_ambiguous, cands.size))
                    null
                }
            }
        }

        // ---- resolution
        val ratioNode = upTargets(coreId, "width", setOf("ResolutionSelector")).singleOrNull()
        val resolution = ratioNode?.let { id ->
            val ins = n.getJSONObject(id).getJSONObject("inputs")
            val mp = (ins.opt("megapixels") as? Number)?.toDouble()
            val ar = ins.opt("aspect_ratio") as? String
            if (mp == null || ar == null) {
                warnings.add(H3Message(R.string.plan_resolution_incomplete, id))
                null
            } else ResolutionTarget(id, ar, mp, (ins.opt("multiple") as? Number)?.toInt() ?: 32)
        }
        if (resolution == null) warnings.add(H3Message(R.string.plan_size_fixed))

        // ---- duration (seconds -> frames)
        val duration = run {
            val lengthSrc = link(coreId, "length")
            if (lengthSrc == null) {
                val v = ci.opt("length")
                if (v is Number) DurationTarget(coreId, "length", v.toDouble() / 24.0, null) else null
            } else {
                // Walk up to the numeric leaf, remembering the math node closest to `length`.
                var exprNode: String? = null
                var cursor = lengthSrc
                var depth = 0
                while (depth < 8) {
                    val node = n.optJSONObject(cursor) ?: break
                    val cls = node.optString("class_type")
                    if (cls == "ComfyMathExpression") {
                        if (exprNode == null) exprNode = node.optJSONObject("inputs")?.optString("expression")
                        val feed = (node.optJSONObject("inputs")?.opt("values.a") as? JSONArray)?.optString(0)
                        if (feed.isNullOrEmpty()) break
                        cursor = feed
                    } else break
                    depth++
                }
                val leaf = n.optJSONObject(cursor)
                val value = (leaf?.optJSONObject("inputs")?.opt("value") as? Number)?.toDouble()
                if (leaf == null || value == null || leaf.optString("class_type") !in
                    setOf("PrimitiveFloat", "PrimitiveInt", "PrimitiveNumber")) {
                    warnings.add(H3Message(R.string.plan_duration_not_numeric))
                    null
                } else if (exprNode != null) {
                    DurationTarget(cursor, "value", value, exprNode)
                } else {
                    // No math node: the literal is a frame count, not seconds.
                    DurationTarget(cursor, "value", value / 24.0, null)
                }
            }
        }

        // ---- steps: the BasicScheduler feeding the sampler that consumes this core
        val steps = run {
            // 在包含两阶段调度器（#24 一采 + #102 二采）的 Turbo 工作流中，主界面基础步数精准绑定 #24（一采）
            if (n.has("24") && n.optJSONObject("24")?.optString("class_type") == "BasicScheduler") {
                val v = (n.getJSONObject("24").optJSONObject("inputs")?.opt("steps") as? Number)?.toInt()
                if (v != null) return@run StepsTarget("24", "steps", v)
            }
            val samplers = n.keys().asSequence().filter { n.optJSONObject(it)?.optString("class_type") == "SamplerCustomAdvanced" }.toList()
                .filter { s ->
                    val guider = link(s, "guider") ?: return@filter false
                    upTargets(guider, "conditioning", CORES).contains(coreId) || link(guider, "conditioning") == coreId
                }
            val scheds = samplers.mapNotNull { s ->
                upTargets(s, "sigmas", setOf("BasicScheduler")).singleOrNull()
            }.distinct()
            val primarySchedId = scheds.singleOrNull() ?: scheds.firstOrNull { sid ->
                val title = n.optJSONObject(sid)?.optString("_meta_title")
                    ?.takeIf { it.isNotBlank() }
                    ?: n.optJSONObject(sid)?.optString("title").orEmpty()
                title.contains("First Pass", ignoreCase = true) || title.contains("一采")
            } ?: scheds.firstOrNull()
            val node = primarySchedId?.let { n.optJSONObject(it) }
            val v = (node?.optJSONObject("inputs")?.opt("steps") as? Number)?.toInt()
            if (primarySchedId != null && v != null) StepsTarget(primarySchedId, "steps", v) else {
                // Legacy templates expose steps through a switch; read the branch that is live.
                val route = H3StepsRouting.routes(source).firstOrNull { it.switchValue != null && it.activeNodeId != null }
                if (route?.activeNodeId != null && route.activeSteps != null)
                    StepsTarget(route.activeNodeId, "value", route.activeSteps.toInt())
                else {
                    warnings.add(H3Message(R.string.plan_steps_missing))
                    null
                }
            }
        }

        // ---- base model and LoRA chain
        val guiderModel = n.keys().asSequence().firstOrNull { n.optJSONObject(it)?.optString("class_type") == "SamplerCustomAdvanced" }
            ?.let { s -> link(s, "guider") }
            ?.let { g -> link(g, "model") }
        val chainTargets = setOf("UNETLoader", "LoraLoaderModelOnly", "LoraLoader", "Power Lora Loader (rgthree)")
        val chain = if (guiderModel != null) {
            val out = LinkedHashSet<String>()
            val seen = HashSet<String>()
            fun walk(id: String) {
                if (!seen.add(id)) return
                val node = n.optJSONObject(id) ?: return
                val cls = node.optString("class_type")
                if (cls in chainTargets) out.add(id)
                node.optJSONObject("inputs")?.keys()?.forEach { k -> link(id, k)?.let { walk(it) } }
            }
            walk(guiderModel)
            out.toList()
        } else emptyList()
        fun modelRef(id: String?, input: String): ModelRef? {
            val value = id?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.opt(input) } as? String
            return if (id != null && value != null) ModelRef(id, input, value) else null
        }

        val unet = modelRef(
            chain.firstOrNull { n.getJSONObject(it).optString("class_type") == "UNETLoader" }, "unet_name")
        val clip = modelRef(upTargets(coreId, "clip", setOf("CLIPLoader")).singleOrNull(), "clip_name")
        val videoVae = modelRef(upTargets(coreId, "vae", setOf("VAELoader")).singleOrNull(), "vae_name")
        // The audio VAE is either an input of the core or the VAE of the audio decoder.
        val audioVaeId = upTargets(coreId, "audio_vae", setOf("VAELoader")).singleOrNull()
            ?: n.keys().asSequence()
                .filter { n.optJSONObject(it)?.optString("class_type") == "VAEDecodeAudio" }
                .mapNotNull { upTargets(it, "vae", setOf("VAELoader")).singleOrNull() }
                .firstOrNull()
        val audioVae = modelRef(audioVaeId, "vae_name")
        val loras = mutableListOf<LoraSlot>()
        chain.forEach { id ->
            val node = n.getJSONObject(id)
            val ins = node.optJSONObject("inputs") ?: return@forEach
            when (node.optString("class_type")) {
                "LoraLoaderModelOnly", "LoraLoader" -> {
                    val file = ins.opt("lora_name") as? String ?: return@forEach
                    loras.add(LoraSlot(id, null, file,
                        (ins.opt("strength_model") as? Number)?.toDouble() ?: 1.0, true,
                        (ins.opt("strength_clip") as? Number)?.toDouble()))
                }
                "Power Lora Loader (rgthree)" -> {
                    ins.keys().asSequence().filter { Regex("^lora_\\d+$").matches(it) }.sortedBy { it.drop(5).toIntOrNull() ?: 0 }
                        .forEach { key ->
                            val entry = ins.optJSONObject(key) ?: return@forEach
                            val file = entry.optString("lora")
                            if (file.isEmpty()) return@forEach
                            loras.add(LoraSlot(id, key, file,
                                (entry.opt("strength") as? Number)?.toDouble() ?: 1.0,
                                entry.optBoolean("on", true)))
                        }
                }
            }
        }
         if (unet == null) warnings.add(H3Message(R.string.plan_unet_missing))

        // ---- images
        val hasAddGuide = n.keys().asSequence().any { n.optJSONObject(it)?.optString("class_type") == "MiniMaxH3AddGuide" }
        val isR2V = coreClass == "MiniMaxH3ReferenceToVideo" && !hasAddGuide
        val refIndices = ci.keys().asSequence().mapNotNull { key ->
            Regex(REF_RE).find(key)?.groupValues?.get(1)?.toIntOrNull()
        }.sorted().toList()
        // The standalone audio sockets are whatever the file already wires, in slot order; nothing
        // is added here, because a socket with no loader would need a node the template lacks.
        val audioSlots = ci.keys().asSequence()
            .mapNotNull { key ->
                Regex(AUDIO_RE).find(key)?.groupValues?.get(1)?.toIntOrNull()?.let { it to key }
            }
            .sortedBy { it.first }
            .mapNotNull { (_, key) ->
                val link = ci.optJSONArray(key) ?: return@mapNotNull null
                val nodeId = link.optString(0).takeIf { it.isNotEmpty() && n.has(it) } ?: return@mapNotNull null
                val cls = n.optJSONObject(nodeId)?.optString("class_type").orEmpty()
                val fileInput = AUDIO_FILE_INPUTS[cls] ?: return@mapNotNull null
                AudioRef(key, nodeId, cls, fileInput, link.optInt(1, 0))
            }
            .toList()
        val modes = declaredModes
            ?: if (isR2V) listOf(Mode.R2V)
            else listOf(Mode.T2V, Mode.I2V, Mode.FL2V)
        val fpsNode = n.keys().asSequence().firstOrNull { n.optJSONObject(it)?.optString("class_type") == "VHS_VideoCombine" }
        val fps = (fpsNode?.let { n.getJSONObject(it).optJSONObject("inputs")?.opt("frame_rate") as? Number }?.toInt())
            ?: 24
        val outputNodeId = n.keys().asSequence().firstOrNull {
            n.optJSONObject(it)?.optString("class_type") in setOf("VHS_VideoCombine", "SaveVideo", "SaveAnimatedWEBP", "SaveAnimatedPNG")
        }
        if (outputNodeId == null) warnings.add(H3Message(R.string.plan_output_missing))
        // The reference sockets are only unusable when the core cannot take them at all. A
        // first/last-frame template keeps them in its file and drives the same core, so warning
        // there was a false alarm on every Turbo template.
        if (refIndices.isNotEmpty() && coreClass != REF_CORE) warnings.add(H3Message(R.string.plan_refcore_mismatch))

        // ---- parameters that are real but not on the everyday page
        val taken = buildSet {
            steps?.let { add("${it.nodeId}:${it.input}") }
            resolution?.let { add("${it.nodeId}:aspect_ratio"); add("${it.nodeId}:megapixels") }
            duration?.let { add("${it.nodeId}:${it.input}") }
            listOfNotNull(unet, clip, videoVae, audioVae).forEach { add(it.key) }
            loras.forEach { add(it.id) }
            add("$coreId:prompt")
        }
        val extras = mutableListOf<Extra>()
        // Each entry carries both a plain label and a string resource: the plain one is only a
        // fallback for logs, the interface reads the resource so an English screen never shows the
        // Chinese node names this map used to hand straight to the UI.
        val extraMap = mapOf(
            "BasicScheduler" to listOf(
                Triple("scheduler", "采样调度 Scheduler", R.string.h3_field_scheduler),
                Triple("steps", "采样步数 Steps", R.string.h3_field_steps),
                Triple("denoise", "降噪强度 Denoise", R.string.h3_field_denoise)
            ),
            "KSamplerSelect" to listOf(
                Triple("sampler_name", "采样器 Sampler", R.string.h3_field_sampler)
            ),
            "SamplerCustomAdvanced" to listOf(),
            "ResolutionSelector" to listOf(
                Triple("multiple", "分辨率对齐倍数", R.string.h3_field_res_multiple)
            ),
            "VHS_VideoCombine" to listOf(
                Triple("frame_rate", "输出帧率", R.string.h3_field_frame_rate),
                Triple("crf", "视频质量 CRF", R.string.h3_field_crf),
                Triple("format", "视频格式", R.string.h3_field_format)
            ),
            "ModelPreviewOverrideKJ" to listOf(
                Triple("preview_fps", "预览帧率", R.string.h3_field_preview_fps)
            ),
            "easy seed" to listOf(Triple("seed", "随机种子 Seed", R.string.h3_field_seed)),
            "RandomNoise" to listOf(Triple("noise_seed", "随机种子 Seed", R.string.h3_field_seed)),
            "PrimitiveBoolean" to listOf(Triple("value", "开关状态", R.string.h3_field_switch)),
            "PrimitiveFloat" to listOf(Triple("value", "浮点数值", R.string.h3_field_float)),
            "INTConstant" to listOf(Triple("value", "整型常量", R.string.h3_field_int)),
            "RTXVideoSuperResolution" to listOf(
                Triple("resize_type.scale", "RTX超分倍率", R.string.h3_field_rtx_scale)
            )
        )
        nodeLoop@ for (id in n.keys()) {
            val node = n.optJSONObject(id) ?: continue@nodeLoop
            val cls = node.optString("class_type")
            val fields = extraMap[cls] ?: continue@nodeLoop
            val ins = node.optJSONObject("inputs") ?: continue@nodeLoop
            val combo = cls in setOf("BasicScheduler", "KSamplerSelect", "VHS_VideoCombine")
            val nodeTitle = node.optString("_meta_title")
                .takeIf { it.isNotBlank() }
                ?: node.optString("title").orEmpty()
            val isFirstPass = id == "24" || nodeTitle.contains("First", ignoreCase = true) || nodeTitle.contains("一采")
            val isSecondPass = id == "102" || nodeTitle.contains("Second", ignoreCase = true) || nodeTitle.contains("二采")
            val isGrain = id == "174" || nodeTitle.contains("grain", ignoreCase = true)

            for ((input, defaultLabel, defaultRes) in fields) {
                val key = "$id:$input"
                if (key in taken) continue
                val value = ins.opt(input)
                if (value != null && value !== JSONObject.NULL && value !is JSONArray && value !is JSONObject) {
                    var labelRes = defaultRes
                    val label = when {
                        cls == "BasicScheduler" && isFirstPass -> when (input) {
                            "scheduler" -> { labelRes = R.string.h3_field_first_scheduler; "一采采样调度 Scheduler" }
                            "denoise" -> { labelRes = R.string.h3_field_first_denoise; "一采降噪强度 Denoise" }
                            "steps" -> { labelRes = R.string.h3_field_first_steps; "一采采样步数 Steps" }
                            else -> defaultLabel
                        }
                        cls == "BasicScheduler" && isSecondPass -> when (input) {
                            "scheduler" -> { labelRes = R.string.h3_field_second_scheduler; "二采采样调度 Scheduler" }
                            "denoise" -> { labelRes = R.string.h3_field_second_denoise; "二采降噪强度 Denoise" }
                            "steps" -> { labelRes = R.string.h3_field_second_steps; "二采采样步数 Steps" }
                            else -> defaultLabel
                        }
                        isGrain -> { labelRes = R.string.h3_field_grain; "胶片颗粒强度 (0=关闭)" }
                        id == "23" -> { labelRes = R.string.h3_field_second_mp; "二采目标像素" }
                        id == "94" -> { labelRes = R.string.h3_field_second_skip; "二采跳过开关" }
                        id == "173" -> { labelRes = R.string.h3_field_frame_gen; "插帧倍数" }
                        id == "39" -> { labelRes = R.string.h3_field_rtx_video_scale; "RTX 视频超分倍率" }
                        else -> defaultLabel
                    }
                    val hidden = when {
                        // 胶片颗粒强度开放给用户手动在高级设置调整
                        isGrain -> false
                        // 步数由基础参数和二采卡片接管，不在高级设置列表重复出现
                        cls == "BasicScheduler" && input == "steps" -> true
                        // 专属于二采卡片的内部控制节点，不在高级设置列表展示
                        cls in setOf("PrimitiveBoolean", "INTConstant", "RTXVideoSuperResolution") -> true
                        cls == "PrimitiveFloat" && (id == "23" || isSecondPass) -> true
                        else -> false
                    }
                    extras.add(Extra(id, input, label, value, combo, hidden, labelRes))
                }
            }
        }

        return Spec(
            coreId = coreId, coreClass = coreClass, modes = modes,
            prompt = promptTarget, resolution = resolution, duration = duration, steps = steps,
            unet = unet, clip = clip, videoVae = videoVae, audioVae = audioVae,
            loras = loras, refIndices = refIndices,
            maxRefSlots = maxRefSlots(), audioSlots = audioSlots, fps = fps, outputNodeId = outputNodeId,
            format = format, extras = extras.distinctBy { it.key }, warnings = warnings.distinct(),
        )
    }

    /**
     * Reference slots the server accepts for `ref_images`.
     * Read from the COMBO_AUTOGROW declaration (min/max) rather than assumed; 9 is the observed
     * ceiling of the installed MiniMax node, and a workflow may define fewer.
     */
    fun maxRefSlots(): Int = AutogrowLimits.max("MiniMaxH3ReferenceToVideo", "ref_images") ?: 0

    /** Where the next reference image goes, or a request for the server's declaration. */
    sealed class NextRef {
        data class Pick(val index: Int) : NextRef()
        data object AskServer : NextRef()
    }

    /**
     * The slots the workflow itself wires are always legal, so they are never gated on a server
     * probe — a cached node definition that never populated [AutogrowLimits] must not be able to
     * lock the page. Only going past those slots needs the declaration, and "not known yet" means
     * "ask", never "refuse".
     */
    fun nextRefSlot(usedTiles: Int, templateSlots: Int, knownCeiling: Int): NextRef = when {
        usedTiles < templateSlots.coerceAtLeast(1) -> NextRef.Pick(usedTiles)
        knownCeiling > usedTiles -> NextRef.Pick(usedTiles)
        else -> NextRef.AskServer
    }

    /**
     * Slots a submission may use: the server's declared ceiling, or — before `/object_info` has
     * been read — the interfaces the workflow itself already defines. Never more than the server
     * allows, never fewer than the file needs.
     */
    private fun ceiling(spec: Spec): Int {
        // 节点声明 max 9，可工作流只连了 6 个 ref_image 槽（turbo 版就是从 1 到 6）。
        // 只认声明的 9 会让第七张图回落到槽 6，把第六张覆盖掉。
        val defined = spec.refIndices.size.coerceAtLeast(1)
        return if (spec.maxRefSlots > 0) minOf(spec.maxRefSlots, defined) else defined
    }

    // ---------------------------------------------------------------- submission copy

    /**
     * Build the JSON that will be posted for exactly this mode. The input document is never
     * mutated: it is serialised back out, so the stored workflow keeps every branch and image.
     */
    fun build(source: String, spec: Spec, request: Request, aspectOptions: List<String> = emptyList()): Result {
        val (n, _) = apiNodes(source)
            ?: return Result(source, emptyList(), listOf(H3Message(R.string.plan_err_invalid_json)))
        val core = n.optJSONObject(spec.coreId) ?: return Result(source, emptyList(), listOf(H3Message(R.string.plan_err_no_core)))
        val ci = core.getJSONObject("inputs")
        val notes = mutableListOf<H3Message>()
        val errors = mutableListOf<H3Message>()

        // ---- mode decides the optional frame inputs
        when (request.mode) {
            Mode.T2V -> {
                ci.remove("first_frame"); ci.remove("last_frame")
                notes.add(H3Message(R.string.plan_note_t2v))
            }
            Mode.I2V -> {
                ci.remove("last_frame")
                if (request.firstFrame == null && ci.opt("first_frame") !is JSONArray)
                    errors.add(H3Message(R.string.plan_err_i2v_first))
                notes.add(H3Message(R.string.plan_note_i2v))
            }
            Mode.FL2V -> {
                // Both keyframes are optional inputs of MiniMaxH3ImageToVideo, so "end on this
                // picture" is a real request: only the last frame is submitted. A frame the UI
                // cleared must also be cut from the copy, or the template's sample image rides
                // along and pins the opening shot.
                if (request.clearFirstFrame) ci.remove("first_frame")
                if (request.clearLastFrame) ci.remove("last_frame")
                val hasFirst = request.firstFrame != null || ci.opt("first_frame") is JSONArray
                val hasLast = request.lastFrame != null || ci.opt("last_frame") is JSONArray
                if (!hasFirst && !hasLast) errors.add(H3Message(R.string.plan_err_fl_frames))
                notes.add(
                    when {
                        hasFirst && hasLast -> H3Message(R.string.plan_note_fl_both)
                        hasLast -> H3Message(R.string.plan_note_fl_last)
                        else -> H3Message(R.string.plan_note_fl_first)
                    }
                )
            }
            Mode.R2V -> {
                if (request.references.none { it != null }) errors.add(H3Message(R.string.plan_err_r2v_ref))
                notes.add(H3Message(R.string.plan_note_r2v, request.references.count { it != null }))
            }
        }
        if (spec.modes.isNotEmpty() && request.mode !in spec.modes)
            errors.add(H3Message(R.string.plan_err_mode_unsupported, H3Res(request.mode.labelRes)))

        // ---- reference video is not exposed yet, so it is never submitted.
        // A template that still wires a clip would post whatever filename its author left behind,
        // and the server rejects an unknown name outright — a dormant node must not break a run it
        // takes no part in.
        ci.keys().asSequence().toList().forEach { key ->
            if (key.startsWith("ref_videos.") || key.startsWith("ref_video_audios.")) ci.remove(key)
        }

        // ---- images
        when (request.mode) {
            Mode.I2V, Mode.FL2V -> {
                // 首尾帧输入只有 MiniMaxH3ImageToVideo 收。参考生视频核心（ReferenceToVideo）
                // 的 execute 签名里没有 first_frame，硬挂上去服务端直接
                // TypeError: unexpected keyword argument 'first_frame'。
                // 这类 Turbo 工作流是靠 MiniMaxH3AddGuide 串联引导图的，图交给 wireTurboGuides 写。
                val coreType = core.optString("class_type")
                val hasGuides = n.keys().asSequence().any {
                    n.optJSONObject(it)?.optString("class_type") == "MiniMaxH3AddGuide"
                }
                if (coreType == "MiniMaxH3ImageToVideo") {
                    val first = request.firstFrame
                    if (first != null) attach(n, spec.coreId, "first_frame", first)
                    val last = request.lastFrame
                    if (last != null) attach(n, spec.coreId, "last_frame", last)
                } else if (hasGuides) {
                    notes.add(H3Message(R.string.plan_note_guides))
                } else {
                    errors.add(H3Message(R.string.plan_err_core_no_frames))
                }
            }
            Mode.R2V -> {
                val wanted = request.references.filterNotNull()
                val slots = spec.refIndices.ifEmpty { listOf(0, 1, 2) }
                val limit = request.maxReferences?.takeIf { it > 0 } ?: ceiling(spec)
                val activeSlots = mutableSetOf<Int>()
                wanted.forEachIndexed { i, file ->
                    // 槽位用完就停，不能再回落到 i：那会撞上已经用掉的槽，后一张图把前一张覆盖掉。
                    val slot = slots.getOrNull(i)
                    when {
                        i >= limit -> errors.add(H3Message(R.string.plan_err_ref_limit, limit))
                        slot == null -> errors.add(H3Message(R.string.plan_err_ref_slots, slots.size, i + 1))
                        else -> {
                            activeSlots.add(slot)
                            attach(n, spec.coreId, "$REF_PREFIX$slot", file)
                        }
                    }
                }
                // Interfaces beyond the uploaded count are dropped only from this copy, so a
                // shorter set never sends a stale third image.
                val allRefKeys = ci.keys().asSequence().filter { it.startsWith(REF_PREFIX) }.toList()
                allRefKeys.forEach { key ->
                    val slotNum = Regex(REF_RE).find(key)?.groupValues?.get(1)?.toIntOrNull()
                    if (slotNum == null || slotNum !in activeSlots) {
                        ci.remove(key)
                    }
                }
            }
            else -> Unit
        }

        // Turbo 引导节点连线与旁路自适应（支持纯文生T2V / 单首帧I2V / 首尾帧FL2V）
        wireTurboGuides(n, spec.coreId, request.firstFrame, request.lastFrame, notes)

        // ---- standalone reference audio: the file's own sockets, only what was picked.
        // Every socket that gets nothing is cut, so the author's sample never rides along and the
        // loader goes unreachable (and is pruned below) instead of posting a file that is gone.
        if (spec.audioSlots.isNotEmpty()) {
            val picked = request.refAudios.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
            spec.audioSlots.forEachIndexed { i, slot ->
                val file = picked.getOrNull(i)
                val ins = n.optJSONObject(slot.nodeId)?.optJSONObject("inputs")
                when {
                    file == null -> ci.remove(slot.coreInput)
                    ins == null -> errors.add(H3Message(R.string.plan_err_audio_node_missing, slot.nodeId))
                    else -> {
                        ins.put(slot.fileInput, file)
                        ci.put(slot.coreInput, JSONArray().put(slot.nodeId).put(slot.output))
                        notes.add(H3Message(R.string.plan_note_audio, i + 1, file))
                    }
                }
            }
        }

        // ---- resolution
        val target = spec.resolution
        if (target != null) {
            val ins = n.optJSONObject(target.nodeId)?.optJSONObject("inputs")
            if (ins == null) errors.add(H3Message(R.string.plan_err_res_node_missing))
            else {
                if (request.customWidth != null || request.customHeight != null) {
                    val w = request.customWidth ?: return Result(source, notes,
                        errors + H3Message(R.string.plan_err_custom_wh))
                    val h = request.customHeight ?: return Result(source, notes,
                        errors + H3Message(R.string.plan_err_custom_wh))
                    val sw = H3Resolution.snap(w.toDouble(), 32, 32, 16384)
                    val sh = H3Resolution.snap(h.toDouble(), 32, 32, 16384)
                    ci.put("width", sw); ci.put("height", sh)
                    notes.add(H3Message(R.string.plan_note_custom_wh, sw, sh))
                    if (sw != w || sh != h) notes.add(H3Message(R.string.plan_note_aligned32))
                } else {
                    request.ratio?.let { label ->
                        val option = matchRatio(label, aspectOptions.ifEmpty { defaultAspectOptions })
                        if (option == null) errors.add(H3Message(R.string.plan_err_ratio, label))
                        else { ins.put("aspect_ratio", option); notes.add(H3Message(R.string.plan_note_ratio, option)) }
                    }
                    request.megapixels?.let {
                        val clamped = it.coerceIn(0.1, 16.0)
                        ins.put("megapixels", clamped)
                        notes.add(H3Message(R.string.plan_note_megapixels, clamped))
                    }
                    if (ins.has("multiple")) request.multiple?.let {
                        val m = it.coerceIn(8, 128)
                        ins.put("multiple", m)
                        notes.add(H3Message(R.string.plan_note_multiple, m))
                    }
                }
            }
        } else if (request.customWidth != null && request.customHeight != null) {
            val sw = H3Resolution.snap(request.customWidth.toDouble(), 32, 32, 16384)
            val sh = H3Resolution.snap(request.customHeight.toDouble(), 32, 32, 16384)
            ci.put("width", sw); ci.put("height", sh)
            notes.add(H3Message(R.string.plan_note_wh, sw, sh))
        }

        // ---- duration (seconds into the workflow's own frames expression)
        val duration = spec.duration
        if (duration != null && request.seconds != null) {
            val node = n.optJSONObject(duration.nodeId)
            val ins = node?.optJSONObject("inputs")
            if (ins == null) errors.add(H3Message(R.string.plan_err_duration_node_missing))
            else {
                if (duration.expression == null) {
                    // No math node: the field is a frame count.
                    val frames = H3Resolution.snap(request.seconds * spec.fps, 17, 5, 3600)
                    ins.put(duration.input, frames)
                    notes.add(H3Message(R.string.plan_note_duration_frames, request.seconds, frames))
                } else {
                    ins.put(duration.input, request.seconds)
                    val frames = ComfyExpr.frames(duration.expression, request.seconds)
                    notes.add(H3Message(R.string.plan_note_duration_expression, request.seconds,
                        frames ?: "?"))
                }
            }
        }

        // ---- steps
        if (request.steps != null && spec.steps != null) {
            val ins = n.optJSONObject(spec.steps.nodeId)?.optJSONObject("inputs")
            if (ins == null) errors.add(H3Message(R.string.plan_err_steps_node_missing))
            else { ins.put(spec.steps.input, request.steps.coerceIn(1, 10000)); notes.add(H3Message(R.string.plan_note_steps, request.steps)) }
        }

        // ---- prompt
        if (request.prompt != null && spec.prompt != null)
            n.optJSONObject(spec.prompt.nodeId)?.optJSONObject("inputs")?.put(spec.prompt.input, request.prompt)

        // ---- LoRA edits
        request.loras.forEach { edit ->
            val node = n.optJSONObject(edit.id.substringBefore('/')) ?: return@forEach
            val ins = node.optJSONObject("inputs") ?: return@forEach
            if (edit.id.contains('/')) {
                val key = edit.id.substringAfter('/')
                val entry = ins.optJSONObject(key) ?: return@forEach
                edit.file?.let { entry.put("lora", it) }
                edit.strength?.let { entry.put("strength", it) }
                edit.enabled?.let { entry.put("on", it) }
            } else {
                edit.file?.let { ins.put("lora_name", it); notes.add(H3Message(R.string.plan_note_lora, it)) }
                edit.strength?.let {
                    if (ins.has("strength_model")) ins.put("strength_model", it)
                }
                edit.enabled?.let { on ->
                    if (ins.has("strength_model")) ins.put("strength_model", if (on) (edit.strength ?: 1.0) else 0.0)
                    // LoraLoader patches the text encoder as well; leaving that half alone would
                    // keep part of the LoRA active while the switch reads off.
                    if (!on && ins.has("strength_clip")) ins.put("strength_clip", 0.0)
                }
            }
        }

        // ---- extra LoRA chain, appended after the workflow's own loaders
        if (request.extraLoras.isNotEmpty()) {
            val added = injectExtraLoras(n, spec, request.extraLoras)
            if (added == null) errors.add(H3Message(R.string.plan_err_lora_tail))
            else notes.add(H3Message(R.string.plan_note_lora_added, added))
        }

        // ---- advanced parameters, written to the cell they were read from
        request.extras.forEach { (key, value) ->
            val id = key.substringBefore(':')
            val input = key.substringAfter(':', "")
            val ins = n.optJSONObject(id)?.optJSONObject("inputs")
            if (ins == null || input.isEmpty()) { errors.add(H3Message(R.string.plan_err_extra_missing, key)); return@forEach }
            if (ins.opt(input) is JSONArray) errors.add(H3Message(R.string.plan_err_extra_linked, key))
            else ins.put(input, value)
        }

        if (errors.isNotEmpty()) return Result(source, notes, errors)

        // ---- drop what nothing consumes
        // A keyframe `LoadImage` left over from an unused frame keeps whatever filename the
        // template author had. ComfyUI only validates and executes nodes an output can reach, so
        // it is harmless today, but it becomes a hard `/prompt` failure the moment a path re-wires
        // it. Pruning is cheaper than remembering that trap.
        spec.outputNodeId?.let { pruneUnreachable(n, it) }

        // `/prompt` takes the bare node map; the display wrapper (`nodes`, name) is dropped here.
        return Result(n.toString(2), notes, emptyList())
    }

    /** Nodes that only feed media in; an unused one is a stale filename, never a side effect. */
    private val IMAGE_SOURCES = setOf(
        "LoadImage", "LoadImageMask", "LoadImageOutput", "VHS_LoadVideo", "VHS_LoadVideoPath",
        "VHS_LoadAudioUpload", "LoadAudio"
    )

    /**
     * Drop image sources the generation output cannot reach.
     *
     * ComfyUI validates and executes only what an output can reach, so an unused keyframe loader
     * is inert — but it still carries whatever filename the template author had, and re-wiring it
     * later turns that into a `/prompt` failure. Pruning is narrower than dropping every orphan:
     * a save/preview branch the user added is left alone, because removing it would silently
     * change what their workflow produces.
     */
    private fun pruneUnreachable(n: JSONObject, outputId: String) {
        if (!n.has(outputId)) return
        val reachable = mutableSetOf(outputId)
        val queue = ArrayDeque<String>().apply { add(outputId) }
        while (queue.isNotEmpty()) {
            val inputs = n.optJSONObject(queue.removeFirst())?.optJSONObject("inputs") ?: continue
            inputs.keys().asSequence().forEach { key ->
                val link = inputs.opt(key) as? JSONArray ?: return@forEach
                val next = link.optString(0, "")
                if (next.isNotEmpty() && reachable.add(next) && n.has(next)) queue.add(next)
            }
        }
        // A broken output id would otherwise be treated as "nothing is reachable".
        if (reachable.size < 2) return
        n.keys().asSequence().toList().forEach { id ->
            if (id in reachable) return@forEach
            val cls = n.optJSONObject(id)?.optString("class_type").orEmpty()
            if (cls in IMAGE_SOURCES) n.remove(id)
        }
    }

    /**
     * Append [chain] after the workflow's own model loaders, the same way ComfyChair's
     * `LoraInjectionUtils` does: build `LoraLoaderModelOnly` nodes in order, then repoint every
     * consumer of the old tail at the last inserted node. The file itself is never rewritten.
     *
     * @return number of inserted nodes, or null when the model chain tail cannot be located.
     */
    private fun injectExtraLoras(n: JSONObject, spec: Spec, chain: List<LoraAdd>): Int? {
        val own = (listOfNotNull(spec.unet?.nodeId) + spec.loras.map { it.id.substringBefore('/') }).distinct()
        if (own.isEmpty()) return null
        // Tail = a loader nothing else in the chain consumes.
        val fedBy = own.associateWith { id ->
            own.any { other ->
                other != id && n.optJSONObject(other)?.optJSONObject("inputs")?.let { ins ->
                    ins.keys().asSequence().any { k -> (ins.opt(k) as? JSONArray)?.optString(0) == id }
                } == true
            }
        }
        // Tail = the loader that no other loader in the chain consumes.
        val tail = own.firstOrNull { fedBy[it] == false } ?: return null

        val consumers = mutableListOf<Triple<String, String, Int>>()
        n.keys().asSequence().forEach { id ->
            val ins = n.optJSONObject(id)?.optJSONObject("inputs") ?: return@forEach
            ins.keys().asSequence().forEach { k ->
                val link = ins.opt(k) as? JSONArray ?: return@forEach
                if (link.optString(0) == tail) consumers.add(Triple(id, k, link.optInt(1, 0)))
            }
        }
        if (consumers.isEmpty()) return null

        var next = (n.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1
        var current = tail
        chain.take(LoraAdd.MAX_ENTRIES).forEach { entry ->
            var id = next.toString()
            while (n.has(id)) { next++; id = next.toString() }
            n.put(id, JSONObject()
                .put("class_type", "LoraLoaderModelOnly")
                .put("inputs", JSONObject()
                    .put("lora_name", entry.name)
                    .put("strength_model", entry.strength.coerceIn(LoraAdd.MIN_STRENGTH, LoraAdd.MAX_STRENGTH))
                    .put("model", JSONArray().put(current).put(0))))
            current = id
            next++
        }
        consumers.forEach { (id, key, slot) ->
            n.optJSONObject(id)?.optJSONObject("inputs")?.put(key, JSONArray().put(current).put(slot))
        }
        return chain.size.coerceAtMost(LoraAdd.MAX_ENTRIES)
    }

    /** Ratio label (`16:9`) -> the server's own COMBO string (`16:9 (Widescreen)`). */
    fun matchRatio(label: String, options: List<String>): String? {
        val clean = label.substringBefore('(').trim()
        return options.firstOrNull { it.substringBefore('(').trim() == clean }
            ?: options.firstOrNull { it.startsWith(clean) }
    }

    private val defaultAspectOptions = listOf(
        "1:1 (Square)", "2:3 (Portrait Photo)", "3:2 (Photo)", "3:4 (Portrait Standard)",
        "4:3 (Standard)", "9:16 (Portrait Widescreen)", "16:9 (Widescreen)", "21:9 (Ultrawide)"
    )

    /**
     * Point [input] at [filename]. A dedicated LoadImage is added when the current source is shared
     * or is not a LoadImage, so no other interface changes its filename.
     */
    private fun attach(n: JSONObject, coreId: String, input: String, filename: String) {
        val core = n.optJSONObject(coreId)?.optJSONObject("inputs") ?: return
        val current = (core.opt(input) as? JSONArray)?.optString(0)
        val src = current?.let { n.optJSONObject(it) }
        if (src != null && src.optString("class_type") == "LoadImage") {
            val consumers = n.keys().asSequence().sumOf { id ->
                val ins = n.optJSONObject(id)?.optJSONObject("inputs") ?: return@sumOf 0
                ins.keys().asSequence().count { k -> (ins.opt(k) as? JSONArray)?.optString(0) == current }
            }
            if (consumers <= 1) { src.getJSONObject("inputs").put("image", filename); return }
        }
        var id = ((n.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        while (n.has(id)) id += "_h3"
        n.put(id, JSONObject().put("class_type", "LoadImage").put("inputs", JSONObject().put("image", filename)))
        core.put(input, JSONArray().put(id).put(0))
    }

    /**
     * For H3 Turbo Guide workflows (where guides are chained via MiniMaxH3AddGuide rather than
     * first_frame/last_frame sockets on the core node):
     * - T2V (neither frame): bypass both guides so guiders connect directly to core ["151:11", 0].
     * - I2V (first only): set node 9's image, bypass tail guide so guiders connect to 181/182.
     * - FL2V (both): set node 9 and node 190, keep full chain.
     * - Tail only: set node 190, bypass head guide so 192/193 connect to ["151:11", 0].
     */
    private fun wireTurboGuides(n: JSONObject, coreId: String, first: String?, last: String?, notes: MutableList<H3Message>) {
        val guideIds = n.keys().asSequence().filter { n.optJSONObject(it)?.optString("class_type") == "MiniMaxH3AddGuide" }.toList()
        if (guideIds.isEmpty()) return

        val core = n.optJSONObject(coreId)?.optJSONObject("inputs")

        /** ref_images 那条连线指向的 LoadImage。不写死节点号：换一版模板就失效，且是静默失效。 */
        fun loaderOf(input: String): String? =
            (core?.opt(input) as? JSONArray)?.optString(0)
                ?.takeIf { n.optJSONObject(it)?.optString("class_type") == "LoadImage" }

        // Identify head guides (frame_idx == 0) and tail guides (frame_idx == -1)
        val headGuides = guideIds.filter { n.optJSONObject(it)?.optJSONObject("inputs")?.optInt("frame_idx", 999) == 0 }
        val tailGuides = guideIds.filter { n.optJSONObject(it)?.optJSONObject("inputs")?.optInt("frame_idx", 999) == -1 }

        // Find guiders (e.g. BasicGuider) that consume from tail guides or head guides
        val guiders = n.keys().asSequence().filter { id ->
            val cond = (n.optJSONObject(id)?.optJSONObject("inputs")?.opt("conditioning") as? JSONArray)?.optString(0)
            cond != null && (cond in tailGuides || cond in headGuides)
        }.toList()

        when {
            // 1. Pure T2V: neither frame provided
            first == null && last == null -> {
                guiders.forEach { gId ->
                    n.optJSONObject(gId)?.optJSONObject("inputs")?.put("conditioning", JSONArray().put(coreId).put(0))
                }
                core?.remove("ref_images.ref_image_0")
                core?.remove("ref_images.ref_image_1")
                notes.add(H3Message(R.string.plan_note_turbo_bypass_all))
            }
            // 2. Head only: first frame provided, no tail frame
            first != null && last == null -> {
                headGuides.forEach { hId ->
                    val imgLoader = (n.optJSONObject(hId)?.optJSONObject("inputs")?.opt("image") as? JSONArray)?.optString(0)
                    imgLoader?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.put("image", first) }
                }
                loaderOf("ref_images.ref_image_0")?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.put("image", first) }

                // Bypass tail guides: connect guiders to head guides (181 for 151:29, 182 for 151:32)
                guiders.forEach { gId ->
                    val condArr = n.optJSONObject(gId)?.optJSONObject("inputs")?.optJSONArray("conditioning")
                    val currentTail = condArr?.optString(0)
                    val parentHead = if (currentTail != null) {
                        (n.optJSONObject(currentTail)?.optJSONObject("inputs")?.opt("positive") as? JSONArray)?.optString(0)
                    } else null
                    if (parentHead != null && parentHead in headGuides) {
                        n.optJSONObject(gId)?.optJSONObject("inputs")?.put("conditioning", JSONArray().put(parentHead).put(0))
                    } else if (headGuides.isNotEmpty()) {
                        n.optJSONObject(gId)?.optJSONObject("inputs")?.put("conditioning", JSONArray().put(headGuides.first()).put(0))
                    }
                }
                core?.remove("ref_images.ref_image_1")
                notes.add(H3Message(R.string.plan_note_turbo_first_only))
            }
            // 3. Both frames provided
            first != null && last != null -> {
                headGuides.forEach { hId ->
                    val imgLoader = (n.optJSONObject(hId)?.optJSONObject("inputs")?.opt("image") as? JSONArray)?.optString(0)
                    imgLoader?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.put("image", first) }
                }
                loaderOf("ref_images.ref_image_0")?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.put("image", first) }

                tailGuides.forEach { tId ->
                    val imgLoader = (n.optJSONObject(tId)?.optJSONObject("inputs")?.opt("image") as? JSONArray)?.optString(0)
                    imgLoader?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.put("image", last) }
                }
                loaderOf("ref_images.ref_image_1")?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.put("image", last) }
                notes.add(H3Message(R.string.plan_note_turbo_both))
            }
            // 4. Tail only: no first frame, only tail frame
            first == null && last != null -> {
                tailGuides.forEach { tId ->
                    val imgLoader = (n.optJSONObject(tId)?.optJSONObject("inputs")?.opt("image") as? JSONArray)?.optString(0)
                    imgLoader?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.put("image", last) }
                    // Bypass head guide: connect tail guide directly to core
                    n.optJSONObject(tId)?.optJSONObject("inputs")?.put("positive", JSONArray().put(coreId).put(0))
                }
                loaderOf("ref_images.ref_image_1")?.let { n.optJSONObject(it)?.optJSONObject("inputs")?.put("image", last) }
                core?.remove("ref_images.ref_image_0")
                notes.add(H3Message(R.string.plan_note_turbo_last_only))
            }
        }
    }

    /** Status text for one mode, used by the mode selector and the acceptance report. */
    fun modeHint(spec: Spec, mode: Mode): H3Message = when (mode) {
        Mode.T2V -> H3Message(R.string.plan_hint_t2v)
        Mode.I2V -> H3Message(R.string.plan_hint_i2v)
        Mode.FL2V -> H3Message(R.string.plan_hint_fl2v)
        Mode.R2V -> H3Message(R.string.plan_hint_r2v, spec.maxRefSlots.coerceAtLeast(3))
    }
}
