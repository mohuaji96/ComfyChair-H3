package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * Which submissions belong to a 连拍 chain.
 *
 * The server only knows filenames, and a user may rename the prefix, so the tag is kept here on
 * the phone: promptId -> chain name and position. The gallery filters on this set, which means a
 * chain stays recognisable even if its files were named by hand.
 */
object SequenceStore {

    private const val TAG = "SequenceStore"
    private const val FILE_NAME = "h3_sequences.json"
    private const val MAX_ENTRIES = 500

    data class Entry(val promptId: String, val name: String, val position: Int, val at: Long)

    private var loaded: MutableList<Entry>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun all(context: Context): MutableList<Entry> {
        loaded?.let { return it }
        val out = mutableListOf<Entry>()
        try {
            val f = file(context)
            if (f.exists()) {
                val array = JSONArray(f.readText())
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val id = row.optString("promptId")
                    if (id.isBlank()) continue
                    out.add(Entry(id, row.optString("name"), row.optInt("position"), row.optLong("at")))
                }
            }
        } catch (error: Exception) {
            DebugLogger.w(TAG, "Read failed: ${error.javaClass.simpleName}")
        }
        loaded = out
        return out
    }

    private fun persist(context: Context, entries: List<Entry>) {
        try {
            val array = JSONArray()
            entries.takeLast(MAX_ENTRIES).forEach {
                array.put(JSONObject().put("promptId", it.promptId).put("name", it.name)
                    .put("position", it.position).put("at", it.at))
            }
            file(context).writeText(array.toString())
        } catch (error: Exception) {
            DebugLogger.w(TAG, "Write failed: ${error.javaClass.simpleName}")
        }
    }

    fun record(context: Context, promptId: String, name: String, position: Int) {
        if (promptId.isBlank()) return
        val entries = all(context)
        entries.removeAll { it.promptId == promptId }
        entries.add(Entry(promptId, name, position, System.currentTimeMillis()))
        persist(context, entries)
    }

    fun ids(context: Context): Set<String> = all(context).map { it.promptId }.toSet()

    fun entry(context: Context, promptId: String): Entry? = all(context).firstOrNull { it.promptId == promptId }

    /** Next position for a chain, so a resumed chain continues numbering instead of restarting. */
    fun nextPosition(context: Context, name: String): Int =
        (all(context).filter { it.name == name }.maxOfOrNull { it.position } ?: 0) + 1

    fun forget(context: Context, promptId: String) {
        val entries = all(context)
        if (entries.removeAll { it.promptId == promptId }) persist(context, entries)
    }
}
