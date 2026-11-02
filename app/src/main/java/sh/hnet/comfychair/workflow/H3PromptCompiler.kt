package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/** Execution-copy-only rewiring. Ambiguous virtual/bypass paths fail instead of dropping inputs. */
object H3PromptCompiler {
    private val transparent = setOf("Reroute", "ReroutePrimitive", "Relay", "Repeater")
    /** Removed frontend helper: node id to class_type, in removal order. */
    data class CompileResult(val json: String, val removed: List<Pair<String, String>>)
    fun compile(source: String, definition: (String) -> NodeTypeDefinition?): String =
        compileDetailed(source, definition).json
    fun compileDetailed(source: String, definition: (String) -> NodeTypeDefinition?): CompileResult {
        val doc=JSONObject(source);val nodes=H3WorkflowAdapter.nodes(doc)
        val ids=nodes.keys().asSequence().toList()
        val virtual=ids.filter { id ->
            val node=nodes.getJSONObject(id)
            node.optInt("mode",0)==4 || (node.optString("class_type") in transparent && definition(node.optString("class_type"))==null)
        }.toSet()
        fun resolve(value: Any, seen: Set<String>): Any {
            if(value !is JSONArray || value.length()!=2 || value.opt(1) !is Number) return value
            val id=value.optString(0)
            if(id !in virtual) { require(nodes.has(id)) { "连接引用不存在的节点 #$id" };return value }
            require(id !in seen) { "前端/绕过节点出现循环：#$id" }
            val node=nodes.getJSONObject(id);val inputs=node.optJSONObject("inputs") ?: error("节点 #$id 无输入")
            val type=node.optString("class_type");val def=definition(type)
            val output=value.getInt(1)
            val expected=def?.outputs?.getOrNull(output)?.type
            val candidates=inputs.keys().asSequence().filter { key ->
                inputs.opt(key) is JSONArray && (expected==null || def?.inputs?.any { it.name==key && it.type==expected }==true)
            }.toList()
            require((expected!=null || output==0) && candidates.size==1) {
                "节点 #$id $type 的输出 $output 无唯一透传来源，请在专业编辑器明确连接；未删除任何原始数据"
            }
            return resolve(inputs.get(candidates.single()),seen+id)
        }
        ids.filter { it !in virtual }.forEach { id ->
            val inputs=nodes.getJSONObject(id).optJSONObject("inputs") ?: return@forEach
            inputs.keys().asSequence().toList().forEach { key -> inputs.put(key,resolve(inputs.get(key),emptySet())) }
        }
        val removed=virtual.map { it to nodes.getJSONObject(it).optString("class_type") }
        virtual.forEach {nodes.remove(it)}
        // ComfyUI validates output ancestors; disconnected editor helpers are retained, not executed.
        val active=mutableSetOf<String>()
        fun visit(id:String) {
            if(!active.add(id)) return
            val ins=nodes.optJSONObject(id)?.optJSONObject("inputs") ?: return
            ins.keys().asSequence().forEach { key -> (ins.opt(key) as? JSONArray)?.let { if(it.length()==2 && it.opt(1) is Number) visit(it.optString(0)) } }
        }
        nodes.keys().asSequence().filter { nodes.getJSONObject(it).optString("class_type")=="SaveVideo" }.forEach(::visit)
        require(active.isNotEmpty()) { "未识别到 SaveVideo 输出节点，请使用专业编辑器确认输出" }
        val errors=mutableListOf<String>()
        nodes.keys().asSequence().forEach { id ->
            if(id !in active) return@forEach
            val node=nodes.getJSONObject(id);val type=node.optString("class_type")
            val def=definition(type)
            if(def==null) {
                errors += if(type in transparent || type.contains("Relay") || type.contains("Repeater"))
                    "前端辅助节点 #$id $type 不能安全透传，请先导出 API 工作流"
                else "服务器未提供节点定义 #$id $type"
            } else {
                val inputs=node.optJSONObject("inputs") ?: JSONObject()
                def.inputs.filter {it.isRequired}.forEach { input ->
                    if(!inputs.has(input.name) && !inputs.keys().asSequence().any { it.startsWith(input.name+".") }) errors += "缺少必填输入 #$id.${input.name}"
                }
                inputs.keys().asSequence().forEach { key ->
                    val input=def.inputs.firstOrNull {it.name==key} ?: return@forEach
                    val value=inputs.get(key)
                    if(value !is JSONArray) {
                        if(!(type=="LoadImage" && key=="image") && !input.options.isNullOrEmpty() && value.toString() !in input.options.orEmpty()) errors += "#$id.$key 不在服务器合法 COMBO 选项中"
                        if(value is Number) {
                            if(input.min!=null && value.toDouble()<input.min.toDouble()) errors += "#$id.$key 低于服务器最小值"
                            if(input.max!=null && value.toDouble()>input.max.toDouble()) errors += "#$id.$key 超过服务器最大值"
                        }
                    }
                }
            }
        }
        require(errors.isEmpty()) {errors.distinct().joinToString("\n")}
        return CompileResult(doc.toString(2), removed)
    }
}
