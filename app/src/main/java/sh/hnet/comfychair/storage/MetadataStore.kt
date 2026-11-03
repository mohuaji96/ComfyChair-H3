package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.util.GenerationMetadata
import sh.hnet.comfychair.util.LoraInfo
import java.io.File

/**
 * Generation parameters extracted once from a file, kept on this phone.
 *
 * /history carries no prompt, so the sheet used to download the whole image or video again every
 * time it was opened -- and after a ComfyUI restart that data was gone entirely. The parsed result
 * is about a kilobyte, so it is stored per server and written as soon as extraction succeeds.
 *
 * Keyed by `promptId/filename`: the same prompt can emit several files, and a filename alone is
 * not unique across folders.
 */
object MetadataStore {

    private const val TAG = "MetadataStore"
    private const val FILE_PREFIX = "params_"
    private const val FILE_SUFFIX = ".json"
    private const val MAX_ENTRIES = 2000

    private val cache = mutableMapOf<String, MutableMap<String, Stored>>()

    private data class Stored(val metadata: GenerationMetadata, val at: Long)

    fun key(promptId: String, filename: String): String = "$promptId/$filename"

    private fun file(context: Context, serverId: String): File =
        File(context.filesDir, "$FILE_PREFIX$serverId$FILE_SUFFIX")

    private fun entries(context: Context, serverId: String): MutableMap<String, Stored> =
        cache.getOrPut(serverId) { load(context, serverId) }

    private fun load(context: Context, serverId: String): MutableMap<String, Stored> {
        val out = LinkedHashMap<String, Stored>()
        try {
            val f = file(context, serverId)
            if (!f.exists()) return out
            val array = JSONArray(f.readText())
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val k = row.optString("key")
                if (k.isBlank()) continue
                decode(row.optJSONObject("value"))?.let { out[k] = Stored(it, row.optLong("at")) }
            }
        } catch (error: Exception) {
            DebugLogger.w(TAG, "Read failed: ${error.javaClass.simpleName}")
        }
        return out
    }

    /** Newest wins when the file is over the cap; a phone that generates daily must not grow forever. */
    private fun trim(entries: MutableMap<String, Stored>) {
        if (entries.size <= MAX_ENTRIES) return
        val keep = entries.entries.sortedByDescending { it.value.at }.take(MAX_ENTRIES)
        entries.clear()
        keep.forEach { entries[it.key] = it.value }
    }

    private fun persist(context: Context, serverId: String, entries: Map<String, Stored>) {
        try {
            val array = JSONArray()
            entries.forEach { (k, v) ->
                array.put(JSONObject().put("key", k).put("at", v.at).put("value", encode(v.metadata)))
            }
            file(context, serverId).writeText(array.toString())
        } catch (error: Exception) {
            DebugLogger.w(TAG, "Write failed: ${error.javaClass.simpleName}")
        }
    }

    fun get(context: Context, serverId: String, promptId: String, filename: String): GenerationMetadata? =
        entries(context, serverId)[key(promptId, filename)]?.metadata

    fun put(
        context: Context,
        serverId: String,
        promptId: String,
        filename: String,
        metadata: GenerationMetadata
    ) {
        val entries = entries(context, serverId)
        entries[key(promptId, filename)] = Stored(metadata, System.currentTimeMillis())
        trim(entries)
        persist(context, serverId, entries)
    }

    fun forget(context: Context, serverId: String, promptId: String, filename: String) {
        val entries = entries(context, serverId)
        if (entries.remove(key(promptId, filename)) != null) persist(context, serverId, entries)
    }

    fun count(context: Context, serverId: String): Int = entries(context, serverId).size

    private fun encode(m: GenerationMetadata): JSONObject = JSONObject().apply {
        m.positivePrompt?.let { put("positive", it) }
        m.negativePrompt?.let { put("negative", it) }
        m.seed?.let { put("seed", it) }
        m.steps?.let { put("steps", it) }
        m.cfg?.let { put("cfg", it) }
        m.sampler?.let { put("sampler", it) }
        m.scheduler?.let { put("scheduler", it) }
        m.width?.let { put("width", it) }
        m.height?.let { put("height", it) }
        if (m.models.isNotEmpty()) put("models", JSONArray().also { a -> m.models.forEach { a.put(it) } })
        if (m.vaes.isNotEmpty()) put("vaes", JSONArray().also { a -> m.vaes.forEach { a.put(it) } })
        if (m.unets.isNotEmpty()) put("unets", JSONArray().also { a -> m.unets.forEach { a.put(it) } })
        if (m.loras.isNotEmpty()) put("loras", JSONArray().also { a ->
            m.loras.forEach { a.put(JSONObject().put("name", it.name).put("strength", it.strength)) }
        })
    }

    private fun decode(o: JSONObject?): GenerationMetadata? {
        if (o == null) return null
        fun list(name: String): List<String> {
            val a = o.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        }
        val loras = o.optJSONArray("loras")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { LoraInfo(it.optString("name"), it.optDouble("strength", 1.0)) }
            }
        }.orEmpty()
        return GenerationMetadata(
            positivePrompt = o.optString("positive").takeIf { it.isNotBlank() },
            negativePrompt = o.optString("negative").takeIf { it.isNotBlank() },
            seed = if (o.has("seed")) o.optLong("seed") else null,
            steps = if (o.has("steps")) o.optInt("steps") else null,
            cfg = if (o.has("cfg")) o.optDouble("cfg") else null,
            sampler = o.optString("sampler").takeIf { it.isNotBlank() },
            scheduler = o.optString("scheduler").takeIf { it.isNotBlank() },
            models = list("models"),
            loras = loras,
            width = if (o.has("width")) o.optInt("width") else null,
            height = if (o.has("height")) o.optInt("height") else null,
            vaes = list("vaes"),
            unets = list("unets")
        )
    }
}
