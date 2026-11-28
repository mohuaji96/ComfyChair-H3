package sh.hnet.comfychair.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.ContentProfile
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.model.localizedText
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.OptimizeHistoryStore
import sh.hnet.comfychair.storage.TagComposerState
import sh.hnet.comfychair.storage.TagLibraryStore
import sh.hnet.comfychair.util.DebugLogger
import sh.hnet.comfychair.workflow.RecentWords
import sh.hnet.comfychair.workflow.RequirementFiller
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagDefaults
import sh.hnet.comfychair.workflow.TagPick

/**
 * State holder for the tag composer page.
 *
 * Edits to the library go straight to disk: the working copy is the single source of truth
 * for both the randomizer and the exported file, so what you see is what you export.
 *
 * Solo and group are two views on one library, not two libraries. Each keeps its own
 * selection and its own allowed head count, so switching tabs never disturbs the other side.
 */
class TagComposerViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private var libraryStore = TagLibraryStore.get(application, AppSettings.getContentProfile(application))
    private val stateStore = TagComposerState(application)

    /** 当前内容级别。两档各自一整套词库，切档不走混合，只换加载的文件。 */
    var profile by mutableStateOf(AppSettings.getContentProfile(application))
        private set

    private val _library = MutableStateFlow(libraryStore.library.value)
    val library: StateFlow<TagLibrary> = _library.asStateFlow()

    /** 首次切到 SFW 的那张说明卡，只弹一次。 */
    var showSfwIntro by mutableStateOf(false)
        private set

    private var libraryJob: Job? = null

    /** 跟着当前档的 store 走：切档后必须重新订阅，否则编辑词条时页面读到的还是旧库。 */
    private fun watchLibrary() {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            libraryStore.library.collect { _library.value = it }
        }
    }

    private val stored: MutableMap<String, Map<String, TagPick>> = mutableMapOf(
        TagComposerState.MODE_SOLO to emptyMap(),
        TagComposerState.MODE_MULTI to emptyMap()
    )

    var mode by mutableStateOf(TagComposerState.MODE_SOLO)
        private set

    var picks by mutableStateOf<Map<String, TagPick>>(emptyMap())
        private set

    /**
     * 整段锁住的段。只影响「随机」这类全局重抽，段内单格点名字重抽照常生效。
     *
     * 不写进 pick.locked：那样会把单格锁混进来，取消段锁时分不清哪些是用户的、哪些是段锁
     * 附带的；而且锁住的空格需要先补一个词才锁得住，那正好违背"锁住不等于增词"。
     */
    var lockedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    var message by mutableStateOf<String?>(null)
        private set

    /** 增强模式: every guard runs. 普通模式: only the ones that prevent self-contradiction. */
    var strict by mutableStateOf(stateStore.loadStrict())
        private set

    /** Weight down whatever a slot just used, so a 36-word pool stops feeling like six. */
    var avoidRepeat by mutableStateOf(stateStore.loadAvoidRepeat())
        private set

    /** Survives engine rebuilds; a fresh instance would forget what it just drew. */
    private val recent = RecentWords()

    /**
     * Follows renames recorded in the catalog, so a save made before 冷灰低饱和 became 灰蓝低饱和
     * keeps its selection instead of silently emptying the slot.
     */
    private fun migrateAliases(saved: Map<String, TagPick>): Map<String, TagPick> {
        val lib = library.value
        if (lib.constraints.wordAliases.isEmpty()) return saved
        return saved.mapValues { (_, pick) ->
            val renamed = lib.canonicalWord(pick.value)
            if (renamed == pick.value) pick else pick.copy(value = renamed)
        }
    }

    init {
        stored[TagComposerState.MODE_SOLO] = migrateAliases(stateStore.load(TagComposerState.MODE_SOLO))
        stored[TagComposerState.MODE_MULTI] = migrateAliases(stateStore.load(TagComposerState.MODE_MULTI))
        mode = stateStore.loadMode()
        lockedGroups = stateStore.loadLockedGroups(mode)
        picks = stored[mode].orEmpty()
        applyDefaults()
        pruneOrRoll()
        // A library that was refreshed on launch says so once. applyDefaults already speaks up
        // when the defaults moved, and two lines about the same update would only fight.
        if (message == null) message = libraryStore.takeUpgradeNotice()
        watchLibrary()
    }

    /**
     * A fresh selection starts with the categories the library switches off: toys stay out of
     * the draw until asked for, and the medium is drawn once then pinned so a reroll keeps the
     * same visual language. Untouched slots only — anything the player has set stays as it is.
     */
    private fun applyDefaults() {
        val lib = library.value
        if (lib.defaultIgnored.isEmpty() && lib.defaultLocked.isEmpty()) return
        // Only seed untouched slots — unless the catalogue itself changed, in which case the
        // defaults were re-decided and a stale saved selection would keep outranking them.
        val applied = stateStore.loadDefaultsVersion(mode)
        val reseed = lib.version.isNotBlank() && applied != lib.version
        val out = TagDefaults.ignored(lib, picks)

        // "Untouched" is judged against the saved selection, never against `out`: the
        // default-ignored entries were just written into `out`, so a category that also carries
        // one of those keys looked hand-edited. 风格 is exactly that case -- it was never drawn
        // and never pinned, leaving two blank slots and a new look on every reroll.
        val saved = if (reseed) emptyMap() else picks
        TagDefaults.lockedToSeed(lib, saved).forEach { categoryId ->
            val slots = lib.category(categoryId)?.slots ?: return@forEach
            engine().randomize(out, categoryId)
            slots.forEach { slot ->
                val slotKey = "$categoryId.${slot.id}"
                val pick = out[slotKey] ?: return@forEach
                // Pin only what was actually drawn; a default-ignored dimension stays off.
                if (pick.value.isEmpty()) return@forEach
                out[slotKey] = pick.copy(locked = true, ignored = false)
            }
        }

        if (reseed) {
            stateStore.saveDefaultsVersion(mode, lib.version)
            // Half the list names a slot rather than a category (camera.crop, skin.mark), and the
            // old lookup only knew categories -- those entries vanished from the toast, so the
            // message claimed fewer things were switched off than actually were.
            val names = lib.defaultIgnored.mapNotNull { entry ->
                lib.category(entry)?.label ?: run {
                    val parts = entry.split('.')
                    if (parts.size != 2) return@mapNotNull null
                    val slot = lib.slot(parts[0], parts[1]) ?: return@mapNotNull null
                    "${lib.category(parts[0])?.label ?: parts[0]}·${slot.label}"
                }
            }
            if (names.isNotEmpty()) {
                message = app.getString(
                    R.string.tc_library_updated_defaults,
                    names.joinToString(app.getString(R.string.tc_list_sep))
                )
            }
        }

        if (out != picks) picks = out
    }

    fun engine(): TagComposerEngine =
        TagComposerEngine(library.value, allowedPeople(), strict, recent.takeIf { avoidRepeat })

    fun setStrictMode(next: Boolean) {
        if (next == strict) return
        strict = next
        stateStore.saveStrict(next)
        val mutable = picks.toMutableMap()
        engine().repair(mutable, holdGroups = lockedGroups)
        picks = mutable
    }

    fun setAvoidRepeatMode(next: Boolean) {
        if (next == avoidRepeat) return
        avoidRepeat = next
        stateStore.saveAvoidRepeat(next)
        if (!next) recent.clear()
    }

    /** Why a slot is empty right now, for the panel to explain instead of showing 未选. */
    fun blockReason(categoryId: String, slotId: String): String? =
        engine().slotBlockReason(categoryId, slotId, picks)

    /** Words that contradict the current selection, so the panel can grey them out. */
    fun blockedWords(categoryId: String, slotId: String): Map<String, String> =
        engine().blockedWords(categoryId, slotId, picks)

    /** Swaps tabs. The outgoing selection is stored first, so nothing is lost. */
    fun switchMode(next: String) {
        if (next == mode) return
        stateStore.save(mode, picks)
        stored[mode] = stored[mode].orEmpty() + picks
        mode = next
        stateStore.saveMode(next)
        lockedGroups = stateStore.loadLockedGroups(next)
        picks = visiblePicks(library.value, stored[next].orEmpty())
        pruneOrRoll()
    }

    /**
     * 切内容级别：白名单式过滤，不做全清空。
     *
     * 存档是两档共用一份的（键是 `分类.槽位`），切过去只把「目标库没有的」从**视图**里拿掉，
     * 存档原样留着，切回来还能恢复。所以这里不调 save，改名/覆盖由 [commit] 负责合并。
     */
    fun switchProfile(next: ContentProfile) {
        if (next == profile) return
        profile = next
        AppSettings.setContentProfile(app, next)
        libraryStore = TagLibraryStore.get(app, next)
        _library.value = libraryStore.library.value
        watchLibrary()

        val saved = stored[mode].orEmpty()
        picks = visiblePicks(library.value, saved)
        val dropped = saved.size - picks.size
        if (next.isSfw && !AppSettings.hasSeenSfwIntro(app)) showSfwIntro = true
        message = if (dropped > 0) {
            app.getString(
                R.string.tc_profile_switch_dropped,
                if (next.isSfw) "SFW" else app.getString(R.string.tc_guard_adult),
                dropped
            )
        } else null
        // 两档的「近期抽过」互不相干，带过去只会让 SFW 头几抽莫名其妙地空。
        recent.clear()
        pruneOrRoll()
    }

    fun dismissSfwIntro() {
        showSfwIntro = false
        AppSettings.setSfwIntroSeen(app)
    }

    /** 只保留目标库里真实存在的键与值；返回被拿掉的条数。 */
    private fun visiblePicks(lib: TagLibrary, saved: Map<String, TagPick>): Map<String, TagPick> =
        saved.filter { (key, pick) ->
            val parts = key.split('.')
            if (parts.size != 2) return@filter false
            val slot = lib.slot(parts[0], parts[1]) ?: return@filter false
            pick.value.isEmpty() || pick.value in slot.randomPool()
        }

    fun clearMessage() {
        message = null
    }

    fun hint(text: String) {
        message = text
    }

    fun randomizeAll() = draw("随机") { engine, mutable ->
        engine.randomize(mutable, holdGroups = lockedGroups)
    }

    // 段锁对「重抽一个分类」同样有效：锁住的是那一段不被改动，不是只有全局随机才守。
    fun randomizeCategory(categoryId: String) =
        draw("重抽分类") { engine, mutable ->
            engine.randomize(mutable, categoryId, holdGroups = lockedGroups)
        }

    /** 锁住/解锁一整段。只拦全局随机，段内单格重抽不受影响。 */
    fun toggleSectionLock(group: String) {
        lockedGroups = if (group in lockedGroups) lockedGroups - group else lockedGroups + group
        stateStore.saveLockedGroups(mode, lockedGroups)
    }

    /**
     * Rerolls one section heading's worth of categories in a single commit.
     *
     * 点段头就是明确要重抽这一段，所以锁着的时候先解锁再抽；否则点了没反应，看起来像坏了。
     */
    fun randomizeSection(group: String) {
        if (group in lockedGroups) toggleSectionLock(group)
        draw("整段重抽") { engine, mutable ->
            library.value.categories
                .filter { it.group == group }
                .forEach { engine.randomize(mutable, it.id) }
        }
    }

    fun setValue(categoryId: String, slotId: String, value: String) = commit { mutable ->
        val key = "${categoryId}.${slotId}"
        val previous = mutable[key]
        val eng = engine()
        // A pick can switch a whole category off (or release one); the skip set has to be taken
        // before the edit, otherwise a released 上衣 / 下衣 is never drawn back.
        val skipBefore = eng.skipped(mutable)
        if (!ensureRequirements(value, key, mutable)) {
            mutable[key] = previous ?: TagPick()
            return@commit
        }
        mutable[key] = (mutable[key] ?: TagPick()).copy(value = value)
        if (!expandHeadCountFor(categoryId, value, mutable)) {
            mutable[key] = previous ?: TagPick()
            return@commit
        }
        // Value by slot before repair: a slot that conflicts with the new pick is re-drawn, and
        // silently swapping another block's word reads as the app picking for you.
        val before = mutable.mapValues { it.value.value }
        eng.repair(mutable, protect = key, skipBefore = skipBefore, holdGroups = lockedGroups)
        val swapped = mutable.keys.filter { other ->
            other != key && !before[other].isNullOrEmpty() &&
                mutable[other]?.value != before[other] &&
                !mutable[other].let { it == null || it.value.isEmpty() }
        }
        if (swapped.isNotEmpty()) {
            val names = swapped.mapNotNull { library.value.category(it.substringBefore('.'))?.label }.distinct()
            if (names.isNotEmpty()) {
                val listed = names.joinToString(app.getString(R.string.tc_list_sep)) {
                    app.getString(R.string.tc_word_wrapped, it)
                }
                hint(app.getString(R.string.tc_conflict_swapped, listed))
            }
        }
    }

    /**
     * A hand-picked word is exempt from repair, so its prerequisites have to be settled here.
     * 青筋手背 under 两个女孩 is the same class of bug as 骑乘 under 单人: the pick survived
     * because nothing checked it. When the missing piece is the male head count, raise it;
     * otherwise drop the pick rather than emit a word that cannot appear.
     */
    private fun ensureRequirements(
        value: String,
        selfKey: String,
        mutable: MutableMap<String, TagPick>
    ): Boolean {
        val lib = library.value
        val needs = lib.constraints.requires[value] ?: return true
        if (needs.isEmpty()) return true
        val met = needs.any { need ->
            val scope = RequirementFiller.needCategory(need)
            mutable.entries.any { entry ->
                entry.key != selfKey && !entry.value.ignored &&
                    (scope == null || entry.key.startsWith("$scope.")) &&
                    RequirementFiller.names(entry.value.value, RequirementFiller.needWord(need))
            }
        }
        if (met) return true
        if (needs.contains("男")) {
            val needed = lib.peopleNeeded(selfKey.substringBefore('.'), value).coerceAtLeast(2)
            val allowed = allowedPeople()
            val target = lib.constraints.peopleCount.entries
                .filter { isMalePeople(lib, it.key) && it.value >= needed }
                .filter { allowed.isEmpty() || it.key in allowed }
                .minByOrNull { it.value }
            if (target != null) {
                val countKey = lib.category(PEOPLE_ID)?.slots?.firstOrNull()
                    ?.let { "$PEOPLE_ID.${it.id}" }
                if (countKey != null) {
                    mutable[countKey] = (mutable[countKey] ?: TagPick())
                        .copy(value = target.key, ignored = false)
                    hint(app.getString(R.string.tc_need_male_raised, value, target.key))
                    return true
                }
            }
        }
        val lockedLabel = RequirementFiller.lockedProvider(
            library = lib,
            needs = needs,
            selfCategoryId = selfKey.substringBefore('.'),
            picks = mutable
        )
        if (lockedLabel != null) {
            hint(
                app.getString(
                    R.string.tc_need_locked_provider,
                    value,
                    needs.joinToString(app.getString(R.string.tc_or_sep)) {
                        RequirementFiller.needWord(it)
                    },
                    lockedLabel
                )
            )
            return false
        }

        // Nothing supplies it yet: put the plainest word that does into the slot that can hold
        // it, and say so. Cancelling here is what made these chips feel broken.
        val eng = engine()
        val fill = RequirementFiller.find(
            library = lib,
            needs = needs,
            selfCategoryId = selfKey.substringBefore('.'),
            picks = mutable
        ) { categoryId, slotId -> eng.blockedWords(categoryId, slotId, mutable) }
        if (fill != null) {
            mutable[fill.slotKey] = (mutable[fill.slotKey] ?: TagPick())
                .copy(value = fill.word, ignored = false)
            hint(
                app.getString(
                    R.string.tc_need_filled,
                    value, fill.need, fill.categoryLabel, fill.word
                )
            )
            return true
        }
        hint(
            app.getString(
                R.string.tc_need_missing,
                value,
                needs.joinToString(app.getString(R.string.tc_or_sep))
            )
        )
        return false
    }

    /**
     * A word that needs two or three bodies cannot live under 单人. Rather than silently keep an
     * impossible pair, lift the count to the smallest tag that can hold it and say so.
     */
    private fun expandHeadCountFor(
        categoryId: String,
        value: String,
        mutable: MutableMap<String, TagPick>
    ): Boolean {
        val lib = library.value
        // Scoped lookup: 全裸 means one body in 穿脱 and "a man is present" in 男性. Reading the
        // global map here made the female word demand a second body, and because 单人 cannot
        // offer one the pick was reverted with no message at all — the chip simply did nothing.
        val needed = lib.peopleNeeded(categoryId, value)
        if (needed <= 1) return true
        val countKey = lib.category(PEOPLE_ID)?.slots?.firstOrNull()?.let { "$PEOPLE_ID.${it.id}" }
            ?: return true
        val current = mutable[countKey]?.value.orEmpty()
        val currentCount = lib.constraints.peopleCount[current] ?: 0
        if (currentCount >= needed) return true
        // The count has to stay inside what this tab can draw, or the word imports a 人数 the
        // picker cannot even show and the selection can never be undone.
        val allowed = allowedPeople()
        val target = lib.constraints.peopleCount.entries
            .filter { it.value == needed && it.key != current }
            .filter { allowed.isEmpty() || it.key in allowed }
            .map { it.key }
            .firstOrNull() ?: run {
                // Silence here is what made the dead chip unreportable: say it instead.
                hint(app.getString(R.string.tc_need_people_overflow, value, needed))
                return false
            }
        mutable[countKey] = (mutable[countKey] ?: TagPick()).copy(value = target, ignored = false)
        hint(app.getString(R.string.tc_need_people_raised, value, needed, target))
        return true
    }

    fun toggleLock(categoryId: String, slotId: String) = commit { mutable ->
        val key = "${categoryId}.${slotId}"
        val current = mutable[key] ?: TagPick()
        if (current.locked) {
            mutable[key] = current.copy(locked = false)
            return@commit
        }
        // Locking an empty slot used to freeze it forever: the draw skips locked slots, so the
        // category never filled in and the prompt silently lost a whole block. Draw first, then
        // pin whatever came out.
        if (current.value.isEmpty()) engine().randomize(mutable, categoryId)
        val after = mutable[key] ?: TagPick()
        if (after.value.isEmpty()) {
            hint(app.getString(R.string.tc_lock_no_words))
            mutable[key] = after
        } else {
            mutable[key] = after.copy(locked = true, ignored = false)
        }
    }

    fun toggleIgnore(categoryId: String, slotId: String) = commit { mutable ->
        val key = "${categoryId}.${slotId}"
        val current = mutable[key] ?: TagPick()
        val eng = engine()
        // Ignoring the slot that was holding 上衣 / 下衣 switched off is what released them; the
        // skip set has to be captured before the flag flips, or nobody draws them back.
        val skipBefore = eng.skipped(mutable)
        mutable[key] = if (current.ignored) current.copy(ignored = false)
        else current.copy(ignored = true, locked = false)
        eng.repair(mutable, skipBefore = skipBefore, holdGroups = lockedGroups)
    }

    fun clearSlot(categoryId: String, slotId: String) = commit { mutable ->
        val key = "${categoryId}.${slotId}"
        val current = mutable[key] ?: TagPick()
        val eng = engine()
        val skipBefore = eng.skipped(mutable)
        // Clearing drops the word and its lock -- a lock with nothing in it can never draw
        // again -- but keeps 忽略, which describes the slot rather than the word.
        mutable[key] = current.copy(value = "", locked = false)
        eng.repair(mutable, skipBefore = skipBefore, holdGroups = lockedGroups)
    }

    // ---- 模式相关 ----

    /** Head counts this tab may draw. Empty means the library has no 人数 mapping at all. */
    fun allowedPeople(): Set<String> {
        val counting = library.value.constraints.peopleCount
        if (counting.isEmpty()) return emptySet()
        val solo = mode == TagComposerState.MODE_SOLO
        return counting.filterValues { if (solo) it <= 1 else it >= 2 }.keys
    }

    /**
     * Categories that cannot contribute anything on this tab are hidden.
     * A group-only category such as 关系 simply disappears from the solo page.
     */
    fun visibleCategories(): List<TagCategory> {
        val alone = library.value.constraints.people
        val order = library.value.uiOrder.ifEmpty {
            library.value.promptTiers.ifEmpty { listOf(TagComposerEngine.promptOrder) }.flatten()
        }
        // His rows are noise in an all-female cast: 阴茎 showing under 五个女孩 was exactly the
        // confusion this regroup is meant to remove.
        val chosenPeople = picks["$PEOPLE_ID.count"]?.value.orEmpty()
        val malePossible = if (chosenPeople.isNotBlank()) {
            isMalePeople(library.value, chosenPeople)
        } else {
            allowedPeople().any { isMalePeople(library.value, it) }
        }
        return library.value.categories
            .filter { category ->
                if (category.who == "male" && !malePossible) return@filter false
                category.slots.any { slot -> slot.randomPool().any { (alone[it] ?: 0) <= 1 } }
            }
            // Same order as the prompt: what you read top to bottom is what the model reads.
            .sortedBy { order.indexOf(it.id).let { i -> if (i < 0) order.size else i } }
    }

    fun slotPool(categoryId: String, slot: TagSlot, includeExcluded: Boolean = false): List<String> {
        // 弹层显示全部，包括被眼睛关掉的；否则一关就从列表里消失，看着像被删了。
        val source = if (includeExcluded) slot.pool else slot.randomPool()
        val pool = orderedPool(categoryId, slot, source)
        val allowed = allowedPeople()
        if (allowed.isEmpty()) return pool
        if (categoryId == PEOPLE_ID) return pool.filter { it in allowed }
        // A two-person word cannot live under 单人. Offering it only to reject it afterwards left
        // a 人数 behind that this tab cannot even display.
        val crowd = allowed.mapNotNull { library.value.constraints.peopleCount[it] }.maxOrNull()
            ?: return pool
        // Scope the lookup to this category: the same word can mean "needs a man" in the male
        // wardrobe and "one woman, nothing on" in 穿脱.
        val lib = library.value
        return pool.filter { lib.peopleNeeded(categoryId, it) <= crowd }
    }

    /** Frequently used words first so manual picking does not start with the obscure end. */
    private fun orderedPool(categoryId: String, slot: TagSlot, pool: List<String>): List<String> {
        val first = library.value.commonFirst["$categoryId.${slot.id}"] ?: return pool
        val head = first.filter { it in pool }
        return if (head.isEmpty()) pool else head + pool.filter { it !in head }
    }

    // ---- 词库编辑 ----

    fun addTag(categoryId: String, slot: TagSlot, tag: String) {
        val text = tag.trim()
        if (text.isEmpty()) return
        if (text in slot.pool) {
            message = app.getString(R.string.tc_word_exists, text)
            return
        }
        // 普通档手打也要过一遍禁词表，和导入口同一把尺子。
        val hits = libraryStore.guardHits(slot.pool + text)
        if (hits.isNotEmpty()) {
            message = hits.first()
            return
        }
        libraryStore.updateSlot(categoryId, slot.copy(pool = slot.pool + text))
    }

    fun removeTag(categoryId: String, slot: TagSlot, tag: String) {
        val pool = slot.pool - tag
        if (pool.isEmpty()) {
            message = app.getString(R.string.tc_keep_one_word)
            return
        }
        libraryStore.updateSlot(categoryId, slot.copy(pool = pool, excluded = slot.excluded - tag))
        commit { mutable ->
            val key = "${categoryId}.${slot.id}"
            if (mutable[key]?.value == tag) mutable[key] = TagPick()
        }
    }

    fun toggleExcluded(categoryId: String, slot: TagSlot, tag: String) {
        val excluded = if (tag in slot.excluded) slot.excluded - tag else slot.excluded + tag
        libraryStore.updateSlot(categoryId, slot.copy(excluded = excluded))
    }

    /** 跨档导入要用户点头：先记下来，界面弹窗问一句。 */
    data class PendingImport(val uri: Uri, val merge: Boolean, val found: ContentProfile)

    var pendingImport by mutableStateOf<PendingImport?>(null)
        private set

    fun confirmPendingImport() {
        val pending = pendingImport ?: return
        pendingImport = null
        importLibrary(pending.uri, pending.merge, force = true)
    }

    fun dismissPendingImport() {
        pendingImport = null
    }

    fun importLibrary(uri: Uri, merge: Boolean, force: Boolean = false) {
        // 读文件、解析、体检、落盘都在后台线程：词库动辄上千条，放在主线程上会卡住
        // 界面几秒，用户以为点空了，往往就再点一次。
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                libraryStore.importFrom(uri, merge, force)
            }
            result
            .onSuccess { imported ->
                if (merge) {
                    // Keep both sheets as they are; only re-check keys that may have disappeared.
                    pruneOrRoll()
                } else {
                    stateStore.clear()
                    stored[TagComposerState.MODE_SOLO] = emptyMap()
                    stored[TagComposerState.MODE_MULTI] = emptyMap()
                    picks = emptyMap()
                    mode = TagComposerState.MODE_SOLO
                    stateStore.saveMode(mode)
                    pruneOrRoll()
                }
                val verb = app.getString(
                    if (merge) R.string.tc_import_merged else R.string.tc_import_imported
                )
                message = app.getString(
                    R.string.tc_import_done,
                    verb,
                    imported.name.ifBlank { app.getString(R.string.tc_library_word) },
                    imported.categories.size,
                    imported.tagCount
                )
            }
            .onFailure { error ->
                if (error is TagLibraryStore.ProfileMismatch) {
                    pendingImport = PendingImport(uri, merge, error.found)
                } else {
                    message = app.getString(
                        R.string.tc_import_failed,
                        error.localizedText(app, R.string.tc_bad_file)
                    )
                }
            }
        }
    }

    fun exportLibrary(uri: Uri) {
        libraryStore.exportTo(uri)
            .onSuccess { message = app.getString(R.string.tc_export_done) }
            .onFailure {
                message = app.getString(
                    R.string.tc_export_failed,
                    it.message ?: app.getString(R.string.tc_write_failed)
                )
            }
    }

    fun resetLibrary() {
        libraryStore.resetToDefault()
        val lib = libraryStore.library.value
        // 只换词库。选择、锁定、忽略和优化历史都不该跟着没——以前这里全清了，
        // 于是「忽略」过的分类在还原之后又活了回来。
        TagComposerState.MODES.forEach { stateStore.saveDefaultsVersion(it, lib.version) }
        pruneOrRoll()
        message = app.getString(
            R.string.tc_reset_done,
            lib.categories.size,
            lib.tagCount
        )
    }

    // ---- 内部 ----

    private fun pruneOrRoll() {
        val valid = library.value.categories
            .flatMap { category -> category.slots.map { "${category.id}.${it.id}" } }
            .toSet()
        val kept = picks.filterKeys { it in valid }
        picks = kept
        if (kept.isEmpty()) {
            randomizeAll()
        } else {
            commit { mutable ->
                ensurePeopleValue(mutable)
                // A mode switch can strand tags that need more people than this tab allows.
                engine().repair(mutable, holdGroups = lockedGroups)
                // Restoring a sparse save only repaired conflicts, so categories that came
                // back empty stayed empty for good. Draw them now.
                engine().fillBlanks(mutable, holdGroups = lockedGroups)
            }
        }
    }

    /** Keeps the 人数 slot inside this tab's range after a switch or a library change. */
    private fun ensurePeopleValue(mutable: MutableMap<String, TagPick>) {
        val allowed = allowedPeople()
        if (allowed.isEmpty()) return
        val category = library.value.category(PEOPLE_ID) ?: return
        val slot = category.slots.firstOrNull() ?: return
        val key = "${category.id}.${slot.id}"
        val current = mutable[key] ?: TagPick()
        if (current.value in allowed) return
        val pool = slot.randomPool().filter { it in allowed }
        if (pool.isEmpty()) return
        mutable[key] = current.copy(value = pool.random())
    }

    /** A draw, plus the one line the debug log needs to explain a later complaint. */
    private fun draw(
        action: String,
        block: (TagComposerEngine, MutableMap<String, TagPick>) -> Unit
    ) {
        val engine = engine()
        commit { block(engine, it) }
        DebugLogger.d(TAG, "$action ${engine.drawNote()}")
    }

    private fun commit(block: (MutableMap<String, TagPick>) -> Unit) {
        val mutable = picks.toMutableMap()
        block(mutable)
        picks = mutable
        // 存档里还留着只有另一档才认的键（切到 SFW 后的 sexAct.*、breasts.*）。整份覆盖会把
        // 它们抹掉，切回成人档就恢复不了——等于「切一次丢一次」。
        val carried = stored[mode].orEmpty().filterKeys { key ->
            if (key in mutable) return@filterKeys false
            val parts = key.split('.')
            parts.size == 2 && library.value.slot(parts[0], parts[1]) == null
        }
        val merged = if (carried.isEmpty()) mutable else mutable + carried
        stored[mode] = merged
        stateStore.save(mode, merged)
    }

    private fun isMalePeople(lib: TagLibrary, peopleWord: String): Boolean {
        val explicit = lib.constraints.malePeople
        return if (explicit.isNotEmpty()) peopleWord in explicit else peopleWord.contains("男")
    }

    companion object {
        private const val TAG = "TagComposer"
        private const val PEOPLE_ID = "people"
    }
}

