package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagLibrary
import kotlin.random.Random

/** What one slot currently holds. Locked slots survive a reroll; ignored slots stay out of the prompt. */
data class TagPick(
    val value: String = "",
    val locked: Boolean = false,
    val ignored: Boolean = false
)

/**
 * Random tag compiler.
 *
 * The flat mutex list from the word library only says "at most one of these". It cannot say
 * "these two tags both need two hands" or "this framing has no chest in it", so [satisfies]
 * adds the constraints the flat list cannot express:
 *
 * - hand budget: every tag costs 0-2 hands, the whole selection may not exceed [handCap];
 * - hand block: 捆绑 and 手铐 leave no hand free, so the hand-action slot empties entirely;
 * - mouth / head budgets: one mouth, and a limited number of things on one face;
 * - frame regions: every framing word declares the body regions it puts out of frame, and
 *   every content word declares where it lives;
 * - surface: a place offers the supports (bed, floor, wall…) a pose needs;
 * - posture / place / gaze groups: two different groups of the same kind exclude each other;
 * - people / exact people / solo pose: how many bodies a tag assumes;
 * - male gate: acts and poses that need a man are dropped from an all-female cast;
 * - requires: a tag is only eligible when a related tag is already selected
 *   ("解开衬衫" needs a shirt to be worn at all);
 * - synonyms / excludes: one member per family, and hand-written pair bans.
 *
 * All of it comes from the library file, so tuning needs no rebuild.
 */
class TagComposerEngine(
    private val library: TagLibrary,
    /**
     * 人数 values this view is allowed to draw. Empty means the library declares no head
     * counts, in which case the slot behaves as before.
     */
    private val peoplePool: Set<String> = emptySet(),
    /**
     * 增强模式 validates everything; 普通模式 only stops things that would contradict each other
     * inside one sentence. See [blockTag] for the split.
     */
    private val strict: Boolean = true,
    /** Null switches the recency weighting off entirely. */
    private val recent: RecentWords? = null
) {

    /**
     * Triggers are pre-indexed as sets. A rule can list a whole category as its trigger, so
     * scanning the list against every selection would grow with the library; iterating the
     * few active values instead keeps the check flat.
     */
    private val skipTriggers: List<Set<String>> = library.skipRules.map { it.whenAny.toSet() }

    /** Rules that name individual words rather than whole categories. */
    private val hasWordRules: Boolean = library.skipRules.any { it.skipWords.isNotEmpty() }

    /**
     * Rules that close one slot rather than a whole category, written as `分类.格`.
     *
     * 一类里各格互不相干时整类让位太狠：形象这一格是妆容，另外两格是年龄感与气质，
     * 挡住脸的只有第一格。当前词库已不再用这种写法，规则留着，自定义词库仍可写 `分类.格`。
     */
    private val slotGates: List<SlotGate> = library.skipRules.mapNotNull { rule ->
        val targets = rule.skip.filter { '.' in it }.toSet()
        if (targets.isEmpty()) {
            null
        } else {
            SlotGate(rule.whenAny.toSet(), targets, rule.from.toSet(), rule.unless.toSet())
        }
    }

    /**
     * A slot-level rule, plus where its triggers may come from.
     *
     * `from` 为空时沿旧行为：任意分类的选中值都能触发。写了 `from` 就只看那几个分类——
     * 「白色」在下衣、袜子、鞋里都有，不限定来源的规则会在裸上身时被下衣的颜色触发。
     */
    private class SlotGate(
        val triggers: Set<String>,
        val targets: Set<String>,
        val from: Set<String>,
        val unless: Set<String>
    )

    private val gatedSlotKeys: Set<String> = slotGates.flatMap { it.targets }.toSet()

    /**
     * 纯场景排布姿势（三人围坐、两人面对面站立）只说明谁跟谁在一起，不说明怎么摆。
     *
     * 动作一旦写清「蹲在他身上」，再叠一个「三人围坐」就是两套体位，模型只能二选一。
     */
    private fun fitsScenePose(candidate: String, picks: Map<String, TagPick>): Boolean {
        if (candidate !in library.constraints.scenePoses) return true
        return selectedWord(picks, SEX_ACT).isNullOrEmpty()
    }

    /**
     * 动作本身已经把体位写死了（蹲在他身上、后入跪趴、仰躺张腿），姿势格再补一个就是
     * 第二套体位：「蹲在他身上」配「三人并排跪坐」「坐在脸上」都读不通。整格让位。
     */
    private fun actNamesPosition(picks: Map<String, TagPick>): Boolean {
        val act = selectedWord(picks, SEX_ACT).orEmpty()
        if (act.isEmpty()) return false
        return ACT_POSITIONS.any { act.contains(it) }
    }

    /** True when the current selection closes this slot by rule (a locked pick outranks it). */
    private fun slotGated(selfKey: String, picks: Map<String, TagPick>): Boolean {
        if (selfKey !in gatedSlotKeys) return false
        val active = selectedValues(picks, excludeMale = true)
        return slotGates.any { gate ->
            if (selfKey !in gate.targets) return@any false
            // 衣襟敞着、衣衫滑到肩下：衣服还在，但已经不是「遮住」的状态。
            if (gate.unless.any { it in active }) return@any false
            val triggers = if (gate.from.isEmpty()) active else valuesFrom(picks, gate.from)
            triggers.any { it in gate.triggers }
        }
    }

    /**
     * 只取这些来源里已选中的词，忽略的不算。
     *
     * 元素可以是分类（`top`）也可以是单格（`bottom.type`）。阴部遮挡必须写单格：写成
     * `bottom` 时下衣的**颜色**也算穿了裙子，光着下身照样被关掉。
     */
    private fun valuesFrom(picks: Map<String, TagPick>, refs: Set<String>): List<String> =
        picks.entries
            .filter { (key, pick) ->
                !pick.ignored && pick.value.isNotEmpty() &&
                    refs.any { ref ->
                        if ('.' in ref) key == ref else key.substringBefore('.') == ref
                    }
            }
            .map { it.value.value }

    /**
     * Words ruled out by the current cover state.
     *
     * 只穿内衣 keeps the bra category so a style can still be described, but "无胸罩" in the same
     * sentence is a contradiction no category-level rule can catch.
     */
    private fun bannedWords(picks: Map<String, TagPick>): Set<String> {
        val active = selectedValues(picks, emptySet())
        val out = mutableSetOf<String>()
        library.skipRules.forEach { rule ->
            if (rule.skipWords.isEmpty()) return@forEach
            if (active.any { it in rule.whenAny }) out.addAll(rule.skipWords)
        }
        return out
    }

    /**
     * 奔跑 and 贴墙抬一条腿 cannot both be true.
     *
     * Each category holds a single value, so neither looks wrong on its own; the contradiction
     * only exists between them. Checked in both directions because the two are drawn in either
     * order depending on the section.
     */
    private fun clashesByMotion(
        categoryId: String,
        candidate: String,
        picks: Map<String, TagPick>
    ): Boolean {
        val roaming = candidate in ROAMING
        val anchored = ANCHORED.any { it in candidate }
        if (!roaming && !anchored) return false
        if (roaming && categoryId == ACTIVITY) {
            val pose = selectedWord(picks, POSE) ?: return false
            return ANCHORED.any { it in pose }
        }
        if (anchored && categoryId == POSE) {
            val act = selectedWord(picks, ACTIVITY) ?: return false
            return act in ROAMING
        }
        return false
    }
    /**
     * 从背后揉着她的乳房 needs a pose that actually turns the body away.
     *
     * Pose vocabulary never records orientation, so the check looks for the words those poses are
     * built from. When nothing is selected yet the word is allowed: it will be re-checked when the
     * pose is drawn, and blocking here would empty the pool for no reason.
     */
    private fun fitsPostureHint(candidate: String, picks: Map<String, TagPick>): Boolean {
        val hints = library.constraints.postureHints[candidate] ?: return true
        // 动作和姿势一起看：后入说明他在她身后，即使姿势本身没写方位。
        val pose = (selectedWord(picks, SEX_ACT).orEmpty() + selectedWord(picks, POSE).orEmpty())
        // 没有任何方位信息时，带方位的词一律不成立——宁可少一只手，也不写矛盾。
        if (pose.isEmpty()) return false
        return hints.any { it in pose }
    }
    /**
     * 口交 keeps the head at the groin; 仰头 turns it up.
     *
     * Each word lists every place the head may be while it happens. Two words disagree only when
     * those sets do not overlap, which is why 低头 still sits next to 口交 while 仰头 cannot.
     */
    /**
     * 水面倒影 needs water and 镜面反射 needs a mirror.
     *
     * A place that is not in the table offers nothing, so the shot is refused there. The check
     * runs both ways because 取景 sits above 场景: whichever is drawn second has to yield.
     */
    private fun fitsSceneProps(candidate: String, picks: Map<String, TagPick>): Boolean {
        val needs = library.constraints.propNeeds[candidate]
        if (needs != null) {
            val scene = selectedWord(picks, SCENE)
            if (scene.isNullOrEmpty()) return true
            val has = library.constraints.sceneProps[scene].orEmpty().toSet()
            return needs.all { it in has }
        }
        // An untagged place offers nothing, but it still has to be checked: otherwise 森林 quietly
        // keeps a mirror shot that the tagged places would have refused.
        val sceneWords = library.category(SCENE)?.slots?.flatMap { it.pool } ?: return true
        if (candidate !in sceneWords) return true
        val available = library.constraints.sceneProps[candidate].orEmpty().toSet()
        return picks.values.none { pick ->
            val other = pick.value
            other.isNotEmpty() && other != candidate &&
                library.constraints.propNeeds[other]?.any { it !in available } == true
        }
    }

    private fun fitsHeadPlacement(candidate: String, picks: Map<String, TagPick>): Boolean {
        val mine = library.constraints.headPlacements[candidate] ?: return true
        return picks.values.none { pick ->
            val other = pick.value
            other.isNotEmpty() && other != candidate &&
                library.constraints.headPlacements[other]?.none { it in mine } == true
        }
    }
    /** 外层遮住了，里层就不抽。手选不受限，只是系统不再自作主张。 */
    /**
     * 脸被挡住时，表情和视线没有落笔的地方。
     *
     * 妆容并进「形象」以后只能按槽判：整类拦会把年龄感和气质一起藏掉，
     * 可脸被手掌盖住并不影响写「二十八岁、冷艳」。
     */
    private fun hiddenByFace(
        categoryId: String,
        picks: Map<String, TagPick>,
        slotId: String? = null
    ): Boolean {
        // 整类依赖脸的（表情、视线、SFW 里独立的妆容）走分类名；
        // NSFW 的「形象」只有妆容那一槽依赖脸，年龄和气质不受影响。
        val hit = if (slotId == null) categoryId in FACE_DEPENDENT
        else "$categoryId.$slotId" in FACE_DEPENDENT || categoryId in FACE_DEPENDENT
        if (!hit) return false
        val head = selectedWord(picks, HEAD_POSE) ?: return false
        return head in FACE_COVERING
    }

    private fun hiddenByLayer(categoryId: String, picks: Map<String, TagPick>): Boolean {
        if (library.hiddenRules.isEmpty()) return false
        return library.hiddenRules.any { rule ->
            if (rule.category != categoryId) return@any false
            // 上衣有颜色、款式、图案三格，露不露要看款式那一格，只取第一格会把每个颜色都当成
            // 「没说清楚」而一律遮住 —— 白名单永远命中不了，等于整条规则白写。
            val covering = selectedWords(picks, rule.coveredBy)
            covering.isNotEmpty() && covering.none { it in rule.visibleWhen }
        }
    }

    /** Trigger word -> the categories its rule switches off. */
    private val skipTargets: Map<String, Set<String>> = buildMap {
        library.skipRules.forEach { rule ->
            val targets = rule.skip.toSet()
            rule.whenAny.forEach { word ->
                put(word, (get(word) ?: emptySet()) + targets)
            }
        }
    }

    fun key(categoryId: String, slotId: String): String = "$categoryId.$slotId"

    private fun groupOf(categoryId: String): String = library.category(categoryId)?.group.orEmpty()

    /** Categories the current selection switches off, e.g. 全裸 removes 上衣 / 下衣. */
    fun skipped(picks: Map<String, TagPick>): Set<String> {
        val skip = mutableSetOf<String>()
        var changed = true
        var rounds = 0
        while (changed && rounds < MAX_RULE_ROUNDS) {
            changed = false
            rounds++
            // 男方身体（who == "male"）的穿脱状态绝不能跨界触发女方衣服的跳过
            val active = selectedValues(picks, skip, excludeMale = true).toSet()
            library.skipRules.forEachIndexed { index, rule ->
                // 敞开、衣衫滑落一类的放行词：规则对它不生效。
                if (rule.unless.any { it in active }) return@forEachIndexed
                // 写了来源就只看那几个分类：「黑色」在下衣、袜子、鞋里都有，不限定来源时
                // 上衣的颜色会把阴部整类关掉。
                val triggers = if (rule.from.isEmpty()) active else valuesFrom(picks, rule.from.toSet())
                if (triggers.any { it in skipTriggers[index] }) {
                    rule.skip.forEach { if (skip.add(it)) changed = true }
                }
            }
        }
        // A word never switches off the category it lives in. 连体衣 sits in 下衣 and its rule
        // says "no 下衣", which deleted the very word that describes the outfit; 赤足 and 光腿
        // did the same to 鞋子 / 袜子.
        if (skip.isNotEmpty()) {
            val owners = picks.entries.filter { it.value.value.isNotEmpty() }
            skip.removeAll { category ->
                owners.any { (slotKey, pick) ->
                    slotKey.substringBefore('.') == category &&
                        category in (skipTargets[pick.value] ?: emptySet())
                }
            }
        }
        // 外层衣物遮挡：上衣挡胸罩、下衣挡内裤。直接接入系统底层 skip 集合，
        // 这样修补程序（repair）和状态恢复（reviveReleased）绝不会擅自回填，UI 也统一呈现「本次已跳过」。
        library.hiddenRules.forEach { rule ->
            val covering = selectedWords(picks, rule.coveredBy)
            if (covering.isNotEmpty() && covering.none { it in rule.visibleWhen }) {
                skip.add(rule.category)
            }
        }
        // 取景把胸部裁掉了（背面、私处特写、下半身特写）：三格整类让位，面板显示
        // 「本次已跳过」。否则空着三格和「抽不到」长得一样。
        if (frameHidesChest(picks)) skip.add("breasts")
        // 主戏在身时不会同时在煮咖啡、拖地、写毛笔字。装饰性动作（接吻、射在脸上、
        // 抚摸大腿内侧）不算主戏，边喝咖啡边接吻说得通，所以只认 anchors.addOn 之外的。
        val actWord = library.category(SEX_ACT)?.slots?.firstOrNull()
            ?.let { picks[key(SEX_ACT, it.id)] }
            ?.takeIf { !it.ignored && it.value.isNotEmpty() }
            ?.value
        if (actWord != null && actWord !in library.anchors.addOn) skip.add(ACTIVITY)
        // 一件式整身包住，下衣与内裤一起让位。锁住的格子不抢：锁住就是玩家明确要的那一件，
        // 那种情况下连身件根本不会抽出来（见 fitsOnePiece），两者不会同时成立。
        if (wornType(picks, "top") in library.constraints.onePiece) {
            if (!lockedType(picks, "bottom")) skip.add("bottom")
            if (!lockedType(picks, "panties")) skip.add("panties")
        }
        // 反向：下衣里的连体衣、西装套裙本身就带住了上半身。
        if (wornType(picks, "bottom") in library.constraints.onePieceBottom && !lockedType(picks, "top")) {
            skip.add("top")
        }
        return skip
    }

    /**
     * 束缚道具只能跟着对应玩法出场。
     *
     * 「全裸自慰，拿着束缚绳」是玩具格自己抽出来的：绳子没有用处，还占着画面。没有登记
     * 玩法的道具随时可抽，所以这里只拦「登记了、但当前玩法不对」的那些。
     */
    private fun toyFitsTheAct(candidate: String, picks: Map<String, TagPick>): Boolean {
        val act = picks[key(SEX_ACT, "act")]?.takeIf { !it.ignored }?.value.orEmpty()
        // 玩法点名要的那件道具之外的，一律让位：否则「捆绑」配的是缩阴球。
        val required = library.constraints.requires[act]
            ?.firstOrNull { it.startsWith("toys:") }
            ?.substringAfter(':')
        if (required != null) return candidate == required
        // 主戏没选就无从判断玩法。这种时候整格是纯道具装饰，登记过玩法的也放行，
        // 否则「卧室里挂着吊环」会因为没写性行为而被删掉。
        if (act.isEmpty()) return true
        val acts = library.constraints.toyActs[candidate] ?: return true
        // 登记为「通用」的道具（润滑液、听诊器）与「未登记」分开：前者是确认过不挑玩法，
        // 后者是还欠一份玩法清单。两者都放行，但只有后者该在体检里报出来。
        if (acts.isEmpty() || GENERAL_TOY_ACT in acts) return true
        return act in acts
    }

    /** 这一分类「款式」格上选中的词（颜色格不参与整身判断）。 */
    private fun wornType(picks: Map<String, TagPick>, categoryId: String): String =
        picks[key(categoryId, TYPE_SLOT)]?.takeIf { !it.ignored }?.value.orEmpty()

    /** 这一分类的款式格是否被玩家锁了一个非空值。 */
    private fun lockedType(picks: Map<String, TagPick>, categoryId: String): Boolean =
        picks[key(categoryId, TYPE_SLOT)]?.let { it.locked && it.value.isNotEmpty() } == true

    /**
     * 锁了下衣或内裤时，一件式上衣不参与抽取。
     *
     * 手选的东西优先于系统整理：玩家既然指定了裙子，就不能让系统抽出「婚纱」再把裙子关掉。
     */
    private fun fitsOnePiece(candidate: String, picks: Map<String, TagPick>, selfKey: String): Boolean {
        if (selfKey != key("top", TYPE_SLOT)) return true
        if (candidate !in library.constraints.onePiece) return true
        return !lockedType(picks, "bottom") && !lockedType(picks, "panties")
    }

    /**
     * 白衬衫 already says 白; prefixing the colour slot writes 黑色的白衬衫.
     *
     * Only the colour slot yields: the style slot keeps whatever it holds. The registry is a
     * list rather than a colour-character test because 青城弟子服 and 青丘侍女裙 are places, not
     * 青色, and 红莲妖姬套装's 红莲 is an image.
     */
    private fun fitsSelfColour(candidate: String, picks: Map<String, TagPick>, selfKey: String): Boolean {
        val categoryId = selfKey.substringBefore('.')
        if (selfKey.substringAfter('.', "") != COLOR_SLOT) return true
        val type = wornType(picks, categoryId)
        if (type.isEmpty()) return true
        // 自带图案的款式同样不吃颜色，否则「白色的豹纹内裤」。
        if (type in library.constraints.selfColoured || type in library.constraints.selfPatterned) return false
        // 名单兜底：以后新加的花色款不必逐条登记，名字里带这些词根就当作自带花色的。
        return PATTERN_ROOTS.none { it in type }
    }

    /** Drops the colour slot under a garment that already names its colour, or names nothing. */
    private fun dropSelfColouredColour(picks: MutableMap<String, TagPick>, categoryId: String) {
        val colourKey = key(categoryId, COLOR_SLOT)
        val pick = picks[colourKey] ?: return
        if (pick.value.isEmpty() || pick.locked) return
        val worn = wornType(picks, categoryId)
        // 款式本身就说「没穿」，前面再挂一个颜色就是「蓝色的无内裤」。
        if (worn in BARE_STATE_WORDS) {
            picks[colourKey] = pick.copy(value = "")
            return
        }
        // 自带图案的款式和自带上色的一样，再挂一个颜色就撞：「白色的豹纹内裤」。
        if (worn !in library.constraints.selfColoured && worn !in library.constraints.selfPatterned) return
        picks[colourKey] = pick.copy(value = "")
    }

    /** Pools the anchor act prefers, and the poses it allows. Cleared after every draw. */
    private var softPools: Map<String, List<String>> = emptyMap()
    private var anchorAct: String? = null
    private var cachedCrowd: Int = -1
    private var cachedPoseWhitelist: Set<String> = emptySet()

    /** Slots already decided before the main sweep, so the loop does not roll them twice. */
    private var preDrawn: Set<String> = emptySet()

    /**
     * One line about the draw that just finished, for the debug log.
     *
     * The composer used to be silent, so a sparse board carried no evidence: the exported log
     * from a report covered 42 seconds of a later session and not one line of the draw. This
     * costs a string per draw and answers "which category came back empty, and was the hand
     * budget full" without a debugger attached.
     */
    private var lastDrawNote: String = ""
    fun drawNote(): String = lastDrawNote

    /**
     * 画幅 / 构图 / 出画 are settled before anything else exists.
     *
     * The anchor act is drawn ahead of the main sweep and is nearly always a groin-level tag,
     * so every crop word that hides groin lost the argument against content it was supposed to
     * remove. Measured over 300 draws: only 膝盖以上 / 只露出背影 / 胸部在画外 / 脚部在画外 ever
     * appeared — exactly the four words that do not hide groin.
     */
    private fun drawFraming(
        picks: MutableMap<String, TagPick>,
        skip: MutableSet<String>,
        holdGroups: Set<String> = emptySet()
    ): Set<String> {
        val drawn = mutableSetOf<String>()
        // The lens yields to one thing only: the act. Checked against the anchor directly
        // rather than through `satisfies`, because at this point `picks` is still holding the
        // previous draw's body words — passing framingWins = false would let stale content
        // veto every crop word instead of just the four that hide groin.
        val actRegions = library.category(SEX_ACT)?.slots?.firstOrNull()
            ?.let { picks[key(SEX_ACT, it.id)]?.value }
            ?.takeIf { it.isNotEmpty() }
            ?.let { regionsOf(it, SEX_ACT) }
            .orEmpty()
        fun keepsTheAct(candidate: String): Boolean =
            actRegions.isEmpty() ||
                library.constraints.frameHides[candidate].orEmpty().none { it in actRegions }
        library.categories
            .filter { it.id in FRAMING }
            .sortedBy { it.tier }
            .forEach { category ->
                if (category.id in skip) return@forEach
                if (category.group in holdGroups) {
                    category.slots.forEach { drawn += key(category.id, it.id) }
                    return@forEach
                }
                category.slots.forEach { slot ->
                    val slotKey = key(category.id, slot.id)
                    val current = picks[slotKey] ?: TagPick()
                    if (current.locked || current.ignored) {
                        drawn += slotKey
                        return@forEach
                    }
                    val accepted = selectedValues(picks, skip).toMutableSet().apply {
                        remove(current.value)
                    }
                    var pool = slot.randomPool().filter { candidate ->
                        allowedIn(category.id, candidate) &&
                            !conflicts(candidate, accepted) &&
                            keepsTheAct(candidate) &&
                            satisfies(candidate, picks, slotKey, skip, framingWins = true)
                    }
                    // 没穿衣服时胸部是主角，裁胸的取景留三成偶然性就够了，不占一半。
                    if (category.id == CAMERA && chestBare(picks)) {
                        pool = pool.filter { candidate ->
                            library.constraints.frameHides[candidate].orEmpty().none { it == CHEST } ||
                                kotlin.random.Random.nextInt(100) < 30
                        }.ifEmpty { pool }
                    }
                    picks[slotKey] = current.copy(value = pickRandom(pool, current.value, slotKey) ?: "")
                    drawn += slotKey
                }
            }
        return drawn
    }

    /**
     * @param holdGroups 整段锁住的段。这里连清空都不做：锁住的意思是这一段保持原样，
     *   而不是"重抽后再放回去"——后者会顺手把被规则关掉的格子清掉。
     */
    fun randomize(
        picks: MutableMap<String, TagPick>,
        onlyCategory: String? = null,
        holdGroups: Set<String> = emptySet()
    ) {
        val skip: MutableSet<String> = skipped(picks).toMutableSet()
        // What this pass starts from. A draw can release a skip as well as add one, and the
        // released categories are never redrawn on their own — 只穿内衣 → 衣冠不整 left
        // 上衣 / 下衣 blank with no rule to explain it.
        val skipBefore: Set<String> = skip.toSet()
        val ordered = library.categories.sortedBy { it.tier }

        // preDrawn is a field so the main sweep can skip what was already drawn. Left over
        // from the previous call, it made a category reroll silently do nothing: tapping 构图
        // or 人数 after a full draw hit its own key and returned without drawing.
        preDrawn = emptySet()

        if (onlyCategory == null) {
            // 人数, then the act, then the lens. Framing used to come first so crop words could
            // be drawn at all (they lost every argument against a full board), but that let the
            // lens delete the act: 4 of the 8 crop words hide groin, so half of all draws came
            // out with no sexual content at all. Drawing the act first and letting framing
            // yield to it restores the original design: the act is the anchor, everything else
            // is arranged around it. 出画 still fills — the four words that do not hide groin
            // stay available — and all eight come back whenever 性行为 is off.
            preDrawn = mutableSetOf()
            if (groupOf(PEOPLE) !in holdGroups) preDrawn += setOfNotNull(drawHeadCount(picks))
            // 场地先定，动作才有的挑。锚点过去比场景先抽，读到的还是上一轮的值：
            // 换了场景，「抵墙站立插入」仍然按旧场地判成合法。
            if (groupOf(SCENE) !in holdGroups) preDrawn += setOfNotNull(drawScene(picks))
            pickAnchor(picks, holdGroups)
            preDrawn += drawFraming(picks, skip, holdGroups)
            // 主戏是刚抽出来的，跳过集合也得跟着更新：activity 排在 sexAct 后面，
            // 不重算就会让「自慰 + 洗菜」落到同一句里。
            skip.addAll(skipped(picks))
        }
        try {
        ordered.forEach { category ->
            if (onlyCategory != null && category.id != onlyCategory) return@forEach
            // 整段锁住：连跳过规则的清空都绕开，这一段在本次抽取里完全不动。
            if (category.group in holdGroups) return@forEach
            if (category.id in skip) {
                category.slots.forEach { slot ->
                    val pick = picks[key(category.id, slot.id)] ?: TagPick()
                    if (!pick.locked) picks[key(category.id, slot.id)] = pick.copy(value = "")
                }
                return@forEach
            }
            if (maleCategoryBlocked(category.id, picks)) {
                category.slots.forEach { slot ->
                    val pick = picks[key(category.id, slot.id)] ?: TagPick()
                    if (!pick.locked) picks[key(category.id, slot.id)] = pick.copy(value = "")
                }
                return@forEach
            }
            if (hiddenByFace(category.id, picks)) {
                category.slots.forEach { slot ->
                    val pick = picks[key(category.id, slot.id)] ?: TagPick()
                    if (!pick.locked) picks[key(category.id, slot.id)] = pick.copy(value = "")
                }
                return@forEach
            }
            if (hiddenByLayer(category.id, picks)) {
                category.slots.forEach { slot ->
                    val pick = picks[key(category.id, slot.id)] ?: TagPick()
                    if (!pick.locked) picks[key(category.id, slot.id)] = pick.copy(value = "")
                }
                return@forEach
            }
            category.slots.forEachIndexed { index, slot ->
                val slotKey = key(category.id, slot.id)
                if (slotKey in preDrawn) return@forEachIndexed
                val current = picks[slotKey] ?: TagPick()

                if (current.locked) {
                    if (index + 1 < category.slots.size) skip.addAll(skipped(picks))
                    return@forEachIndexed
                }
                // An ignored slot is switched off for the session: leave it alone rather than
                // drawing a value the player never asked for.
                if (current.ignored) return@forEachIndexed
                // 同场动作写的是「另一个人在做什么」，一男一女只有两个人，写出来就是凭空多一个人。
                // 一格要满三人、第二格要满四人，人数不够就留空。
                if (category.id == PARTNER_ACT && !partnerHasSomeone(picks, slot.id)) {
                    if (!current.locked && current.value.isNotEmpty()) {
                        picks[slotKey] = current.copy(value = "")
                    }
                    return@forEachIndexed
                }

                val accepted = selectedValues(picks, skip).toMutableSet().apply { remove(current.value) }
                val eligible = slot.randomPool().filter { candidate ->
                    allowedIn(category.id, candidate) &&
                        !conflicts(candidate, accepted) &&
                        !duplicatesPartner(category.id, slot.id, candidate, picks) &&
                        satisfies(candidate, picks, slotKey, skip)
                }
                val narrowed = narrowFor(category.id, slot.id, eligible, picks)
                val pool = preferPeople(category.id, narrowed, picks)
                val next = pickRandom(pool, current.value, slotKey)
                val updated = when {
                    next != null -> current.copy(value = next)
                    // Nothing left to draw: drop the value only when it now conflicts.
                    current.value.isNotEmpty() && conflicts(current.value, accepted) -> current.copy(value = "")
                    else -> current
                }
                picks[slotKey] = updated
                if (index + 1 < category.slots.size) skip.addAll(skipped(picks))
            }
            dropSelfColouredColour(picks, category.id)
            skip.addAll(skipped(picks))
        }
        } finally {
            softPools = emptyMap()
            anchorAct = null
            cachedCrowd = -1
            cachedPoseWhitelist = emptySet()
            preDrawn = emptySet()
        }
        // A single-category reroll can strand another slot; settle the whole selection.
        repair(picks, skipBefore = skipBefore, holdGroups = holdGroups)
        lastDrawNote = describeDraw(picks)
    }

    /**
     * Draws the categories a reroll just let out of skip.
     *
     * `repair()` only removes what is no longer allowed; it never puts back what a previous
     * state had switched off. Rerolling 穿脱 from 只穿内衣 to 拉链半开 therefore lifted the
     * skip on 上衣 / 下衣 and left both blank — the UI showed 未选 while the engine reported
     * no skip at all.
     */
    private fun reviveReleased(
        picks: MutableMap<String, TagPick>,
        skipBefore: Set<String>,
        holdGroups: Set<String> = emptySet()
    ) {
        val released = skipBefore - skipped(picks)
        if (released.isEmpty()) return
        released.forEach { categoryId ->
            val category = library.category(categoryId) ?: return@forEach
            if (category.group in holdGroups) return@forEach
            val allBlank = category.slots.all { slot ->
                picks[key(categoryId, slot.id)]?.value.isNullOrEmpty() != false
            }
            if (!allBlank) return@forEach
            drawOneCategory(picks, category)
        }
    }

    /** Draws one category once. Used when a lifted skip has nobody else to refill it. */
    private fun drawOneCategory(picks: MutableMap<String, TagPick>, category: TagCategory) {
        val skip = skipped(picks)
        if (category.id in skip) return
        if (maleCategoryBlocked(category.id, picks)) return
        category.slots.forEach { slot ->
            val slotKey = key(category.id, slot.id)
            val current = picks[slotKey] ?: TagPick()
            if (current.locked || current.ignored) return@forEach
            if (category.id == PARTNER_ACT && !partnerHasSomeone(picks, slot.id)) {
                // 人数被上游改少了（从一男两女退到一男一女），这格也得跟着清掉。
                if (current.value.isNotEmpty()) picks[slotKey] = current.copy(value = "")
                return@forEach
            }
            val accepted = selectedValues(picks, skip).toMutableSet().apply { remove(current.value) }
            val pool = slot.randomPool().filter { candidate ->
                allowedIn(category.id, candidate) &&
                    !conflicts(candidate, accepted) &&
                    !duplicatesPartner(category.id, slot.id, candidate, picks) &&
                    satisfies(candidate, picks, slotKey, skip)
            }
            val chosen = pickRandom(preferPeople(category.id, narrowFor(category.id, slot.id, pool, picks), picks), current.value, slotKey)
            if (chosen != null) picks[slotKey] = current.copy(value = chosen)
        }
        dropSelfColouredColour(picks, category.id)
    }

    private fun describeDraw(picks: Map<String, TagPick>): String {
        val active = picks.filter { it.value.value.isNotEmpty() && !it.value.ignored }
        val skip = skipped(picks)
        val blank = library.categories
            .filter { it.id !in skip }
            .filter { category -> category.slots.none { slot -> active.containsKey(key(category.id, slot.id)) } }
            .joinToString(",") { it.id }
        val cap = library.constraints.handCap
        val used = active.values.sumOf { library.constraints.handCost[it.value] ?: 0 }
        val act = library.category(SEX_ACT)?.slots?.firstOrNull()
            ?.let { picks[key(SEX_ACT, it.id)]?.value }.orEmpty()
        return "filled=${active.size} hand=$used/$cap act=${act.ifEmpty { "-" }} blank=[$blank]"
    }

    /**
     * Draws the act first, then lets it steer the rest of the scene.
     *
     * Picking poses before knowing the act is what produced "standing" next to "lying oral":
     * the most constrained slot was filled blind, and the act had to take whatever was left.
     * Acts that only decorate another act (cum on face, kissing) are kept out of the draw so
     * the anchor is always something that decides the body.
     */
    /**
     * 人数 has to exist before the act is drawn.
     *
     * The act decides what the whole scene can contain, so it was drawn first — but the head
     * count was still the previous one, and the loop below only rolled it afterwards. A
     * two-man act could therefore land on a three-women selection and pull a man in from
     * nowhere. Drawing the count first makes the gate mean something.
     */
    private fun drawHeadCount(picks: MutableMap<String, TagPick>): String? {
        val category = library.category(PEOPLE) ?: return null
        val slot = category.slots.firstOrNull() ?: return null
        val slotKey = key(category.id, slot.id)
        val current = picks[slotKey] ?: TagPick()
        if (current.locked || current.ignored) return slotKey
        val pool = slot.randomPool().filter { allowedIn(category.id, it) }
        if (pool.isEmpty()) return null
        val next = pickRandom(pool, current.value, slotKey) ?: return null
        picks[slotKey] = current.copy(value = next)
        return slotKey
    }

    /** 当前取景会不会把胸部裁出画外。 */
    private fun frameHidesChest(picks: Map<String, TagPick>): Boolean {
        val hides = library.constraints.frameHides
        return picks.entries.any { (slotKey, pick) ->
            slotKey.startsWith("camera.") && !pick.ignored && pick.value.isNotEmpty() &&
                hides[pick.value].orEmpty().any { it == CHEST }
        }
    }

    /** 全裸、上身赤裸、衣襟敞开这类状态，胸口是本该出现的内容。 */
    private fun chestBare(picks: Map<String, TagPick>): Boolean {
        val state = selectedWord(picks, OUTFIT_STATE).orEmpty()
        if (state.isEmpty()) return false
        return listOf("全裸", "上身赤裸", "只穿内衣", "衣襟", "敞开", "半褪", "只围", "只剩")
            .any { it in state }
    }

    /**
     * 场地先于动作定下来。
     *
     * 这里只挡人数与性别这类硬门，不做场地反向检查：场地本身就是要换的东西，让旧姿态否决
     * 新场地，等于再也换不了场景。抽完场景后抽到的动作与它不配，由 pickAnchor 过滤、repair 收尾。
     */
    private fun drawScene(picks: MutableMap<String, TagPick>): String? {
        val category = library.category(SCENE) ?: return null
        val slot = category.slots.firstOrNull() ?: return null
        val slotKey = key(category.id, slot.id)
        val current = picks[slotKey] ?: TagPick()
        if (current.locked || current.ignored) return slotKey
        if (category.id in skipped(picks)) return slotKey
        val pool = slot.randomPool().filter { allowedIn(category.id, it) }
        if (pool.isEmpty()) return slotKey
        val next = pickRandom(pool, current.value, slotKey) ?: return slotKey
        picks[slotKey] = current.copy(value = next)
        return slotKey
    }

    private fun pickAnchor(picks: MutableMap<String, TagPick>, holdGroups: Set<String> = emptySet()) {
        val category = library.category(SEX_ACT) ?: return
        val slot = category.slots.firstOrNull() ?: return
        val slotKey = key(category.id, slot.id)
        val current = picks[slotKey] ?: TagPick()

        // 性行为所在段被锁住：动作不重抽，但锚点仍要登记，否则姿势白名单会按"没有动作"
        // 放开整池，锁住的动作与姿势就能各说各话。
        if (category.group in holdGroups) {
            if (current.value.isNotEmpty()) {
                anchorAct = current.value
                applySoft(current.value)
            }
            return
        }

        if (current.locked && current.value.isNotEmpty()) {
            anchorAct = current.value
            applySoft(current.value)
            return
        }

        // Switching 性行为 off has to mean something: the anchor is drawn before the sweep, so
        // ignoring the category used to leave the act in place. Cleared-and-ignored is the one
        // way to ask for a scene with no act at all.
        if (current.ignored) {
            if (current.value.isNotEmpty()) {
                anchorAct = current.value
                applySoft(current.value)
            }
            return
        }

        val eligible = slot.randomPool().filter { candidate ->
            allowedIn(category.id, candidate) &&
                fitsMale(candidate, picks) &&
                fitsPeople(candidate, picks, key(category.id, slot.id)) &&
                fitsExactPeople(candidate, picks) &&
                fitsAllowedPostures(candidate, picks) &&
                // 主扫会查场地，锚点不走 satisfies，所以这里得自己补上；否则「抵墙」类动作
                // 永远不经过 placeSupports。
                (!strict || fitsSurface(candidate, picks))
        }
        val addOn = library.anchors.addOn.toSet()
        val anchors = eligible.filter { it !in addOn }
        val chosen = pickRandom(if (anchors.size >= 4) anchors else eligible, current.value, slotKey) ?: return
        picks[slotKey] = current.copy(value = chosen)
        anchorAct = chosen
        applySoft(chosen)
    }

    private fun applySoft(act: String) {
        val soft = library.anchors.soft(act) ?: return
        softPools = listOf(EXPRESSION, GAZE_DIR, FLUIDS, CAMERA, OUTFIT_STATE)
            .associateWith { soft.forCategory(it) }
            .filterValues { it.isNotEmpty() }
    }

    /** Activities the anchor act allows. Words with no declared posture are never filtered. */
    private fun activityWhitelistFor(): Set<String> {
        val act = anchorAct ?: return emptySet()
        val allow = library.constraints.allowedPostures[act]
        if (allow.isNullOrEmpty()) return emptySet()
        val category = library.category(ACTIVITY) ?: return emptySet()
        val postureOf = library.constraints.posture
        return category.slots.flatMap { it.pool }
            .filter { postureOf[it] == null || postureOf[it] in allow }
            .toSet()
    }

    /** Poses the anchor act can physically happen in, given how many bodies are in frame. */
    private fun poseWhitelistFor(crowd: Int): Set<String> {
        if (cachedCrowd == crowd) return cachedPoseWhitelist
        val act = anchorAct
        val allow = act?.let { library.constraints.allowedPostures[it] }
        val category = library.category(POSE)
        val result = if (allow.isNullOrEmpty() || category == null) emptySet() else {
            val postureOf = library.constraints.posture
            val people = library.constraints.people
            val exact = library.constraints.exactPeople.toSet()
            category.slots.flatMap { it.pool }
                .filter { postureOf[it] in allow }
                .filter { (people[it] ?: 1) <= crowd }
                .filter { it !in exact || (people[it] ?: 1) == crowd }
                .toSet()
        }
        cachedCrowd = crowd
        cachedPoseWhitelist = result
        return result
    }

    private fun narrowFor(
        categoryId: String,
        slotId: String,
        eligible: List<String>,
        picks: Map<String, TagPick>
    ): List<String> {
        if (eligible.isEmpty()) return eligible
        // 霓虹夜色 without neon is just a night portrait, so the style pins the 调色 word it
        // needs. Narrowing the pool (rather than rewriting the output) keeps the panel honest:
        // the player sees the word that will actually be used, and can still change it.
        if (categoryId == GRADE && slotId == "feel") {
            val forced = library.styleFeel[canonicalStyle(selectedWord(picks, MOOD).orEmpty())]
            if (forced != null) return eligible.filter { it == forced }.ifEmpty { eligible }
        }
        raceAllowed(categoryId, slotId, picks)?.let { allowed ->
            return eligible.filter { it in allowed }
        }
        val preferred = if (categoryId == POSE) {
            poseWhitelistFor(crowdSize(picks) ?: 1)
        } else if (categoryId == ACTIVITY) {
            // 动作 never used to look at the act's permitted postures, so 后入跪趴 could be
            // paired with 瑜伽动作. Only words that declare a posture are constrained; the rest
            // pass through untouched.
            activityWhitelistFor()
        } else {
            softPools[categoryId] ?: return eligible
        }
        if (preferred.isEmpty()) return eligible
        return eligible.filter { it in preferred }.ifEmpty { eligible }
    }

    /**
     * What the prompt should push away, derived from the chosen medium.
     *
     * A style word alone rarely holds: the encoder is already biased toward photographic
     * realism because that is what most training data looks like. Naming the thing you do not
     * want is what actually keeps the picture on the chosen side.
     */
    fun negatives(picks: Map<String, TagPick>, english: Boolean = false): String {
        val category = library.category(MOOD) ?: return ""
        if (MOOD in skipped(picks)) return ""
        val text = activeValues(picks, category).values.joinToString("")
        if (text.isEmpty()) return ""

        // 动漫 is its own word: "日式动漫" contains neither 动画 nor 漫画, so it used to fall
        // through both checks and ship with no negative at all.
        val drawn = listOf(
            "2D", "动画", "动漫", "漫画", "插画", "平涂", "水墨", "铅笔", "色粉",
            "油画", "水彩", "版画", "拼贴", "黏土", "赛璐璐", "立绘", "厚涂",
        ).any { text.contains(it) }
        // 三维和实拍怕的东西不一样。三维怕被画成照片的颗粒与噪点，实拍怕被画成
        // CG 的塑料光泽。混成一条「不是画」的反向词，两边都防不住。
        val is3d = text.contains("3D")
        val isPhoto = !is3d && listOf("写实", "摄影", "胶片", "实拍", "照片")
            .any { text.contains(it) }

        return when {
            drawn && english ->
                "3d render, 3d model, photorealistic, real skin texture, figurine, blind box, plastic sheen"
            drawn -> "3D渲染, 三维模型, 照片写实, 真实皮肤质感, 手办, 盲盒, 塑料光泽"
            is3d && english ->
                "anime, cartoon, hand-drawn lines, flat colour blocking, photo grain, camera noise"
            is3d -> "动漫, 卡通, 手绘线条, 平涂色块, 实拍照片, 胶片颗粒"
            isPhoto && english ->
                "anime, cartoon, hand-drawn lines, flat colour blocking, 3d render, cg, plastic sheen"
            isPhoto -> "动漫, 卡通, 手绘线条, 平涂色块, 3D渲染, CG, 塑料光泽"
            else -> ""
        }
    }

    /**
     * Stitches the current selection into a prompt.
     *
     * Order matters as much as the tags themselves: a text encoder drops detail past its token
     * window, so the medium and framing have to lead or they get ignored and the model falls
     * back to its default look. Grouped into three lines, most-defining first.
     */
    /** Output order from the catalog when it carries one, else the built-in default. */
    private val tiers: List<List<String>> get() = library.promptTiers.ifEmpty { PROMPT_TIERS }

    fun compose(picks: Map<String, TagPick>): String {
        val byCategory = collectPhrases(picks)
        return if (library.sectionMode) sectionedPrompt(byCategory, picks) else legacyPrompt(byCategory)
    }

    /**
     * One sentence for whoever is in frame: 妆容，年龄的国籍气质女名词.
     *
     * 碎片并列时「二十八岁」没有归属，异族更只剩一串特征没有名词收尾。这句话把名词放在
     * 句尾：人类以职业收，异族以种族名收，没有职业时退回 女孩 / 女性。
     */
    private fun personClause(picks: Map<String, TagPick>): String? {
        fun value(categoryId: String, slotId: String) =
            picks[key(categoryId, slotId)]?.value.orEmpty()

        val kind = value(RACE, "kind")
        val ethnicity = value(RACE, "ethnicity")
        // 日常档没有「形象」分类，年龄感是单独一格（age.range），不兜底就整句少一个年龄段。
        val age = value(PERSON, "age").ifEmpty { value("age", "range") }
        val look = value(PERSON, "look").ifEmpty { value("look", "kind") }
        // 妆容是人物句直接读的，不经过分类表那道遮挡；脸埋进手掌时这里也得自己让掉。
        // NSFW 把妆容并进了「形象」；SFW 那边仍是独立分类，两档都要能取到。
        val faceCat = if (library.category(PERSON)?.slots?.any { it.id == "face" } == true) PERSON else "makeup"
        // 取景把脸裁掉（背面视角、只露出背影）时妆容也没有落笔的地方，年龄和气质不受影响。
        val framing = hiddenRegionsOf(picks.values.filter { it.value.isNotEmpty() }.map { it.value })
        val makeup = if ("face" in framing || hiddenByFace(faceCat, picks, "face")) "" else value(faceCat, "face")
        // 日常档的种族格写的是「东亚面孔」这类地域词。它不是异族，直接当名词会成
        // 「她是一名东亚面孔」，也会走去种族那一支补前置，收成「东亚女性」才站得住。
        val faceKind = kind.endsWith("面孔")
        val fantasy = kind.isNotEmpty() && kind != HUMAN_KIND && !faceKind
        // 身份没有了，人类一律用 女孩 / 女性 收尾，按年龄切换，免得写出「二十八岁的女」。
        val noun = when {
            faceKind -> kind.removeSuffix("面孔") + "女性"
            fantasy -> library.constraints.raceName[kind] ?: kind
            else -> womanNoun(age)
        }
        val faceDetailLook = if (look in FACE_DETAIL_LOOKS) look else ""
        val temperamentLook = if (look in FACE_DETAIL_LOOKS) "" else formatLookModifier(lookAfter(age, look))
        val (lookTail, nounTail) = womanTail(temperamentLook, noun)
        // 女警 / 龙女 自带「女」，再加就成了 女女警；夜班护士这种四字职业前面塞一个「女」
        // 也读不顺；气质是「欲女」时同样会叠成「欲女女护士」。一律不加。
        val female = if (nounTail.isEmpty() || noun.contains("女") || nounTail.length > 3 ||
            lookTail.endsWith("女") || nounTail in GENDERLESS
        ) "" else "女"
        val core = if (fantasy) {
            val lead = library.constraints.raceLead[kind].orEmpty()
            buildString {
                append(lead)
                append(age)
                // 没有前置（自定义种族没写 raceLead）或前置以「，」收尾时，年龄要带「的」；
                // 已经以「的」收尾就别再叠一个。
                if (age.isNotEmpty() && (lead.isEmpty() || lead.endsWith("，"))) append("的")
                append(ethnicity)
                append(lookTail)
                append(female)
                append(nounTail)
            }
        } else {
            buildString {
                if (age.isNotEmpty()) append(age).append("的")
                append(ethnicity)
                append(lookTail)
                append(female)
                append(nounTail)
            }
        }
        return listOf(makeup, faceDetailLook, core).filter { it.isNotEmpty() }.joinToString("，").ifEmpty { null }
    }

    /** 年龄词里已经带了「熟女」，气质再写一遍就成了「四十岁熟女的熟女护士」。 */
    private fun lookAfter(age: String, look: String): String =
        if (look.isNotEmpty() && age.contains(look)) "" else look

    /** 「媚态」「风尘味」「纯欲风」等名词型气质直接贴在「女孩」前会生硬，补上连词同时保留原词子串。 */
    private fun formatLookModifier(look: String): String = when {
        look.isEmpty() -> ""
        look in WTIH_DE_LOOKS -> "带着${look}的"
        look in DE_SUFFIX_LOOKS -> "${look}的"
        else -> look
    }

    /**
     * 气质本身以「女」结尾时，收尾名词要换个接法。
     *
     * 「熟女」+「女性」是「熟女女性」，「绝世美女」+「女警」是「绝世美女女警」。前一种让
     * 气质自己当名词，后一种用「般的」隔开。返回（气质写法，名词写法）。
     */
    private fun womanTail(look: String, noun: String): Pair<String, String> = when {
        !look.endsWith("女") || noun.isEmpty() -> look to noun
        noun == "女性" || noun == "女孩" -> look to ""
        noun.startsWith("女") || noun.endsWith("女") -> "${look}般的" to noun
        else -> look to noun
    }

    /** 没有职业时的收尾名词：年轻用女孩，成熟用女性。 */
    private fun womanNoun(age: String): String =
        if ((ageNumber(age) ?: 99) <= 28) "女孩" else "女性"

    /**
     * 两个人以上时，第二位、第三位女性各补一句独立描写。
     *
     * 原来只有一套人物属性，第二位女性只有动作没有长相，模型只能把她画成第一个人的
     * 复制品。这里从剩下的词里给她配年龄、气质和种族，并把第一个人的选择排除掉。
     * 随机数按当前所有选中值固定，重绘不会改字，只有重抽才换人。
     */
    private fun companionClauses(women: Int, picks: Map<String, TagPick>): List<String> {
        if (women < 2) return emptyList()
        val seed = picks.entries.sortedBy { it.key }
            .joinToString("|") { "${it.key}=${it.value.value}" }
            .hashCode()
        val random = Random(seed)
        fun pool(categoryId: String, slotId: String): MutableList<String> =
            library.category(categoryId)?.slots?.firstOrNull { it.id == slotId }
                ?.pool?.toMutableList() ?: mutableListOf()

        val kinds = pool(RACE, "kind").apply { remove(picks[key(RACE, "kind")]?.value.orEmpty()) }
        val ages = pool(PERSON, "age").apply { remove(picks[key(PERSON, "age")]?.value.orEmpty()) }
        val looks = pool(PERSON, "look").apply { remove(picks[key(PERSON, "look")]?.value.orEmpty()) }
        val hairColors = pool("hair", "color").apply { remove(picks[key("hair", "color")]?.value.orEmpty()) }
        val hairStyles = pool("hair", "style").apply { remove(picks[key("hair", "style")]?.value.orEmpty()) }

        // 一男多女时每一位都要有外貌，不能只写前两位：四人档第四位一直是个哑巴。
        return (2..women).map {
            val kind = kinds.randomOrNull(random).orEmpty().also(kinds::remove)
            val age = ages.randomOrNull(random).orEmpty().also(ages::remove)
            val look = looks.randomOrNull(random).orEmpty().also(looks::remove)
            val hairColor = hairColors.randomOrNull(random).orEmpty().also(hairColors::remove)
            val hairStyle = hairStyles.randomOrNull(random).orEmpty().also(hairStyles::remove)
            // 锁骨微卷、湿发贴脸这类状态词不是发型名，前面挂颜色会读成「金色锁骨微卷」，退回长发；
            // 马尾、丸子头、波波头、姬发式、麻花辫等正常发型名词直接接颜色。
            val hair = when {
                hairColor.isEmpty() -> ""
                hairStyle.isNotEmpty() &&
                    hairStyle !in library.constraints.hairState &&
                    !hairStyle.contains("的") ->
                    hairColor + hairStyle
                else -> hairColor + "长发"
            }
            val fantasy = kind.isNotEmpty() && kind != HUMAN_KIND
            val noun = if (fantasy) library.constraints.raceName[kind] ?: kind else womanNoun(age)
            val faceDetail = if (look in FACE_DETAIL_LOOKS) look else ""
            val cleanLook = if (look in FACE_DETAIL_LOOKS) "" else formatLookModifier(lookAfter(age, look))
            // 年龄词已经写着「熟女」，气质再补一个就是「四十岁熟女的熟女机械人形」。
            val (lookOut, nounOut) = womanTail(cleanLook, noun)
            val female = if (nounOut.isEmpty() || noun.contains("女") || nounOut.length > 3 ||
                lookOut.endsWith("女") || nounOut in GENDERLESS
            ) "" else "女"
            buildString {
                append("另一位是")
                if (age.isNotEmpty()) append(age).append("的")
                append(lookOut)
                append(female)
                append(nounOut)
                if (faceDetail.isNotEmpty()) append("，").append(faceDetail)
                // 头发放在句尾，避开「银色长直发的三十岁的」这种连着的两个「的」。
                if (hair.isNotEmpty()) append("，").append(hair)
                append("。")
            }
        }
    }

    /** 十八岁外表 -> 18，三十出头 -> 30，娃娃脸成年 -> null。 */
    private fun ageNumber(text: String): Int? {
        val head = text.substringBefore('岁')
        var total = 0
        var current = 0
        var seen = false
        head.forEach { ch ->
            when {
                ch in CHINESE_DIGITS -> {
                    current = CHINESE_DIGITS.getValue(ch)
                    seen = true
                }

                ch == '十' -> {
                    total += (if (current == 0) 1 else current) * 10
                    current = 0
                    seen = true
                }

                else -> Unit
            }
        }
        return if (seen) total + current else null
    }

    /** One rendered phrase per category, before any ordering decision. */
    private fun collectPhrases(picks: Map<String, TagPick>): Map<String, String> {
        val skip = skipped(picks)
        val crowd = crowdSize(picks) ?: 1
        val byCategory = LinkedHashMap<String, String>()
        var skipPanties = false
        val panties = library.category(PANTIES)

        // A framing word that survives repair still has to be honoured at render time: a
        // hand-set 私处特写 must not print 潮吹 just because nobody re-ran the sweep.
        val chosen = picks.values.filter { it.value.isNotEmpty() && !it.ignored }.map { it.value }
        val hidden = hiddenRegionsOf(chosen)
        val hiddenCats = chosen
            .flatMap { library.constraints.frameHidesCategories[it].orEmpty() }
            .toSet()
        fun inFrame(categoryId: String, word: String): Boolean =
            hidden.isEmpty() || regionsOf(word, categoryId).none { it in hidden }

        library.categories.forEach { category ->
            if (category.id in skip || category.id in hiddenCats) return@forEach
            // 脸埋进枕头/手掌时表情、视线、妆容都看不见。抽词阶段挡不住：表情在分类表里
            // 排在 headPose 之前，抽它的时候还不知道脸会被埋住，这里按最终结果再判一次。
            if (hiddenByFace(category.id, picks)) return@forEach
            if (category.id == PANTIES && skipPanties) return@forEach

            val values = activeValues(picks, category)
                .filterValues { word -> inFrame(category.id, word) }
            if (values.isEmpty()) return@forEach
            if (category.id == BOTTOM) {
                val bottomPhrase = tidy(phrase(category, values))
                val pantyValues = if (panties != null && PANTIES !in skip) {
                    activeValues(picks, panties).filterValues { word -> inFrame(PANTIES, word) }
                } else emptyMap()
                val pantiesPhrase = if (panties != null) tidy(phrase(panties, pantyValues)) else ""
                val type = values[TYPE_SLOT].orEmpty()
                if (isPants(type) && bottomPhrase.isNotEmpty() && pantiesPhrase.isNotEmpty()) {
                    byCategory[category.id] = tidy("${bottomPhrase}的边缘漏出${pantiesPhrase}")
                    skipPanties = true
                    return@forEach
                }
                if (bottomPhrase.isNotEmpty()) byCategory[category.id] = bottomPhrase
                return@forEach
            }

            // 痕迹 lives inside 皮肤 now, so the owner can only be prefixed onto that one slot:
            // "她身上的冷白皮，颈侧咬痕" would hand the skin colour to both bodies as well.
            val owned = category.id in OWNED && crowd >= 2
            val mark = values["mark"].orEmpty()
            val markOwned = category.id == SKIN && crowd >= 2 && mark.isNotEmpty()
            val phrase = if (markOwned) {
                tidy(phrase(category, values.toMutableMap().apply { put("mark", "她身上的$mark") }))
            } else {
                tidy(phrase(category, values))
            }
            if (phrase.isNotEmpty()) {
                byCategory[category.id] = when {
                    category.id == PEOPLE_ID -> phrase + roleNote(phrase)
                    // 手部动作在模型眼里是没有主语的碎片：「手掌按在膝上」会被摊到画面里所有人身上。
                    category.id == HAND_ACTION -> herHands(phrase)
                    // With two bodies on screen an unowned mark lands on both of them.
                    owned -> "她身上的$phrase"
                    else -> phrase
                }
            }
        }

        // A tag can word two categories identically; keep the first so it is not said twice.
        val seen = mutableSetOf<String>()
        byCategory.entries.removeAll { !seen.add(it.value) }
        return byCategory
    }

    private fun legacyPrompt(byCategory: Map<String, String>): String {
        val known = tiers.flatten().toSet()
        val tierOf: (String) -> Int = { library.category(it)?.tier ?: Int.MAX_VALUE }
        // 新增分类忘了登记顺序表时，过去一律甩到最末一段，没人会发现。改成按层级号
        // 塞进层级区间对得上的那一段，至少它还在该在的位置附近。
        val ranges = tiers.map { line ->
            val tiers = line.map(tierOf)
            (tiers.minOrNull() ?: 0) to (tiers.maxOrNull() ?: 0)
        }
        val extras = byCategory.keys.filter { it !in known }
            .groupBy { id ->
                val t = tierOf(id)
                ranges.indexOfFirst { t >= it.first && t <= it.second }
                    .let { if (it < 0) tiers.lastIndex else it }
            }
        val blocks = tiers.mapIndexed { index, tier ->
            val own = tier.mapNotNull { byCategory[it] }
            own + extras[index].orEmpty().sortedBy(tierOf).mapNotNull { byCategory[it] }
        }
        // 段尾补句号：模型把换行当空格，两段就会连读成「匀称穿白色衬衫」。
        // 换行留给人看，句号给模型断句，两个都要。
        return blocks.filter { it.isNotEmpty() }
            .map { sentenceEnd(it.joinToString(SEPARATOR)) }
            .joinToString("\n")
    }

    /**
     * One paragraph per subject instead of one run-on list.
     *
     * A flat list gives every tag the same weight and no owner, so a scene of five women reads
     * exactly like a scene of one — the model fills the other four by copying the first. The
     * paragraphs below are 内容 first, body, then staging, then the act, then whoever else is in
     * frame; each subject is stated once, in its own sentence.
     */
    private fun sectionedPrompt(byCategory: Map<String, String>, picks: Map<String, TagPick>): String {
        val used = mutableSetOf<String>()
        fun take(id: String): String? {
            val text = byCategory[id] ?: return null
            used += id
            return if (id == EXPRESSION) expressionWithUndertone(text) else text
        }

        val blocks = mutableListOf<List<String>>()
        val malePresent = byCategory[PEOPLE]?.isNotEmpty() == true && malePresent(picks)
        // Style (with its render cues), environment and light lead. They used to sit behind forty
        // body words, and the model answered 走廊 with a bed because by then it had decided the
        // scene was a close interior.
        val styleLine = moodStyle(picks)
        if (styleLine != null) {
            used += MOOD
            used += GRADE
            blocks += listOf(styleLine)
        }
        blocks += SECTION_HEAD.filter { it != MOOD && it != GRADE }.mapNotNull { take(it) }
        // 乳房 / 腰腹 / 臀部 / 腿 are four ways of saying the same thing, so only three may
        // speak. Choosing which one to drop has to happen after knowing what is actually there:
        // counting every member first spent the whole budget on the three absent ones and threw
        // 腿 away on every single draw.
        val bodyKeeping = BODY_BLOCK.filter { it in byCategory }
            .let { present ->
                if (present.size <= BODY_BLOCK_CAP) present.toSet()
                else present.shuffled().take(BODY_BLOCK_CAP).toSet()
            }
        // 种族 / 妆容 / 年龄 / 气质 已经并进同一句，不再各自成段。
        val personLine = personClause(picks)
        used += RACE
        used += PERSON
        used += "makeup"
        // 人物句已经用掉的这两格，别再让末尾兜底把同一句拆开重说一遍。
        used += "age"
        used += "look"
        val heroItems = SECTION_HERO.mapNotNull { id ->
            if (id == RACE || id == PERSON || id == "makeup") return@mapNotNull null
            if (id in BODY_BLOCK && id !in bodyKeeping) {
                // Mark it spent, or the leftovers sweep below puts it back as a new block.
                used += id
                return@mapNotNull null
            }
            take(id)?.let { id to it }
        }
        // With a man in frame the run of twenty fragments had no owner, so the model was free to
        // spread 巨乳 and 丝袜 onto both bodies. One sentence names her, the male clause below
        // names him; a solo scene keeps the old flat list.
        if (malePresent) {
            val body = listOfNotNull(personLine) + heroItems.filter { it.first in HER_BODY }.map { it.second }
            val wearItems = heroItems.filter { it.first !in HER_BODY }
            val wear = wearItems.map { it.second }
            val sentence = buildString {
                if (body.isNotEmpty()) append("她是").append(body.joinToString("，"))
                if (wear.isNotEmpty()) {
                    if (isNotEmpty()) append("；")
                    // 无内裤 / 无内衣 already carry the negation; prefixing 穿 produced 穿无内裤.
                    // 衣物是「穿」，配饰是「戴」，道具只能「拿」；穿脱状态、衣物细节与半脱走光短语
                    // 自成子句，不挂「穿」前缀（避免「穿双手在背后解胸罩」「穿露出一边乳房」）。
                    val dressedItems = wearItems.filter {
                        it.first in wearClothes() && it.second !in BARE_WORD && !isStandaloneWearClause(it.first, it.second)
                    }
                    // 外衣和内衣挤在同一个「穿」后面，模型分不清哪件在外面。分开说：
                    // 五cm 的吊带裙和底下那件蕾丝，层次才对得上。
                    val dressed = dressedItems.filter { it.first !in INNER_WEAR }.map { it.second }
                    val innerDressed = dressedItems.filter { it.first in INNER_WEAR }.map { it.second }
                    val stateClothes = wearItems.filter {
                        it.second !in BARE_WORD && (
                            it.first in wearStates() ||
                                (it.first in wearClothes() && isStandaloneWearClause(it.first, it.second))
                            )
                    }.map { it.second }
                    val worn = wearItems.filter { it.first in WEAR_ACCESSORY || it.first in WEAR_PROPS }
                        .filter { it.second !in BARE_WORD }
                    val bare = wear.filter { it in BARE_WORD }.map { BARE_WORD.getValue(it) }
                    // 没有外衣时别写「内搭」——她身上就那一件，说「穿」才对。
                    val innerPhrase = when {
                        innerDressed.isEmpty() -> null
                        dressed.isEmpty() -> "穿${innerDressed.joinToString("，")}"
                        else -> "内搭${innerDressed.joinToString("，")}"
                    }
                    val groups = listOfNotNull(
                        "穿${dressed.joinToString("，")}".takeIf { dressed.isNotEmpty() },
                        innerPhrase
                    ) + stateClothes + wearGroups(worn)
                    if (groups.isNotEmpty()) append(groups.joinToString("，"))
                    bare.forEachIndexed { index, text ->
                        if (groups.isNotEmpty() || index > 0) append("，")
                        append(text)
                    }
                }
            }
            if (sentence.isNotEmpty()) blocks += listOf(sentence)
        } else {
            // 没有男性时不拆「她是…」那句，但衣物同样要按穿/状态/戴/拿分组，
            // 否则配饰和道具会裸列成「花环，金框墨镜，素圈戒指，跳蛋」，衣物细节则会变成「穿露出一边乳房」。
            val body = listOfNotNull(personLine) + heroItems.filter { it.first in HER_BODY }
                .map { (_, value) -> BARE_WORD[value] ?: value }
            val wearItems = heroItems.filter { it.first !in HER_BODY }
            val clothes = wearItems.filter {
                it.first in wearClothes() && it.second !in BARE_WORD && !isStandaloneWearClause(it.first, it.second)
            }.map { it.second }
            val stateClothes = wearItems.filter {
                it.second !in BARE_WORD && (
                    it.first in wearStates() ||
                        (it.first in wearClothes() && isStandaloneWearClause(it.first, it.second))
                    )
            }.map { it.second }
            val worn = wearItems.filter { it.first in WEAR_ACCESSORY || it.first in WEAR_PROPS }
                .filter { it.second !in BARE_WORD }
            val bare = wearItems.map { it.second }.filter { it in BARE_WORD }
                .map { BARE_WORD.getValue(it) }
            val parts = listOfNotNull(
                body.joinToString("，").takeIf { body.isNotEmpty() },
                "穿${clothes.joinToString("，")}".takeIf { clothes.isNotEmpty() }
            ) + stateClothes + wearGroups(worn) + bare
            if (parts.isNotEmpty()) blocks += listOf(parts.joinToString("，"))
        }

        val act = mutableListOf<String>()
        SECTION_ACT_PRE.forEach { take(it)?.let(act::add) }
        // 这两格由 maleSentence 自己渲染。不先 take 掉的话，下面的 rest 兜底会把它们
        // 再拼一遍到她的段落里，同一个词就出现两次。
        take(MALE_FACE)
        take(MALE_FEATURE)
        // 男方单独成句：混在她的动作串里，逗号会把「她」和「他」连成一个人。
        // 位置仍在主戏之前——放在动词后面，模型会把他随手摆在任意位置。
        val maleText = maleSentence(byCategory, picks)
        val mainAct = mutableListOf<String>()
        SECTION_ACT_MAIN.forEach { take(it)?.let(mainAct::add) }
        blocks += act
        if (maleText != null) blocks += listOf(maleText)
        blocks += mainAct

        // 第三个人的动作必须自己带主语。混进上面那串逗号里，读起来像是她自己在做,
        // 而旁观者句又会让同一个人同时出现在角落——两套机制都描述第三个人。
        // 「三人并排跪坐」这类姿势已经把第三个人写进画面了，再补一句她的动作就是同一个人描述两遍。
        val poseAlreadyCovers = selectedWord(picks, POSE).orEmpty().startsWith("三人")
        // 锁定的格不会被重抽，抽词阶段那道门控也就绕得过去：先把「含着他的阴茎根部」
        // 锁住、再把动作换成整根没入，两句就又能同框。出口这里再判一次。
        fun partnerWord(slot: String): String? =
            picks[key(PARTNER_ACT, slot)]?.value
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { fitsPartnerReach(it, picks) }
        // 存档里可能留着人数改小之前的旧值，出口这里再确认一次真的有人能做这件事：
        // 一男一女只有两个人，「另一个人在做什么」写出来就是凭空多一个人。
        val partner = if (poseAlreadyCovers || !partnerHasSomeone(picks, PARTNER_ACT_SLOT)) null
                      else partnerWord(PARTNER_ACT_SLOT)
        // 一男三女才有第二个旁观者；两格抽到同一句等于同一个人被写两遍。
        val partner2 = if (poseAlreadyCovers || partner == null ||
            !partnerHasSomeone(picks, PARTNER_ACT_SLOT_2)
        ) null else partnerWord(PARTNER_ACT_SLOT_2)?.takeIf { it != partner }
        // 这两格各自成句，不能再交给 rest 兜底拼回一串。
        used += PARTNER_ACT
        // 第二位、第三位女性各自一句，否则三个人共用一张脸。她的动作接在同一句末尾，
        // 不再另起一个「另一名女性」，同一个人才不会被写两遍。
        val women = (crowdSize(picks) ?: 1) - if (malePresent) 1 else 0
        val companions = companionClauses(women, picks)
        when {
            companions.isEmpty() -> if (partner != null) blocks += listOf("另一名女性$partner。")
            partner == null && partner2 == null -> blocks += companions
            else -> {
                val heads = companions.dropLast(1).map { it }.toMutableList()
                if (partner2 != null && heads.isNotEmpty()) {
                    heads[0] = heads[0].trimEnd('。') + "，她$partner2。"
                }
                val last = companions.last().let {
                    if (partner != null) it.trimEnd('。') + "，她$partner。" else it
                }
                blocks += heads
                blocks += listOf(last)
            }
        }
        supportSentence(byCategory, hasPartner = partner != null)?.let { blocks += listOf(it) }
        blocks += SECTION_TAIL.mapNotNull { take(it) }

        // A category the catalog gained after this table was written still reaches the prompt.
        val rest = byCategory.filterKeys {
            it !in used && it != MALE_BODY && it != MALE_HAND && it != PENIS
        }
        if (rest.isNotEmpty()) blocks += rest.values.toList()


        return blocks.filter { it.isNotEmpty() }
            .map { sentenceEnd(it.joinToString(SEPARATOR).replace("。$SEPARATOR", "。")) }
            .joinToString("\n")
    }

    /**
     * 一句收在句号上。
     *
     * 换行对模型不是分隔符：段尾只靠换行，两段会连读成「匀称穿白色衬衫」。
     * 已经收在句末标点上的不再重复；右括号、右引号要留在句号里面。
     */
    private fun sentenceEnd(text: String): String {
        if (text.isEmpty()) return text
        val trimmed = text.trimEnd()
        val tail = listOf("）", "」", "』", "”", "’", ")", "\"", "'")
        var body = trimmed
        var closing = ""
        while (body.isNotEmpty() && tail.any { body.endsWith(it) }) {
            closing = body.last() + closing
            body = body.dropLast(1)
        }
        if (body.isEmpty()) return trimmed
        return when (body.last()) {
            '。', '！', '？', '…', '!', '?' -> trimmed
            else -> body + "。" + closing
        }
    }

    /**
     * 同场动作（partnerAct）要真有那第几个人在场：一格满三人，第二格满四人。
     *
     * 一男一女只有两个人，抽出来就是凭空多一个人；这条判据同时挡住「上游把人数改小了」的残留。
     */
    private fun partnerHasSomeone(picks: Map<String, TagPick>, slotId: String): Boolean {
        val crowd = crowdSize(picks) ?: 0
        return if (slotId == PARTNER_ACT_SLOT_2) crowd >= 4 else crowd >= 3
    }

    /**
     * 判断某条衣物槽产出的短语是否为独立状态/动作子句（不能前置「穿」）。
     */
    private fun isStandaloneWearClause(categoryId: String, phrase: String): Boolean {
        if (categoryId in wearStates()) return true
        if (library.constraints.pos[phrase] == "verb") return true
        return STANDALONE_WEAR_PREFIXES.any { phrase.startsWith(it) }
    }

    /**
     * garment 在两档里是两个东西：成人档放的是衣物细节与穿脱状态（双手在背后解胸罩），
     * 日常档放的是连身装（米白针织连衣裙、深蓝成套西装）。同一个分类 id，接法必须分开。
     */
    private fun wearClothes(): Set<String> =
        if (library.profile == "sfw") WEAR_CLOTHES + GARMENT else WEAR_CLOTHES

    private fun wearStates(): Set<String> =
        if (library.profile == "sfw") WEAR_STATES - GARMENT else WEAR_STATES

    /**
     * 人数 says a man exists but never where he stands, so the organ arrives without its owner
     * and sometimes without a body at all. His face is hidden on purpose: a second full face
     * competes with hers and invites a second torso.
     */
    /**
     * 配饰、道具按词性分堆。
     *
     * 「戴着创可贴」「拿着拘束椅」是整类同前缀的锅：一个格子里既有能戴的，也有只能贴、
     * 只能摆在旁边的。词性表给了例外，其余按类别默认——配饰戴、道具拿。空前缀表示这个词
     * 本身就是完整句子，不再加动词。
     */
    private fun wearGroups(items: List<Pair<String, String>>): List<String> {
        if (items.isEmpty()) return emptyList()
        val pos = library.constraints.pos
        val prefixes = listOf(
            "wear" to "戴着", "tie" to "系着", "attach" to "贴着",
            "placed" to "旁边摆着", "hold" to "拿着"
        )
        val buckets = LinkedHashMap<String, MutableList<String>>()
        items.forEach { (categoryId, text) ->
            val tag = pos[text] ?: if (categoryId in WEAR_PROPS) "hold" else "wear"
            val prefix = prefixes.firstOrNull { it.first == tag }?.second.orEmpty()
            buckets.getOrPut(prefix) { mutableListOf() } += text
        }
        val ordered = prefixes.map { it.second }.filter { it in buckets } +
            if ("" in buckets) listOf("") else emptyList()
        return ordered.map { prefix ->
            val words = buckets.getValue(prefix).joinToString("，")
            if (prefix.isEmpty()) words else prefix + words
        }
    }

    /**
     * 手部动作补主语。
     *
     * 词条本身是碎片：名词打头（手指抵唇、手掌按在膝上）补「她的」不补会读成「她的手指
     * 抵唇」之外的人；动词打头（撩头发、摊开手掌）补「她」。代词后面不加「的」——
     * 「她的只剩丝袜了」这种恰恰是加错了地方。
     */
    private fun herHands(phrase: String): String {
        if (phrase.isEmpty() || phrase.startsWith("她")) return phrase
        val nounLed = listOf("手", "指", "掌", "臂", "拳", "肘")
        return if (nounLed.any { phrase.startsWith(it) }) "她的$phrase" else "她$phrase"
    }

    private fun maleSentence(byCategory: Map<String, String>, picks: Map<String, TagPick>): String? {
        val people = byCategory[PEOPLE].orEmpty()
        if (!people.contains("男")) return null

        val act = byCategory[SEX_ACT].orEmpty() + byCategory[POSE].orEmpty()
        val posture = library.constraints.posture[byCategory[POSE].orEmpty()]
        // Read the slots directly: the joined maleBody phrase ("穿着背心短裤，汗湿的背的熊系男人")
        // is a label, and a label cannot be given its own sentence or its own posture.
        val male = library.category(MALE_BODY)?.let { activeValues(picks, it) }.orEmpty()
        val build = male["body"].orEmpty()
        val age = male["age"].orEmpty()
        // 日常档把男方外貌放在独立的 maleFeature 分类里，成人档才是 maleBody.feature。
        val feature = male["feature"].orEmpty().ifEmpty { byCategory[MALE_FEATURE].orEmpty() }
        val outfit = male["outfit"].orEmpty()
        val state = male["state"].orEmpty()
        val organ = byCategory[PENIS]

        // His hands were the one thing the library never described, so the model invented
        // them. Both slots are worded as verb-first fragments (揉着她的乳房), which is what
        // lets them hang off 一只手 / 另一只手 without rewriting the word itself.
        val hands = library.category(MALE_HAND)?.let { activeValues(picks, it) }.orEmpty()
        // 手指进入 already says what his hand is doing; adding 探进她体内 says it twice.
        val below = hands["below"].orEmpty().takeIf { !act.contains("手指进入") }.orEmpty()
        val handPart = listOf(hands["chest"].orEmpty(), below)
            .filter { it.isNotEmpty() }
            .let { chosen ->
                when (chosen.size) {
                    0 -> ""
                    1 -> "，一只手${chosen[0]}"
                    else -> "，一只手${chosen[0]}，另一只手${chosen[1]}"
                }
            }

        val wear = maleWear(outfit, state)
        val tag = listOf(age, build).filter { it.isNotEmpty() }.joinToString("、")
        val who = if (tag.isEmpty() && feature.isEmpty() && wear.isEmpty()) {
            "画面里有一名男性"
        } else {
            "他是一名" + (if (tag.isEmpty()) "" else "${tag}的") + "男性" +
                (if (feature.isEmpty()) "" else "，$feature") + wear
        }

        // Below the waist is off screen, so his pose and organ cannot be described there either.
        val cropped = "groin" in hiddenRegionsOf(byCategory.values)
        val poseText = byCategory[POSE].orEmpty()
        val stage = if (cropped) "紧贴在她身侧" else when {
            // 她坐他脸上、背对背，这两种姿势下他的方位与高度都被姿势定死，
            // 兜底的「坐在她身旁」「站在她面前」会把画面说反。
            act.contains("坐在脸上") || act.contains("骑脸") || act.contains("舔阴") -> "跨在她上方"
            poseText.contains("背对背") -> "背对着她"
            act.contains("口交") || act.contains("深喉") || act.contains("口爆") || act.contains("吞入") ->
                "将胯部停在她面前"
            act.contains("后入") || act.contains("狗式") || act.contains("背后") -> "紧贴在她身后"
            act.contains("骑乘") || act.contains("跨坐") || act.contains("坐入") -> "仰躺在她身下"
            act.contains("股交") || act.contains("腿交") -> "把腿卡进她的双腿之间"
            // A round of shared service says nothing about his height off the floor; the pose
            // does, and 三人围坐 used to be answered with 站在她面前.
            act.contains("共同服侍") || act.contains("围成一圈") -> when (posture) {
                "sit" -> "坐在她对面"
                "lie" -> "躺在她身侧"
                "kneel" -> "跪在她面前"
                else -> "站在她面前"
            }
            act.contains("叠在一起") || act.contains("夹在中间") || act.contains("叠着") -> "在层叠的最下方"
            // 站立时他抱着她的腿，胯部贴着，不是「抵在双腿之间」——那还是进入前的姿势。
            act.contains("站立") || act.contains("抱腿") || act.contains("抱起") ->
                "托着她的腿，胯部紧贴着她"
            act.contains("进入") || act.contains("插入") || act.contains("性交") ->
                "将胯部抵在她的双腿之间"
            posture == "sit" -> "坐在她身旁"
            posture == "lie" -> "侧躺在她身侧"
            posture == "kneel" -> "跪在她身旁"
            else -> "紧贴在她身侧"
        }
        val organPart = if (cropped) "" else organ?.let { "，${it}可见" }.orEmpty()
        // Only the framings that physically cut his head out get the note. The old rule keyed off
        // 全身/半身 and fired on 92 of the 103 camera words, so his face was missing nine draws out
        // of ten. 特写 / 中近景 / 腰部以上 can hold his face without taking over the frame.
        val shot = byCategory[CAMERA].orEmpty()
        val faceOff = listOf(
            "私处特写", "下半身特写", "下半身在画外", "只拍到腰腹以下",
            "从腿间仰拍", "从裙摆下方的视角", "脚部特写", "臀部特写"
        ).any { shot.contains(it) }
        // 他的脸 is a category the user can pick, lock or switch off. Only when it is off does the
        // framing decide, so the default output stays exactly what it was.
        val facePick = byCategory[MALE_FACE].orEmpty()
        val face = when {
            facePick.isNotEmpty() -> "$facePick。"
            faceOff -> "他的脸不在画面中。"
            else -> ""
        }
        // Where he stands, then what he is doing with his hands, then what shows of him.
        return "$who。他$stage$handPart$organPart。$face"
    }

    /**
     * Some outfits are states, not garments: 只穿内裤 and 西装革履 cannot take 穿着 in front.
     */
    private fun maleWear(outfit: String, state: String = ""): String {
        // 「什么都没穿」不是衣服的状态，是「没有衣服」，它替代整条穿着描述。
        if (state == "什么都没穿") return "，身上什么也没穿"
        val base = when {
            outfit.isEmpty() -> ""
            outfit == "全裸" -> "，全身赤裸"
            outfit.startsWith("只") || outfit.contains("革履") || outfit.contains("解开") ||
                outfit.contains("围着") || outfit.contains("挂着") || outfit.contains("身上什么也没穿") ||
                outfit.contains("赤裸") || outfit.contains("未着寸缕") || outfit.contains("一丝不挂") ->
                "，$outfit"
            else -> "，穿着$outfit"
        }
        return if (base.isEmpty()) "" else base + (if (state.isEmpty()) "" else "，$state")
    }

    /**
     * 风格整句，后面跟着颜色和光。
     *
     * Every medium now carries one full natural-language sentence instead of four fragments.
     * The fragments were instructions -- "赛璐璐上色", "高光色相偏移" -- and an encoder reads a
     * description far better than a checklist. The sentence deliberately names no hue and no
     * light source: colour belongs to 调色 / 主色, lighting to 光质 / 光位, so the three layers
     * say three different things instead of repeating one.
     */
     /** 改名后的旧风格名映射到新名，让存档里锁着旧名的那一格照样拿到整句。 */
    private fun canonicalStyle(name: String): String =
        if (name.isEmpty()) name else library.styleAliases[name] ?: name

    private fun moodStyle(picks: Map<String, TagPick>): String? {
        val style = library.category(MOOD)
        val grade = library.category(GRADE)
        val medium = style?.let { activeValues(picks, it)["medium"] }.orEmpty()
        val graded = grade?.let { activeValues(picks, it) }.orEmpty()
        // A style whose name promises a colour cannot leave it to chance: 霓虹夜色 without neon
        // is a night portrait. The forced word still shows in 调色, and the player can change it.
        val feel = graded["feel"].orEmpty().ifEmpty { library.styleFeel[canonicalStyle(medium)].orEmpty() }
        // 调色与主色已并成一格、光质与光位并成一格，这里只认这两格。
        val tone = graded["tone"].orEmpty()
        if (medium.isEmpty() && feel.isEmpty() && tone.isEmpty()) {
            return null
        }

        val parts = mutableListOf<String>()
        // 普通模式 is a word joiner end to end: even the style drops its sentence and ships the
        // bare word, exactly like every other slot. 增强模式 keeps the sentence, which is the
        // whole reason it reads better.
        if (medium.isNotEmpty()) {
            parts += if (strict) library.stylePrompts[canonicalStyle(medium)] ?: medium else medium
        }
        if (feel.isNotEmpty()) parts += feel
        if (tone.isNotEmpty()) parts += tone
        // The sentence already ends with 。; joining with a comma produced "。，暗调胶片".
        return parts.joinToString("，").replace("。，", "。")
    }

    /**
     * Everyone who is not the described subject gets one position and one difference. Without
     * it the model decides that "三个女孩" means three copies of the woman it was just given.
     */
    private fun supportSentence(
        byCategory: Map<String, String>,
        hasPartner: Boolean = false
    ): String? {
        // The rendered phrase may carry the single-male note; the head count is only the first part.
        val people = byCategory[PEOPLE].orEmpty().substringBefore("，")
        val crowd = library.constraints.peopleCount[people] ?: return null
        if (crowd < 2) return null

        val others = crowd - 1 - if (people.contains("男")) 1 else 0
        if (others <= 0) return null
        // 第三个人已经在做具体动作，不能再让她退到角落旁观。
        if (hasPartner) return null

        val seed = (people + byCategory[SEX_ACT].orEmpty() + byCategory[POSE].orEmpty()).hashCode()
        val side = SUPPORT_SIDES[(seed and 0x7fffffff) % SUPPORT_SIDES.size]
        val head = when (others) {
            1 -> "另外一名女性"
            2 -> "另外两名女性"
            3 -> "另外三名女性"
            4 -> "其余四名女性"
            5 -> "其余五名女性"
            else -> "其余几名女性"
        }
        // An act that needs a second body has to name one. With a man on scene he already is
        // the partner; without one, "everyone quietly watching" left 吻颈 / 被两人同时抚摸 /
        // 踩踏 with nobody performing them.
        val act = byCategory[SEX_ACT].orEmpty().ifEmpty { byCategory[POSE].orEmpty() }
        val needs = library.constraints.people[act] ?: 1
        if (!people.contains("男") && needs >= 2) {
            val joinOptions = if (others == 1) SUPPORT_JOINING_ONE else SUPPORT_JOINING_MANY
            val verb = joinOptions[((seed ushr 12) and 0x7fffffff) % joinOptions.size]
            return "${head}在画面$side，$verb。"
        }
        // A back view cannot stare back at the camera; drop it when the gaze already does.
        val gaze = byCategory[GAZE_DIR].orEmpty()
        val facing = gaze.contains("回头") || gaze.contains("看镜头")
        val options = if (facing) SUPPORT_FACING else SUPPORT_TURNED
        val orient = options[((seed ushr 8) and 0x7fffffff) % options.size]
        return "${head}在画面$side，$orient。"
    }

    /**
     * An expression alone is a label. Reference it against how she normally looks and the model
     * has something to act out. Applied to half of them so the tail does not read as boilerplate.
     */
    private fun expressionWithUndertone(expression: String): String {
        if (expression.isEmpty()) return expression
        val seed = expression.hashCode() and 0x7fffffff
        if (seed % 2 == 0) return expression
        return expression + UNDERSTATEMENTS[seed / 2 % UNDERSTATEMENTS.size]
    }

    /**
     * 一男两女 says how many, not who. Without an owner the model gave every nude figure the
     * male organ it had been told about, so state the single-male fact in words.
     */
    private fun roleNote(people: String): String =
        if (people.contains("男") && (people.contains("两女") || people.contains("多女")))
            "，其中只有一名男性，其余为女性"
        else ""

    /** Slot values that actually reach the prompt. */
    fun activeValues(picks: Map<String, TagPick>, category: TagCategory): Map<String, String> {
        val values = mutableMapOf<String, String>()
        category.slots.forEach { slot ->
            val slotKey = key(category.id, slot.id)
            val pick = picks[slotKey] ?: return@forEach
            if (pick.value.isEmpty() || pick.ignored) return@forEach
            // 「分类.格」规则也要在出句时认一次：穿上衣以后，已经抽中的乳房形态与乳头
            // 不能还挂在句子里。锁住的不算——锁住就是玩家明确要的那一格。
            if (!pick.locked && slotGated(slotKey, picks)) return@forEach
            values[slot.id] = pick.value
        }
        return values
    }

    fun selectedValues(
        picks: Map<String, TagPick>,
        skip: Set<String> = emptySet(),
        excludeMale: Boolean = false
    ): List<String> {
        val values = mutableListOf<String>()
        library.categories.forEach { category ->
            if (category.id in skip) return@forEach
            if (excludeMale && category.who == "male") return@forEach
            category.slots.forEach { slot ->
                val pick = picks[key(category.id, slot.id)] ?: return@forEach
                if (pick.value.isNotEmpty() && !pick.ignored) values += pick.value
            }
        }
        return values
    }

    /** The 人数 slot may only offer head counts that belong to the tab being shown. */
    private fun allowedIn(categoryId: String, candidate: String): Boolean =
        categoryId != PEOPLE || peoplePool.isEmpty() || candidate in peoplePool

    private fun conflicts(candidate: String, accepted: Collection<String>): Boolean =
        library.mutex.any { group ->
            candidate in group && accepted.any { it != candidate && it in group }
        }

    private fun satisfies(
        candidate: String,
        picks: Map<String, TagPick>,
        selfKey: String,
        skip: Set<String>,
        framingWins: Boolean = false
    ): Boolean = blockTag(candidate, picks, selfKey, skip, framingWins) == null

    /**
     * Why a candidate is refused, or null when it is usable.
     *
     * 增强模式 runs every guard. 普通模式 keeps only the ones that would otherwise put a
     * contradiction inside a single sentence -- the same word twice, two postures, two colours --
     * and drops the ones about physical feasibility (two hands, one mouth, what the room has).
     * Those are the checks that also mis-fire, so this is the release valve for a wrong 拦.
     *
     * Returning a reason rather than a boolean is what lets the panel answer "why is this slot
     * empty" without a second copy of the rules drifting away from this one.
     */
    private fun blockTag(
        candidate: String,
        picks: Map<String, TagPick>,
        selfKey: String,
        skip: Set<String>,
        framingWins: Boolean = false
    ): String? {
        basicTag(candidate, picks, selfKey, skip)?.let { return it }
        if (!strict) return null
        if (!fitsHeadPlacement(candidate, picks)) return "头部位置与已选动作冲突"
        if (!fitsSceneProps(candidate, picks)) return "场地没有它需要的道具"
        if (!fitsHandBudget(candidate, picks, selfKey, skip)) return "手不够用"
        if (!fitsMouthBudget(candidate, picks, selfKey, skip)) return "嘴不够用"
        if (!fitsFaceZone(candidate, picks, selfKey, skip)) return "脸上这个位置已被占用"
        if (!fitsSurface(candidate, picks)) return "这里的支撑面撑不住"
        if (!fitsFrame(candidate, picks, selfKey, framingWins)) return "被取景裁掉了"
        if (!fitsHandBlock(candidate, picks, selfKey)) return "双手已被占用"
        if (!fitsAllowedPostures(candidate, picks)) return "姿势白名单不允许"
        if (!fitsExactPeople(candidate, picks)) return "人数对不上"
        if (!fitsSoloPose(candidate, picks, selfKey)) return "只能独自出镜的词不能带双人动作"
        if (!fitsHeadBudget(candidate, picks, selfKey)) return "头部饰品超量"
        if (!fitsRaceFeature(selfKey, candidate, picks)) return "种族特征对不上"
        if (!requirementsMet(candidate, picks, selfKey, skip)) return "缺少成立前提"
        return null
    }

    /**
     * The checks both modes keep: a word that directly contradicts another word, not a word that
     * describes something the picture physically cannot do. 普通模式 keeps these because dropping
     * them writes "站立" and "躺着" into one sentence, which is not freedom, it is broken data.
     */
    private fun basicTag(
        candidate: String,
        picks: Map<String, TagPick>,
        selfKey: String,
        skip: Set<String>,
        manual: Boolean = false
    ): String? {
        if (hasWordRules && candidate in bannedWords(picks)) return "与已选词的排除规则冲突"
        // 第二格是第三位女性的动作，一男三女才有她。写在这里，面板问「为什么这格是空的」
        // 才答得出来；只摆在抽词循环里，界面只会显示一格永远抽不中的空槽。
        if (selfKey == key(PARTNER_ACT, PARTNER_ACT_SLOT_2) && (crowdSize(picks) ?: 0) < 4) {
            return "一男三女才有第三位"
        }
        if (slotGated(selfKey, picks)) return "当前选择关掉了这一格"
        if (!fitsOnePiece(candidate, picks, selfKey)) return "下衣已锁定，一件式上衣本次不参与"
        if (!fitsSelfColour(candidate, picks, selfKey)) return "这一件自带颜色，不再叠颜色"
        if (!fitsScenePose(candidate, picks)) return "动作已经写清体位，不再叠场景排布"
        if (selfKey.startsWith("$POSE.") && actNamesPosition(picks)) return "动作已经写死了体位"

        if (selfKey.startsWith("toys.") && !toyFitsTheAct(candidate, picks)) return "这个道具要配对应的玩法"
        if (clashesByMotion(selfKey.substringBefore('.'), candidate, picks)) return "与动作语义冲突"
        if (!fitsGroup(library.constraints.posture, candidate, picks, selfKey, skip)) return "姿势组冲突"
        if (!fitsGroup(library.constraints.place, candidate, picks, selfKey, skip)) return "地点组冲突"
        if (!fitsGroup(library.constraints.gaze, candidate, picks, selfKey, skip)) return "视线组冲突"
        if (!fitsSynonyms(candidate, picks, selfKey, skip)) return "与已选词是同义词"
        excludePartner(candidate, picks, selfKey)?.let { return "与「${wordLabels[it] ?: it}」不能共存" }
        // Head count and sex belong here too: one woman cannot perform a two-man act, and that is
        // a contradiction in the words, not a feasibility question.
        if (!fitsMale(candidate, picks)) return "画面里没有男人"
        if (!fitsPeople(candidate, picks, selfKey, manual)) return "人数不够"
        // 「后入」配「一只手从背后揉着她的乳房」是同句直接矛盾，不是可行性问题，
        // 普通模式也得拦。放在 feasibility 那一段就等于默认放过。
        if (!fitsPostureHint(candidate, picks)) return "方位与当前动作对不上"
        if (!fitsOrganState(candidate, picks)) return "阴茎状态与当前动作对不上"
        if (!fitsPartnerReach(candidate, picks)) return "整根没入时够不到根部"
        if (!fitsRegionSkin(candidate, picks, selfKey)) return "这个地域不长这种肤色"
        // 古装与现代场地不互斥：古装站在便利店就当 cosplay 读。硬拦会把古装的可选场地砍到
        // 只剩二十来个，抽到古装时整段衣服空着的比例也跟着涨。eras 表留在数据里备用。
        if (!fitsLowerBareState(candidate, picks, selfKey)) return "下身已经穿好了，不能再写赤裸"
        return null
    }

    /**
     * 地域与肤色对不上。
     *
     * 两张表各自独立抽，会出「印度，雪白皮肤」。只在肤色格上判：地域格先抽，改地域时
     * 修复程序会从肤色这头把话收回。
     */
    private fun fitsRegionSkin(candidate: String, picks: Map<String, TagPick>, selfKey: String): Boolean {
        if (selfKey != key(SKIN, "tone")) return true
        val table = library.constraints.skinByRegion
        if (table.isEmpty()) return true
        val region = picks[key(RACE, "ethnicity")]?.value.orEmpty()
        val allowed = table[region] ?: return true
        return candidate in allowed
    }

    /**
     * 下身已经穿好了，就不能再写「下身赤裸」。
     *
     * 抽脱状态排在衣服之前，正常情况下这一格会先把下衣关掉；但玩家手动锁了下衣、或者只重抽
     * 这一格时，锁住的值不会被清，两句就会挤进同一段。反方向不用管：抽到「下身赤裸」时，
     * 跳过规则本来就会清下衣。
     */
    private fun fitsLowerBareState(candidate: String, picks: Map<String, TagPick>, selfKey: String): Boolean {
        if (selfKey != key(OUTFIT_STATE, "state")) return true
        val blocked = LOWER_BARE_STATES[candidate] ?: return true
        return blocked.none { wornType(picks, it).isNotEmpty() }
    }

    // 年代配对（fitsEra）已撤掉：古装与现代场地不互斥。eras 表仍留在词库里，
    // 谁要恢复这条限制，按「场地标了年代就只认同年代的衣服」写一遍即可。

    /**
     * 抵着入口的阴茎 only exists before entry.
     *
     * 口交 puts the organ at her face, not at an entrance; pairing the two writes a picture
     * that cannot be drawn. Everything oral or non-penetrating is excluded.
     */
    private fun fitsOrganState(candidate: String, picks: Map<String, TagPick>): Boolean {
        val act = selectedWord(picks, SEX_ACT).orEmpty()
        if (act.isEmpty()) return true
        // 半软、刚射完这类状态写进插入的场景就是画不出来的图。
        if (candidate in FLACCID_ORGANS) return !isPenetrating(act)
        if (candidate !in ENTRY_ONLY_ORGANS) return true
        return NOT_AT_ENTRANCE.none { act.contains(it) }
    }

    private fun isPenetrating(act: String): Boolean =
        PENETRATION_HINTS.any { act.contains(it) }


    /**
     * 第三个人能做什么，取决于主戏进行到哪一步。
     *
     * 整根没入时根部在她体内，够不到；口交、乳交这类没有交合处，也就谈不上舔交合处、
     * 扶着往里送。两句都写进提示词就是画不出来的图。
     */
    private fun fitsPartnerReach(candidate: String, picks: Map<String, TagPick>): Boolean {
        val act = selectedWord(picks, SEX_ACT).orEmpty()
        if (act.isNotEmpty() && candidate in PENETRATION_ONLY_PARTNER && !isPenetrating(act)) return false
        if (candidate !in BASE_NEEDS_EXPOSED) return true
        if (act.isEmpty()) return true
        return FULL_DEPTH_MARKS.none { act.contains(it) }
    }

    /** 两格第三人动作抽到同一句，就是同一个人被写了两遍。 */
    private fun duplicatesPartner(
        categoryId: String,
        slotId: String,
        candidate: String,
        picks: Map<String, TagPick>
    ): Boolean {
        if (categoryId != PARTNER_ACT || slotId != PARTNER_ACT_SLOT_2) return false
        return picks[key(PARTNER_ACT, PARTNER_ACT_SLOT)]?.value == candidate
    }
    /**
     * Words the panel greys out, with the reason.
     *
     * Grey-ing uses [basicTag], the same rule set 普通模式 keeps, so a greyed word is never one the
     * draw would happily pick. Feasibility checks stay out on purpose: 手不够用 is fixable by
     * clearing something else, and greying it would read as "never selectable".
     */
    fun blockedWords(categoryId: String, slotId: String, picks: Map<String, TagPick>): Map<String, String> =
        reasonsFor(categoryId, slotId, picks) { candidate, slotKey, skip ->
            // 置灰只表示「自动抽取不会选它」。景别与构图在多人档不自动抽，但玩家点名要时
            // 照给，所以这里按手动口径判，不把它灰掉。
            basicTag(candidate, picks, slotKey, skip, manual = true)
        }

    /**
     * The same map with every 增强模式 guard applied, 独处词 and physical feasibility included.
     *
     * Not used for greying: a word blocked by 手不够用 becomes selectable once something else is
     * cleared, and grey would claim otherwise. It exists so a caller — mostly the tests — can ask
     * "would the draw refuse this exact word, and why" without duplicating the guard order.
     */
    fun strictBlockedWords(categoryId: String, slotId: String, picks: Map<String, TagPick>): Map<String, String> =
        reasonsFor(categoryId, slotId, picks) { candidate, slotKey, skip ->
            blockTag(candidate, picks, slotKey, skip)
        }

    private fun reasonsFor(
        categoryId: String,
        slotId: String,
        picks: Map<String, TagPick>,
        reason: (candidate: String, slotKey: String, skip: Set<String>) -> String?
    ): Map<String, String> {
        val category = library.category(categoryId) ?: return emptyMap()
        val slot = category.slots.firstOrNull { it.id == slotId } ?: return emptyMap()
        val skip = skipped(picks)
        val slotKey = key(categoryId, slotId)
        val current = picks[slotKey]?.value.orEmpty()
        val out = mutableMapOf<String, String>()
        slot.randomPool().forEach { candidate ->
            if (candidate == current || !allowedIn(categoryId, candidate)) return@forEach
            reason(candidate, slotKey, skip)?.let { out[candidate] = it }
        }
        return out
    }

    /**
     * One line for an empty slot: which guard is doing the blocking, and how much of the pool it
     * took. Null when the slot still has options, so the panel only explains real emptiness.
     */
    fun slotBlockReason(categoryId: String, slotId: String, picks: Map<String, TagPick>): String? {
        val category = library.category(categoryId) ?: return null
        val slot = category.slots.firstOrNull { it.id == slotId } ?: return null
        val skip = skipped(picks)
        if (categoryId in skip) return "整个「${category.label}」被当前选择关掉了"
        val slotKey = key(categoryId, slotId)
        val current = picks[slotKey] ?: TagPick()
        if (current.ignored) return "这个槽被你忽略了"

        val accepted = selectedValues(picks, skip).toMutableSet().apply { remove(current.value) }
        val tally = mutableMapOf<String, Int>()
        var open = 0
        var total = 0
        slot.randomPool().forEach { candidate ->
            if (!allowedIn(categoryId, candidate) || conflicts(candidate, accepted)) return@forEach
            total++
            val tag = blockTag(candidate, picks, slotKey, skip)
            if (tag == null) open++ else tally[tag] = (tally[tag] ?: 0) + 1
        }
        if (open > 0) return null
        if (total == 0) return "候选池里没有能被选中的词"
        val top = tally.maxByOrNull { it.value } ?: return null
        return "${top.key}（${top.value}/$total 个候选被拦）"
    }

    /**
     * A race only reads if the matching organ shows up. Left alone, 恶魔 is a bare word: the
     * picture may grow bat wings or nothing at all, and the race quietly disappears. Races
     * with no entry are unrestricted, so 人类 can still take a wing on purpose.
     */
    private fun raceAllowed(categoryId: String, slotId: String, picks: Map<String, TagPick>): List<String>? {
        // 头部特征 / 身体特征 share one category now, so the table is picked by slot, not id.
        if (categoryId != FEATURE_HEAD) return null
        val map = if (slotId == "body") {
            library.constraints.raceFeatureBody
        } else {
            library.constraints.raceFeatureHead
        }
        if (map.isEmpty()) return null
        val race = picks.entries
            .firstOrNull { it.key.startsWith("$RACE.") }
            ?.value?.value.orEmpty()
        if (race.isEmpty()) return null
        return map[race]?.takeIf { it.isNotEmpty() }
    }

    private fun fitsRaceFeature(
        selfKey: String,
        candidate: String,
        picks: Map<String, TagPick>
    ): Boolean {
        // 人类不长角、不长翅膀。特征池里十二条头部、十二条身体全是异族的，人类一个都不该拿。
        // 这一条只对种族特征那一类成立，别把别的分类也一起挡掉。
        if (selfKey.substringBefore('.') == FEATURE_HEAD && selectedWord(picks, RACE) == HUMAN_KIND) {
            return false
        }
        val allowed = raceAllowed(
            selfKey.substringBefore('.'),
            selfKey.substringAfter('.', ""),
            picks
        ) ?: return true
        return candidate in allowed
    }

    /**
     * A pose such as 两人面对面站立 names the headcount outright. Requiring `<=` is not enough:
     * with three girls selected it would still be drawn at seven percent.
     */
    private fun fitsExactPeople(candidate: String, picks: Map<String, TagPick>): Boolean {
        val exact = library.constraints.exactPeople
        if (exact.isEmpty() || candidate !in exact) return true
        val crowd = crowdSize(picks) ?: return true
        return (library.constraints.people[candidate] ?: 0) == crowd
    }

    /**
     * The act and the pose have to agree on the body's orientation. Checked from both sides so
     * the order of the draw does not matter.
     */
    private fun fitsAllowedPostures(candidate: String, picks: Map<String, TagPick>): Boolean {
        val allow = library.constraints.allowedPostures
        if (allow.isEmpty()) return true
        val postureOf = library.constraints.posture
        val selected = picks.values.map { it.value }.filter { it.isNotEmpty() }

        val mine = allow[candidate]
        if (mine != null) {
            return selected.none { other -> postureOf[other]?.let { it !in mine } ?: false }
        }
        val myPosture = postureOf[candidate] ?: return true
        return selected.all { other -> allow[other]?.let { myPosture in it } ?: true }
    }

    /**
     * A pose that reads as "alone with the camera" cannot carry an act that needs a second
     * body. Without this the picker happily pairs 坐在床边晃腿 with 前后同时进入.
     */
    /**
     * Ear, crown, glasses, earrings: four accessories on one head is how a portrait turns into
     * two pictures. The budget counts accessories, not garment layers.
     */
    private fun fitsHeadBudget(
        candidate: String,
        picks: Map<String, TagPick>,
        selfKey: String
    ): Boolean {
        val cost = library.constraints.headCost
        val cap = library.constraints.headCap
        if (cap >= Int.MAX_VALUE / 2) return true
        val myCost = cost[candidate] ?: return true
        val spent = picks.entries
            .filter { it.key != selfKey }
            .filter { !it.value.ignored }
            .sumOf { cost[it.value.value] ?: 0 }
        return spent + myCost <= cap
    }

    private fun fitsSoloPose(candidate: String, picks: Map<String, TagPick>, selfKey: String): Boolean {
        val solo = library.constraints.soloPose
        if (solo.isEmpty()) return true
        val selfCategory = selfKey.substringBefore('.')
        val selfIsSolo = candidate in solo
        // Head count is read per category: 全裸 was two people under the global table.
        val selfNeedsTwo = library.peopleNeeded(selfCategory, candidate) >= 2
        if (!selfIsSolo && !selfNeedsTwo) return true
        // Both directions, so the order the two words are drawn in stops mattering: before this,
        // a pose drawn after the act was never re-checked and 对镜自拍 sat next to 后入.
        return picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() && !it.value.ignored }
            .none { entry ->
                val otherCategory = entry.key.substringBefore('.')
                val other = entry.value.value
                (selfIsSolo && library.peopleNeeded(otherCategory, other) >= 2) ||
                    (selfNeedsTwo && other in solo)
            }
    }

    /**
     * Mouth is a budget of one. Without this, 口交 and 含住手指 can both be drawn even though
     * they compete for the same body part — hands already had a cap, the mouth did not.
     */
    private fun fitsMouthBudget(candidate: String, picks: Map<String, TagPick>, selfKey: String, skip: Set<String>): Boolean {
        val cap = library.constraints.mouthCap
        if (cap <= 0) return true
        val cost = library.constraints.mouthCost
        val mine = cost[candidate] ?: return true
        val used = picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() }
            .mapNotNull { entry ->
                val categoryId = entry.key.substringBefore('.')
                if (categoryId in skip) null else cost[entry.value.value]
            }
            .sum()
        return used + mine <= cap
    }

    /**
     * One accessory per spot on the head.
     *
     * 面纱, 眼镜 and 口塞 live in three different categories, so the draw happily put all three on
     * one face. A zone of [FACE_ALL] (yashmak, hood) takes the whole head and pushes everything
     * else off; the other zones hold one item each, which is why 眼镜 and 口塞 can still meet.
     */
    private fun fitsFaceZone(candidate: String, picks: Map<String, TagPick>, selfKey: String, skip: Set<String>): Boolean {
        val zoneOf = library.constraints.faceZone
        if (zoneOf.isEmpty()) return true
        val zone = zoneOf[candidate] ?: return true
        val taken = picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() && !it.value.ignored }
            .filter { it.key.substringBefore('.') !in skip }
            .mapNotNull { zoneOf[it.value.value] }
            .toSet()
        if (taken.isEmpty()) return true
        if (zone == FACE_ALL || FACE_ALL in taken) return false
        return zone !in taken
    }

    /** One member per synonym group: 冷酷 and 魅惑 say the same thing twice. */
    private fun fitsSynonyms(candidate: String, picks: Map<String, TagPick>, selfKey: String, skip: Set<String>): Boolean {
        val groups = library.constraints.synonyms
        if (groups.isEmpty()) return true
        val group = groups.firstOrNull { candidate in it } ?: return true
        val used = picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() }
            .filter { it.key.substringBefore('.') !in skip }
            .map { it.value.value }
        return used.none { it != candidate && it in group }
    }

    /**
     * A saved state with holes never heals on its own: the store drops empty slots, and
     * restore only repairs conflicts instead of drawing what is missing. That is how a page
     * opens with half the categories showing 未选 and stays that way until something rerolls
     * the whole set.
     *
     * Only categories that came back entirely blank are redrawn; a partially filled category
     * keeps what the player already had.
     */
    fun fillBlanks(picks: MutableMap<String, TagPick>, holdGroups: Set<String> = emptySet()) {
        val skip = skipped(picks)
        library.categories.forEach { category ->
            if (category.group in holdGroups) return@forEach
            if (category.id in skip) return@forEach
            val holes = category.slots.count { slot ->
                val pick = picks[key(category.id, slot.id)]
                pick == null || (pick.value.isEmpty() && !pick.locked && !pick.ignored)
            }
            if (holes == 0 || holes != category.slots.size) return@forEach
            randomize(picks, category.id)
        }
    }

    /**
     * Re-checks every already-selected tag after something changed.
     *
     * Rerolling one category can invalidate another: switching 人数 to a solo value leaves
     * a 关系 tag that needs two people behind. Picking is checked when a value is drawn,
     * so a repair sweep is what keeps the whole selection consistent afterwards.
     *
     * [protect] is the slot the user just edited by hand — it keeps whatever they chose
     * instead of being silently reverted.
     */
    fun repair(
        picks: MutableMap<String, TagPick>,
        protect: String? = null,
        skipBefore: Set<String>? = null,
        holdGroups: Set<String> = emptySet()
    ) {
        val skip: MutableSet<String> = skipped(picks).toMutableSet()
        val skipAtEntry: Set<String> = skip.toSet()
        val ordered = library.categories.sortedBy { it.tier }
        ordered.forEach { category ->
            // 锁住的段连修补都不进：被另一段的新内容顶掉，就等于锁没生效。
            if (category.group in holdGroups) return@forEach
            if (category.id in skip) {
                category.slots.forEach { slot ->
                    val slotKey = key(category.id, slot.id)
                    val pick = picks[slotKey]
                    if (pick != null && !pick.locked && slotKey != protect) {
                        picks[slotKey] = pick.copy(value = "")
                    }
                }
                return@forEach
            }
            if (maleCategoryBlocked(category.id, picks)) {
                category.slots.forEach { slot ->
                    val slotKey = key(category.id, slot.id)
                    val pick = picks[slotKey]
                    if (pick != null && !pick.locked && slotKey != protect) {
                        picks[slotKey] = pick.copy(value = "")
                    }
                }
                return@forEach
            }
            category.slots.forEach { slot ->
                val slotKey = key(category.id, slot.id)
                val current = picks[slotKey] ?: return@forEach
                if (current.value.isEmpty() || current.locked || slotKey == protect) return@forEach
                val worn = wornType(picks, category.id)
                if (slot.id == COLOR_SLOT &&
                    (worn in library.constraints.selfColoured || worn in library.constraints.selfPatterned)
                ) {
                    picks[slotKey] = current.copy(value = "")
                    return@forEach
                }
                val accepted = selectedValues(picks, skip).filter { it != current.value }
                val wins = category.id in FRAMING
                // A word deleted from the library has to be re-drawn, but through the same
                // filter as any other candidate: replacing it directly skipped the rules and
                // could put back a word the frame had just removed.
                val stale = current.value !in slot.pool
                // Mutex belongs here too: drawing enforces it, but a value that was already
                // selected when the other half changed (出画 switched by hand) used to survive.
                if (!stale &&
                    satisfies(current.value, picks, slotKey, skip, wins) &&
                    !conflicts(current.value, accepted)
                ) {
                    return@forEach
                }
                val pool = slot.randomPool().filter { candidate ->
                    allowedIn(category.id, candidate) &&
                        !conflicts(candidate, accepted) &&
                        satisfies(candidate, picks, slotKey, skip, wins)
                }
                picks[slotKey] = current.copy(value = pickRandom(pool, current.value, slotKey) ?: "")
            }
            skip.addAll(skipped(picks))
        }
        // A pass can release a skip as well as add one: repair rewrites 只穿内衣 to 衣冠不整,
        // but `skip` only ever grows, so 上衣 / 下衣 stayed cleared under a rule that no longer
        // applies. Draw the released categories back before returning.
        // Union, not either: a reroll releases what the sweep entry was holding, while a state
        // that repair rewrites itself releases what repair came in with. Both counts as "was
        // switched off during this pass, is not switched off any more".
        reviveReleased(picks, skipBefore.orEmpty() + skipAtEntry, holdGroups)
        dropUnbackedProps(picks, holdGroups)
    }

    /**
     * 依赖道具才成立的词，要等道具落定再看一遍。
     *
     * 「蜡油痕」长在身体层，比道具层先抽，抽的时候场上还没有低温蜡烛，前提判不成；
     * 反过来道具抽到后也不会回头补一句痕迹。只在所有格子都定下来之后统一复核，
     * 前提不在就换一个同格能成立的词，而不是把整格清空。
     */
    private fun dropUnbackedProps(picks: MutableMap<String, TagPick>, holdGroups: Set<String>) {
        if (!strict) return
        val skip = skipped(picks)
        library.categories.forEach { category ->
            if (category.group in holdGroups) return@forEach
            if (category.id in skip) return@forEach
            category.slots.forEach { slot ->
                val slotKey = key(category.id, slot.id)
                val pick = picks[slotKey] ?: return@forEach
                if (pick.value.isEmpty() || pick.locked || pick.ignored) return@forEach
                val need = library.constraints.requires[pick.value].orEmpty()
                if (need.none { it.startsWith("toys:") }) return@forEach
                if (requirementsMet(pick.value, picks, slotKey, skip)) return@forEach
                val accepted = selectedValues(picks, skip).filter { it != pick.value }
                val pool = slot.randomPool().filter { candidate ->
                    allowedIn(category.id, candidate) &&
                        !conflicts(candidate, accepted) &&
                        satisfies(candidate, picks, slotKey, skip)
                }
                picks[slotKey] = pick.copy(value = pickRandom(pool, pick.value, slotKey) ?: "")
            }
        }
    }

    /** How many people the current 人数 selection means, or null when unset. */
    private fun crowdSize(picks: Map<String, TagPick>): Int? {
        val category = library.category(PEOPLE) ?: return null
        val slot = category.slots.firstOrNull() ?: return null
        val value = picks[key(category.id, slot.id)]?.value.orEmpty()
        if (value.isEmpty()) return null
        return library.constraints.peopleCount[value]
    }

    /**
     * A tag may only be used when the scene has at least as many people as the tag assumes.
     * A solo subject therefore never gets a two-person action, while a group shot still
     * accepts the single-person pool — most poses are written from one viewpoint.
     */
    private fun fitsPeople(
        candidate: String,
        picks: Map<String, TagPick>,
        selfKey: String,
        manual: Boolean = false
    ): Boolean {
        // An unset 人数 used to mean "no gate at all", so a solo page could show 几个人一起
        // 靠过来. Fall back to the largest head count this tab is allowed to draw.
        val crowd = crowdSize(picks) ?: crowdLimit() ?: return true
        val categoryId = selfKey.substringBefore('.')
        // 景别与构图是给一个主体取景的：两人同框时画面由人和关系决定，
        // 「胸部特写」「三分构图」这种只对一个人成立的说法就会打架。
        // 只拦自动抽取：玩家自己点名要这一格时照给，多人档输出偏短可以靠手补。
        if (!manual && crowd > 1 && selfKey in library.constraints.soloOnlySlots) return false
        // 场上有男人时，只写一个人自己的动作会让另一个人凭空消失：男方介绍句刚说完，
        // 下一句却是她独自骑在玩具上。这类词只在纯女或单人场出现。
        if (categoryId == SEX_ACT && malePresent(picks) &&
            library.peopleNeeded(categoryId, candidate) <= 1
        ) return false
        if (categoryId !in library.constraints.peopleByCategory &&
            candidate !in library.constraints.people
        ) return true
        return library.peopleNeeded(categoryId, candidate) <= crowd
    }

    /** The biggest head count the current tab can draw, or null when the library has none. */
    private fun crowdLimit(): Int? =
        peoplePool.mapNotNull { library.constraints.peopleCount[it] }.maxOrNull()

    /**
     * A place and a pose are drawn independently, so a garage floor can be asked to hold a
     * bedsheet and a window sill to hold someone lying down. Reject the pair when the place has
     * no surface the pose can use. Unannotated words stay unrestricted.
     */
    private fun fitsSurface(candidate: String, picks: Map<String, TagPick>): Boolean {
        val needs = library.constraints.poseNeeds
        val offers = library.constraints.placeSupports
        if (needs.isEmpty() && offers.isEmpty()) return true

        // "Has a wall" and "can stand" are both requirements, not alternatives. `any` let
        // 贴墙抬一条腿 pass on 纯白背景 because the stand half matched, and the wall it needs was
        // never there. Everything a pose lists must be present.
        needs[candidate]?.let { required ->
            // 关掉场景 is a deliberate "no place rules" choice, and the panel says so. Treating
            // an empty scene the same way left 把脸埋进枕头 legal on a forest floor.
            if (picks[key(SCENE, PLACE_SLOT)]?.ignored == true) return true
            val place = selectedWord(picks, SCENE)
            // An unset scene is not "anything goes": standing and lying on the ground work
            // anywhere, a bed, a wall, a chair and water do not.
            val available = if (place.isNullOrEmpty()) DEFAULT_SURFACES else offers[place] ?: DEFAULT_SURFACES
            return fitsRequirement(required, available)
        }

        val available = offers[candidate].orEmpty()
        if (available.isEmpty()) return true
        SURFACE_HOLDERS.forEach { categoryId ->
            val word = selectedWord(picks, categoryId) ?: return@forEach
            val required = needs[word] ?: return@forEach
            if (!fitsRequirement(required, available.toSet())) return false
        }
        return true
    }

    /**
     * 「站着靠墙」是两个条件，「床上或地上」是同一件事的几种写法。
     *
     * 表里两种混着写：侧身靠墙 = [stand, wall]，仰躺 = [bed, floor]。按「stand 必须满足，
     * 其余任选一个」读，两边都读得通：森林有 stand 没有 wall，靠墙仍然被拒；没有场景时
     * 有 stand 和 floor，仰躺成立而埋进枕头不成立。
     */
    private fun fitsRequirement(required: List<String>, available: Collection<String>): Boolean {
        if (required.isEmpty()) return true
        if ("stand" in required && "stand" !in available) return false
        val rest = required.filter { it != "stand" }
        return rest.isEmpty() || rest.any { it in available }
    }

    /**
     * Framing decides what is even inside the picture.
     *
     * 私处特写 next to 潮吹 reads as a mistake because the camera never asked what the content
     * was: the crop slot and the act slot were drawn independently. Every framing word now
     * declares the regions it removes, every content word declares where it lives, and the two
     * are checked in both directions so the draw order stops mattering.
     */
    private fun fitsFrame(
        candidate: String,
        picks: Map<String, TagPick>,
        selfKey: String,
        framingWins: Boolean = false
    ): Boolean {
        val hides = library.constraints.frameHides
        val hidesCats = library.constraints.frameHidesCategories
        if (hides.isEmpty() && hidesCats.isEmpty()) return true
        val others = picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() && !it.value.ignored }

        val mine = hides[candidate]
        val myCats = hidesCats[candidate].orEmpty()
        // When repairing, the framing word keeps its choice and the content it hides is what
        // gets re-drawn. Otherwise a crop can never be added to an already-written body.
        if (!framingWins && (mine != null || myCats.isNotEmpty())) {
            val cut = mine.orEmpty()
            others.forEach { entry ->
                if (entry.key.substringBefore('.') in myCats) return false
                val region = regionsOf(
                    entry.value.value,
                    entry.key.substringBefore('.'),
                    entry.key.substringAfter('.', "")
                )
                if (cut.any { it in region }) return false
            }
        }

        val myCategory = selfKey.substringBefore('.')
        val myRegions = regionsOf(candidate, myCategory, selfKey.substringAfter('.', ""))
        others.forEach { entry ->
            // A back view has no face to read, so the whole category goes, not just a region.
            if (myCategory in hidesCats[entry.value.value].orEmpty()) return false
            val cut = hides[entry.value.value] ?: return@forEach
            if (myRegions.any { it in cut }) return false
        }
        return true
    }

    /** Regions a word is drawn on; per-word, then its slot, then the category default. */
    private fun regionsOf(word: String, categoryId: String, slotId: String = ""): List<String> =
        library.constraints.regions[word]
            ?: SLOT_REGIONS["$categoryId.$slotId"]
            ?: library.constraints.categoryRegions[categoryId].orEmpty()

    /**
     * 一格落在画幅里的位置。
     *
     * 「形象」把妆容、年龄感、气质三样合在一类，只有妆容长在脸上：背面视角要收掉妆容，
     * 年龄和气质照写。分类默认区域给不了这种精度，只能按格声明。
     */
    private val SLOT_REGIONS = mapOf("person.face" to listOf("face"))

    private fun hiddenRegionsOf(values: Collection<String>): Set<String> {
        val out = mutableSetOf<String>()
        values.forEach { word -> library.constraints.frameHides[word]?.let { out += it } }
        return out
    }

    /**
     * Tied hands cannot reach for a lip. 捆绑 and 手铐拘束 already cost two hands, but a budget
     * only sums: 一只手按在锁骨上 costs one and still slipped in. A restraint removes the
     * whole hand-action slot instead.
     */
    private fun fitsHandBlock(candidate: String, picks: Map<String, TagPick>, selfKey: String): Boolean {
        val blocked = library.constraints.handBlock
        if (blocked.isEmpty()) return true
        val others = picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() && !it.value.ignored }
        val bound = others.any { it.value.value in blocked }
        if (bound && selfKey.startsWith("$HAND_ACTION.")) return false
        if (candidate in blocked) {
            return others.none { it.key.startsWith("$HAND_ACTION.") }
        }
        return true
    }

    private fun selectedWords(picks: Map<String, TagPick>, categoryId: String): List<String> {
        val category = library.category(categoryId) ?: return emptyList()
        return activeValues(picks, category).values.filter { it.isNotEmpty() }
    }

    private fun selectedWord(picks: Map<String, TagPick>, categoryId: String): String? {
        val category = library.category(categoryId) ?: return null
        return activeValues(picks, category).values.firstOrNull { it.isNotEmpty() }
    }

    /**
     * Whether anyone in frame is male. The head count is the only place the library states it,
     * so an unset or unrecognised count stays permissive rather than emptying every pool.
     */
    private fun malePresent(picks: Map<String, TagPick>): Boolean {
        val category = library.category(PEOPLE) ?: return true
        val slot = category.slots.firstOrNull() ?: return true
        val value = picks[key(category.id, slot.id)]?.value.orEmpty()
        if (value.isEmpty()) {
            if (peoplePool.isNotEmpty() && peoplePool.none { it.contains("男") }) return false
            return true
        }
        val listed = library.constraints.malePeople
        return if (listed.isEmpty()) value.contains("男") else value in listed
    }

    /** Words that need a man in frame are dropped the moment the cast is all female. */
    private fun fitsMale(candidate: String, picks: Map<String, TagPick>): Boolean {
        if (candidate !in library.constraints.maleOnly) return true
        return malePresent(picks)
    }

    /**
     * Male-anchored categories exist only when a man is in frame.
     *
     * Their words used to say so themselves — `requires: 男`, or a head count of two — and since
     * both are keyed by the word's text, the female copy of 全裸 carried the male reading and
     * disappeared from 单人. The gate belongs to the category, not to the string.
     */
    private fun maleCategoryBlocked(categoryId: String, picks: Map<String, TagPick>): Boolean =
        library.category(categoryId)?.who == "male" && !malePresent(picks)

    /**
     * Narrow the pool towards tags that match the current head count, strongest match first.
     *
     * Tiers are tried in order and a tier is only used when it still offers enough choices,
     * so a three-person scene with no three-person poses falls back to the two-person pool
     * instead of shrinking to nothing.
     */
    private fun preferPeople(
        categoryId: String,
        pool: List<String>,
        picks: Map<String, TagPick>
    ): List<String> {
        val crowd = crowdSize(picks) ?: return pool
        fun needed(word: String) = library.peopleNeeded(categoryId, word)
        val fits = pool.filter { needed(it) <= crowd }
        if (fits.isEmpty()) return pool
        if (crowd < 2) return fits

        // 只有人数会改变画面构成的分类才按精确人数收窄（双人画面配单人动作会别扭）。
        // 头部姿态这类修饰词不在此列：一条 people=2 会把整槽独占，锁一男一女后
        // 永远只出「下巴抵在肩上」。
        if (categoryId !in PEOPLE_SENSITIVE) return fits

        val exact = fits.filter { needed(it) == crowd }
        if (exact.isNotEmpty()) return exact

        val near = fits.filter { needed(it) >= crowd - 1 }
        if (near.size >= NEAR_PEOPLE_FLOOR) return near

        val pair = fits.filter { needed(it) >= 2 }
        return if (pair.size >= NEAR_PEOPLE_FLOOR) pair else fits
    }

    private fun fitsHandBudget(candidate: String, picks: Map<String, TagPick>, selfKey: String, skip: Set<String>): Boolean {
        val cap = library.constraints.handCap
        if (cap <= 0) return true
        val cost = library.constraints.handCost
        if (cost.isEmpty()) return true
        // A tag that does not use a hand is never made impossible by one that does. Without
        // this, an over-budget selection (两手被绑 + a hand action) rejected every candidate
        // in the library — body, outfit, hair, eyes — and emptied the whole board.
        val mine = cost[candidate] ?: 0
        if (mine <= 0) return true
        val used = picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() && !it.value.ignored }
            .filter { entry -> library.categories.none { it.id in skip && entry.key.startsWith("${it.id}.") } }
            .sumOf { entry -> cost[entry.value.value] ?: 0 }
        return used + mine <= cap
    }

    /** Posture and place share one rule: two different groups of the same kind exclude each other. */
    private fun fitsGroup(
        groups: Map<String, String>,
        candidate: String,
        picks: Map<String, TagPick>,
        selfKey: String,
        skip: Set<String>
    ): Boolean {
        if (groups.isEmpty()) return true
        val mine = groups[candidate] ?: return true
        return picks.entries.none { entry ->
            entry.key != selfKey &&
                entry.value.value.isNotEmpty() &&
                !entry.value.ignored &&
                library.categories.none { it.id in skip && entry.key.startsWith("${it.id}.") } &&
                groups[entry.value.value].let { it != null && it != mine }
        }
    }

    /**
     * The already-selected word that forbids [candidate], or null.
     *
     * Returning the partner instead of a boolean is what lets the panel say 与「内裤·湿痕内裤」
     * 不能共存 instead of the useless 与已选词不能共存.
     */
    private fun excludePartner(candidate: String, picks: Map<String, TagPick>, selfKey: String): String? {
        val pairs = library.constraints.excludes
        if (pairs.isEmpty()) return null
        val selected = picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() && !it.value.ignored }
            .map { it.value.value }
            .toSet()
        return pairs
            .firstOrNull { pair ->
                pair.contains(candidate) && pair.any { other -> other != candidate && other in selected }
            }
            ?.firstOrNull { it != candidate && it in selected }
    }

    /** `内裤·湿痕内裤`-style label, built once from the catalog. */
    private val wordLabels: Map<String, String> by lazy {
        buildMap {
            library.categories.forEach { category ->
                category.slots.forEach { slot ->
                    slot.pool.forEach { word ->
                        putIfAbsent(word, "${category.label}·$word")
                    }
                }
            }
        }
    }

    private fun requirementsMet(candidate: String, picks: Map<String, TagPick>, selfKey: String, skip: Set<String>): Boolean {
        val need = library.constraints.requires[candidate] ?: return true
        val present = picks.entries
            .filter { it.key != selfKey && it.value.value.isNotEmpty() && !it.value.ignored }
            .filter { entry -> library.categories.none { it.id in skip && entry.key.startsWith("${it.id}.") } }
            .map { it.key to it.value.value }
        // A plain entry matches any slot; `bottom:裤` insists on that category, so a pair of
        // panties ending in 裤 cannot stand in for trousers.
        return present.any { (key, value) ->
            need.any { entry ->
                val scope = RequirementFiller.needCategory(entry)
                (scope == null || key.startsWith("$scope.")) &&
                    RequirementFiller.names(value, RequirementFiller.needWord(entry))
            }
        }
    }

    private fun pickRandom(pool: List<String>, avoid: String, slotKey: String = ""): String? {
        if (pool.isEmpty()) return null
        // 人类是职业那一整格的开关，但它在种类池里只占二十六分之一。用权重做不到三分之一：
        // 近期抽过的词会被压到四分之一，平均只剩两成。所以这里直接掷一次。放在入口而不是
        // 抽词循环里，是因为修补和单格重抽也走这条路，否则人类只会在整组重抽时出现。
        if (slotKey == key(RACE, "kind") && HUMAN_KIND in pool && Random.nextDouble() < HUMAN_SHARE) {
            return HUMAN_KIND
        }
        val filtered = pool.filter { it != avoid }
        val source = filtered.ifEmpty { pool }
        // Below four candidates the pool is small enough that weighting only shuffles the same
        // words; leave those slots on a plain uniform draw.
        val recent = if (recent != null && source.size >= 4) recent else null
        val custom = library.constraints
        val chosen = when {
            // A hand-tuned weight outranks the plain draw; without one this stays a plain pick.
            recent == null && !custom.hasWeight -> source.randomOrNull()
            recent == null -> source.weightedPick { word -> custom.weightOf(word) }
            else -> source.weightedPick {
                word -> recent.weightOf(slotKey, word) * custom.weightOf(word)
            }
        }
        if (chosen != null && recent != null) recent.remember(slotKey, chosen)
        return chosen
    }

    /** Weighted draw over [List]<[String]>; weights come from [weight] so the policy stays outside. */
    private fun List<String>.weightedPick(weight: (String) -> Double): String? {
        val weights = map(weight)
        val total = weights.sum()
        if (total <= 0.0) return randomOrNull()
        var roll = Random.nextDouble() * total
        for (index in indices) {
            roll -= weights[index]
            if (roll <= 0.0) return this[index]
        }
        return last()
    }

    internal fun phrase(category: TagCategory, values: Map<String, String>): String {
        values.values.firstOrNull { it in library.singletons }?.let { return it }
        if (category.id == EXPRESSION) return expressionPhrase(values)
        if (category.id == RACE) {
            val kind = values["kind"].orEmpty()
            val ethnicity = values["ethnicity"].orEmpty()
            val phrase = FANTASY_RACE_PHRASES[kind]
            return if (phrase != null) {
                if (ethnicity.isNotEmpty()) {
                    val humanPrefixes = listOf("人类容颜，", "人类女性容颜，", "人类容貌，", "人类面孔，")
                    val matched = humanPrefixes.firstOrNull { phrase.startsWith(it) }
                    if (matched != null) {
                        "${ethnicity}面容，" + phrase.removePrefix(matched)
                    } else {
                        "${ethnicity}面容，$phrase"
                    }
                } else phrase
            } else {
                (ethnicity + kind).ifEmpty { "" }
            }
        }

        return when (category.join) {
            JOIN_PERSON -> {
                // 身份已经删掉，这一类只剩妆容、年龄感、气质。整体仍由 [personClause] 出句，
                // 这里保底：二十八岁的 + 清纯 会读成「二十八岁的清纯」，所以年龄另起一段。
                val age = values["age"].orEmpty()
                val look = values["look"].orEmpty()
                when {
                    age.isEmpty() -> look
                    look.isNotEmpty() -> "$look，$age"
                    else -> age
                }
            }

            JOIN_BREAST -> {
                val size = values["size"].orEmpty()
                val shape = values["shape"].orEmpty()
                // 乳头单独成一小句：这一格的词自带名词（乳头硬挺、粉嫩乳晕），接在「水滴形的巨乳」
                // 后面若用「的」，会读成「巨乳的乳头」，把归属写错。
                val nipple = values["nipple"].orEmpty()
                val breast = when {
                    shape.isNotEmpty() && size.isNotEmpty() ->
                        if (shape in BREAST_POST_SHAPES) {
                            "$size，$shape"
                        } else {
                            val cleanShape = shape.removeSuffix("胸部").removeSuffix("的")
                            "${cleanShape}的$size"
                        }
                    else -> size.ifEmpty { shape }
                }
                when {
                    nipple.isEmpty() -> breast
                    breast.isEmpty() -> nipple
                    else -> "$breast，$nipple"
                }
            }

            JOIN_NIPPLE -> {
                val state = values["state"].orEmpty()
                val look = values["look"].orEmpty()
                val bare = state.replace("乳头", "")
                // 被吸吮 + 大乳晕 read as one noun phrase ("the sucked large areola") under 的.
                // An action state has to stand apart from the size/shade that follows it.
                val glue = if (bare.startsWith("被")) "，" else "的"
                when {
                    bare.isNotEmpty() && look.isNotEmpty() ->
                        if (look.contains("乳头") || look.contains("乳晕")) "$bare$glue$look" else "$bare$glue${look}乳头"

                    else -> look.ifEmpty { state }
                }
            }

            // One subject, one noun: 穿着运动裤，体毛浓密的结实男人
            JOIN_MALE -> {
                val outfit = values["outfit"].orEmpty()
                val feature = values["feature"].orEmpty()
                val body = values["body"].orEmpty()
                val look = if (feature.isNotEmpty() && body.isNotEmpty()) "${feature}的$body"
                           else if (body.isNotEmpty()) body else feature
                // 「身上什么也没穿」这类本身就是完整状态，再加「穿着」就成了「穿着身上什么也没穿」。
        val head = when {
            outfit.isEmpty() -> ""
            outfit.startsWith("身上") || outfit.startsWith("下身") || outfit == "全裸" -> outfit
            else -> "穿着$outfit"
        }
                listOf(head, look).filter { it.isNotEmpty() }.joinToString("，") +
                    if (look.isNotEmpty() || head.isNotEmpty()) "男人" else ""
            }

            // 冷白皮 + 皮肤上挂着水珠 used to concatenate into 冷白皮皮肤上挂着水珠.
            JOIN_SKIN -> {
                var tone = values["tone"].orEmpty()
                val state = values["state"].orEmpty()
                val toneIsFeature = tone in SKIN_FEATURE_TONES
                // 皮肤油亮 + 皮肤上挂着水珠 said 皮肤 twice; the state already carries the noun.
                // 「棕褐皮肤的汗水浸透的皮肤」说了两遍皮肤：状态自带名词时，色调只留颜色。
                if (!toneIsFeature && state.contains("皮肤")) {
                    tone = tone.removePrefix("皮肤").removeSuffix("皮肤").removeSuffix("的")
                }
                val extra = category.slots
                    .filter { it.id != "tone" && it.id != "state" }
                    .mapNotNull { values[it.id] }
                    .filter { it.isNotEmpty() }
                    .joinToString("，")
                val base = when {
                    tone.isNotEmpty() && state.isNotEmpty() ->
                        if (toneIsFeature) "$tone，$state" else "${tone}的$state"
                    else -> tone.ifEmpty { state }
                }
                listOf(base, extra).filter { it.isNotEmpty() }.joinToString("，")
            }

            // 黑色的长直发 + 被汗黏成一缕一缕: the state describes the hair, it does not name it,
            // so it sits outside the 的-phrase instead of running into it with no separator.
            JOIN_OF_COLOR -> {
                val color = values["color"].orEmpty()
                val rest = category.slots
                    .filter { it.id != "color" && it.id != "state" }
                    .mapNotNull { values[it.id] }
                    .filter { it.isNotEmpty() }
                    .joinToString("")
                // 「无刘海」「中分」「微卷」描述的是状态，不是发型名；挂上「的」就成了
                // 「亚麻色的无刘海」。状态词改用逗号另起一段，前面补一个「长发」站住。
                // 没有款式的时候颜色无处依附：「穿米色」「红色」都是半个句子。
                if (rest.isEmpty()) return values["state"].orEmpty()
                val stateful = category.id == HAIR && rest in library.constraints.hairState
                // 白衬衫 keeps its own colour even when the colour slot still holds one: a locked
                // pick is never cleared, so this is the last place that guarantees the line reads.
                val selfColoured = rest.isNotEmpty() &&
                    (rest in library.constraints.selfColoured || rest in library.constraints.selfPatterned)
                val head = when {
                    selfColoured -> rest
                    color.isNotEmpty() && rest.isNotEmpty() ->
                        if (stateful) "${color}长发，$rest" else "${color}的$rest"
                    else -> color.ifEmpty { rest }
                }
                listOf(head, values["state"].orEmpty())
                    .filter { it.isNotEmpty() }
                    .joinToString("，")
            }

            // 瞳色 + 眼型, 犄角 + 尾巴, 长焦 + 胸部在画外: separate statements, one comma apart.
            JOIN_LIST -> category.slots
                .mapNotNull { values[it.id] }
                .filter { it.isNotEmpty() }
                .joinToString("，")

            else -> category.slots.mapNotNull { values[it.id] }.filter { it.isNotEmpty() }.joinToString("")
        }
    }

    /** 表情与复合情绪已经并成一格，这里只要取那一个值。 */
    private fun expressionPhrase(values: Map<String, String>): String = values["face"].orEmpty()

    private fun isPants(type: String): Boolean = type.contains("裤") && !type.contains("裙")

    /**
     * Two categories can each be grammatical and still repeat each other: 皮肤光滑无毛 + 皮肤带着
     * 油光 read as 皮肤 twice, and a state word such as 油光 repeats the tone it decorates. Fold
     * `X的X` first, then drop a comma segment that a previous segment already contains.
     */
    private fun tidy(text: String): String {
        val folded = text
            .replace(Regex("([^，的]{2,6})的\\1"), "$1")
            .replace(Regex("的+"), "的")
            .replace(Regex("^的+|的+$"), "")
            .replace(Regex("\\s+"), "")
            .trim()
        if (!folded.contains("，")) return folded
        val kept = mutableListOf<String>()
        folded.split("，").map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            if (kept.none { it.contains(part) }) kept += part
        }
        return kept.joinToString("，")
    }

    companion object {
        private const val OUTFIT_STATE = "outfitState"
        private const val PEOPLE = "people"
        private const val POSE = "pose"
        private const val SEX_ACT = "sexAct"
        private const val EXPRESSION = "expression"
        private const val GAZE_DIR = "gazeDir"
        private const val FLUIDS = "fluids"
        private const val CAMERA = "camera"

        /** 他的脸 — optional category; when off, the framing decides as before. */
        private const val MALE_FACE = "maleFace"

        /** 日常档的男方外貌单独成一个分类，成人档并进 maleBody.feature，两档都要取到。 */
        private const val MALE_FEATURE = "maleFeature"

        /** 第三个人的动作，单独成句，不混进她的动作列表。 */
        private const val PARTNER_ACT = "partnerAct"

        /** 一男三女时两位旁观女性各一格，两格分开成句。 */
        private const val PARTNER_ACT_SLOT = "act"
        private const val PARTNER_ACT_SLOT_2 = "act2"

        /** 只在真正插入时才成立的阴茎描述。 */
        private val ENTRY_ONLY_ORGANS = setOf("抵着入口的阴茎")

        /** 疲软状态下无法插入。 */
        private val FLACCID_ORGANS = setOf("半软的阴茎", "刚射完的阴茎")

        /** 这些动作要碰到根部或入口，前提是根部还露在外面。 */
        private val BASE_NEEDS_EXPOSED = setOf("含着他的阴茎根部", "扶着阴茎往里送")

        /** 写的是阴茎正在进出；口交、乳交这类没有交合处，两句都不成立。 */
        private val PENETRATION_ONLY_PARTNER = setOf("扶着阴茎往里送", "凑过来舔着交合的地方")

        /** 整根吞入之后，根部不在外面，第三个人够不到。 */
        private val FULL_DEPTH_MARKS = listOf(
            "坐下去", "顶到底", "一次进到底", "全根", "坐入", "吞到底", "整根", "坐到最深处"
        )

        /**
         * 把脸埋起来的姿势。
         *
         * 这些词和表情、视线是互斥的：脸埋在手掌里，画不出「嘟嘴看镜头」。
         * 景别遮挡走 frameHidesCategories，头上这套没人管过。
         */
        private val FACE_COVERING = setOf("把脸埋进手掌", "脸埋进枕头", "额头抵着床单")

        /** 依赖脸可见的分类。 */
        // 整类依赖脸：表情、视线，以及 SFW 档里仍然是独立分类的妆容。
    // NSFW 的「形象」只有 face 槽在这里，整类不算。
    private val FACE_DEPENDENT = setOf("expression", "gazeDir", "makeup", "person.face")

        /** 动作名里出现这些字样，说明器官正在她体内。 */
        private val PENETRATION_HINTS = listOf(
            "插入", "进入", "后入", "侧入", "肛交", "抽插", "顶到底",
            "骑乘", "坐入", "骑在", "上下动", "抽送", "双穴"
        )

        /** 这些动作里器官不在入口，抵着入口那句话就不成立。 */
        private val NOT_AT_ENTRANCE = listOf(
            "口交", "深喉", "口爆", "舔阴茎", "含住", "吞吐", "颜骑",
            "乳交", "足交", "腋交", "股间", "素股", "夹着", "蹭着磨"
        )

        /** 人数直接决定画面构成的分类；其余分类不按精确人数收窄。 */
        private val PEOPLE_SENSITIVE = setOf("activity", "sexAct", "pose")

        /** Categories that only describe where the lens is, not what is in it. */
        private val FRAMING = setOf(CAMERA)

        /**
         * Negations get their 穿/戴 verb, everything else starting with 无 is an adjective.
         *
         * Prefixing on `startsWith("无")` turned 无痕 into 没穿痕 and 无辜大眼 into 没穿辜大眼. Only
         * these six words mean "not wearing it"; 无钢圈 / 无肩带 / 无框眼镜 / 无刘海 describe the
         * garment itself and must keep their own wording.
         */
        private val BARE_WORD = mapOf(
            "无内裤" to "没穿内裤",
            "无胸罩" to "没穿胸罩",
            "无头饰" to "没戴头饰",
            "无帽" to "没戴帽",
            "无饰品" to "没戴饰品",
            "无妆" to "没化妆",
        )

        /**
         * 款式本身就表示「这件没穿」。颜色槽对它们一律让位：写了「没穿内裤」再补「蓝色」
         * 就成了「蓝色的没穿内裤」。两个同义写法都在这里登记，行为才一致。
         */
        private val BARE_STATE_WORDS = BARE_WORD.keys + setOf(
            "没穿内裤", "没穿胸罩", "赤足", "光脚", "光腿", "全裸"
        )

        /**
         * Draw order is the catalog's tier field: 人数 1 → 场景 2 → 性行为 3 → 取景 4 →
         * 穿搭状态 5 → 姿势动作 6 → 身体 7 → 衣物 8 → 配饰 9 → 派生 10 → 风格 11.
         * A low tier may remove what a high tier put down, never the other way round.
         */
         /** 登记为「通用」的玩具不挑玩法，与「还没登记」区分开。 */
         private const val GENERAL_TOY_ACT = "通用"

         /** 前提写成 `top:` 时只要求那一类身上有东西，不点名具体款式。 */
         private const val ANY_IN_CATEGORY = ""

         /** 贴身里衣：和外套分开说，模型才分得清里外。 */
        private val INNER_WEAR = setOf("bra", "panties")

        /** 名字里带这些词根的款式自带花色，前面不该再挂一个纯色。 */
         private val PATTERN_ROOTS = listOf(
             "格纹", "条纹", "波点", "碎花", "豹纹", "印花", "彩色", "渐变", "双色"
         )

         private const val RACE = "race"
         private const val PERSON = "person"
         
         private const val HUMAN_KIND = "人类"

        /** 动作里出现这些字，说明体位已经由动作本身交代了。 */
        private val ACT_POSITIONS = listOf(
            "蹲", "坐", "躺", "趴", "跪", "站", "骑", "跨", "伏", "压", "抱起", "抬腿",
            "抱", "悬空", "卧", "仰"
        )

        /** 这些种族名本身没有性别，前面加「女」会读成「女亡灵」。 */
        private val GENDERLESS = setOf("亡灵", "幽灵", "妖怪", "机械人形", "仿生人")
         private val FANTASY_RACE_PHRASES = mapOf(
             "精灵" to "修长尖耳，银白长发，清冷出尘的精灵容貌",
             "魅魔" to "弯曲黑色恶魔角，小巧蝙蝠翅膀，修长桃心形尾巴，魅惑妖娆的魅魔",
             "恶魔" to "粗大恶魔角，宽大恶魔翼，锋利甲片与暗黑魔气的恶魔",
             "小恶魔" to "小巧恶魔双角，娇小蝠翼，细尖尾巴，俏皮神情的小恶魔",
             "吸血鬼" to "苍白如纸的皮肤，微露细尖牙，高贵神秘的吸血鬼",
             "天使" to "洁白巨大羽翼，头顶神圣光环，宁静圣洁的天使",
             "堕天使" to "漆黑巨大羽翼，残破光环，冷艳孤傲的堕天使",
             "龙女" to "人类女性容颜，一对微弯龙角，细密泛光鳞片，修长龙尾的龙女",
             "幽灵" to "半透明散发幽蓝微光的身躯，双脚虚化漂浮在半空的幽灵",
             "狐妖" to "人类容颜，一对毛茸茸白色狐耳，身后多条蓬松狐尾的狐妖",
             "猫妖" to "人类容颜，灵动猫耳，细长猫尾，眼眸灵动的猫妖",
             "狼女" to "人类容颜，警惕竖起的狼耳，粗长蓬松狼尾，野性桀骜的狼女",
             "兔耳娘" to "人类容颜，长长软萌兔耳，身后毛球短尾巴的兔耳娘",
             "蛇女" to "人类女性上半身，微露金色竖瞳，修长灵活的细鳞蛇尾",
             "美人鱼" to "有着人类上半身，耳后半透明鱼鳍，修长泛着蓝绿鳞片鱼尾，美人鱼",
             "海妖" to "深海海灵容貌，耳侧深色鱼鳍，带着危险魅惑的妖异海妖",
             "雪女" to "极度苍白冰冷的肌肤，黑色长发，素白和服与冷冽霜气的雪女",
             "妖精" to "娇小身躯，透明脉络薄翼，散发微光的仙子妖精",
             "花精" to "微小体态，花瓣形状的双翼，发丝间带有花朵植物细节的花精",
             "花妖" to "常人体型的草木花灵，藤蔓纹理，发丝与衣物带有点点花瓣的花妖",
             "牛娘" to "人类容颜，头生小巧牛角与花斑牛耳，身后细尾，身姿丰满的牛娘",
             "史莱姆娘" to "半透明水润果冻般的胶质发丝与身躯，软弹微透的史莱姆娘",
             "鹿娘" to "人类容颜，额前生有精美珊瑚状鹿角，灵动温顺的鹿娘",
             "僵尸娘" to "额前微斜黄符，苍白冰冷肌肤，清冷病娇的僵尸娘",
             "羊娘" to "人类容颜，微卷绵羊角，毛茸茸软萌温顺的羊娘",
             "机械人形" to "可见的精密机械关节，金属肢体，合成人造面板的机械人形",
             "仿生人" to "高度逼真的合成皮肤，细微人造分模线，太阳穴处精密接口的仿生人"
         )
         private const val PEOPLE_ID = "people"
         private const val OWNED_FLUIDS = "fluids"
         // 体液词多半自带方位（精液在舌头上 / 锁骨上的水珠 / 大腿内侧的水光），
        // 前面再加「她身上的」就成了「她身上的精液在舌头上」。不加反而读得通。
        private val OWNED = emptySet<String>()
         private const val SKIN = "skin"
         private const val FEATURE_HEAD = "featureHead"
        private const val MOOD = "mood"
        private const val GRADE = "grade"
        private const val PANTIES = "panties"
        private const val BOTTOM = "bottom"
        private const val HAIR = "hair"
        private const val TYPE_SLOT = "type"
        private const val COLOR_SLOT = "color"
        private const val PLACE_SLOT = "place"
        private const val CHEST = "chest"

        /** 没有场景时仍然成立的两件事：能站着，能躺在地上。 */
        private val DEFAULT_SURFACES = setOf("stand", "floor")
        private const val SEPARATOR = ", "

/**
 * Output order, most defining first. The first line decides what the picture is made of ——
 * medium, framing, place, who. The second sets state and action. The third is decoration that
 * a text encoder may never reach, which is where it belongs.
 */
private val PROMPT_TIERS: List<List<String>> = listOf(
    // 一、风格与影调：最先说，模型才不会退回默认观感。
    listOf("mood", "grade"),
    // 二、取景、场景、环境痕迹、人数。痕迹挨着场景读，「浴室里还弥漫着水汽」就该跟「浴室」连着。
    listOf("camera", "scene", "sceneTrace", "people"),
    // 三、她是谁：种族、年龄气质、头发、眼睛、皮肤、身材、乳房、腿、阴部、体液。
    listOf(
        "race", "person", "hair", "eyeColor", "skin", "bodyType",
        "breasts", "legs", "pussy", "fluids", "featureHead",
    ),
    // 四、此刻在做什么。有主戏时这一格整格跳过，见 skipped()。
    listOf("activity"),
    // 五、穿着：先贴身再外穿，配件与道具居中，穿脱状态收在这句末尾。
    listOf(
        "bra", "panties", "top", "bottom", "garment", "socks", "shoes",
        "headwear", "jewelry", "toys", "outfitState",
    ),
    // 六、姿态、手部、表情与视线先说清她这一侧，再写男方与性行为。
    // 反过来的话，她的表情和视线会接在男方那句后面，读起来全成了他在做。
    listOf(
        "pose", "headPose", "handAction", "expression", "gazeDir",
        "maleFace", "maleBody", "maleHand", "penis", "sexAct", "partnerAct",
    ),
)

/** Same order, flattened: the on-screen list reads in the order the prompt is written. */
val promptOrder: List<String> get() = PROMPT_TIERS.flatten()
        private const val JOIN_PERSON = "person"
        private const val JOIN_BREAST = "breast"
        private const val JOIN_NIPPLE = "nipple"
        private const val JOIN_OF_COLOR = "of-color"
        private const val JOIN_LIST = "list"
        private const val JOIN_SKIN = "skin"
        private const val JOIN_MALE = "male"
        private const val SCENE = "scene"
        private const val ACTIVITY = "activity"
        private const val HAND_ACTION = "handAction"
        private const val HEAD_POSE = "headPose"

        /** Categories whose words can require a particular surface. */
        private val SURFACE_HOLDERS = listOf(POSE, SEX_ACT, ACTIVITY, HAND_ACTION, HEAD_POSE, SKIN)

        /** 参与年代配对的衣物分类。没登记年代的词按现代算。 */
        /** 这些穿脱状态本身就说「只剩这件」，与它点名的分类不能并存。 */
        private val LOWER_BARE_STATES = mapOf(
            "下身赤裸" to listOf("bottom", "panties"),
            "全裸" to listOf("bottom", "panties", "top"),
            "只穿上衣" to listOf("bottom", "panties"),
            "只穿袜子" to listOf("bottom", "panties"),
            "只围毛巾" to listOf("bottom", "panties"),
            "毛巾遮在胸前" to listOf("bottom", "panties"),
            "床单松散裹身" to listOf("bottom", "panties"),
            "裸体围裙" to listOf("bottom", "panties"),
            "只穿内裤" to listOf("bottom"),
            "只穿裤子" to listOf("top")
        )

    /** Activities that need room to move. */
    private val ROAMING = setOf("奔跑", "行走", "跳跃", "爬行", "扑倒", "冲撞")

    /** A pose that pins the body in one spot. Matched by these words rather than listed. */
    private val ANCHORED = listOf("靠", "躺", "坐", "跪", "趴", "贴墙", "倚", "压", "撑")

        private const val MALE_BODY = "maleBody"
        private const val MALE_HAND = "maleHand"
        private const val PENIS = "penis"

        private const val MAX_RULE_ROUNDS = 8

        /** 人类在种类池里的出现比例，靠掷骰子而不是权重，权重会被近期惩罚抹平。 */
        private const val HUMAN_SHARE = 1.0 / 3.0

        private val CHINESE_DIGITS = mapOf(
            '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )

        /** faceZone value that covers the whole head (面纱, 头纱, 面罩). */
        private const val FACE_ALL = "all"
        private const val NEAR_PEOPLE_FLOOR = 5

        /**
         * Paragraph layout for the sectioned output. Every category appears once, except
         * maleBody and penis which the staging sentence speaks for.
         */
        private val SECTION_HEAD = listOf(
            "mood", "grade",
            // 日常档独有的媒介、质感、光线、构图与焦段，属于定调的一层，必须排在最前。
            "style", "film", "light", "composition", "lens",
            // 环境痕迹挪到人物之后：冰箱门开没开、楼下有人经过，这些在人物登场前
            // 堆一长串，会把媒介风格和主体一起冲淡。
            // 环境痕迹留在场景这一层。挪到末尾试过：「浴室里还弥漫着水汽」会接在
            // 动作之后，读起来像事后补话，比挤在人物前面更别扭。
            "camera", "scene", "season", "weather", "sceneTrace", "people"
        )

        private val SECTION_HERO = listOf(
            "race", "person", "hair", "eyeColor", "featureHead", "skin", "bodyType",
            "breasts", "waist", "hips", "legs", "pussy",
            "top", "bottom", "bra", "panties", "socks", "shoes",
            "outfitState",
            // 衣物细节跟在着装与穿脱状态之后。
            "garment",
            "headwear", "jewelry", "toys", "propObject", "handProp"
        )

        private val SECTION_ACT_PRE = listOf(
            "pose", "headPose", "handAction", "expression", "gazeDir", "activity"
        )
        private val SECTION_ACT_MAIN = listOf(
            "sexAct", "interaction", "fluids", "pet"
        )
        private val SECTION_TAIL = emptyList<String>()

        /**
         * What reads as her static body/appearance rather than her clothes, for the 她是 / 穿 split.
         */
        private val HER_BODY = setOf(
            "race", "person", "featureHead",
            "eyeColor", "hair", "bodyType",
            "breasts", "legs", "pussy",
            "skin"
        )

        /** 靠「穿」连接的衣物分类（其中独立状态词由 isStandaloneWearClause 分流）。 */
        private val WEAR_CLOTHES = setOf("top", "bottom", "bra", "panties", "socks", "shoes")

        private const val GARMENT = "garment"

        /** 穿脱状态与衣物细节：不加「穿」，紧随衣物之后作为状态子句。 */
        private val WEAR_STATES = setOf("outfitState", "garment")

        /** 靠「戴着」连接。 */
        private val WEAR_ACCESSORY = setOf("headwear", "jewelry")

        /** 靠「拿着」连接——肛塞、冰块、皮拍及 SFW 随身物/手部道具。 */
        private val WEAR_PROPS = setOf("toys", "propObject", "handProp")

        /** 形象格中描述五官/肤色细节的词：单独放作前置子句，不直接塞在「女孩」前。 */
        private val FACE_DETAIL_LOOKS = setOf(
            "苍白肤色浅青黑眼圈", "冷白皮薄唇", "小麦色肌肤健康感", "深眼窝高鼻梁",
            "鹅蛋脸柔美轮廓", "素颜圆脸", "混血感立体五官", "微肉脸颊", "窄脸尖下巴"
        )

        /** 名词型气质：修饰人物名词时补「带着…的」或「…的」，同时保留原词子串。 */
        private val WTIH_DE_LOOKS = setOf("媚态", "风尘味", "反差萌", "书卷气")
        private val DE_SUFFIX_LOOKS = setOf(
            "纯欲风", "厌世感", "破碎感", "清冷感", "故事感",
            "距离感", "松弛感", "娇纵感", "反差感", "贵族气质"
        )

        /** 肤色槽里描述面部/局部特征而非底色的词，与皮肤状态用逗号并列而不挂「的」。 */
        private val SKIN_FEATURE_TONES = setOf("有雀斑", "晒痕")

        /** 乳房形态槽里属于动作/分句的词，放在罩杯之后用逗号衔接。 */
        private val BREAST_POST_SHAPES = setOf("用手挡住却没挡住", "从侧面能看见弧度", "随动作轻微晃动")

        /** 衣物槽中自带完整主谓或动作状态、不能前置「穿」的词首。 */
        private val STANDALONE_WEAR_PREFIXES = listOf(
            "只穿", "不穿", "没穿", "赤足", "光脚", "光腿", "全裸", "半裸", "半脱",
            "胸罩移位", "胸罩滑落", "胸罩被", "胸罩扣", "胸罩下缘", "解开一半",
            "褪到", "挂在", "被拨", "被拉", "拉高", "湿透", "透出", "滑落到", "只挂在", "半褪到",
            "丝袜破洞", "丝袜抽丝", "丝袜滑落", "袜口", "单只",
            "高跟鞋挂在", "高跟鞋掉了", "高跟鞋踩在", "鞋带散开"
        )

        /** Breast and legs are the only body groups left; 腰腹与臀部已并入身材，不再单独成段。 */
        private val BODY_BLOCK = listOf("breasts", "legs")
        private const val BODY_BLOCK_CAP = 2

        // Every entry must read after 在画面…; a side that names the frame itself doubles it.
        private val SUPPORT_SIDES = listOf(
            "左侧", "右侧", "背景中", "边缘处", "深处", "靠门的一侧", "窗边", "角落"
        )
        private val SUPPORT_TURNED = listOf(
            "背对镜头", "侧身站着", "低头看向地面", "安静地看着这一幕",
            "抱着手臂旁观", "半跪着没有起身", "把脸别向一边", "靠在墙边没有动"
        )
        // Used instead of the spectator lines when the hero's act needs a second woman and the
        // cast has no man: someone has to be doing it.
        private val SUPPORT_JOINING_ONE = listOf(
            "贴在她身侧，扶着她的腰",
            "从身后托着她的肩",
            "俯身凑近，掌心停在她胸前",
            "跪在她身旁，按着她的小腿"
        )
        private val SUPPORT_JOINING_MANY = listOf(
            "围在她身边，其中一人扶着她的腰",
            "分列两侧，有人托着她的肩",
            "凑近围拢，其中一人按着她的腿",
            "围成一圈，离她最近的那个正贴着她"
        )
        private val SUPPORT_FACING = listOf(
            "侧身站着", "低头看向地面", "安静地看着这一幕",
            "抱着手臂旁观", "交叠着双腿旁观", "目光停在这边", "把脸别向一边"
        )

        private val UNDERSTATEMENTS = listOf(
            "，和平时冷静从容的样子形成反差",
            "，平日里那股克制此刻完全不见",
            "，已经顾不上维持平时的表情",
            "，和平日镜头前的从容判若两人"
        )
    }
}

