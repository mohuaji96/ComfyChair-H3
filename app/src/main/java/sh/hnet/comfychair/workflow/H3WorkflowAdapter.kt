package sh.hnet.comfychair.workflow

import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.R

/** Structural API-workflow adapter. No fixed IDs; never rewrites the import. */
object H3WorkflowAdapter {
    /** AMBIGUOUS: several H3 cores exist and the execution branch cannot be proven. */
    enum class Mode { T2V, I2V, FL2V, R2V, AMBIGUOUS }
    data class Binding(
        val nodeId: String, val input: String, @StringRes val labelRes: Int, val kind: String,
        val value: Any, val slot: Int? = null,
        /** Bypassed/muted node or an unproven execution path: show, never edit. */
        val readOnly: Boolean = false,
        /** String resource of the diagnostic note; null when the node runs normally. */
        @StringRes val noteRes: Int? = null,
        /** Filled in for the single placeholder of [labelRes], when that label carries one. */
        val labelArg: String? = null
    ) {
        val key: String get() = "$nodeId:$input"
    }
    data class Analysis(val mode: Mode, val coreId: String, val bindings: List<Binding>,
        val referenceInputs: Map<Int, String>, val warnings: List<H3Message>,
        /** "api", "litegraph" or "" when ambiguous/unrecognized. */
        val format: String = "api")
    private val PROMPT_SOURCES = setOf("PrimitiveStringMultiline", "PrimitiveString")
    private val TRANSPARENT_PREFIX = "ref_images.ref_image_"
    fun nodes(document: JSONObject): JSONObject = document.optJSONObject("nodes") ?: document

    /** API first, then LiteGraph. Never throws: recognition failure must not block ordinary imports. */
    fun inspectAny(source: String): Analysis? = runCatching {
        inspect(source) ?: H3LiteGraphAdapter.analyze(source)
    }.getOrNull()

    /**
     * Recognise a workflow with a user-supplied mode. Needed when automatic detection is
     * AMBIGUOUS or when a file carries several cores and the user knows which branch is meant.
     * The node analysis itself is untouched: only the reported mode changes.
     */
    fun inspectWithMode(source: String, override: Mode?): Analysis? {
        val base = inspectAny(source) ?: return null
        return if (override == null || base.mode == override) base else base.copy(mode = override)
    }

    fun inspect(source: String): Analysis? {
        val n = nodes(JSONObject(source))
        val ids = n.keys().asSequence().filter { n.optJSONObject(it)?.optString("class_type") in
            setOf("MiniMaxH3ImageToVideo", "MiniMaxH3ReferenceToVideo") }.toList()
        if (ids.isEmpty()) return null
        // Several cores: report instead of throwing, so the import is never aborted by this check.
        if (ids.size > 1) return Analysis(Mode.AMBIGUOUS, "", emptyList(), emptyMap(),
            listOf(H3Message(R.string.ad_ambiguous_cores, ids.size, ids.joinToString { "#$it" })), "")
        val core = ids.single(); val ci = n.getJSONObject(core).getJSONObject("inputs")
        // 快捷面板只认 6 个槽（0..5）；ref_image_6 以上原样保留并单独提示，
        // 这是 P0 的既有契约，放宽会让「超出范围」的提示消失。
        val refs = ci.keys().asSequence().mapNotNull { key ->
            Regex("ref_images\\.ref_image_([0-5])").matchEntire(key)?.groupValues?.get(1)?.toInt()?.let { it to key }
        }.toMap()
        // last_frame is checked before first_frame: FL2V also carries a first_frame.
        val mode = when {
            n.getJSONObject(core).getString("class_type") == "MiniMaxH3ReferenceToVideo" -> Mode.R2V
            ci.opt("last_frame") is JSONArray -> Mode.FL2V
            ci.opt("first_frame") is JSONArray -> Mode.I2V
            else -> Mode.T2V
        }
        val fields = mutableListOf<Binding>(); val warnings = mutableListOf<H3Message>()
        fun add(id: String, input: String, @StringRes labelRes: Int, kind: String = "text", slot: Int? = null,
                readOnly: Boolean = false, @StringRes noteRes: Int? = null, labelArg: String? = null) {
            val owner = n.optJSONObject(id)
            val value = owner?.optJSONObject("inputs")?.opt(input)
            if (value != null && value !== JSONObject.NULL && value !is JSONArray && value !is JSONObject) {
                if (fields.none { it.nodeId == id && it.input == input && it.slot == slot })
                    fields += Binding(id, input, labelRes, kind, value, slot, readOnly, noteRes, labelArg)
            } else warnings += H3Message(R.string.ad_mapping_failed, H3Res(labelRes), id, input)
        }
        /** Every distinct source of [target] reachable through [input]; >1 means ambiguous. */
        fun upstreamAll(id: String, input: String, target: String, seen: Set<String> = emptySet()): List<String> {
            if (id in seen) return emptyList()
            val link = n.optJSONObject(id)?.optJSONObject("inputs")?.optJSONArray(input) ?: return emptyList()
            val sourceId = link.optString(0); val node = n.optJSONObject(sourceId) ?: return emptyList()
            if (node.optString("class_type") == target) return listOf(sourceId)
            val ins = node.optJSONObject("inputs") ?: return emptyList()
            return ins.keys().asSequence().flatMap { upstreamAll(sourceId, it, target, seen + id).asSequence() }
                .distinct().toList()
        }
        fun upstream(id: String, input: String, target: String, seen: Set<String> = emptySet()): String? =
            upstreamAll(id, input, target, seen).singleOrNull()
        fun upstreamAny(id: String, input: String, targets: Set<String>,
                        seen: Set<String> = emptySet()): List<Pair<String, String>> {
            if (id in seen) return emptyList()
            val link = n.optJSONObject(id)?.optJSONObject("inputs")?.optJSONArray(input) ?: return emptyList()
            val sourceId = link.optString(0); val node = n.optJSONObject(sourceId) ?: return emptyList()
            val type = node.optString("class_type")
            if (type in targets) return listOf(sourceId to type)
            val ins = node.optJSONObject("inputs") ?: return emptyList()
            return ins.keys().asSequence().flatMap { upstreamAny(sourceId, it, targets, seen + id).asSequence() }
                .distinct().toList()
        }
        if (ci.opt("prompt") is JSONArray) {
            val sources = upstreamAny(core, "prompt", PROMPT_SOURCES)
            when {
                sources.size == 1 -> add(sources[0].first, "value", R.string.ad_label_prompt, "prompt")
                sources.isEmpty() -> warnings.add(H3Message(R.string.ad_prompt_unsupported))
                else -> warnings.add(H3Message(R.string.ad_prompt_ambiguous, sources.size,
                    sources.joinToString { "#${it.first} ${it.second}" }))
            }
        } else add(core, "prompt", R.string.ad_label_prompt, "prompt")
        listOf("vae" to R.string.ad_label_video_vae, "audio_vae" to R.string.ad_label_audio_vae).forEach { (input, labelRes) ->
            upstream(core, input, "VAELoader")?.let { add(it, "vae_name", labelRes, "model") }
        }
        // T2V/I2V audio VAE is connected to the audio decoder, not the H3 input.
        n.keys().asSequence().filter { n.optJSONObject(it)?.optString("class_type") == "VAEDecodeAudio" }.forEach { id ->
            upstream(id, "vae", "VAELoader")?.let { add(it, "vae_name", R.string.ad_label_audio_vae, "model") }
        }
        upstream(core, "clip", "CLIPLoader")?.let { add(it, "clip_name", R.string.ad_label_clip, "model") }
        upstream(core, "width", "ResolutionSelector")?.let {
            add(it, "aspect_ratio", R.string.ad_label_aspect_ratio, "combo")
            add(it, "megapixels", R.string.ad_label_megapixels, "number")
        }
        upstream(core, "length", "PrimitiveFloat")?.let { add(it, "value", R.string.ad_label_duration, "number") }
        n.keys().asSequence().forEach { id ->
            val node = n.optJSONObject(id) ?: return@forEach
            val type = node.optString("class_type")
            val bypassed = node.optInt("mode", 0) != 0
            val state = if (bypassed) R.string.ad_note_bypassed else null
            when(type) {
                "UNETLoader" -> add(id, "unet_name", R.string.ad_label_unet, "model", readOnly = bypassed, noteRes = state)
                // LoRA: discovered per node, never labelled by product name (no hardcoded Turbo/加速).
                "LoraLoaderModelOnly" -> {
                    add(id, "lora_name", R.string.ad_label_lora_file, "model", readOnly = bypassed, noteRes = state)
                    add(id, "strength_model", R.string.ad_label_lora_model_strength, "number", readOnly = bypassed, noteRes = state)
                }
                "LoraLoader" -> {
                    add(id, "lora_name", R.string.ad_label_lora_file, "model", readOnly = bypassed, noteRes = state)
                    add(id, "strength_model", R.string.ad_label_lora_model_strength, "number", readOnly = bypassed, noteRes = state)
                    add(id, "strength_clip", R.string.ad_label_lora_clip_strength, "number", readOnly = bypassed, noteRes = state)
                }
                "RandomNoise" -> add(id, "noise_seed", R.string.ad_label_seed, "number", readOnly = bypassed, noteRes = state)
                "PrimitiveBoolean" -> add(id, "value", R.string.ad_label_generation_switch, "boolean", readOnly = bypassed, noteRes = state)
                "PrimitiveInt" -> {
                    val branches=n.keys().asSequence().filter { n.optJSONObject(it)?.optString("class_type")=="ComfySwitchNode" }
                        .mapNotNull { switch ->
                            val ins=n.getJSONObject(switch).getJSONObject("inputs")
                            when(id) { ins.optJSONArray("on_false")?.optString(0) -> R.string.ad_label_steps_branch_true
                                ins.optJSONArray("on_true")?.optString(0) -> R.string.ad_label_steps_branch_false
                                else -> null }
                        }.distinct().toList()
                    val branchRes = branches.singleOrNull()
                    add(id, "value", branchRes ?: R.string.ad_label_steps_branch_node, "number",
                        readOnly = bypassed, noteRes = state, labelArg = if (branchRes == null) id else null)
                }
                "BasicScheduler" -> if(n.getJSONObject(id).getJSONObject("inputs").opt("steps") is Number)
                    add(id, "steps", R.string.ad_label_steps, "number", readOnly = bypassed, noteRes = state)
                // Unknown LoRA-like loaders (stacks, custom nodes) stay visible as diagnostics only.
                else -> {
                    if (type.contains("Stack", true) && type.contains("lora", true)) {
                        warnings.add(H3Message(R.string.ad_lora_stack, id, type))
                    } else if (type.contains("lora", true)) {
                        warnings.add(H3Message(R.string.ad_lora_unsupported, id, type))
                    }
                }
            }
        }
        if(mode == Mode.I2V || mode == Mode.FL2V) upstream(core,"first_frame","LoadImage")
            ?.let { add(it,"image",R.string.ad_label_frame_first,"image",0) } ?: warnings.add(H3Message(R.string.ad_first_frame_missing))
        if(mode == Mode.FL2V) upstream(core,"last_frame","LoadImage")
            ?.let { add(it,"image",R.string.ad_label_frame_last,"image",1) } ?: warnings.add(H3Message(R.string.ad_last_frame_missing))
        if(mode == Mode.R2V) refs.toSortedMap().forEach { (index,key) ->
            upstream(core,key,"LoadImage")?.let { add(it,"image",R.string.ad_label_reference,"image",index, labelArg = "${index+1}") }
                ?: warnings.add(H3Message(R.string.ad_ref_not_loadimage, index+1))
        }
        if(mode == Mode.R2V && 2 !in refs) warnings += H3Message(R.string.ad_ref_slots_limited, refs.size)
        listOf(
            R.string.ad_label_prompt to { b: Binding -> b.kind=="prompt" },
            R.string.ad_label_unet to { b: Binding -> b.input=="unet_name" },
            R.string.ad_label_clip to { b: Binding -> b.input=="clip_name" },
            R.string.ad_label_video_vae to { b: Binding -> b.labelRes==R.string.ad_label_video_vae },
            R.string.ad_label_duration to { b: Binding -> b.labelRes==R.string.ad_label_duration },
            R.string.ad_label_megapixels to { b: Binding -> b.input=="megapixels" },
            R.string.ad_label_steps to { b: Binding ->
                b.labelRes==R.string.ad_label_steps || b.labelRes==R.string.ad_label_steps_branch_node }
        ).forEach { (labelRes,test) -> if(fields.none(test)) warnings += H3Message(R.string.ad_missing_mapping, H3Res(labelRes)) }
        ci.keys().asSequence().filter { it.startsWith(TRANSPARENT_PREFIX) && it !in refs.values }.forEach {
            warnings += H3Message(R.string.ad_ref_out_of_range, it)
        }
        return Analysis(mode,core,fields,refs,warnings)
    }
    /** Patch a literal only. Links and every unrelated node/metadata remain untouched. */
    fun change(source: String, binding: Binding, value: Any): String {
        if (binding.readOnly) throw H3EditException(
            H3Message(R.string.ad_err_read_only, H3Res(binding.labelRes)))
        val document = JSONObject(source);val n = nodes(document)
        val inputs = n.getJSONObject(binding.nodeId).getJSONObject("inputs")
        if (!(inputs.has(binding.input) && inputs.opt(binding.input) !is JSONArray)) throw H3EditException(
            H3Message(R.string.ad_err_now_linked))
        if (!(when(binding.value) { is Boolean -> value is Boolean; is Number -> value is Number && value.toString().toDouble().isFinite(); else -> value is String })) throw H3EditException(
            H3Message(R.string.ad_err_type_mismatch))
        inputs.put(binding.input,value);return document.toString(2)
    }
    /** R2V reference slot (0..5): replace or clear one extension interface. */
    fun setReference(source: String, slot: Int, filename: String?): String {
        require(slot in 0..5)
        val a = inspect(source) ?: throw H3EditException(H3Message(R.string.ad_err_not_h3))
        require(a.mode == Mode.R2V)
        return setSlotImage(source, "ref_images.ref_image_$slot", filename)
    }
    /**
     * I2V / FL2V frame input: `first_frame` or `last_frame`.
     *
     * `last_frame` stays writable after being cleared: clearing removes the wire, which makes the
     * workflow read as I2V, and re-attaching must restore FL2V. Both are documented optional inputs
     * of the same `MiniMaxH3ImageToVideo` class (official FL2V sample), so re-attaching is not an
     * invented mapping.
     */
    fun setFrame(source: String, frame: String, filename: String?): String {
        if (!(frame=="first_frame" || frame=="last_frame")) throw H3EditException(
            H3Message(R.string.ad_err_unknown_frame, frame))
        val a = inspect(source) ?: throw H3EditException(H3Message(R.string.ad_err_not_h3))
        if (a.mode == Mode.R2V) throw H3EditException(H3Message(R.string.ad_err_r2v_no_frames))
        return setSlotImage(source, frame, filename)
    }
    /**
     * Replace or clear the image feeding one H3 input.
     * A wire shared by several inputs is never edited in place: a dedicated LoadImage is added instead,
     * so untouched interfaces keep their original node and filename.
     */
    fun setSlotImage(source: String, input: String, filename: String?): String {
        val doc=JSONObject(source);val n=nodes(doc)
        val core=n.keys().asSequence().firstOrNull { n.optJSONObject(it)?.optString("class_type") in
            setOf("MiniMaxH3ImageToVideo","MiniMaxH3ReferenceToVideo") } ?: throw H3EditException(H3Message(R.string.ad_err_not_h3))
        val inputs=n.getJSONObject(core).getJSONObject("inputs")
        if(filename==null) { inputs.remove(input);return doc.toString(2) } // Keep the loader node; only the interface goes.
        val srcId=(inputs.opt(input) as? JSONArray)?.optString(0)
        val linked=srcId?.let { n.optJSONObject(it) }
        if(linked!=null && linked.optString("class_type")=="LoadImage") {
            val consumers=n.keys().asSequence().sumOf { id ->
                val ins=n.optJSONObject(id)?.optJSONObject("inputs")
                ins?.keys()?.asSequence()?.count { key -> (ins.opt(key) as? JSONArray)?.optString(0)==srcId } ?: 0
            }
            if(consumers==1) { linked.getJSONObject("inputs").put("image",filename);return doc.toString(2) }
        }
        var id=((n.keys().asSequence().mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0)+1).toString()
        while(n.has(id)) id += "_h3"
        n.put(id,JSONObject().put("class_type","LoadImage").put("inputs",JSONObject().put("image",filename)))
        inputs.put(input,JSONArray().put(id).put(0));return doc.toString(2)
    }
}
