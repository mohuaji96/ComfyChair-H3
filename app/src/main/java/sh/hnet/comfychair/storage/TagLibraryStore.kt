package sh.hnet.comfychair.storage

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.model.ContentProfile
import sh.hnet.comfychair.model.SfwGuard
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.util.DebugLogger
import java.io.File

/**
 * Holds the word library.
 *
 * The bundled file in assets is the reference; the copy in filesDir is what the user edits. On
 * launch the two are compared by catalogue revision, and a newer bundled file is folded into the
 * working copy instead of being ignored — otherwise one edit made inside the app froze the library
 * forever, and every later release silently shipped its word fixes to nobody.
 */
class TagLibraryStore private constructor(
    private val context: Context,
    val profile: ContentProfile = ContentProfile.NSFW
) {

    // 六个文件名必须成套派生。只给工作副本加后缀的话，两档会共用 .pinned 与 .backup：
    // SFW 档一旦读到 NSFW 的「导入过」标记，就再也收不到内置更新，而界面上看不出来。
    private val workingFile: File get() = File(context.filesDir, "${profile.tagStem}.json")

    /** The shipped library the working copy was last reconciled against. Never edited in place. */
    private val baseFile: File get() = File(context.filesDir, "${profile.tagStem}.base.json")

    /** One deep of pre-upgrade copy, so a bad fold is recoverable without losing the edits. */
    private val backupFile: File get() = File(context.filesDir, "${profile.tagStem}.backup.json")

    /** Set when the user imported a whole library: his file outranks ours from then on. */
    private val pinnedFile: File get() = File(context.filesDir, "${profile.tagStem}.pinned")

    /** Read once by the composer page; null means the launch was an ordinary one. */
    private var notice: String? = null

    private val _library = MutableStateFlow(readInitial())
    val library: StateFlow<TagLibrary> = _library.asStateFlow()

    /** Returns the one-off upgrade line and clears it, so a tab switch does not repeat it. */
    fun takeUpgradeNotice(): String? = notice.also { notice = null }

    /** Replaces the working copy with the bundled library. */
    fun resetToDefault() {
        workingFile.delete()
        pinnedFile.delete()
        val bundled = runCatching { TagLibraryJson.parse(bundledText()) }.getOrNull() ?: TagLibrary()
        runCatching { writeBase(bundled) }
        _library.value = bundled
        notice = null
    }

    /**
     * Reads a file the user picked. The current library is untouched when parsing fails.
     *
     * With [merge], the file is folded into the working copy instead of replacing it: tags the
     * user added or removed survive, and anything new in the file is appended. Replace is the
     * right choice only when the file is meant to become the whole library — and then the copy is
     * pinned, because a library the user chose on purpose must not be quietly replaced by the next
     * app update.
     */
    /** 导入的文件自报的是另一个档，先让用户确认再收。 */
    class ProfileMismatch(val found: ContentProfile) : IllegalArgumentException("档位不符")

    fun importFrom(uri: Uri, merge: Boolean = false, force: Boolean = false): Result<TagLibrary> = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalArgumentException("无法读取文件")
        val incoming = TagLibraryJson.parse(text)
        if (incoming.schemaVersion > SUPPORTED_SCHEMA) {
            throw IllegalArgumentException("词库版本过新（schemaVersion ${incoming.schemaVersion}），请升级应用")
        }
        // 跨档不硬拦，但要先问一句：拿 SFW 的词覆盖 NSFW 库，一次误操作就找不回来了。
        val declared = when (incoming.profile) {
            "sfw" -> ContentProfile.SFW
            "nsfw" -> ContentProfile.NSFW
            else -> ContentProfile.fromRating(incoming.rating)
        }
        if (!force && declared != profile) throw ProfileMismatch(declared)
        val library = if (merge) mergeInto(_library.value, incoming) else incoming
        if (!merge) {
            pinnedFile.createNewFile()
            runCatching { writeBase(incoming) }
        }
        persist(library)
        library
    }

    /** Union of two libraries, keeping the current one's order and wording where they differ. */
    private fun mergeInto(current: TagLibrary, incoming: TagLibrary): TagLibrary {
        val categories = current.categories.toMutableList()
        for (incomingCategory in incoming.categories) {
            val index = categories.indexOfFirst { it.id == incomingCategory.id }
            if (index < 0) {
                categories.add(incomingCategory)
                continue
            }
            val base = categories[index]
            val slots = base.slots.toMutableList()
            for (incomingSlot in incomingCategory.slots) {
                val slotIndex = slots.indexOfFirst { it.id == incomingSlot.id }
                if (slotIndex < 0) {
                    slots.add(incomingSlot)
                    continue
                }
                val existing = slots[slotIndex]
                slots[slotIndex] = existing.copy(
                    pool = existing.pool + incomingSlot.pool.filterNot { it in existing.pool },
                    excluded = existing.excluded +
                        incomingSlot.excluded.filterNot { it in existing.excluded }
                )
            }
            categories[index] = base.copy(slots = slots)
        }

        val left = current.constraints
        val right = incoming.constraints
        val mergedExcludes = left.excludes.toMutableList()
        right.excludes.forEach { pair ->
            if (mergedExcludes.none { it.toSet() == pair.toSet() }) mergedExcludes.add(pair)
        }

        return current.copy(
            categories = categories,
            singletons = (current.singletons + incoming.singletons).distinct(),
            mutex = (current.mutex + incoming.mutex).distinct(),
            skipRules = (current.skipRules + incoming.skipRules).distinct(),
            constraints = left.copy(
                // Incoming annotations win: they come from a newer library.
                handCost = left.handCost + right.handCost,
                posture = left.posture + right.posture,
                place = left.place + right.place,
                requires = left.requires + right.requires,
                people = left.people + right.people,
                peopleCount = left.peopleCount + right.peopleCount,
                excludes = mergedExcludes,
                handCap = maxOf(left.handCap, right.handCap)
            )
        )
    }

    /** Writes the working copy out, edits included. */
    fun exportTo(uri: Uri): Result<Unit> = runCatching {
        val text = TagLibraryJson.write(_library.value)
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } ?: throw IllegalArgumentException("无法写入文件")
        Unit
    }

    /** Adds, removes or excludes tags in one slot and persists the result. */
    fun updateSlot(categoryId: String, slot: TagSlot) {
        persist(_library.value.withSlot(categoryId, slot))
    }

    /**
     * Written through a temp file and renamed. A half-written catalog would leave the user
     * with an empty page after the next launch, and rename is the only step that is atomic.
     */
    private fun persist(library: TagLibrary) {
        writeAtomic(workingFile, TagLibraryJson.write(library))
        _library.value = library
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(context.filesDir, "${target.name}.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw java.io.IOException("无法写入词库文件，请检查存储空间")
                }
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun writeBase(library: TagLibrary) {
        if (library.categories.isEmpty()) return
        writeAtomic(baseFile, TagLibraryJson.write(library))
    }

    private fun readInitial(): TagLibrary {
        val bundled = runCatching { TagLibraryJson.parse(bundledText()) }.getOrNull()

        if (!workingFile.exists()) {
            bundled?.let {
                runCatching {
                    writeAtomic(workingFile, TagLibraryJson.write(it))
                    writeBase(it)
                }
            }
            return bundled ?: TagLibrary()
        }

        // 本地副本原样载入。SFW 档不再因为命中禁词就丢弃它——导入是用户的决定，
        // 悄悄换回内置只会让人以为导入没生效。
        val working = runCatching { TagLibraryJson.parse(workingFile.readText(Charsets.UTF_8)) }.getOrNull()
        if (working == null) {
            // A broken working copy must not leave the page blank: fall back to the asset.
            workingFile.delete()
            bundled?.let {
                runCatching {
                    writeAtomic(workingFile, TagLibraryJson.write(it))
                    writeBase(it)
                }
            }
            return bundled ?: TagLibrary()
        }

        // An asset that will not parse is a build problem, and no reason to touch the user's copy.
        if (bundled == null) return working
        // A library imported on purpose is the user's decision, not a stale copy to be refreshed.
        if (pinnedFile.exists()) return working
        // 版本号一样不代表内容一样：规则表（hiddenBy 这类）是纯结构，改了不会体现在词数上。
        // 只看版本号会让「内置词库已经修好、手机里那份还是旧的」永远升不上去。
        val staleRules = working.hiddenRules.isEmpty() && bundled.hiddenRules.isNotEmpty()
        if (bundled.version == working.version && !staleRules) return working

        val base = runCatching { TagLibraryJson.parse(baseFile.readText(Charsets.UTF_8)) }.getOrNull()
            ?: seedBase()?.takeIf { it.version == working.version }
        val merged = LibraryMerge.fold(base, working, bundled)
        runCatching {
            writeAtomic(backupFile, TagLibraryJson.write(working))
            writeAtomic(workingFile, TagLibraryJson.write(merged))
            writeBase(bundled)
        }.onFailure { return working }
        notice = upgradeNotice(working, merged, bundled)
        return merged
    }

    /**
     * The library this app shipped before the automatic upgrade existed.
     *
     * Without it the first upgrade after installing this feature has nothing to compare against:
     * "the user added 粉色霓虹" and "the new library dropped 粉色霓虹" look identical, and keeping
     * the word would quietly undo the cleanup it was released for.
     */
    private fun seedBase(): TagLibrary? = runCatching {
        TagLibraryJson.parse(context.assets.open(profile.tagSeedName).use { it.bufferedReader(Charsets.UTF_8).readText() })
    }.getOrNull()

    private fun upgradeNotice(before: TagLibrary, after: TagLibrary, bundled: TagLibrary): String {
        val wasThere = before.categories.flatMap { category -> category.slots.flatMap { it.pool } }.toSet()
        val shipped = bundled.categories.flatMap { category -> category.slots.flatMap { it.pool } }.toSet()
        val kept = wasThere.count { it !in shipped }
        val fresh = bundled.tagCount - before.tagCount
        return when {
            kept > 0 -> "词库已更新到 ${bundled.version}，你加的 $kept 个词保留了"
            fresh > 0 -> "词库已更新到 ${bundled.version}，新增 $fresh 个词"
            else -> "词库已更新到 ${bundled.version}"
        }
    }

    private fun bundledText(): String =
        context.assets.open(profile.tagAsset).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }

    companion object {
        private const val SUPPORTED_SCHEMA = 1
        private const val TAG = "TagLibraryStore"
        private val instances = mutableMapOf<ContentProfile, TagLibraryStore>()

        /**
         * 一档一个实例。缓存不能只留一个：用户在词库窗口来回切档时，另一个档的
         * StateFlow 必须还在，否则切回来会重新读盘、丢掉「已忽略 / 已锁定」的瞬时状态。
         */
        fun get(context: Context, profile: ContentProfile = ContentProfile.NSFW): TagLibraryStore =
            synchronized(this) {
                instances.getOrPut(profile) {
                    TagLibraryStore(context.applicationContext, profile)
                }
            }
    }
}
