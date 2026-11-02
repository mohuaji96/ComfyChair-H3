package sh.hnet.comfychair.storage

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import sh.hnet.comfychair.workflow.H3WorkflowAdapter

/** API origins are separate from LiteGraph origins and from prepared prompts. */
class H3SourceStore(context: Context) {
    private val root=File(context.filesDir,"h3_api_sources")
    private fun dir(id: String): File { require(Regex("[a-zA-Z0-9_-]{1,128}").matches(id));return File(root,id) }
    private fun write(file: File, text: String) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try { stream.write(text.toByteArray());atomic.finishWrite(stream) } catch(e:Exception) { atomic.failWrite(stream);throw e }
    }
    @Synchronized fun open(id: String, source: String): String {
        require(H3WorkflowAdapter.inspect(source)!=null)
        val origin=File(dir(id),"original.json")
        if(!origin.exists()) write(origin,source)
        return File(dir(id),"working.json").takeIf { it.exists() }?.readText() ?: origin.readText()
    }
    @Synchronized fun save(id: String, source: String) {
        require(H3WorkflowAdapter.inspect(source)!=null)
        write(File(dir(id),"working.json"),source)
    }
    fun original(id: String): String = File(dir(id),"original.json").readText()
    @Synchronized fun reset(id: String): String {
        val source=File(dir(id),"original.json").readText();save(id,source);return source
    }
    fun recordPrompt(id: String, source: String) = write(File(dir(id),"last_prepared_prompt.json"),source)
    /** Merge only explicit H3 changes, preserving subsequent professional-editor edits. */
    fun apply(id: String, prepared: String): String = applyDetailed(id, prepared).json

    /** Same merge, but with the applied differences so the UI can preview them first. */
    fun applyDetailed(id: String, prepared: String): sh.hnet.comfychair.workflow.H3ApiPatch.PatchResult {
        val originFile=File(dir(id),"original.json");val work=File(dir(id),"working.json")
        if(!originFile.exists() || !work.exists())
            return sh.hnet.comfychair.workflow.H3ApiPatch.PatchResult(prepared, emptyList(), emptyList())
        return sh.hnet.comfychair.workflow.H3ApiPatch.applyDetailed(originFile.readText(),work.readText(),prepared)
    }
}
