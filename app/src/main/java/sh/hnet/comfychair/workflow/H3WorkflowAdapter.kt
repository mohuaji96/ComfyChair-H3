package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/** Structural API-workflow adapter. No fixed IDs; never rewrites the import. */
object H3WorkflowAdapter {
    /** AMBIGUOUS: several H3 cores exist and the execution branch cannot be proven. */
    enum class Mode { T2V, I2V, FL2V, R2V, AMBIGUOUS }
    data class Binding(
        val nodeId: String, val input: String, val label: String, val kind: String,
        val value: Any, val slot: Int? = null,
        /** Bypassed/muted node or an unproven execution path: show, never edit. */
        val readOnly: Boolean = false,
        val note: String? = null
    ) {
        val key: String get() = "$nodeId:$input"
    }
    data class Analysis(val mode: Mode, val coreId: String, val bindings: List<Binding>,
        val referenceInputs: Map<Int, String>, val warnings: List<String>,
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
            listOf("检测到 ${ids.size} 个 H3 核心节点（${ids.joinToString { "#$it" }}）；无法确定执行分支，请在专业编辑器中确认"), "")
        val core = ids.single(); val ci = n.getJSONObject(core).getJSONObject("inputs")
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
        val fields = mutableListOf<Binding>(); val warnings = mutableListOf<String>()
        fun add(id: String, input: String, label: String, kind: String = "text", slot: Int? = null,
                readOnly: Boolean = false, note: String? = null) {
            val owner = n.optJSONObject(id)
            val value = owner?.optJSONObject("inputs")?.opt(input)
            if (value != null && value !== JSONObject.NULL && value !is JSONArray && value !is JSONObject) {
                if (fields.none { it.nodeId == id && it.input == input && it.slot == slot })
                    fields += Binding(id, input, label, kind, value, slot, readOnly, note)
            } else warnings += "$label 无法安全映射（$id.$input）"
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
                sources.size == 1 -> add(sources[0].first, "value", "提示词", "prompt")
                sources.isEmpty() -> warnings.add("提示词不是已支持的文字节点，请使用专业编辑器")
                else -> warnings.add("提示词有 ${sources.size} 个候选来源（" +
                    sources.joinToString { "#${it.first} ${it.second}" } + "），无法唯一确定，未自动选择")
            }
        } else add(core, "prompt", "提示词", "prompt")
        listOf("vae" to "视频 VAE", "audio_vae" to "音频 VAE").forEach { (input, label) ->
            upstream(core, input, "VAELoader")?.let { add(it, "vae_name", label, "model") }
        }
        // T2V/I2V audio VAE is connected to the audio decoder, not the H3 input.
        n.keys().asSequence().filter { n.optJSONObject(it)?.optString("class_type") == "VAEDecodeAudio" }.forEach { id ->
            upstream(id, "vae", "VAELoader")?.let { add(it, "vae_name", "音频 VAE", "model") }
        }
        upstream(core, "clip", "CLIPLoader")?.let { add(it, "clip_name", "CLIP 模型", "model") }
        upstream(core, "width", "ResolutionSelector")?.let {
            add(it, "aspect_ratio", "宽高比", "combo"); add(it, "megapixels", "分辨率（百万像素）", "number")
        }
        upstream(core, "length", "PrimitiveFloat")?.let { add(it, "value", "视频时长（秒）", "number") }
        n.keys().asSequence().forEach { id ->
            val node = n.optJSONObject(id) ?: return@forEach
            val type = node.optString("class_type")
            val bypassed = node.optInt("mode", 0) != 0
            val state = if (bypassed) "已绕过/静音，仅显示诊断，不提供快捷修改" else null
            when(type) {
                "UNETLoader" -> add(id, "unet_name", "UNet 模型", "model", readOnly = bypassed, note = state)
                // LoRA: discovered per node, never labelled by product name (no hardcoded Turbo/加速).
                "LoraLoaderModelOnly" -> {
                    add(id, "lora_name", "LoRA 文件", "model", readOnly = bypassed, note = state)
                    add(id, "strength_model", "LoRA 强度(Model)", "number", readOnly = bypassed, note = state)
                }
                "LoraLoader" -> {
                    add(id, "lora_name", "LoRA 文件", "model", readOnly = bypassed, note = state)
                    add(id, "strength_model", "LoRA 强度(Model)", "number", readOnly = bypassed, note = state)
                    add(id, "strength_clip", "LoRA 强度(CLIP)", "number", readOnly = bypassed, note = state)
                }
                "RandomNoise" -> add(id, "noise_seed", "Seed", "number", readOnly = bypassed, note = state)
                "PrimitiveBoolean" -> add(id, "value", "生成模式开关", "boolean", readOnly = bypassed, note = state)
                "PrimitiveInt" -> {
                    val branches=n.keys().asSequence().filter { n.optJSONObject(it)?.optString("class_type")=="ComfySwitchNode" }
                        .mapNotNull { switch ->
                            val ins=n.getJSONObject(switch).getJSONObject("inputs")
                            when(id) { ins.optJSONArray("on_false")?.optString(0) -> "Steps 分支（on_true）";ins.optJSONArray("on_true")?.optString(0) -> "Steps 分支（on_false）";else -> null }
                        }.distinct().toList()
                    add(id, "value", branches.singleOrNull() ?: "Steps（分支节点 #$id）", "number",
                        readOnly = bypassed, note = state)
                }
                "BasicScheduler" -> if(n.getJSONObject(id).getJSONObject("inputs").opt("steps") is Number)
                    add(id, "steps", "Steps", "number", readOnly = bypassed, note = state)
                // Unknown LoRA-like loaders (stacks, custom nodes) stay visible as diagnostics only.
                else -> {
                    if (type.contains("Stack", true) && type.contains("lora", true)) {
                        warnings.add("检测到 LoRA 堆栈 #$id $type；动态堆栈暂不提供快捷修改，请在专业编辑器编辑")
                    } else if (type.contains("lora", true)) {
                        warnings.add("检测到未支持的 LoRA 节点 #$id $type，请在专业编辑器中确认文件与强度")
                    }
                }
            }
        }
        if(mode == Mode.I2V || mode == Mode.FL2V) upstream(core,"first_frame","LoadImage")
            ?.let { add(it,"image","首帧图片","image",0) } ?: warnings.add("首帧未找到可安全编辑的 LoadImage")
        if(mode == Mode.FL2V) upstream(core,"last_frame","LoadImage")
            ?.let { add(it,"image","尾帧图片","image",1) } ?: warnings.add("尾帧未找到可安全编辑的 LoadImage")
        if(mode == Mode.R2V) refs.toSortedMap().forEach { (index,key) ->
            upstream(core,key,"LoadImage")?.let { add(it,"image","参考图 ${index+1}","image",index) }
                ?: warnings.add("参考图 ${index+1} 不是已支持的 LoadImage 来源")
        }
        if(mode == Mode.R2V && 2 !in refs) warnings += "原始工作流只有 ${refs.size} 个参考接口；第 3 张上传后才添加 ref_image_2，不修改已有顺序。"
        listOf("提示词" to { b: Binding -> b.kind=="prompt" },
            "UNet" to { b: Binding -> b.input=="unet_name" },
            "CLIP" to { b: Binding -> b.input=="clip_name" },
            "视频 VAE" to { b: Binding -> b.label=="视频 VAE" },
            "时长" to { b: Binding -> b.label.contains("时长") },
            "分辨率" to { b: Binding -> b.input=="megapixels" },
            "Steps" to { b: Binding -> b.label.contains("Steps") }
        ).forEach { (label,test) -> if(fields.none(test)) warnings += "$label 未找到唯一安全映射，请在专业编辑器调整" }
        ci.keys().asSequence().filter { it.startsWith(TRANSPARENT_PREFIX) && it !in refs.values }.forEach {
            warnings += "参考接口 $it 超出当前快捷面板范围，原样保留"
        }
        return Analysis(mode,core,fields,refs,warnings)
    }
    /** Patch a literal only. Links and every unrelated node/metadata remain untouched. */
    fun change(source: String, binding: Binding, value: Any): String {
        require(!binding.readOnly) { "${binding.label} 当前只读（节点被绕过或路径未确认）" }
        val document = JSONObject(source);val n = nodes(document)
        val inputs = n.getJSONObject(binding.nodeId).getJSONObject("inputs")
        require(inputs.has(binding.input) && inputs.opt(binding.input) !is JSONArray) { "参数已变为连接，请重新打开面板" }
        require(when(binding.value) { is Boolean -> value is Boolean; is Number -> value is Number && value.toString().toDouble().isFinite(); else -> value is String }) { "参数类型不匹配" }
        inputs.put(binding.input,value);return document.toString(2)
    }
    /** R2V reference slot (0..5): replace or clear one extension interface. */
    fun setReference(source: String, slot: Int, filename: String?): String {
        require(slot in 0..5)
        val a = inspect(source) ?: error("不是 H3 工作流")
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
        require(frame=="first_frame" || frame=="last_frame") { "未知的首尾帧输入：$frame" }
        val a = inspect(source) ?: error("不是 H3 工作流")
        require(a.mode != Mode.R2V) { "参考生视频没有首尾帧输入" }
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
            setOf("MiniMaxH3ImageToVideo","MiniMaxH3ReferenceToVideo") } ?: error("不是 H3 工作流")
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
