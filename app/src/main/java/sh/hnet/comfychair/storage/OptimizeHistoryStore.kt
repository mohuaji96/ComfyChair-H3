package sh.hnet.comfychair.storage

import android.content.Context
import sh.hnet.comfychair.util.DebugLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * One finished rewrite, kept for the history list.
 *
 * Only text is stored. The optimiser can be pointed at adult prompts, so nothing here ever goes
 * near a thumbnail, a notification or a backup.
 */
data class OptimizeRecord(
    val id: String,
    val at: Long,
    val templateName: String,
    val input: String,
    val output: String,
    /** The model's own one-line report, already stripped of its [调整] prefix. */
    val note: String,
    val favorite: Boolean = false
) {
    val inChars: Int get() = input.length
    val outChars: Int get() = output.length
}

/**
 * Rewrite history: newest first, capped, favourite-aware.
 *
 * The cap is what keeps this usable — an unbounded list turns into a junk drawer within weeks.
 * When the cap is hit the oldest record that is *not* starred goes first, so a starred entry
 * survives a busy week.
 */
object OptimizeHistoryStore {
    private const val TAG = "OptimizeHistory"
    private const val FILE = "optimize_history.json"

    /** Shown in the UI as "24/100"; the eviction rule below depends on it. */
    const val MAX_RECORDS = 100

    private const val NOTE_PREFIX = "[调整]"

    /**
     * Pulls the model's report line out of the body.
     *
     * The line is part of the contract, not of the prompt, so it must never reach the clipboard or
     * the generate button. A model that forgets it simply yields an empty note.
     */
    fun splitNote(text: String): Pair<String, String> {
        val withoutNote = StringBuilder()
        var note = ""
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (note.isEmpty() && trimmed.startsWith(NOTE_PREFIX)) {
                note = trimmed.removePrefix(NOTE_PREFIX).trim().trimStart(':', '：').trim()
            } else {
                withoutNote.append(line).append('\n')
            }
        }
        return withoutNote.toString().trim() to note
    }

    fun all(context: Context): List<OptimizeRecord> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                OptimizeRecord(
                    id = row.optString("id"),
                    at = row.optLong("at"),
                    templateName = row.optString("template"),
                    input = row.optString("input"),
                    output = row.optString("output"),
                    note = row.optString("note"),
                    favorite = row.optBoolean("favorite")
                )
            }.filter { it.id.isNotEmpty() }
        }.getOrElse {
            DebugLogger.w(TAG, "read failed: ${it.message}")
            emptyList()
        }
    }

    fun add(context: Context, record: OptimizeRecord): List<OptimizeRecord> {
        val list = (listOf(record) + all(context)).toMutableList()
        while (list.size > MAX_RECORDS) {
            // Oldest unstarred first; everything starred means the oldest one goes anyway.
            val victim = list.indexOfLast { !it.favorite }.takeIf { it >= 0 } ?: list.lastIndex
            list.removeAt(victim)
        }
        return write(context, list)
    }

    fun toggleFavorite(context: Context, id: String): List<OptimizeRecord> {
        val list = all(context).map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
        return write(context, list)
    }

    fun remove(context: Context, id: String): List<OptimizeRecord> =
        write(context, all(context).filterNot { it.id == id })

    fun clear(context: Context) {
        file(context).delete()
        DebugLogger.i(TAG, "history cleared")
    }

    fun newRecord(
        templateName: String,
        input: String,
        output: String,
        note: String
    ): OptimizeRecord = OptimizeRecord(
        id = UUID.randomUUID().toString(),
        at = System.currentTimeMillis(),
        templateName = templateName,
        input = input,
        output = output,
        note = note
    )

    private fun write(context: Context, list: List<OptimizeRecord>): List<OptimizeRecord> {
        val array = JSONArray()
        list.forEach { record ->
            array.put(
                JSONObject()
                    .put("id", record.id)
                    .put("at", record.at)
                    .put("template", record.templateName)
                    .put("input", record.input)
                    .put("output", record.output)
                    .put("note", record.note)
                    .put("favorite", record.favorite)
            )
        }
        runCatching { file(context).writeText(array.toString()) }
            .onFailure { DebugLogger.w(TAG, "write failed: ${it.message}") }
        return list
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}
