package sh.hnet.comfychair.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.model.ContentProfile
import sh.hnet.comfychair.model.SfwGuard
import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.util.DebugLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 剧情词库的存取。
 *
 * 和标签词库不同，这里没有三路合并：剧情词库是一份整体，导入就整体替换，旧的留一份备份。
 * 合并逻辑放到这里只会拼出怪句——句子是连贯的，半新半旧读起来就是错的。
 *
 * 随包内置一份 assets/story_catalog.json，首次启动写到 filesDir；之后以本地副本为准，
 * 只有「恢复内置」才会把它扔掉。
 */
class StoryLibraryStore private constructor(
    private val context: Context,
    val profile: ContentProfile = ContentProfile.NSFW
) {

    // 与标签词库同一条规矩：所有派生文件成套按档位取名，漏一个就会让两档互相污染。
    private val workingFile: File get() = File(context.filesDir, "${profile.storyStem}.json")
    private val backupFile: File get() = File(context.filesDir, "${profile.storyStem}.backup.json")
    /** 用户手动导入过就留个记号，之后不再被随包词库悄悄顶掉。 */
    private val importMark: File get() = File(context.filesDir, "${profile.storyStem}.imported")

    /** 自定义词单独存一份，换词库和还原内置都不碰它。 */
    private val customFile: File get() = File(context.filesDir, profile.storyCustomName)
    private var custom: MutableMap<String, MutableList<String>> = mutableMapOf()
    private var excluded: MutableMap<String, MutableSet<String>> = mutableMapOf()
    private var removed: MutableMap<String, MutableSet<String>> = mutableMapOf()

    /** 不带覆写的那份，增删都以它为底重新合并。 */
    private var base: StoryLibrary = readInitial()

    private val _library = MutableStateFlow(base.withCustom(custom, excluded, removed))
    val library: StateFlow<StoryLibrary> = _library.asStateFlow()

    init {
        readOverrides()
        _library.value = base.withCustom(custom, excluded, removed)
    }

    private fun readOverrides() {
        if (!customFile.exists()) return
        runCatching {
            val root = JSONObject(customFile.readText(Charsets.UTF_8))
            custom = readGroup(root.optJSONObject("add") ?: root)
            excluded = readSetGroup(root.optJSONObject("exclude"))
            removed = readSetGroup(root.optJSONObject("remove"))
        }
    }

    private fun readGroup(node: JSONObject?): MutableMap<String, MutableList<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        node?.keys()?.forEach { pool ->
            val list = node.optJSONArray(pool) ?: return@forEach
            out[pool] = (0 until list.length()).map { list.getString(it) }.toMutableList()
        }
        return out
    }

    private fun readSetGroup(node: JSONObject?): MutableMap<String, MutableSet<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        node?.keys()?.forEach { pool ->
            val list = node.optJSONArray(pool) ?: return@forEach
            out[pool] = (0 until list.length()).map { list.getString(it) }.toMutableSet()
        }
        return out
    }

    private fun writeOverrides() {
        val root = JSONObject()
        val addNode = JSONObject()
        custom.forEach { (pool, words) -> addNode.put(pool, JSONArray(words)) }
        val offNode = JSONObject()
        excluded.forEach { (pool, words) -> offNode.put(pool, JSONArray(words.toList())) }
        val goneNode = JSONObject()
        removed.forEach { (pool, words) -> goneNode.put(pool, JSONArray(words.toList())) }
        root.put("add", addNode)
        root.put("exclude", offNode)
        root.put("remove", goneNode)
        runCatching { customFile.writeText(root.toString(2), Charsets.UTF_8) }
    }

    private fun publish() {
        _library.value = base.withCustom(custom, excluded, removed)
    }

    /** 加一个自己写的词。空串、重复的都不收。 */
    fun addCustom(pool: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // 判重要拿「看得见的池子」：删掉的词还在 base 里，不加这个判断就加不回来。
        val gone = removed[pool].orEmpty()
        if (base.pool(pool).any { it.text == trimmed && trimmed !in gone }) return false
        val list = custom.getOrPut(pool) { mutableListOf() }
        if (trimmed in list) return false
        removed[pool]?.remove(trimmed)
        list += trimmed
        writeOverrides()
        publish()
        return true
    }

    /** 垃圾桶：从列表里拿掉，内置词也能删。 */
    fun removeWord(pool: String, text: String) {
        val mine = custom[pool]
        if (mine != null && mine.remove(text)) {
            if (mine.isEmpty()) custom.remove(pool)
        } else {
            removed.getOrPut(pool) { mutableSetOf() } += text
        }
        excluded[pool]?.remove(text)
        writeOverrides()
        publish()
    }

    /** 眼睛：还在列表里，但不参与随机。 */
    fun toggleExcluded(pool: String, text: String) {
        val set = excluded.getOrPut(pool) { mutableSetOf() }
        if (!set.remove(text)) set += text
        if (set.isEmpty()) excluded.remove(pool)
        writeOverrides()
        publish()
    }

    /** 还原内置时清掉排除和删除的记录，自己加的词保留。 */
    fun clearOverrides() {
        excluded = mutableMapOf()
        removed = mutableMapOf()
        writeOverrides()
        publish()
    }

    private fun republish(library: StoryLibrary) {
        base = library
        publish()
    }

    private fun readInitial(): StoryLibrary {
        val bundled = runCatching { StoryLibraryJson.parse(bundledText()) }.getOrNull()
        if (workingFile.exists()) {
            // 与标签词库同口径：本地副本原样载入，不因禁词丢弃。
            val local = runCatching { StoryLibraryJson.parse(workingFile.readText(Charsets.UTF_8)) }.getOrNull()
            if (local != null) {
                // 随包词库换了版或补齐了旧版漏写的互斥/非插入标记才顶掉本地那份；用户自己导入的不动。
                val staleData = bundled != null && (
                    (local.mutex.isEmpty() && bundled.mutex.isNotEmpty()) ||
                        (local.pool("act").all { it.penetrating } && bundled.pool("act").any { !it.penetrating })
                    )
                if (bundled != null && !importMark.exists() && (newerThan(bundled.version, local.version) || staleData)) {
                    runCatching { backupFile.writeText(workingFile.readText(Charsets.UTF_8), Charsets.UTF_8) }
                    runCatching { writeAtomic(workingFile, StoryLibraryJson.write(bundled)) }
                    return bundled
                }
                return local
            }
            workingFile.delete()
        }
        val fresh = bundled ?: return StoryLibrary()
        runCatching { writeAtomic(workingFile, StoryLibraryJson.write(fresh)) }
        return fresh
    }

    /** 0.9.8.8382.10 要比 0.9.8.8382.9 新，按段比数字。 */
    private fun newerThan(candidate: String, current: String): Boolean {
        if (current.isBlank()) return true
        val a = candidate.split('.')
        val b = current.split('.')
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(index)?.toIntOrNull() ?: 0
            val y = b.getOrNull(index)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** 导入的文件自报的是另一个档，先让用户确认再收。 */
    class ProfileMismatch(val found: ContentProfile) : IllegalArgumentException("档位不符")

    fun importFrom(uri: Uri, force: Boolean = false): Result<StoryLibrary> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("读不到这个文件")
        val incoming = StoryLibraryJson.parse(text)
        require(incoming.pools.isNotEmpty() && incoming.casts.isNotEmpty()) {
            "这份文件不像剧情词库"
        }
        // 与标签词库同口径：跨档问一句，但不当成硬拒绝。
        val declared = when (incoming.profile) {
            "sfw" -> ContentProfile.SFW
            "nsfw" -> ContentProfile.NSFW
            else -> ContentProfile.fromRating(incoming.rating)
        }
        if (!force && declared != profile) throw ProfileMismatch(declared)
        if (workingFile.exists()) {
            runCatching { backupFile.writeText(workingFile.readText(Charsets.UTF_8), Charsets.UTF_8) }
        }
        writeAtomic(workingFile, StoryLibraryJson.write(incoming))
        runCatching { importMark.writeText(incoming.version, Charsets.UTF_8) }
        republish(incoming)
        incoming
    }

    fun exportTo(uri: Uri): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(StoryLibraryJson.write(_library.value).toByteArray(Charsets.UTF_8))
        } ?: error("写不进这个位置")
    }

    fun resetToDefault(): StoryLibrary {
        val bundled = runCatching { StoryLibraryJson.parse(bundledText()) }
            .getOrElse { return _library.value }
        if (workingFile.exists()) {
            runCatching { backupFile.writeText(workingFile.readText(Charsets.UTF_8), Charsets.UTF_8) }
            workingFile.delete()
        }
        writeAtomic(workingFile, StoryLibraryJson.write(bundled))
        runCatching { importMark.delete() }
        // 还原本就该把「排除」和「删掉」两笔账一起清掉，否则删过的词永远回不来。
        // 你自己加的词不动。
        excluded = mutableMapOf()
        removed = mutableMapOf()
        writeOverrides()
        republish(bundled)
        return _library.value
    }

    private fun writeAtomic(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("写不进 ${target.name}")
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            error("写不进 ${target.name}")
        }
    }

    private fun bundledText(): String =
        context.assets.open(profile.storyAsset).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }

    companion object {
        private const val TAG = "StoryLibraryStore"
        private val instances = mutableMapOf<ContentProfile, StoryLibraryStore>()

        fun get(context: Context, profile: ContentProfile = ContentProfile.NSFW): StoryLibraryStore =
            synchronized(this) {
                instances.getOrPut(profile) {
                    StoryLibraryStore(context.applicationContext, profile)
                }
            }
    }
}
