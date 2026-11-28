package sh.hnet.comfychair.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.model.ContentProfile
import sh.hnet.comfychair.model.SfwGuard
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.util.AppError
import sh.hnet.comfychair.util.AppErrors
import sh.hnet.comfychair.util.DebugLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    /**
     * 把自己加的词、排除、删除三笔账写下去；写不成返回 false。
     *
     * 以前失败被 runCatching 吞掉，随后照样发布内存状态、界面照样报成功，
     * 重启之后用户加的词凭空消失。现在失败就是失败，调用方回滚并提示。
     */
    private fun writeOverrides(): Boolean {
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
        return runCatching { writeAtomic(customFile, root.toString(2)) }
            .onFailure {
                AppErrors.report(
                    AppError(context.getString(R.string.err_st_write_file, customFile.name))
                )
            }
            .isSuccess
    }

    private fun publish() {
        _library.value = base.withCustom(custom, excluded, removed)
    }

    /** 加一个自己写的词。空串、重复的都不收。 */
    fun addCustom(pool: String, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // 普通档手打的新词也要过禁词表，和导入口同一把尺子。
        if (profile == ContentProfile.SFW) {
            val hit = SfwGuard.scan(context, listOf(trimmed)).firstOrNull()
            if (hit != null) {
                AppErrors.report(AppError(hit))
                return false
            }
        }
        // 判重要拿「看得见的池子」：删掉的词还在 base 里，不加这个判断就加不回来。
        val gone = removed[pool].orEmpty()
        if (base.pool(pool).any { it.text == trimmed && trimmed !in gone }) return false
        val list = custom.getOrPut(pool) { mutableListOf() }
        if (trimmed in list) return false
        val restored = removed[pool]?.remove(trimmed)
        list += trimmed
        // 写不下去就把内存里这一笔撤回：留在内存里会让界面显示加成功，重启后却不见了。
        if (!writeOverrides()) {
            list.remove(trimmed)
            if (list.isEmpty()) custom.remove(pool)
            if (restored == true) removed.getOrPut(pool) { mutableSetOf() } += trimmed
            return false
        }
        publish()
        return true
    }

    /** 垃圾桶：从列表里拿掉，内置词也能删。 */
    fun removeWord(pool: String, text: String) {
        val mine = custom[pool]
        val wasMine = mine != null && mine.contains(text)
        if (wasMine) mine.remove(text) else removed.getOrPut(pool) { mutableSetOf() } += text
        if (mine != null && mine.isEmpty()) custom.remove(pool)
        val wasExcluded = excluded[pool]?.remove(text) == true
        if (!writeOverrides()) {
            if (wasMine) custom.getOrPut(pool) { mutableListOf() } += text
            else removed[pool]?.remove(text)
            if (wasExcluded) excluded.getOrPut(pool) { mutableSetOf() } += text
            return
        }
        publish()
    }

    /** 眼睛：还在列表里，但不参与随机。 */
    fun toggleExcluded(pool: String, text: String) {
        val set = excluded.getOrPut(pool) { mutableSetOf() }
        val off = !set.remove(text)
        if (off) set += text
        if (set.isEmpty()) excluded.remove(pool)
        if (!writeOverrides()) {
            // 撤回这一次开关：写着「已排除」但没落盘，下次打开又变回来更难理解。
            if (off) excluded[pool]?.remove(text) else excluded.getOrPut(pool) { mutableSetOf() } += text
            if (excluded[pool]?.isEmpty() == true) excluded.remove(pool)
            return
        }
        publish()
    }

    /** 还原内置时清掉排除和删除的记录，自己加的词保留。 */
    fun clearOverrides() {
        val oldExcluded = excluded
        val oldRemoved = removed
        excluded = mutableMapOf()
        removed = mutableMapOf()
        if (!writeOverrides()) {
            excluded = oldExcluded
            removed = oldRemoved
            return
        }
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

    /**
     * 0.9.8.8382.10 要比 0.9.8.8382.9 新，按段比数字。
     *
     * 日常档的版本号带 -sfw 后缀（0.9.8.8382.611-sfw），整段转数字会失败退化成 0，
     * 610-sfw 和 611-sfw 就变成一样大，随包词库永远顶不掉本地那份。取每段开头的数字。
     */
    private fun newerThan(candidate: String, current: String): Boolean {
        if (current.isBlank()) return true
        val a = candidate.split('.').map { it.takeWhile(Char::isDigit) }
        val b = current.split('.').map { it.takeWhile(Char::isDigit) }
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(index)?.toIntOrNull() ?: 0
            val y = b.getOrNull(index)?.toIntOrNull() ?: 0
            if (x != y) return x > y
        }
        return false
    }

    /** 导入的文件自报的是另一个档，先让用户确认再收。 */
    class ProfileMismatch(val found: ContentProfile, message: String) : IllegalArgumentException(message)

    fun importFrom(uri: Uri, force: Boolean = false): Result<StoryLibrary> = runCatching {
        val text = readImport(uri) ?: error(context.getString(R.string.err_st_read_failed))
        val incoming = StoryLibraryJson.parse(text)
        require(incoming.pools.isNotEmpty() && incoming.casts.isNotEmpty()) {
            context.getString(R.string.err_st_not_story)
        }
        // 与标签词库同口径：跨档问一句，但不当成硬拒绝。
        val declared = when (incoming.profile) {
            "sfw" -> ContentProfile.SFW
            "nsfw" -> ContentProfile.NSFW
            else -> ContentProfile.fromRating(incoming.rating)
        }
        if (!force && declared != profile) {
            throw ProfileMismatch(declared, context.getString(R.string.err_profile_mismatch))
        }
        // 普通档：整包体检，禁词命中就拒收，本地那份一个字都不动。
        if (profile == ContentProfile.SFW) {
            val bad = SfwGuard.validate(context, incoming)
            if (bad.isNotEmpty()) throw IllegalArgumentException(bad.joinToString("\n"))
        }
        if (workingFile.exists()) {
            runCatching { backupFile.writeText(workingFile.readText(Charsets.UTF_8), Charsets.UTF_8) }
        }
        writeAtomic(workingFile, StoryLibraryJson.write(incoming))
        runCatching { importMark.writeText(incoming.version, Charsets.UTF_8) }
        republish(incoming)
        incoming
    }

    /** 与标签词库同口径：整份读进内存之前先限体积，超限当场拒绝。 */
    private fun readImport(uri: Uri): String? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) {
                    throw IllegalArgumentException(
                        context.getString(R.string.err_st_too_large, MAX_IMPORT_BYTES / (1024 * 1024))
                    )
                }
                buffer.write(chunk, 0, read)
            }
            String(buffer.toByteArray(), Charsets.UTF_8)
        }
    }

    fun exportTo(uri: Uri): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(StoryLibraryJson.write(_library.value).toByteArray(Charsets.UTF_8))
        } ?: error(context.getString(R.string.err_st_write_failed))
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

    /**
     * 换掉一个文件，旧的那份在新的一份落位之前一直留在原地。
     *
     * 以前是先删旧文件再改名：改名那一下失败，唯一的副本就没了，用户的自定义词库
     * 会直接消失。现在用覆盖式改名，失败时旧文件仍然完整，只是这次没写成。
     */
    private fun writeAtomic(target: File, text: String) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        try {
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temp.delete()
            throw IllegalStateException(context.getString(R.string.err_st_write_file, target.name), error)
        }
    }

    private fun bundledText(): String =
        context.assets.open(profile.storyAsset).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }

    companion object {
        private const val TAG = "StoryLibraryStore"
        /** 内置剧情库不到 100 KB；16 MB 足够放整包备份，再多就是选错文件。 */
        private const val MAX_IMPORT_BYTES = 16 * 1024 * 1024
        private val instances = mutableMapOf<ContentProfile, StoryLibraryStore>()

        fun get(context: Context, profile: ContentProfile = ContentProfile.NSFW): StoryLibraryStore =
            synchronized(this) {
                instances.getOrPut(profile) {
                    StoryLibraryStore(context.applicationContext, profile)
                }
            }
    }
}
