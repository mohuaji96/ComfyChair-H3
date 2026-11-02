package sh.hnet.comfychair.ui.components

import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.storage.H3SourceStore
import sh.hnet.comfychair.workflow.H3DiffPreview
import sh.hnet.comfychair.workflow.H3PromptCompiler
import sh.hnet.comfychair.workflow.H3Resolution
import sh.hnet.comfychair.workflow.H3StepsRouting
import sh.hnet.comfychair.workflow.H3WorkflowAdapter
import sh.hnet.comfychair.queue.JobRegistry
import sh.hnet.comfychair.viewmodel.ContentType
import java.util.UUID

/** Execution JSON plus the preview of what changed to produce it. */
private data class PreparedPrompt(val json: String, val preview: H3DiffPreview.Preview)

/** Sheet wrapper kept for the existing entry points; the body is shared with the H3 workspace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun H3ParameterSheet(workflowId: String, generationViewModel: sh.hnet.comfychair.viewmodel.GenerationViewModel? = null, front: Boolean = false, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        H3ParameterContent(workflowId, generationViewModel, front, embedded = false, onDismiss = onDismiss)
    }
}

/**
 * H3 parameter body. [embedded] renders inside the H3 workspace tab instead of a sheet,
 * so both entries share exactly one implementation.
 */
@Composable
fun H3ParameterContent(
    workflowId: String,
    generationViewModel: sh.hnet.comfychair.viewmodel.GenerationViewModel? = null,
    front: Boolean = false,
    embedded: Boolean = false,
    onDismiss: () -> Unit = {}
) {
    val context=LocalContext.current
    val store=remember { H3SourceStore(context) }
    val workflow=remember(workflowId) { WorkflowManager.getWorkflowById(workflowId) }
    if(workflow==null) { Text("工作流不存在，请重新导入"); return }
    var source by remember(workflowId) {
        mutableStateOf(runCatching { store.open(workflowId,workflow.jsonContent) }.getOrElse { workflow.jsonContent })
    }
    val analysis=remember(source) { H3WorkflowAdapter.inspectAny(source) }
    if(analysis==null) { Text("该工作流当前无法识别为 H3，请在专业编辑器中检查节点结构"); return }
    val ambiguous = analysis.mode == H3WorkflowAdapter.Mode.AMBIGUOUS
    val models by ConnectionManager.modelCache.collectAsState()
    val client=ConnectionManager.clientOrNull
    val scope=rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Int?>(null) }
    var previewRevision by remember { mutableStateOf(0) }
    var advanced by remember { mutableStateOf(false) }
    val localContext = androidx.compose.ui.platform.LocalContext.current
    var review by remember { mutableStateOf<PreparedPrompt?>(null) }
    var reviewedClient by remember { mutableStateOf<sh.hnet.comfychair.ComfyUIClient?>(null) }
    fun save(next: String) { store.save(workflowId,next);source=next;message="已保存工作副本；原始导入未修改" }
    val main=remember { Handler(Looper.getMainLooper()) }
    var exportOriginal by remember { mutableStateOf(false) }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri!=null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write((if(exportOriginal) store.original(workflowId) else source).toByteArray()) } } }
                .onSuccess {message=if(exportOriginal) "已导出 H3 原始导入" else "已导出 H3 工作副本，原始导入仍保留"}.onFailure {message="导出失败：${it.message}"}
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val slot=selection
        val selectedClient=ConnectionManager.clientOrNull
        if(uri!=null && slot!=null && selectedClient!=null) {
            val slotIndex=slot
            busy=true;message="正在读取并上传图片…"
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes=context.contentResolver.openInputStream(uri)!!.use { it.readNBytes(32*1024*1024+1) }
                        require(bytes.size<=32*1024*1024) { "图片超过 32 MB" }
                        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=32_000_000) { "不支持的图片或图片像素过大" }
                        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size) ?: error("图片解码失败")
                        val png=java.io.ByteArrayOutputStream().use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);out.toByteArray() }
                        bitmap.recycle()
                        png
                    }
                }.onSuccess { png ->
                    selectedClient.uploadImage(png,"h3_${UUID.randomUUID()}.png",overwrite=false,returnFullPath=true) { ok,name,error,_ ->
                        main.post {
                            busy=false
                            if(selectedClient!==ConnectionManager.clientOrNull) message="服务器已切换，上传结果未应用，请在新服务器重新上传"
                             else if(ok && name!=null) runCatching {
                                 val next=when(analysis.mode) {
                                     H3WorkflowAdapter.Mode.R2V -> H3WorkflowAdapter.setReference(source,slotIndex,name)
                                     H3WorkflowAdapter.Mode.FL2V, H3WorkflowAdapter.Mode.I2V ->
                                         H3WorkflowAdapter.setFrame(source,if(slotIndex==0) "first_frame" else "last_frame",name)
                                     else -> {
                                         val target=analysis.bindings.firstOrNull { it.kind=="image" }
                                             ?: error("当前工作流没有可绑定的图片输入")
                                         H3WorkflowAdapter.change(source,target,name)
                                     }
                                 }
                                 save(next)
                                 val preview=java.io.File(context.filesDir,"h3_previews/$workflowId/${UUID.nameUUIDFromBytes(name.toByteArray())}.png")
                                 preview.parentFile!!.mkdirs();preview.writeBytes(png);previewRevision++
                                 context.getSharedPreferences("h3_upload_server",0).edit().putString("$workflowId:$slotIndex",ConnectionManager.currentServerId).apply()
                                 message="上传成功，已绑定服务器文件名"
                             }.onFailure { message="上传成功但绑定失败：${it.message}" }
                            else message="上传失败：${error ?: "服务器未返回文件名"}"
                        }
                    }
                }.onFailure { busy=false;message="图片读取失败：${it.message}" }
            }
        }
    }
    fun prepare(): PreparedPrompt {
        check(!busy)
        check(client!=null && client===ConnectionManager.clientOrNull) { "请先连接服务器" }
        check(models.nodeDefinitionsRevision>0 || ConnectionManager.nodeTypeRegistry.getNodeDefinition("MiniMaxH3ImageToVideo")!=null || ConnectionManager.nodeTypeRegistry.getNodeDefinition("MiniMaxH3ReferenceToVideo")!=null) { "节点定义尚未加载，请等待 /object_info 完成" }
        var exported=WorkflowManager.exportWorkflowToComfyUIFormat(workflowId).getOrThrow()
        exported=JSONObject().put("nodes",JSONObject(exported)).toString()
        val nodesBefore=H3WorkflowAdapter.nodes(JSONObject(exported)).length()
        val attributed=WorkflowManager.applyNodeAttributeEdits(exported,workflowId)
        // Only explicit H3 edits are merged; a structural (link) change is never rewritten here.
        val patch=store.applyDetailed(workflowId,attributed)
        val compiled=H3PromptCompiler.compileDetailed(patch.json) { ConnectionManager.nodeTypeRegistry.getNodeDefinition(it) }
        val prepared=compiled.json
        val nodes=H3WorkflowAdapter.nodes(JSONObject(prepared))
        val errors=mutableListOf<String>()
        val a=H3WorkflowAdapter.inspect(prepared) ?: error("执行副本已无法识别为 H3，请重新打开面板")
        a.bindings.forEach { binding ->
            val type=nodes.getJSONObject(binding.nodeId).getString("class_type")
            val input=ConnectionManager.nodeTypeRegistry.getInputDefinition(type,binding.input)
            if(input==null) errors += "服务器不支持 $type.${binding.input}"
            else if(!input.options.isNullOrEmpty() && binding.kind!="image" && binding.value.toString() !in input.options.orEmpty()) errors += "${binding.label} 不在服务器合法选项中"
            if(binding.kind=="image") {
                val savedServer=context.getSharedPreferences("h3_upload_server",0).getString("$workflowId:${binding.slot}",null)
                if(savedServer!=null && savedServer!=ConnectionManager.currentServerId) errors += "${binding.label} 曾上传到其他服务器，请重新上传"
            }
        }
        require(errors.isEmpty()) { errors.distinct().joinToString("\n") }
        store.recordPrompt(workflowId,prepared)
        val preview=H3DiffPreview.build(patch,compiled.removed,nodesBefore,
            H3WorkflowAdapter.nodes(JSONObject(prepared)).length())
        return PreparedPrompt(prepared,preview)
    }
    Column(Modifier.fillMaxWidth().padding(if(embedded) 0.dp else 16.dp)) {
        Text(if(ambiguous) "H3 参数（待确认执行分支）" else "H3 ${analysis.mode} 参数",
            style=MaterialTheme.typography.titleLarge)
        Text(models.fileRefreshStatus,style=MaterialTheme.typography.labelSmall)
        Text(models.nodeRefreshStatus,style=MaterialTheme.typography.labelSmall)
        if(analysis.format=="litegraph") Text("来源格式：LiteGraph（已离线展平，原始文件另存）",style=MaterialTheme.typography.labelSmall)
        if(message.isNotEmpty()) Text(message,color=MaterialTheme.colorScheme.primary)
        LazyColumn(
            modifier=(if(embedded) Modifier.fillMaxSize() else Modifier.heightIn(max=520.dp)).padding(horizontal=if(embedded) 16.dp else 0.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)
        ) {
            items(analysis.warnings) { Text(it,color=MaterialTheme.colorScheme.error) }
            if(ambiguous) {
                item {
                    Text("该工作流包含多个 H3 核心节点，快速参数面板不提供修改。",
                        style=MaterialTheme.typography.bodyMedium)
                    Text("请在专业节点编辑器中保留唯一执行分支后重新打开。原始文件未被修改。",
                        style=MaterialTheme.typography.bodySmall)
                }
            }
            if(!ambiguous && analysis.mode!=H3WorkflowAdapter.Mode.T2V) {
                // R2V slot count comes from the workflow itself (`ref_images.ref_image_N` keys that
                // actually exist). Never render placeholders for slots the file does not define.
                val slots=when(analysis.mode) {
                    H3WorkflowAdapter.Mode.I2V -> listOf(0)
                    H3WorkflowAdapter.Mode.FL2V -> listOf(0,1)
                    else -> analysis.referenceInputs.keys.sorted()
                }
                items(slots) { slot ->
                    val binding=analysis.bindings.firstOrNull { it.kind=="image" && it.slot==slot }
                    val frameKey=if(slot==0) "first_frame" else "last_frame"
                    Text(when(analysis.mode) {
                        H3WorkflowAdapter.Mode.R2V -> "参考图 ${slot+1} · ref_image_$slot"
                        H3WorkflowAdapter.Mode.FL2V -> if(slot==0) "首帧图片 · first_frame" else "尾帧图片 · last_frame"
                        else -> "首帧图片 · first_frame"
                    })
                    Text(binding?.value?.toString()?.ifEmpty { "（空文件名）" } ?: "未连接图片")
                    if(binding!=null) H3ImagePreview(workflowId,binding.value.toString(),previewRevision)
                    Row {
                        TextButton(enabled=!busy && client!=null,onClick={ selection=slot;picker.launch("image/*") }) { Text(if(binding==null) "上传图片" else "替换图片") }
                        if(binding!=null && (analysis.mode==H3WorkflowAdapter.Mode.R2V || analysis.mode==H3WorkflowAdapter.Mode.FL2V))
                            TextButton(enabled=!busy,onClick={
                                runCatching {
                                    save(if(analysis.mode==H3WorkflowAdapter.Mode.R2V) H3WorkflowAdapter.setReference(source,slot,null)
                                         else H3WorkflowAdapter.setFrame(source,frameKey,null))
                                    previewRevision++
                                }.onFailure { message=it.message ?: "删除失败" }
                            }) { Text(if(analysis.mode==H3WorkflowAdapter.Mode.FL2V) "解除该帧" else "删除接口图片") }
                    }
                }
                if(analysis.mode==H3WorkflowAdapter.Mode.R2V) item {
                    Text("该工作流定义了 ${slots.size} 个参考图接口（ref_image_0…${(slots.maxOrNull() ?: 0)}）。" +
                        "槽位数量由文件本身决定，不提供文件之外的空白槽位。",
                        style=MaterialTheme.typography.labelSmall)
                }
                if(analysis.mode==H3WorkflowAdapter.Mode.FL2V) item {
                    Text("首帧与尾帧各自独立：替换只改对应 LoadImage，不动另一帧。解除后提交会因缺少必填输入被拦截，不会静默按单帧生成。",
                        style=MaterialTheme.typography.labelSmall)
                }
            }
            // Show the size that will actually be submitted, derived from the same rule the
            // submission uses, so the panel can never display one size and post another.
            if(!ambiguous) {
                val ratioBinding=analysis.bindings.firstOrNull { it.input=="aspect_ratio" }
                val mpBinding=analysis.bindings.firstOrNull { it.input=="megapixels" }
                val parts=(ratioBinding?.value as? String)?.split(":")?.mapNotNull { it.trim().toIntOrNull() }
                val mp=(mpBinding?.value as? Number)?.toDouble()
                if(parts?.size==2 && parts[0]>0 && parts[1]>0 && mp!=null) {
                    item {
                        val size=H3Resolution.size(parts[0],parts[1],mp)
                        // Only claim this is the submitted size when width/height are real,
                        // editable inputs; otherwise the workflow derives them itself.
                        Text(if(analysis.bindings.any { it.input=="width" })
                                "将提交尺寸 ${size.width}×${size.height}（按节点步长对齐）"
                            else "预计尺寸 ${size.width}×${size.height}（由工作流分辨率节点计算）",
                            style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
            val fieldBindings = analysis.bindings.filter { it.kind!="image" }
            // Everyday controls stay on the main page; sampler/switch/raw params live behind one
            // toggle instead of being dumped next to the prompt.
            val primaryInputs = setOf(
                "prompt","width","height","length","fps","steps","noise_seed",
                "unet_name","clip_name","vae_name","lora_name","strength_model",
                "aspect_ratio","megapixels","video_length","frame_count"
            )
            if(!ambiguous) item {
                TextButton(onClick={advanced=!advanced}) {
                    Text(if(advanced) "收起高级设置" else "高级设置（Switch、采样器、原始参数）")
                }
            }
            // Megapixel presets write straight into the workflow cell, so the value used for the
            // size preview and the value submitted are always the same number.
            if(!ambiguous) analysis.bindings.firstOrNull { it.input=="megapixels" }?.let { mpB ->
                listOf(0.4, 0.5, 0.6).forEach { preset ->
                    item {
                        TextButton(enabled=!busy,onClick={
                            runCatching { save(H3WorkflowAdapter.change(source,mpB,preset)) }
                                .onFailure { message="修改失败：${it.message}" }
                        }) { Text("$preset MP") }
                    }
                }
            }
            val stepsRoutes = if (ambiguous) emptyList() else H3StepsRouting.routes(source)
            val inactiveStepsIds = stepsRoutes.mapNotNull { it.inactiveNodeId }.toSet()
            if(!ambiguous) stepsRoutes.firstOrNull { it.switchValue != null }?.let { route ->
                item {
                    Text(
                        "当前生效 Steps = ${route.activeSteps?.toInt()}（节点 #${route.activeNodeId}）；" +
                            "未生效分支 #${route.inactiveNodeId} = ${route.inactiveSteps?.toInt()}，" +
                            "切换开关在高级设置",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if(!ambiguous) items(fieldBindings.filter {
                    (advanced || it.input in primaryInputs) && (advanced || it.nodeId !in inactiveStepsIds)
                },key={it.key}) { binding ->
                val node=H3WorkflowAdapter.nodes(JSONObject(source)).getJSONObject(binding.nodeId)
                val options=(models.nodeDefinitionsRevision).let {
                    ConnectionManager.nodeTypeRegistry.getInputDefinition(node.getString("class_type"),binding.input)?.options ?: emptyList()
                }
                val files=when(binding.input) {
                    "unet_name" -> models.unets
                    "clip_name" -> models.clips.ifEmpty { models.textEncoders }
                    "vae_name" -> models.vaes
                    "lora_name" -> models.loras
                    else -> emptyList()
                }
                H3Field(binding,options,files,!busy,
                    floating = ConnectionManager.nodeTypeRegistry.getInputDefinition(node.getString("class_type"),binding.input)?.type=="FLOAT" || node.getString("class_type")=="PrimitiveFloat" || binding.input=="strength_model") { value ->
                    runCatching { save(H3WorkflowAdapter.change(source,binding,value)) }.onFailure { message="修改失败：${it.message}" }
                }
            }
            if(!ambiguous) item {
                TextButton(enabled=!busy,onClick={exportOriginal=false;exporter.launch("H3_${analysis.mode}_working.json")}) {Text("导出 H3 工作副本")}
                TextButton(enabled=!busy,onClick={exportOriginal=true;exporter.launch("H3_${analysis.mode}_original.json")}) {Text("导出 H3 原始导入")}
                Text("Steps 的普通/加速分支分别保留；LoRA 按工作流实际加载器识别。未识别参数可在专业编辑器编辑。")
                OutlinedButton(enabled=!busy,onClick={ runCatching { source=store.reset(workflowId) }.onFailure { message=it.message ?: "重置失败" } }) { Text("恢复 H3 原始副本") }
                Button(enabled=!busy && client!=null,onClick={
                    runCatching { prepare() }.onSuccess { review=it;reviewedClient=client }.onFailure { message="无法提交：${it.message}" }
                }) { Text("检查并提交生成") }
                Text("提交前会列出实际差异；任务与视频结果在原有任务队列、资产页查看。")
            }
        }
    }
    if(review!=null) {
        val p=review!!
        AlertDialog(onDismissRequest={review=null},title={Text("确认提交 ${analysis.mode}")},text={
            Column {
                Text("将向当前服务器提交以下变更（节点 ${p.preview.nodesBefore} → ${p.preview.nodesAfter}）：",
                    style=MaterialTheme.typography.bodySmall)
                if(p.preview.isEmpty) Text("未检测到字段变更，将按当前工作副本提交。",style=MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max=320.dp).padding(top=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    items(p.preview.lines) { line ->
                        Column {
                            Text(line.title,style=MaterialTheme.typography.labelLarge)
                            Text(line.detail,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },confirmButton={TextButton(onClick={
            val selectedClient=reviewedClient;val prompt=p.json;review=null
            if(selectedClient==null || selectedClient!==ConnectionManager.clientOrNull) message="服务器已切换，请重新检查"
            else {
                busy=true
                val result: (Boolean,String?,String?) -> Unit = { ok,id,error -> main.post {
                    busy=false
                    if(selectedClient!==ConnectionManager.clientOrNull) message="服务器已切换，请在原服务器查看已提交任务"
                    else if(ok && id!=null) {
                        if(generationViewModel==null) {
                            JobRegistry.registerJob(id,if(analysis.mode==H3WorkflowAdapter.Mode.T2V) "TEXT_TO_VIDEO" else "IMAGE_TO_VIDEO",ContentType.VIDEO)
                            JobRegistry.saveState(context)
                        }
                                                // PENDING-SAVE: remember the job so a restart or a dropped WebSocket
                        // can re-query /history for exactly this prompt.
                        runCatching {
                            sh.hnet.comfychair.workflow.PendingJobStore(localContext).save(
                                sh.hnet.comfychair.workflow.PendingJobStore.PendingJob(
                                    id, selectedClient.getBaseUrl().orEmpty(),
                                    analysis.mode.name, System.currentTimeMillis(), "QUEUED"))
                        }
                        message="已提交，prompt_id=$id"
                    } else message="提交失败：${error ?: "未返回 prompt_id"}"
                } }
                if(generationViewModel!=null) {
                    if(generationViewModel.getClient()!==selectedClient) {busy=false;message="连接状态尚未同步，请重新打开面板"}
                    else generationViewModel.startGeneration(prompt,if(analysis.mode==H3WorkflowAdapter.Mode.T2V) "TEXT_TO_VIDEO" else "IMAGE_TO_VIDEO",ContentType.VIDEO,front=front,onResult=result)
                } else {
                    val preflight = sh.hnet.comfychair.workflow.SubmitPreflight.prepare(prompt) { ct, key ->
                        sh.hnet.comfychair.connection.ConnectionManager.nodeTypeRegistry
                            .getInputDefinition(ct, key)?.options
                    }
                    if (!preflight.ok) result(false, null, "提交前检查未通过：${preflight.issues.joinToString("；") { it.toString() }}")
                    else selectedClient.submitPrompt(preflight.prompt, front = front) { ok, id, error, _ -> result(ok, id, error) }
                }
            }
        }) {Text("提交")}},dismissButton={TextButton(onClick={review=null}) {Text("取消")}})
    }
}

@Composable
private fun H3Field(binding:H3WorkflowAdapter.Binding,options:List<String>,files:List<String>,enabled:Boolean,floating:Boolean=false,onSave:(Any)->Unit) {
    var draft by remember(binding.key,binding.value) { mutableStateOf(binding.value.toString()) }
    var choose by remember { mutableStateOf(false) }
    var fileChoice by remember { mutableStateOf(false) }
    val editable = enabled && !binding.readOnly
    Column {
        Text(binding.label,style=MaterialTheme.typography.titleSmall)
        if(binding.note!=null) Text(binding.note,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
        if(binding.value is Boolean) Row {
            Switch(enabled=editable,checked=binding.value,onCheckedChange={onSave(it)})
            Text(if(binding.value) "开启" else "关闭")
        } else {
            if(files.isNotEmpty() && editable) TextButton(onClick={fileChoice=true;choose=true}) {Text(if (files.isEmpty()) "选择模型文件" else "选择模型文件（${files.size}）")}
            if(options.isNotEmpty() && editable) TextButton(onClick={fileChoice=false;choose=true}) {Text(if (options.isEmpty()) "选择可选值" else "从服务器选项中选择（${options.size}）")}
            OutlinedTextField(value=draft,onValueChange={draft=it},enabled=editable,modifier=Modifier.fillMaxWidth(),minLines=if(binding.kind=="prompt") 4 else 1,maxLines=if(binding.kind=="prompt") 8 else 2)
            val typed:Any?=when {
                binding.input=="noise_seed" -> draft.toLongOrNull()
                floating -> draft.toDoubleOrNull()?.takeIf { it.isFinite() }
                binding.value is Int -> draft.toIntOrNull()
                binding.value is Long -> draft.toLongOrNull()
                binding.value is Number -> draft.toDoubleOrNull()?.takeIf { it.isFinite() }
                else -> draft
            }
            TextButton(enabled=editable && typed!=null && draft!=binding.value.toString(),onClick={typed?.let(onSave)}) {Text("保存参数")}
        }
    }
    if(choose) AlertDialog(onDismissRequest={choose=false},title={Text(binding.label)},text={LazyColumn(Modifier.heightIn(max=360.dp)) {items(if(fileChoice) files else options) {option -> TextButton(onClick={draft=option;choose=false}) {Text(option)}}}},confirmButton={TextButton(onClick={choose=false}) {Text("关闭")}})
}

@Composable
private fun H3ImagePreview(workflowId:String, filename:String, revision:Int) {
    val context=LocalContext.current
    val client=ConnectionManager.clientOrNull
    var bitmap by remember(client,filename,revision) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember(client,filename,revision) { mutableStateOf(false) }
    DisposableEffect(client,filename,revision) {
        var active=true
        val main=Handler(Looper.getMainLooper())
        val file=java.io.File(context.filesDir,"h3_previews/$workflowId/${UUID.nameUUIDFromBytes(filename.toByteArray())}.png")
        if(file.exists()) bitmap=BitmapFactory.decodeFile(file.path)
        else if(client!=null && filename.isNotEmpty()) {
            val normalized=filename.replace('\\','/')
            client.fetchImage(filename=normalized.substringAfterLast('/'),subfolder=normalized.substringBeforeLast('/',""),type="input") { image,_ ->
                main.post { if(active && client===ConnectionManager.clientOrNull) {bitmap=image;error=image==null} }
            }
        }
        onDispose {active=false}
    }
    if(bitmap!=null) Image(bitmap!!.asImageBitmap(),"服务器参考图片预览",Modifier.fillMaxWidth().height(140.dp))
    else if(filename.isEmpty()) Text("该接口暂无图片文件名",style=MaterialTheme.typography.labelSmall)
    else Text(if(error) "图片预览失败，请检查当前服务器素材" else "正在加载图片预览…",style=MaterialTheme.typography.labelSmall)
}
