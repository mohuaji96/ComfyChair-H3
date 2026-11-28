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
import sh.hnet.comfychair.R
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
    class ProfileMismatch(val found: ContentProfile, message: String) : IllegalArgumentException(message)

    fun importFrom(uri: Uri, merge: Boolean = false, force: Boolean = false): Result<TagLibrary> = runCatching {
        val text = readImport(uri)
            ?: throw IllegalArgumentException(context.getString(R.string.err_tl_read_failed))
        val incoming = TagLibraryJson.parse(text)
        if (incoming.schemaVersion > SUPPORTED_SCHEMA) {
            throw IllegalArgumentException(
                context.getString(R.string.err_tl_schema_too_new, incoming.schemaVersion)
            )
        }
        // 跨档不硬拦，但要先问一句：拿 SFW 的词覆盖 NSFW 库，一次误操作就找不回来了。
        val declared = when (incoming.profile) {
            "sfw" -> ContentProfile.SFW
            "nsfw" -> ContentProfile.NSFW
            else -> ContentProfile.fromRating(incoming.rating)
        }
        if (!force && declared != profile) {
            throw ProfileMismatch(declared, context.getString(R.string.err_profile_mismatch))
        }
        val library = if (merge) mergeInto(_library.value, incoming) else incoming
        // 普通档自己声明 rating 不算数：整包体检，禁词命中就整包拒收，当前词库一点不动。
        if (profile == ContentProfile.SFW) {
            val bad = SfwGuard.validate(context, library)
            if (bad.isNotEmpty()) throw IllegalArgumentException(bad.joinToString("\n"))
        }
        // 先落主文件，再写基线与「已导入」标记。倒过来做的话，基线写成功、主文件写失败时
        // 库里留下半套状态：本次的基线配上上次的工作副本，下次启动按错误的基线做三方合并。
        persist(library)
        if (!merge) {
            runCatching { writeBase(incoming) }
            runCatching { pinnedFile.createNewFile() }
        }
        library
    }

    /**
     * 整份读进内存之前先看一眼体积。
     *
     * 以前是 readText() 无上限：几十兆的文件会直接把播放器式的 OOM 甩给用户，
     * 而且解析到中途才炸，当前词库是保住了，界面却卡死几秒。现在超限当场拒绝。
     */
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
                        context.getString(R.string.err_tl_too_large, MAX_IMPORT_BYTES / (1024 * 1024))
                    )
                }
                buffer.write(chunk, 0, read)
            }
            String(buffer.toByteArray(), Charsets.UTF_8)
        }
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
        val mergedSynonyms = left.synonyms.toMutableList()
        right.synonyms.forEach { group ->
            if (mergedSynonyms.none { it.toSet() == group.toSet() }) mergedSynonyms.add(group)
        }
        val mergedPeopleByCategory = (left.peopleByCategory.keys + right.peopleByCategory.keys)
            .associateWith { k -> left.peopleByCategory[k].orEmpty() + right.peopleByCategory[k].orEmpty() }

        return current.copy(
            categories = categories,
            singletons = (current.singletons + incoming.singletons).distinct(),
            mutex = (current.mutex + incoming.mutex).distinct(),
            skipRules = (current.skipRules + incoming.skipRules).distinct(),
            hiddenRules = (current.hiddenRules + incoming.hiddenRules).distinct(),
            anchors = current.anchors.copy(
                families = current.anchors.families + incoming.anchors.families,
                of = current.anchors.of + incoming.anchors.of,
                addOn = (current.anchors.addOn + incoming.anchors.addOn).distinct()
            ),
            constraints = left.copy(
                // Incoming annotations win: they come from a newer library.
                handCap = maxOf(left.handCap, right.handCap),
                handCost = left.handCost + right.handCost,
                posture = left.posture + right.posture,
                place = left.place + right.place,
                gaze = left.gaze + right.gaze,
                mouthCap = maxOf(left.mouthCap, right.mouthCap),
                mouthCost = left.mouthCost + right.mouthCost,
                headCap = maxOf(left.headCap, right.headCap),
                headCost = left.headCost + right.headCost,
                soloPose = (left.soloPose + right.soloPose).distinct(),
                scenePoses = (left.scenePoses + right.scenePoses).distinct(),
                hairState = (left.hairState + right.hairState).distinct(),
                onePiece = (left.onePiece + right.onePiece).distinct(),
                onePieceBottom = (left.onePieceBottom + right.onePieceBottom).distinct(),
                toyActs = left.toyActs + right.toyActs,
                synonyms = mergedSynonyms,
                excludes = mergedExcludes,
                requires = left.requires + right.requires,
                allowedPostures = left.allowedPostures + right.allowedPostures,
                postureHints = left.postureHints + right.postureHints,
                headPlacements = left.headPlacements + right.headPlacements,
                sceneProps = left.sceneProps + right.sceneProps,
                propNeeds = left.propNeeds + right.propNeeds,
                exactPeople = (left.exactPeople + right.exactPeople).distinct(),
                raceFeatureHead = left.raceFeatureHead + right.raceFeatureHead,
                raceFeatureBody = left.raceFeatureBody + right.raceFeatureBody,
                raceLead = left.raceLead + right.raceLead,
                raceName = left.raceName + right.raceName,
                people = left.people + right.people,
                peopleByCategory = mergedPeopleByCategory,
                wordAliases = left.wordAliases + right.wordAliases,
                peopleCount = left.peopleCount + right.peopleCount,
                placeSupports = left.placeSupports + right.placeSupports,
                poseNeeds = left.poseNeeds + right.poseNeeds,
                selfColoured = (left.selfColoured + right.selfColoured).distinct(),
                selfPatterned = (left.selfPatterned + right.selfPatterned).distinct(),
                pos = left.pos + right.pos,
                eras = left.eras + right.eras,
                skinByRegion = left.skinByRegion + right.skinByRegion,
                maleOnly = (left.maleOnly + right.maleOnly).distinct(),
                malePeople = (left.malePeople + right.malePeople).distinct(),
                frameHides = left.frameHides + right.frameHides,
                frameHidesCategories = left.frameHidesCategories + right.frameHidesCategories,
                regions = left.regions + right.regions,
                categoryRegions = left.categoryRegions + right.categoryRegions,
                handBlock = (left.handBlock + right.handBlock).distinct(),
                subjects = left.subjects + right.subjects,
                faceZone = left.faceZone + right.faceZone,
                weight = left.weight + right.weight
            )
        )
    }

    /** Writes the working copy out, edits included. */
    fun exportTo(uri: Uri): Result<Unit> = runCatching {
        val text = TagLibraryJson.write(_library.value)
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        } ?: throw IllegalArgumentException(context.getString(R.string.err_tl_export_failed))
        Unit
    }

    /** Adds, removes or excludes tags in one slot and persists the result. */
    fun updateSlot(categoryId: String, slot: TagSlot) {
        persist(_library.value.withSlot(categoryId, slot))
    }

    /**
     * 手打新词之前的体检：普通档命中禁词就返回提示行，调用方据此拦下并告知。
     *
     * 导入口已经拒收禁词了，但「自己加词」是另一个入口——两个口各写一套判定，
     * 就成了导入被拒、手打却能进的破窗。成人档不检查，原样返回空。
     */
    fun guardHits(words: List<String>): List<String> =
        if (profile == ContentProfile.SFW) SfwGuard.scan(context, words) else emptyList()

    /**
     * Written through a temp file and renamed. A half-written catalog would leave the user
     * with an empty page after the next launch, and rename is the only step that is atomic.
     */
    private fun persist(library: TagLibrary) {
        writeAtomic(workingFile, TagLibraryJson.write(library))
        _library.value = library
    }

    /**
     * 换掉一个文件，旧的那份在新的一份落位之前一直留在原地。
     *
     * 以前改名失败会先把目标删掉再改名：第二次改名再失败，词库就两份都没了。
     * 覆盖式改名在 Windows 和 Android 上都可用，失败时旧文件完好。
     */
    private fun writeAtomic(target: File, text: String) {
        val tmp = File(context.filesDir, "${target.name}.tmp")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: java.io.IOException) {
            throw java.io.IOException(context.getString(R.string.err_tl_write_failed), error)
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
            kept > 0 -> context.getString(R.string.err_tl_upgrade_kept, bundled.version, kept)
            fresh > 0 -> context.getString(R.string.err_tl_upgrade_new, bundled.version, fresh)
            else -> context.getString(R.string.err_tl_upgrade_plain, bundled.version)
        }
    }

    private fun bundledText(): String =
        context.assets.open(profile.tagAsset).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }

    companion object {
        private const val SUPPORTED_SCHEMA = 1
        private const val TAG = "TagLibraryStore"
        /** 内置词库不到 1 MB；给到 16 MB 已经够放三份，再大就是选错了文件。 */
        private const val MAX_IMPORT_BYTES = 16 * 1024 * 1024
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
