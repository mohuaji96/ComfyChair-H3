package sh.hnet.comfychair.storage

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import sh.hnet.comfychair.workflow.LiteGraphSourceEditor
import java.io.File
import java.util.UUID

/** Independent LiteGraph origin and revision vault; does not replace API JSON storage. */
class LiteGraphSourceStore(context: Context) {
    private val root = File(context.filesDir, "litegraph_sources")

    private fun workflowDirectory(id: String): File {
        // Workflow IDs are application-generated UUIDs, never filesystem paths.
        require(Regex("[a-zA-Z0-9_-]{1,128}").matches(id)) { "Unsafe workflow ID" }
        return File(root, id)
    }
    private fun atomicWrite(file: File, content: String) {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true) {
            "Could not create LiteGraph storage directory"
        }
        val target = AtomicFile(file)
        val stream = target.startWrite()
        try {
            stream.write(content.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
    }

    /** Immutable import; an existing origin is never overwritten. */
    @Synchronized fun saveOriginal(workflowId: String, source: String) {
        val json = JSONObject(source)
        require(json.optJSONArray("nodes") != null) { "Source is not LiteGraph" }
        val file = File(workflowDirectory(workflowId), "original.json")
        require(!file.exists()) { "LiteGraph origin already exists for $workflowId" }
        atomicWrite(file, source) // Exact original bytes, including custom fields and layout.
    }

    fun readOriginal(workflowId: String): String? =
        File(workflowDirectory(workflowId), "original.json").takeIf { it.exists() }?.readText()

    fun readWorking(workflowId: String): String? {
        val dir = workflowDirectory(workflowId)
        return File(dir, "working.json").takeIf { it.exists() }?.readText()
            ?: File(dir, "original.json").takeIf { it.exists() }?.readText()
    }

    /** Each successful change creates a restorable snapshot; the origin remains intact. */
    @Synchronized fun changeWidget(workflowId: String, nodeId: Int, key: String, value: Any): LiteGraphSourceEditor.WidgetChange {
        val current = readWorking(workflowId) ?: error("Original LiteGraph workflow not found")
        val changed = LiteGraphSourceEditor.changeTopLevelWidget(current, nodeId, key, value)
        val directory = workflowDirectory(workflowId)
        // Save snapshot before replacing the current working copy.
        atomicWrite(File(directory, "revisions/${UUID.randomUUID()}.json"), current)
        atomicWrite(File(directory, "working.json"), changed.json)
        return changed
    }

    /** Enumerate restorable snapshots without allowing arbitrary paths. */
    fun listRevisionIds(workflowId: String): List<String> =
        File(workflowDirectory(workflowId), "revisions")
            .listFiles { candidate -> candidate.isFile && candidate.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removeSuffix(".json") } ?: emptyList()

    @Synchronized fun restoreRevision(workflowId: String, revisionId: String): String {
        require(Regex("[a-fA-F0-9-]{36}").matches(revisionId)) { "Invalid revision ID" }
        val target = File(workflowDirectory(workflowId), "revisions/$revisionId.json")
        require(target.isFile) { "Revision not found" }
        val restored = target.readText()
        require(JSONObject(restored).optJSONArray("nodes") != null) { "Invalid revision data" }
        val current = readWorking(workflowId) ?: error("Original workflow not found")
        atomicWrite(File(workflowDirectory(workflowId), "revisions/${UUID.randomUUID()}.json"), current)
        atomicWrite(File(workflowDirectory(workflowId), "working.json"), restored)
        return restored
    }

    @Synchronized fun resetWorkingToOriginal(workflowId: String): String {
        val original = exportOriginal(workflowId)
        val current = readWorking(workflowId)
        if (current != null && current != original) {
            atomicWrite(File(workflowDirectory(workflowId), "revisions/${UUID.randomUUID()}.json"), current)
        }
        atomicWrite(File(workflowDirectory(workflowId), "working.json"), original)
        return original
    }

    /** Return the original document without touching revisions. */
    fun exportOriginal(workflowId: String): String = readOriginal(workflowId)
        ?: error("No LiteGraph original available")
}
