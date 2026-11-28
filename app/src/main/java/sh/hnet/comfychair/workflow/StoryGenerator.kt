package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.model.StoryWord
import kotlin.random.Random

/** 一个分项的当前状态，语义和标签词库的 TagPick 一致：锁定、忽略、清空。 */
data class StoryPick(
    val value: String = "",
    val locked: Boolean = false,
    val ignored: Boolean = false
) {
    val active: Boolean get() = !ignored && value.isNotEmpty()
}

/**
 * 一个分项。[group] 非空且相邻的几个字段会并成一行显示（年龄｜气质、发型｜发色、
 * 胸部四格），抽取和约束仍是一格一池。
 */
data class StoryField(
    val key: String,
    val label: String,
    val pool: String,
    val group: String = "",
    /** 同组并排时这一行显示的名字，只有组内第一个字段需要写。 */
    val groupLabel: String = ""
)

data class StorySection(val label: String, val fields: List<StoryField>)

/**
 * 从剧情词库里凑一句话。
 *
 * 抽取顺序就是句子的顺序，而且每抽一格都会更新「场景里有什么」：
 * 地点带来台面和镜子，穿着带来围裙和丝袜，脱掉的从身上拿掉但仍算在场。
 * 后面的词只在这份状态成立时才进候选，所以不会出现「全裸还去解围裙」。
 */
class StoryGenerator(
    private val library: StoryLibrary,
    private val random: Random = Random.Default
) {

    val sections: List<StorySection> = listOf(
        StorySection(
            "人物",
            listOf(
                StoryField(CAST, "人数", "cast"),
                StoryField(ETHNICITY, "种族", "ethnicity"),
                StoryField(REGION, "地域", "region"),
                StoryField(AGE, "年龄感", "age", group = "face", groupLabel = "形象"),
                StoryField(LOOK, "气质", "look", group = "face"),
                StoryField(HAIR, "发型", "hair", group = "hair", groupLabel = "发型"),
                StoryField(HAIR_COLOR, "发色", "hair_color", group = "hair"),
                StoryField(SKIN, "肤色", "skin"),
                StoryField(BUILD, "身材", "build"),
                StoryField(BUST_SIZE, "胸围", "bust_size", group = "bust", groupLabel = "胸部"),
                StoryField(BUST_SHAPE, "胸型", "bust_shape", group = "bust"),
                StoryField(AREOLA, "乳晕", "areola", group = "bust"),
                StoryField(NIPPLE, "乳头", "nipple", group = "bust"),
                StoryField(ACCENT, accentLabel, "accent"),
                StoryField(GLASSES, "眼镜", "glasses"),
                // 她穿什么和她是同一件事：从「场景与穿着」挪到这里，跟女二自己那几行对齐。
                // 抽取时地点、时间、天气已经在循环前定好，穿着照样看得见这一轮的环境。
                StoryField(OUTFIT, "穿着", "outfit"),
                StoryField(STATE, "状态", "outfit_state"),
                StoryField(SOCKS, "袜", "socks"),
                StoryField(SHOES, "鞋", "shoes")
            )
        ),
        StorySection(
            "女二",
            listOf(
                StoryField("w2_ethnicity", "种族", "ethnicity"),
                StoryField("w2_region", "地域", "region"),
                StoryField("w2_age", "年龄感", "age", group = "face2", groupLabel = "形象"),
                StoryField("w2_look", "气质", "look", group = "face2"),
                StoryField("w2_hair", "发型", "hair", group = "hair2", groupLabel = "发型"),
                StoryField("w2_hair_color", "发色", "hair_color", group = "hair2"),
                StoryField("w2_skin", "肤色", "skin"),
                StoryField("w2_build", "身材", "build"),
                StoryField("w2_bust_size", "胸围", "bust_size", group = "bust2", groupLabel = "胸部"),
                StoryField("w2_bust_shape", "胸型", "bust_shape", group = "bust2"),
                StoryField("w2_areola", "乳晕", "areola", group = "bust2"),
                StoryField("w2_nipple", "乳头", "nipple", group = "bust2"),
                StoryField("w2_accent", accentLabel, "accent"),
                StoryField("w2_glasses", "眼镜", "glasses"),
                StoryField("w2_outfit", "穿着", "outfit"),
                StoryField("w2_socks", "袜", "socks"),
                StoryField("w2_shoes", "鞋", "shoes"),
                StoryField("w2_state", "状态", "outfit_state"),
                StoryField(W2_MID, "过程中", "outfit_mid")
            )
        ),
        StorySection(
            "男主",
            listOf(
                StoryField(MAN_LOOK, "外形", "man_look"),
                StoryField(MAN_OUTFIT, "穿着", "man_outfit"),
                StoryField(MAN_STATE, "穿脱", "man_state")
            )
        ),
        StorySection(
            "场景",
            listOf(
                StoryField(WORLD, "题材", "world"),
                StoryField(TIME, "时间", "time"),
                // 场景先定，天气才知道该不该抽：室内没有天气，顺序反了会写出「阁楼里 + 湿冷」。
                StoryField(PLACE, "场景", "place"),
                StoryField(WEATHER, "天气", "weather"),
                StoryField(LIGHT, "光线", "light"),
                // 画风原来跟道具挤在一个叫「其它」的段里。道具撤掉后它没伴了，
                // 但它和题材一样是给整条定调的，并进这里比单开一段更贴。
                StoryField(STYLE, "画风", "style")
            )
        ),
        StorySection(
            "过程",
            listOf(
                StoryField(CHORE, "日常", "chore"),
                StoryField(ESCALATE, "开始", "escalate"),
                StoryField(MID, "过程中", "outfit_mid"),
                StoryField(POSE, "姿势", "pose"),
                StoryField(ACT, "动作", "act"),
                StoryField(FINISH, "结束", "act_finish"),
                StoryField(MOOD, "情绪", "mood"),
                StoryField(DETAIL, "细节", "detail"),
                StoryField(MARK, "痕迹", "mark")
            )
        )
    ).mapNotNull { section ->
        // 分项按当前词库实际有的池决定：SFW 没有动作链（开始/过程中/动作/结束），
        // 成人库没有光线。硬留着只会渲染成永远填不上的空行，看上去像词库坏了。
        val active = section.fields.filter {
            // 人数的候选项来自 library.casts，不在 pools 里 —— pool("cast") 恒为空。
            // 不豁免这一格，整个「人数」行会被当成空池滤掉，两档都没有人数可选。
            it.key == CAST || library.pool(it.pool).isNotEmpty()
        }
        if (active.isEmpty()) null else StorySection(section.label, active)
    }

    val fields: List<StoryField> = sections.flatMap { it.fields }

    /**
     * 「accent」两档装的东西不是一回事：常规档是耳环项链这类配饰，日常档全是语速、音色、
     * 笑起来带鼻音这类说话神态。格子的名字跟着内容走，不然日常档点开「配饰」会抽到
     * 「声音偏低」，用户以为是抽错了。
     */
    private val accentLabel: String
        get() = if (library.profile == "sfw") "说话神态" else "配饰"

    fun field(key: String): StoryField? = fields.firstOrNull { it.key == key }

    fun label(key: String, value: String): String = when {
        key == CAST -> library.casts[value]?.text ?: value
        else -> value
    }

    fun isCommon(key: String, value: String): Boolean =
        library.isCommon(field(key)?.pool.orEmpty(), value)

    fun emptyPicks(): Map<String, StoryPick> = fields.associate {
        // 情绪是「想用才用」的一格，默认留空，别每次重抽都往句末塞一句。
        it.key to if (it.key == MOOD || it.key == DETAIL || it.key == MARK) {
            StoryPick(ignored = true)
        } else StoryPick()
    }

    /**
     * 重抽。锁定的保持不动，忽略的留空，其余重新来一遍。
     *
     * 只重抽 [only] 里的分项（整段重抽用），为空就是全部。
     */
    /**
     * @param held 整段锁住的段里所有分项。它们连"值失效就清空"都不走，锁住就是这一段
     *   保持原样；单格重抽不传这个参数，所以点某一格照样生效。
     */
    fun randomize(
        picks: Map<String, StoryPick>,
        only: Set<String> = emptySet(),
        held: Set<String> = emptySet()
    ): Pair<Map<String, StoryPick>, List<String>> {
        val next = picks.toMutableMap()
        val notes = mutableListOf<String>()

        // 整组重抽时题材作为世界观底色最优先确定，避免在古代抽出现代男装后又在后置校验中打脸清空
        if (only.isEmpty()) {
            val worldField = field(WORLD)
            val worldPick = next[WORLD] ?: StoryPick()
            if (worldField != null && WORLD !in held && !worldPick.ignored && !worldPick.locked) {
                val worldOptions = allowed(WORLD, next, forPick = true)
                next[WORLD] = worldPick.copy(value = pickWeighted(WORLD, worldOptions, next))
            }
        }

        // 女二的衣服要按地点与天气挑，而她的字段在面板上排在场景之前。
        // 先一步把时间、地点、天气定下来，后面每一格才看得见这一轮的环境。
        // 抽过的这三格记下来，主循环里不再重抽——重抽等于把环境换掉，
        // 排在它前面的女二那身衣服就是照着上一份环境挑的，白挑。
        val sceneDrawn = mutableSetOf<String>()
        if (only.isEmpty()) {
            listOf(TIME, PLACE, WEATHER).forEach { key ->
                val scene = field(key) ?: return@forEach
                val p = next[key] ?: StoryPick()
                if (key in held || p.ignored || p.locked) return@forEach
                next[key] = p.copy(value = pickWeighted(key, allowed(key, next, forPick = true), next))
                sceneDrawn += key
            }
        }

        for (field in fields) {
            if (field.key in held) continue
            if (field.key in sceneDrawn) continue
            val pick = next[field.key] ?: StoryPick()
            // 独角戏里没有男人：那三格留空，免得后台抽一堆用不上的值。
            if (next[CAST]?.value == "solo" &&
                (field.key == MAN_LOOK || field.key == MAN_OUTFIT || field.key == MAN_STATE)
            ) {
                next[field.key] = pick.copy(value = "")
                continue
            }
            // 女二只在两女及以上档参与抽取，其他档位保持空。
            if (field.key.startsWith("w2_") && next[CAST]?.value !in setOf("mff", "ff")) {
                next[field.key] = pick.copy(value = "")
                continue
            }
            if ((field.key == AREOLA || field.key == NIPPLE) && !chestExposed(next)) {
                next[field.key] = pick.copy(value = "")
                continue
            }
            if ((field.key == "w2_areola" || field.key == "w2_nipple") && !chestExposed(next, W2)) {
                next[field.key] = pick.copy(value = "")
                continue
            }
            if (pick.ignored) {
                next[field.key] = pick.copy(value = "")
                continue
            }
            val inScope = only.isEmpty() || field.key in only
            if (pick.locked) {
                // 锁定的值也可能被上游改掉了（换了场景还在穿围裙），这里清掉并说明。
                // 动作链是用户一段一段点出来的，他说了算，不拿姿势去否它。
                // 姿势和动作都锁着时也照办：两边都是他亲手点的，谁也别否谁。
                if (field.key == ACT || (field.key == POSE && picks[ACT]?.locked == true)) continue
                if (pick.value.isNotEmpty() &&
                    !valueValid(field.key, pick.value, allowed(field.key, next, forPick = true))
                ) {
                    next[field.key] = pick.copy(value = "")
                    notes += "「${field.label}」和现在的设定对不上，已清空"
                }
                continue
            }
            if (!inScope && pick.value.isNotEmpty()) continue
            // 题材已在循环前先行抽定，避免重复抽取打乱随机流
            if (field.key == WORLD && only.isEmpty()) continue
            // 袜子经常不穿，给它留一条空着的路，不然每句都拖着一条丝袜。
            if ((field.key == SOCKS || field.key == "w2_socks") && random.nextInt(100) < SOCK_SKIP_PERCENT) {
                next[field.key] = pick.copy(value = "")
                continue
            }
            if (field.key == POSE) {
                next[field.key] = pick.copy(
                     value = pickChain(allowed(POSE, next, forPick = true))
                )
                continue
            }
            if (field.key == ACT) {
                next[field.key] = pick.copy(value = pairWithPoses(next))
                continue
            }
            val rawOptions = allowed(field.key, next, forPick = true)
            // 两个女人的衣服状态不能落在同一句上：「内裤被拉到膝盖」写两遍就是复读。
            val otherMid = when (field.key) {
                W2_MID -> next[MID]?.value.orEmpty()
                MID -> next[W2_MID]?.value.orEmpty()
                else -> ""
            }
            val options = if (otherMid.isEmpty()) rawOptions
            else rawOptions.filter { it != otherMid }.ifEmpty { rawOptions }
            next[field.key] = pick.copy(
                value = pickWeighted(field.key, options, next, redrawCast = only.isEmpty())
            )
        }
        // 只重抽了一组时，别的分项可能因为上游改变而失效（换了场景，姿势还要求台面）。
        // 捋一遍不够：换掉场景会让姿势失效，重抽姿势又让动作失效，常常两三层。
        // 所以反复捋，直到一遍下来没有任何改动为止；上限三遍，防两条词互相否个没完。
        var pass = 0
        var changed = true
        while (changed && pass < REPAIR_PASSES) {
            changed = false
            pass++
            for (field in fields) {
                if (field.key in held) continue
                val kept = next[field.key] ?: continue
                if (kept.ignored || kept.locked || kept.value.isEmpty()) continue
                if (field.key == ACT) continue
                if (valueValid(field.key, kept.value, allowed(field.key, next, forPick = true))) continue
                // 优先重抽一条符合当前题材/场景设定的值替换，绝不粗暴清空成未选并弹窗骚扰用户
                val replacement = pickWeighted(field.key, allowed(field.key, next, forPick = true), next)
                if (replacement.isNotEmpty()) {
                    next[field.key] = kept.copy(value = replacement)
                    changed = true
                    continue
                }
                next[field.key] = kept.copy(value = "")
                changed = true
                notes += "「${field.label}」和现在的设定对不上，已清空"
            }
        }
        return next to notes
    }

    /** 某个分项在这一刻能选哪些值。界面弹层和抽取共用同一份判断。 */
    fun allowed(key: String, picks: Map<String, StoryPick>, forPick: Boolean = false): List<String> {
        // 人数只看这份库真有的：导入的库少了某一种，界面照样列出来就会出现
        // 「选了却没有模板」的空正文。顺序仍按 CAST_ORDER，缺的跳过。
        if (key == CAST) return StoryLibrary.CAST_ORDER.filter {
            library.casts.containsKey(it) && library.templates[it].orEmpty().isNotEmpty()
        }.ifEmpty { StoryLibrary.CAST_ORDER }
        val field = field(key) ?: return emptyList()
        // 只拦自动抽取：穿着整身衣服时不要自动写乳晕和乳头。
        // 面板（forPick）必须给全量候选，否则点开两格是空的，和别的字段不一样。
        if (!forPick && (key == AREOLA || key == NIPPLE) && !chestExposed(picks)) return emptyList()
        if (!forPick && (key == "w2_areola" || key == "w2_nipple") && !chestExposed(picks, W2)) return emptyList()
        val words = if (forPick) library.pickablePool(field.pool) else library.pool(field.pool)
        if (words.isEmpty()) return emptyList()

        val scene = sceneBefore(key, picks)
        val cast = picks[CAST]?.value.orEmpty().ifEmpty { DEFAULT_CAST }
        val place = picks[PLACE]?.value.orEmpty()
        val group = placeGroup(place)
        val activePrefix = if (key.startsWith(W2)) W2 else ""
        val outfit = pickValue(picks, activePrefix, OUTFIT)
        val worldGroups = library.word("world", picks[WORLD]?.value.orEmpty())?.groups.orEmpty()
        // 年代：题材说这条是古代，电梯和超短裙就不该再出现。
        val eras: Set<String> = library.word("world", picks[WORLD]?.value.orEmpty())?.eras.orEmpty()
        // 主戏被锁成一条链时，体位要迁就它，而不是反过来把链接冲掉。
        val lockedActKeys: Set<String>? = picks[ACT]?.takeIf { it.locked }?.value
            ?.split(SEP)?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { acts ->
                val words = acts.mapNotNull { library.word("act", it) }
                if (words.any { it.poses.isEmpty() }) null
                else words.flatMap { it.poses }.toSet()
            }
        val poseKeys: Set<String> = picks[POSE]?.value.orEmpty().split(SEP)
            .mapNotNull { library.word("pose", it)?.key }
            .filter { it.isNotEmpty() }
            .toSet()

        // 现代/赛博世界里的衣服和种族不按年代卡：汉服、旗袍、JK、魅魔、精灵都是角色扮演的常规道具。
        val looseEra = ("modern" in eras || "cyber" in eras)
        fun eraOk(word: StoryWord): Boolean =
            key == WORLD || (looseEra && key in LOOSE_ERA_KEYS) ||
                word.allowsEra(eras)

        fun passes(word: StoryWord, strict: Boolean): Boolean {
            if (clashesWithMutex(word.text, picks)) return false
            if (!word.allowsCast(cast)) return false
            // 题材和年代本身就是定调的，不该被年代反过来筛。
            if (!eraOk(word)) return false
            // 场景和题材本身就是决定场地的，不该被场地反过来筛。
            if (key != PLACE && key != WORLD && !word.allowsGroup(group)) return false
            // 雨天雪天这类词会把它不待见的组整个封掉（户外）。
            if (word.groups.any { it in scene.blocked }) return false
            if (word.avoid.isNotEmpty() && word.avoid.split("|").any { outfit.contains(it) }) return false
            // 69、乳交、足交这类姿势里根本没在插，只有写明认领它的动作才能配。
            // 这条必须排在「非严格模式直接放行」之前：兜底一旦绕过它，姿势选了乳交、
            // 动作却配成普通抽插，唯一匹配的那条动作被互斥筛掉时尤其明显。
            if (key == ACT && poseKeys.any { it in SPECIAL_POSES } &&
                word.poses.none { it in poseKeys }
            ) return false
            if (!strict) return true
            if (!scene.present.containsAll(word.requires)) return false
            if (key == MAN_STATE) {
                val manOutfit = picks[MAN_OUTFIT]?.value.orEmpty()
                if (manOutfit == "全裸" && word.text.contains("脱光")) return false
                if (word.requiresWorn.isNotEmpty()) {
                    if (manOutfit.isEmpty() || manOutfit == "全裸") return false
                    // 「*」表示身上有一件能脱的衣服；只披浴巾、只穿内裤时没有皮带可解。
                    if (word.requiresWorn.contains("*") && manOutfit in MAN_BARE_OUTFITS) return false
                    if (!word.requiresWorn.contains("*") && !word.requiresWorn.contains(manOutfit)) return false
                }
            } else {
                if (word.requiresWorn.isNotEmpty() && !wearsAny(word.requiresWorn, scene.worn)) return false
            }
            if (key == ACT && poseKeys.isNotEmpty() && word.poses.isNotEmpty() &&
                word.poses.none { it in poseKeys }
            ) return false
            // 内搭是「补一句」，不能把身上已经穿着的说成没穿。
            if ((key == SOCKS || key == "w2_socks") && word.removes.any { it in scene.worn }) return false
            // 「只穿围裙」这类排他表述，腿上什么都不许再加。
            // 这里不能只比 GARMENT_INNER：半截蕾丝袜、罗袜的 token 不在那张表里，
            // 会在「只围了一条围裙」的句子后面挂出一双袜子。凡是带 wears 的袜都算。
            if ((key == SOCKS || key == "w2_socks") && word.wears.isNotEmpty() &&
                library.word("outfit", outfit)?.exclusive == true
            ) return false
            // 全裸开场时脚上也不能穿鞋（只能赤脚），但「只穿围裙」依然可以配高跟鞋。
            if ((key == SHOES || key == "w2_shoes") && word.wears.isNotEmpty() &&
                outfit.contains("全裸")
            ) return false
            // 夜景类画风只在夜里出现。
            if (key == STYLE && word.times.isNotEmpty() && picks[TIME]?.value !in word.times) return false
            if (key == POSE && lockedActKeys != null && word.key !in lockedActKeys) return false
            // 校园题材只出校园及周边场景。
            if (key == PLACE && worldGroups.isNotEmpty() &&
                word.groups.none { it in worldGroups }
            ) return false
            // 男主开场选了全裸，穿脱状态绝不能抽带有 requiresWorn 的脱衣服词（解皮带、上衣脱一半等）。
            if (key == MAN_STATE && word.requiresWorn.isNotEmpty() &&
                picks[MAN_OUTFIT]?.value.orEmpty() == "全裸"
            ) return false
            return true
        }

        val strict = words.filter { passes(it, strict = true) }.map { it.text }
        if (strict.isNotEmpty()) return strict
        // 天气没有可选项就留空：兜底会把整池捞回来，室内又冒出「阁楼里 + 湿冷」。
        if (key == WEATHER) return emptyList()
        // 「状态」「男主穿脱」宁可留空也不要硬凑：身上没外衣时写「外套敞开着」就是错的。
        if (key == STATE || key == "w2_state" || key == MAN_STATE) return emptyList()
        // 物件对不上时放宽一层：宁可场景不完全贴合，也不留空。
        // 但人数和天气这两条不能放宽——单人句子里出现「让她转过身」是硬错。
        val loose = words.filter { passes(it, strict = false) }.map { it.text }
        if (loose.isNotEmpty()) return loose
        // 最后这层只放宽「物件在不在场」，硬约束一条都不能破：互斥、避讳、道具依赖
        // 放进兜底，会出现「姿势选了乳交、动作却配普通抽插」这类自相矛盾的句子。
        return words.filter {
            it.allowsCast(cast) && eraOk(it) && it.allowsGroup(group) &&
                it.groups.none { g -> g in scene.blocked } &&
                !clashesWithMutex(it.text, picks) &&
                (it.avoid.isEmpty() || it.avoid.split("|").none { token -> token in outfit })
        }.map { it.text }
    }

    /** 人在户外时，天气词前面那个「外面」是多余的。 */
    private fun isOutdoor(picks: Map<String, StoryPick>): Boolean {
        val place = library.word(PLACE, picks[PLACE]?.value.orEmpty()) ?: return false
        return place.groups.any { it == "outdoor" || it == "alley" }
    }

    /**
     * 把已经选好的值从头捋一遍，得到「现在场里有什么、身上穿着什么」。
     *
     * [until] 是当前要判断的分项，它自己的值不算进去——不然「穿着」会拿自己的结果判断自己。
     * 女二那几个分项只看她自己身上那身：问她「内裤被拉到膝盖」成不成立，不能拿女一的内裤作答。
     */
    private fun sceneBefore(until: String, picks: Map<String, StoryPick>): Scene {
        val present = mutableSetOf<String>()
        val worn = mutableSetOf<String>()
        val blocked = mutableSetOf<String>()
        // 他身上的东西也算在场：穿西装才有领带可以解下来蒙眼。只进 present，不进 worn——
        // 进了 worn，她全裸开场、第三幕却会「身上只剩衬衫和西裤」。
        val manOutfit = library.word("man_outfit", picks[MAN_OUTFIT]?.value.orEmpty())
        if (manOutfit != null) present += manOutfit.wears
        val only = if (until.startsWith(W2)) W2 else ""
        for (field in fields) {
            if (field.key == until) break
            // 男主身上那三格记的是他的衬衫西裤。算女主「穿着什么」时把它们算进去，
            // 就会得到「她全裸」开场、第三幕却「身上只剩衬衫和西裤」。
            if (field.key == MAN_LOOK || field.key == MAN_OUTFIT || field.key == MAN_STATE) continue
            if (only.isNotEmpty() && !field.key.startsWith(only)) continue
            val value = picks[field.key]?.value.orEmpty()
            if (value.isEmpty()) continue
            val word = library.word(field.pool, value) ?: continue
            present += word.props
            present += word.wears
            worn += word.wears
            // 「只剩丝袜了」这类是全清再留几件，直接把 removes 里的名字减掉是不够的。
            if ("*" in word.removes) worn.retainAll(word.keep) else worn -= word.removes
            blocked += word.avoidGroups
        }
        return Scene(present, worn, blocked)
    }

    /** 「身穿」碰上自带动词的衣服会叠成「身穿只穿浴袍」「身穿全裸」「身穿下半身只裹着浴巾」。 */
    private fun wornClause(text: String): String =
        if (text.startsWith("穿") || text.startsWith("只") || text.startsWith("全") ||
            text.startsWith("浑身") || text.startsWith("身上") || text.startsWith("下半身") ||
            text.startsWith("上身") || text.startsWith("敞怀") || text.startsWith("披着")
        ) text else "身穿$text"

    private fun wearsAny(wanted: List<String>, worn: Set<String>): Boolean =
        if ("*" in wanted) worn.any { it !in PURE_INNER } else wanted.any { it in worn }
    /** 一串的字段（姿势、动作），锁着的值也得整串都还在候选里才算数。 */
    private fun valueValid(key: String, value: String, options: List<String>): Boolean =
        if (key == ACT || key == POSE) value.split(SEP).filter { it.isNotBlank() }.all { it in options }
        else value in options

    /** 抽一串，相邻两段不重复。一次通常换两三个体位，很少一个姿势做到底。 */
    private fun pickChain(options: List<String>, min: Int = 2, max: Int = 3): String {
        if (options.isEmpty()) return ""
        val count = minOf(min + random.nextInt(max - min + 1), options.distinct().size)
        val picked = mutableListOf<String>()
        var guard = 0
        while (picked.size < count && guard < 40) {
            guard++
            val candidate = pickWeighted(POSE, options)
            if (candidate.isNotEmpty() && candidate != picked.lastOrNull()) picked += candidate
        }
        return picked.joinToString(SEP)
    }

    /** 每个姿势配一段它自己认的动作。 */
    private fun pairWithPoses(next: MutableMap<String, StoryPick>): String {
        val poses = next[POSE]?.value.orEmpty().split(SEP).filter { it.isNotBlank() }
        val picked = mutableListOf<String>()
        for (pose in poses) {
            val scoped = next + (POSE to StoryPick(value = pose))
            val options = allowed(ACT, scoped, forPick = true)
            // 通用那几条只当连接词，不能顶掉这条人数专属的主戏。
            val specific = options.filter { library.word(ACT, it)?.common != true }
            val chosen = pickWeighted(ACT, specific.ifEmpty { options })
            if (chosen.isNotEmpty() && chosen != picked.lastOrNull()) picked += chosen
        }
        if (picked.isEmpty()) {
            // 兜底一层层退：先退掉姿势白名单，再退到整个动作池，宁可不太贴合也别空着。
            val noPose = next - POSE
            val chosen = pickWeighted(ACT, allowed(ACT, noPose, forPick = true))
                .ifEmpty { pickWeighted(ACT, allowed(ACT, next, forPick = true)) }
            if (chosen.isNotEmpty()) picked += chosen
        }
        return picked.joinToString(SEP)
    }
    /** 姿势和动作按顺序交错，段与段之间插一句过渡，最后接结束。 */

    /** 姿势和动作按组交错，组内用逗号，组间用分号，最后接结束。 */
    /** 互斥词对：谁先抽到都一样，双向判。 */
    private fun clashesWithMutex(word: String, picks: Map<String, StoryPick>): Boolean {
        if (library.mutex.isEmpty()) return false
        val chosen = picks.entries
            .filter { it.value.value.isNotEmpty() }
            .map { it.value.value }
        return library.mutex.any { (a, b) ->
            (word == a && b in chosen) || (word == b && a in chosen)
        }
    }

    /**
     * @param who 双女档里第一女主叫「女一」，动作词里写死的「她」要跟着换。
     *
     * 「过程中」不在这里拼。它交代的是主戏开始之后整段戏的固定衣着状态，模板里单独占一段，
     * 排在 {escalate} 之后、{acts} 之前；并进动作链会把一句状态塞进动作中间，读起来像插叙。
     */
    fun mergeChain(picks: Map<String, StoryPick>, who: String = "她"): String {
        val cast = picks[CAST]?.value.orEmpty()
        val poses = picks[POSE]?.value.orEmpty().split(SEP).filter { it.isNotBlank() }
        val acts = picks[ACT]?.value.orEmpty().split(SEP).filter { it.isNotBlank() }
        val finish = picks[FINISH]?.value?.takeIf { it.isNotBlank() }
        // 双女档：主戏在两个人之间轮着来，不能一个人包三组，另一个只站着看。
        val twoWomen = cast == "mff" || cast == "ff"
        // 按这一次的选择定，不用随机数：预览和正文各调一次合并函数，
        // 随机两次就会把同一个人说成女一又说成女二。
        val leadFirst = picks.entries.sortedBy { it.key }
            .joinToString { it.value.value }.hashCode() and 1 == 0
        val groups = mutableListOf<String>()
        for (index in 0 until maxOf(poses.size, acts.size)) {
            val parts = mutableListOf<String>()
            val subject = if (!twoWomen) who else if ((index % 2 == 0) == leadFirst) "女一" else "女二"
            val other = if (subject == "女一") "女二" else "女一"
            // 姿势词末尾的「（老汉推车）」是给选词面板认体位用的分类标签，念在故事里就穿帮了。
            val pose = reword(poses.getOrNull(index).orEmpty(), subject).replace(POSE_FAMILY_TAG, "")
            val act = acts.getOrNull(index).orEmpty()
            // 第二组起先来一句过渡，免得从躺着直接跳到骑乘。
            if (index > 0) {
                val bridge = BRIDGES[(index - 1) % BRIDGES.size]
                // 单人动作链里没有第二个人，过渡句不能写「把她翻过来」。
                parts += when {
                    cast == "solo" -> SOLO_BRIDGES[(index - 1) % SOLO_BRIDGES.size]
                    // 姿势自己已经写了「把女二按在镜子上」，再说一句「把女二换个姿势」就是重复。
                    pose.startsWith("把女") || pose.startsWith("将她") || pose.startsWith("把她") -> "然后"
                    else -> reword(bridge, subject)
                }
            }
            // 「顺势挺腰」只对插入类动作成立。口交、足交、夹着磨这类并进去就是病句，
            // 让它们用逗号直接接上。
            val penetrating = library.word("act", act)?.penetrating ?: true
            val actText = if (twoWomen) attributeAct(act, subject, other, cast) else act
            if (pose.isNotEmpty() && actText.isNotEmpty()) {
                // 「顺势挺腰」只能接在纯动词短语前。「把她的腿压到胸前」「一手按着她的小腹」
                // 本身就是完整分句，前面再塞一个动作就成了「顺势挺腰把她抱到台面上」。
                val bareVerb = penetrating && VERB_LEAD_NONE.none { actText.startsWith(it) }
                parts += if (bareVerb) "$pose，顺势挺腰$actText" else "$pose，$actText"
            } else if (pose.isNotEmpty()) {
                parts += pose
            } else if (actText.isNotEmpty()) {
                parts += actText
            }
            if (parts.isNotEmpty()) groups += dedupeAdjacent(parts).joinToString("，")
        }
        // 结束句写的是「射在她胸口」这类，指最后一段的主位。
        val lastIndex = maxOf(poses.size, acts.size) - 1
        val lastSubject = if (!twoWomen) who
        else if ((lastIndex % 2 == 0) == leadFirst) "女一" else "女二"
        finish?.let { groups += reword(it, lastSubject) }
        val joined = groups.joinToString("；")
        // 双女档每个人称已经各自落位，整段再换一遍会把女二说成女一。
        return if (twoWomen) joined else reword(joined, who)
    }

    /**
     * 主戏落到具体某个人身上。
     *
     * 动作池有两种写法：一种是「一人被插着，另一人趴在她身上舔她的阴蒂」，本来就写了两个人；
     * 另一种是单人口吻。前一种把人称换成女一女二，后一种补一句另一个人在做什么，
     * 免得第二个人整段只有外貌、没有动作。
     */
    private fun attributeAct(act: String, subject: String, other: String, cast: String = "mff"): String {
        if (act.isEmpty()) return act
        val lead = act.replace("她们", "两人").replace("她", subject)
        if ("另一人" in lead) {
            return lead.replace("另一人", other).replace("一人", subject)
        }
        if ("一人" in lead) return lead.replace("一人", subject)
        if (MULTI_PERSON_ACT_MARKERS.any { it in act }) {
            return if ("两人" in lead && subject !in lead && other !in lead) {
                lead.replaceFirst("两人", "${subject}和${other}")
            } else {
                lead
            }
        }
        val pool = if (cast == "ff") FF_PARTNER_HALVES else MFF_PARTNER_HALVES
        val partner = pool.randomOrNull(random) ?: return lead
        return "$lead，$other${partner.replace("她", subject)}"
    }

    /** 双女档把「她」换成「女一」；「她们」先换掉，免得变成「女一们」。 */
    private fun reword(text: String, who: String): String =
        if (who == "她") text else text.replace("她们", "两人").replace("她", who)

    /** 只去相邻重复，允许隔一段再回到同一个动作。 */
    private fun dedupeAdjacent(items: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (item in items) if (item != out.lastOrNull()) out += item
        return out
    }


    /** 兼容旧测试用的分词版。 */
    /** 动作链的预览片段。人称与正文一致，双女档同样换成「女一」。 */
    fun actChain(picks: Map<String, StoryPick>): List<String> {
        val cast = picks[CAST]?.value.orEmpty()
        val who = if (cast == "mff" || cast == "ff") "女一" else "她"
        // 组间是分号、组内是逗号，两样都要切开，否则预览会把两段拼成一句。
        return mergeChain(picks, who).split("，", "；").filter { it.isNotBlank() }
    }


    /**
     * 「过程中」后面接一句实算的收尾：脱了什么、还剩什么，按开场实际穿的算。
     * 手写「只剩丝袜」容易和开场的旗袍、长裙对不上，算出来的永远一致。
     */
    private fun midWithSummary(picks: Map<String, StoryPick>, key: String = MID): String {
        val text = picks[key]?.value.orEmpty()
        if (text.isEmpty()) return ""
        val word = library.word("outfit_mid", text) ?: return text
        if (word.removes.isEmpty()) return text
        // 词本身已经交代了结果，就别再接一句重复的。
        if (text.contains("只剩") || text.contains("赤裸") || text.contains("脱光")) return text
        // sceneBefore 按 key 的前缀分人：女二那句拿她自己那身算还剩什么。
        val before = sceneBefore(key, picks).worn
        if (before.isEmpty()) return text
        val after = if ("*" in word.removes) before.intersect(word.keep) else before - word.removes
        if (after.size >= before.size) return text
        if (after.isEmpty()) return "$text，身上什么都没穿"
        // 还剩一大半的时候不总结：只脱了胸罩却说「身上只剩西装、衬衫、裙子」是错的。
        if (after.size > 2) return text
        val ordered = word.keep.filter { it in after } + after.filter { it !in word.keep }.sorted()
        return "$text，身上只剩${joinCjk(ordered)}"
    }

    /**
     * 双女档里「过程中」要说清是谁的衣服，否则「浴衣下摆堆到腰上」不知道挂在谁身上。
     *
     * 这一格写的是女主一开场穿的那身（穿着那一格也只写她），所以跟着叫女一。
     * 词条分两种开头：名词打头（内裤被拉到膝盖）补「女一的」，状态打头（只剩丝袜、半裸着上身）
     * 直接接「女一」，补「的」会变成「女一的只剩丝袜」。
     */
    private fun midOwner(text: String, who: String): String {
        if (who.isEmpty() || text.isEmpty()) return text
        // 「她」后面不加「的」：「她的只剩丝袜了」「她的已经完全脱光」都读不通，
        // 「她内裤被拉到膝盖」才是人话。女一女二是称呼不是代词，照旧分名词／状态两种前缀。
        if (who == "她") return "$who$text"
        val stateLed = listOf("只剩", "全身", "半裸", "已经", "身上", "赤裸")
        return if (stateLed.any { text.startsWith(it) }) "$who$text" else "${who}的$text"
    }

    private fun joinCjk(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        2 -> "${items[0]}和${items[1]}"
        else -> items.dropLast(1).joinToString("、") + "和" + items.last()
    }

    private fun placeGroup(place: String): String =
        if (place.isEmpty()) "" else library.word("place", place)?.groups?.firstOrNull().orEmpty()

    private fun pickWeighted(
        key: String,
        options: List<String>,
        picks: Map<String, StoryPick> = emptyMap(),
        redrawCast: Boolean = false
    ): String {
        if (options.isEmpty()) return ""
        // 整段重抽时人数照常跟着变；单格重抽（含普通字段的连点）人数保持不动，
        // 否则每改一个词人数都会跳一次。
        if (key == CAST && !redrawCast) {
            val current = picks[CAST]?.value.orEmpty()
            if (current.isNotEmpty() && current in options) return current
            return DEFAULT_CAST.takeIf { it in options } ?: options.first()
        }
        val weights = options.map { weightOf(key, it, picks) }
        val total = weights.sum()
        var roll = random.nextInt(total)
        for (index in options.indices) {
            roll -= weights[index]
            if (roll < 0) return options[index]
        }
        return options.last()
    }

    /**
     * 随机抽取的偏好。衣服脱开场景之后，默认还是要「先给合身的」：
     * 贴场景的三倍、贴年代的两倍，不合拍的仍留在池子里，只是少抽到。
     * 手动点选完全不走这里，想穿什么穿什么。
     */
    private fun weightOf(key: String, text: String, picks: Map<String, StoryPick>): Int {
        var weight = if (isCommon(key, text)) COMMON_WEIGHT else 1
        if (key !in WEIGHTED_WEAR_KEYS) return weight
        val word = library.word(field(key)?.pool.orEmpty(), text) ?: return weight
        val group = placeGroup(picks[PLACE]?.value.orEmpty())
        if (group.isNotEmpty() && group in word.groups) weight *= SCENE_WEIGHT
        val eras = library.word("world", picks[WORLD]?.value.orEmpty())?.eras.orEmpty()
        if (eras.isNotEmpty() && word.eras.any { it in eras }) weight *= ERA_WEIGHT
        return weight
    }

    fun compose(rawPicks: Map<String, StoryPick>, detail: Boolean, dialogue: Boolean): String {
        // 池子里已经没有的词（删掉的、被排除的）不要拼进句子。
        val picks = rawPicks.mapValues { (key, pick) ->
            val pool = field(key)?.pool ?: return@mapValues pick
            if (pick.value.isEmpty() || !library.pools.containsKey(pool)) return@mapValues pick
            // 排除掉的词（眼睛关掉的）也要当成不在池子里：只判存在不判排除，
            // 会出现「列表里已经变灰、正文里还照念」的错位。
            val left = pick.value.split(SEP).filter {
                it.isNotBlank() && library.word(pool, it) != null && !library.isExcluded(pool, it)
            }
            pick.copy(value = left.joinToString(SEP))
        }
        val cast = picks[CAST]?.value.orEmpty().ifEmpty { DEFAULT_CAST }
        val story = library.casts[cast] ?: return ""
        val templates = library.templates[cast].orEmpty()
        if (templates.isEmpty()) return ""

        val place = picks[PLACE]?.value.orEmpty()
        val group = placeGroup(place)
        val template = templates[random.nextInt(templates.size)]
        val scene = sceneBefore("", picks)
        // 「状态」属于镜头一，按脱之前身上的东西判断，别被「过程中：已经脱光」带空。
        val stateScene = sceneBefore(STATE, picks)

        val worldGroups = library.word("world", picks[WORLD]?.value.orEmpty())?.groups.orEmpty()
        // 年代：题材说这条是古代，电梯和超短裙就不该再出现。
        val worldEras: Set<String> = library.word("world", picks[WORLD]?.value.orEmpty())?.eras.orEmpty()
         val look = listOfNotNull(
             picks[ETHNICITY]?.value?.takeIf { it.isNotEmpty() },
             picks[SKIN]?.value?.takeIf { it.isNotEmpty() },
             picks[BUILD]?.value?.takeIf { it.isNotEmpty() },
             picks[HAIR]?.value?.takeIf { it.isNotEmpty() },
            picks[BUST]?.value?.takeIf { it.isNotEmpty() },
            picks[ACCENT]?.value?.takeIf { it.isNotEmpty() }
        ).joinToString("、")

         // 袜和鞋跟在穿着后面，各带一个前导逗号：空着就什么都不留，不留半句。
         val outfitText = picks[OUTFIT]?.value.orEmpty()
         val socksText = picks[SOCKS]?.value.orEmpty().let { if (it.isEmpty()) "" else "，$it" }
         val shoesText = picks[SHOES]?.value.orEmpty().let { if (it.isEmpty()) "" else "，$it" }

          // 双女档第一女主叫「女一」：两个人都写作「她」时，读的人分不清谁被插。
          val twoWomen = cast == "mff" || cast == "ff"
          val leadWord = if (twoWomen) "女一" else "她"
          // 「过程中」：女一一句，两女档再补女二那身。两人各写各的，谁的衣服都不含糊。
          fun midClause(key: String, who: String): String {
              // 开场就全裸的人没有衣服可脱，写「衣物已完全除尽」等于让她当场又脱一遍；
              // 这一点和「状态」同一口径：身上什么都没穿时那一格留空。
              val prefix = if (key.startsWith(W2)) W2 else ""
              // 「全裸，身上还挂着水珠」这类同样开场就没衣服，写「衣物已完全除尽」等于让她再脱一遍。
              if (pickValue(picks, prefix, OUTFIT).startsWith("全裸")) return ""
              val mid = midWithSummary(picks, key)
              if (mid.isEmpty()) return ""
              val core = if (mid.contains("已经完全脱光")) "身上的衣物已完全除尽" else mid
              return midOwner(core, who)
          }
          // 他的穿脱也归这一段：入场时他还穿着整齐，到这时才被扯开。写在入场就会变成
          // 「两人刚进屋，他皮带已经解开」，时间线对不上。全裸的人没有可解开的衣物，同女方一样空着。
          val manState = picks[MAN_STATE]?.value.orEmpty().takeIf { s ->
              val word = library.word("man_state", s)
              !(picks[MAN_OUTFIT]?.value.orEmpty() == "全裸" &&
                  (word?.requiresWorn?.isNotEmpty() == true || s.contains("脱光")))
          }.orEmpty()
          // 单人档场上只有她一个，补主语是累赘；一男一女时场上有两个人，
          // 光写「内裤被拉到膝盖」得回头找主语，得补一个「她」。
          val midLead = when {
              twoWomen -> "女一"
              cast == "solo" -> ""
              else -> "她"
          }
          val midParts = mutableListOf<String>().apply {
              midClause(MID, midLead).takeIf { it.isNotEmpty() }?.let { add(it) }
              if (twoWomen) midClause(W2_MID, "女二").takeIf { it.isNotEmpty() }?.let { add(it) }
              // 两女档没有男人，这一句是直接拼进正文的，不像 {male} 那样由模板决定，
              // 所以必须自己确认台上真有他，否则 ff 档会忽然冒出一个「他」。
              if (manState.isNotEmpty() && story.male.isNotBlank()) add("他$manState")
          }
          // 过渡词只管时间，不写身体也不写衣服：她可能开局就全裸，单人档场上更没有第二个人，
          // 任何提到「两个人」「衣裳」的说法都会撞上这两种情况。后面必须带停顿，
          // 原来贴着下一句会读成「间内裤被拉到膝盖」。
          // 不写「拉扯」：模型会当成两人互相推拽，凭空多出一组手部动作，还可能画成抗拒。
          val midText = if (midParts.isEmpty()) "" else "不知不觉间，" + midParts.joinToString("；")
         val actsText = mergeChain(picks, leadWord).replace(SEP, "，")

        val values = mutableMapOf(
            "cast" to story.text,
            // 这一句只说他是谁、穿什么。他的穿脱已经挪到「片刻之后」那一段，
            // 入场就报「皮带已经解开」会把后面那整段戏提前说掉。
            "male" to run {
                val look = picks[MAN_LOOK]?.value.orEmpty()
                val outfit = picks[MAN_OUTFIT]?.value.orEmpty()
                if (look.isEmpty() && outfit.isEmpty()) {
                    ""
                } else {
                    val p1 = if (look.isNotEmpty()) {
                        when {
                            look == "西装革履" -> "他$look"
                            look in MAN_AURA_LOOKS -> "他是一名带着${look}的男子"
                            look.endsWith("男") -> "他是一名${look}子"
                            look.endsWith("哥") || look.endsWith("汉") ||
                                look.endsWith("大叔") || look.endsWith("少年") -> "他是一名$look"
                            else -> "他是一名${look}的男子"
                        }
                    } else "男方"
                    val p2 = when {
                        outfit == "全裸" -> "他浑身赤裸，未着寸缕"
                        outfit.isNotEmpty() -> "他" + wornClause(outfit)
                        else -> ""
                    }
                    if (p2.isNotEmpty()) "$p1。$p2。" else "$p1。"
                }
            },
            "time" to picks[TIME]?.value.orEmpty().let { if (it.isEmpty()) "" else "$it，" },
            "place" to place,
            "light" to picks[LIGHT]?.value.orEmpty(),
            "outfit" to outfitText,
            "socks" to socksText,
            "shoes" to shoesText,
             // 自己占一段，排在「开始」之后：它说的是后面整段戏的固定衣着状态，
             // 所以既不并进动作链，也不跟着动作链有没有内容。
             "outfit_mid" to midText,
             // 身上什么都没穿的时候「状态」没有意义，直接留空。
             "state" to picks[STATE]?.value.orEmpty().takeIf { stateApplies("", picks) }.orEmpty(),
             // 「此时正在回消息」是谁在回？补主语，双女档补「女一」。
             "chore" to picks[CHORE]?.value.orEmpty().let {
                 when {
                     it.isEmpty() -> ""
                     it.startsWith("此时") -> "$it，"
                     it.startsWith("正在") -> "此时${leadWord}$it，"
                     else -> "此时${leadWord}正在$it，"
                 }
             },
            "escalate" to picks[ESCALATE]?.value.orEmpty().let { raw ->
                if (raw.isEmpty()) ""
                else if (cast == "solo") "她$raw"
                else if (cast == "ff") {
                    val cleaned = raw.replace("她们", "两人").replace("她", "女一")
                    if (cleaned.startsWith("两人") || cleaned.startsWith("女")) cleaned
                    else "女二忽然上前$cleaned"
                } else "他忽然上前$raw"
            },
            // 姿势不再单独占位，和动作一起排进 {acts}
            // SFW 库没有 act 池，姿势不再并进动作链，必须自己占位；成人库仍是合并进 {acts}。
            "pose" to if (library.pool("act").isEmpty()) picks[POSE]?.value.orEmpty().replace(SEP, "，") else "",
            "mark" to picks[MARK]?.value.orEmpty().replace(SEP, "，").let { raw ->
                if (raw.isEmpty()) ""
                else if (raw.startsWith("她") || raw.startsWith("女") || raw.startsWith("身上")) raw
                else "${leadWord}身上留着$raw"
            },
            // SEP 是多选在内部的连接符，直接进句子会变成「重心落在后脚|双手插兜站着」。
             "acts" to actsText,
            "female" to formatFemaleParagraph(picks),
             "her" to formatHer(picks, who = leadWord),
             "wear" to formatWear(picks, who = leadWord),
            "her2" to formatHer(picks, "w2_", "女二"),
            "wear2" to formatWear(picks, "w2_", "女二"),
            "weather" to picks[WEATHER]?.value.orEmpty().let { raw ->
                when {
                    raw.isEmpty() -> ""
                    // 人已经站在沙滩上，「在沙滩上，外面下着雨」里的「外面」是多余的。
                    isOutdoor(picks) -> "，" + raw.removePrefix("外面")
                    else -> "，$raw"
                }
            },
            "look_f" to look.ifEmpty { library.pool("hair").randomOrNull(random)?.text.orEmpty() },
            "look_m" to ""
        )
        if ("{outfit_b}" in template) {
            // 第二个人穿的是短句池：条条自带动词（只穿着/披着），模板里不再补「身穿」。
            values["outfit_b"] = library.pool("outfit_b")
                .filter {
                    (worldEras.any { it == "modern" || it == "cyber" } || it.allowsEra(worldEras)) &&
                        it.allowsGroup(group) && scene.present.containsAll(it.requires)
                }
                .randomOrNull(random)?.text.orEmpty()
        }
        if ("{look_m}" in template) {
            values["look_m"] = library.pool("look_m").randomOrNull(random)?.text.orEmpty()
        }
        if ("{mood}" in template) {
            val rawMood = picks[MOOD]?.value.orEmpty()
            val moodWord = library.word("mood", rawMood)
            values["mood"] = rawMood.takeIf {
                it.isNotEmpty() && moodWord != null &&
                    moodWord.allowsGroup(group) &&
                    scene.present.containsAll(moodWord.requires)
            }.orEmpty()
        }

        var out = template.replace("{world}", "")
        // 动作链独立成句：主戏以句号收尾，事后痕迹与情绪另起一句，不再一逗到底。
        // 句号由这里补，不写死在模板里——动作链为空时模板会留下一个孤零零的句号，
        // 后面那半句就被当成空段整段丢掉，情绪也就跟着消失了。
        values["acts_dot"] = values["acts"].orEmpty()
            .let { if (it.isEmpty()) "" else "$it。" }
        // 道具分项已经撤掉，占位符只剩历史模板里还有。整句取道具的写法一并去掉，
        // 存档里的老模板才不会留下一截「顺手拿起，」。
        out = out.replace("{prop_tail}", "")
            .replace("顺手拿起{prop}，", "").replace("拿着{prop}，", "")
            .replace("顺手拿起，", "").replace("拿着，", "")
        // 痕迹没开的时候，模板里那一段整块去掉，免得留下双逗号或「。，」。
        if (values["mark"].isNullOrEmpty()) {
            out = out.replace("。{mark}，", "。").replace("，{mark}，", "，")
        }
        // 详情没开的时候同理，别留下「，。」收尾。
        if ("{detail}" in out && picks[DETAIL]?.value.isNullOrEmpty() && !detail) {
            out = out.replace("，{detail}，", "，").replace("，{detail}。", "。")
        }
        // 空占位连同它前面的连接词一起删，别留下「另一个身穿。」这种半句。
        if (values["outfit_b"].isNullOrEmpty()) out = out.replace("，另一个身穿{outfit_b}", "")
        if (values["chore"].isNullOrEmpty()) out = out.replace("正在{chore}", "")
        if (values["mood"].isNullOrEmpty()) {
            out = out.replace("，她们{mood}", "").replace("，她{mood}", "")
        }
        val order = listOf(
            "time", "cast", "her", "wear", "her2", "wear2", "weather",
            "female", "look_f", "look_m", "place_b", "place", "light",
            "outfit_b", "outfit", "socks", "shoes", "state", "outfit_mid", "chore", "male",
            "escalate", "pose", "prop", "acts", "acts_dot", "mark", "mood"
        )
        for (name in order) {
            val token = "{$name}"
            if (token in out) out = out.replace(token, values[name].orEmpty())
        }
        if ("{detail}" in out) {
            val pickedDetail = picks[DETAIL]?.value.orEmpty()
            // 手选的那句照念：开关管的是「要不要自动补一句」，不是推翻用户点出来的值。
            val detailText = if (pickedDetail.isNotEmpty()) {
                pickedDetail
            } else if (detail) {
                // 兜底抽词要走「可选池」：走原池会把用户眼睛关掉的词抽回来。
                library.pickablePool("detail").filter {
                    it.allowsCast(cast) &&
                        it.allowsGroup(group) &&
                        scene.present.containsAll(it.requires) &&
                        (it.needs.isEmpty() || out.contains(it.needs))
                }.randomOrNull(random)?.text.orEmpty()
            } else {
                ""
            }
            // 细节池里有不少句子以「她」开头。双女档不换人称，读者分不清说的是谁。
            out = out.replace("{detail}", reword(detailText, leadWord))
        }
         out = tidy(out)
         if ("女的" in out) {
             val femaleText = formatFemaleParagraph(picks)
             out = out.replace(Regex("女的[^。]*?。"), femaleText)
         }
         out = out.replace("。。", "。").replace("，，", "，").replace("；；", "；")
             .replace("。，", "。").replace("，。", "。")
         // 「女一」「女二」看着像场记板，但两个人都写成「她」时，读的人根本分不清
         // 谁被按在镜子上。区分清楚比措辞自然重要，保留这两个称呼。

         // 情绪改由模板收尾（她{mood} / 她们{mood}），两个人的场景才说得清是谁。

        val tail = mutableListOf<String>()
        picks[WORLD]?.value?.takeIf { it.isNotEmpty() }?.let { tail += "题材：$it" }
        picks[STYLE]?.value?.takeIf { it.isNotEmpty() }?.let { tail += "画风：$it" }
        // 只给下游一个「这条要说话」的标记，台词内容交给技能按分镜写。
        if (dialogue) tail += "有中文台词"
        // 空段整段不出：没穿、没男主、没女二时不留空行。
         out = out.split("\n").map { it.trim() }
             .filter { it.isNotBlank() && it != "。" && it != "，" }
             .joinToString("\n\n")
         val body = if (out.endsWith("。")) out else "$out。"
        return if (tail.isEmpty()) body else "$body${tail.joinToString("，")}。"
    }

    /** 空格位会留下「，，」这种，压掉；句首句末的逗号也清掉。 */
    private fun tidy(text: String): String {
        var out = text
        while ("，，" in out) out = out.replace("，，", "，")
        out = out.replace("。，", "。").replace("，。", "。").replace("  ", " ")
        out = out.trim()
        while (out.startsWith("，")) out = out.removePrefix("，").trim()
        while (out.endsWith("，")) out = out.removeSuffix("，").trim()
        return out
    }

    private data class Scene(
        val present: Set<String>,
        val worn: Set<String>,
        val blocked: Set<String>
    )

    /**
     * 老存档的分项迁移。
     *
     * 胸部从一个池拆成四格、发型拆出发色以后，旧存档里的 bust / hair 值原样留着，
     * 升级后这两行就是空的。按词面把旧值分派到新格，对得上就不丢。
     */
    fun migrate(picks: Map<String, StoryPick>): Map<String, StoryPick> {
        val out = picks.toMutableMap()
        val oldBust = out.remove(BUST)?.value.orEmpty()
        if (oldBust.isNotEmpty()) {
            val target = when {
                "乳晕" in oldBust -> AREOLA
                "乳头" in oldBust -> NIPPLE
                oldBust.endsWith("罩") || oldBust in setOf(
                    "平胸", "贫乳", "微乳", "小巧胸部", "中等胸部", "丰满胸部",
                    "巨乳", "爆乳", "超巨乳", "胸部很小", "胸部很丰满"
                ) -> BUST_SIZE
                else -> BUST_SHAPE
            }
            if (out[target]?.value.isNullOrEmpty()) out[target] = StoryPick(oldBust)
        }
        // 肛门那组并进动作以后，老存档里开着的值直接接到动作上。
        val oldAnal = out.remove("act_anal")?.value.orEmpty()
        if (oldAnal.isNotEmpty() && out[ACT]?.value.isNullOrEmpty()) {
            out[ACT] = StoryPick(oldAnal)
        }
        val oldHair = out[HAIR]?.value.orEmpty()
        if (oldHair.isNotEmpty() && out[HAIR_COLOR]?.value.isNullOrEmpty()) {
            val colors = listOf(
                "酒红色", "银灰色", "亚麻色", "奶茶色", "铂金色", "深棕色", "浅棕色",
                "黑色", "白色", "金色", "银色", "灰色", "红色", "棕色"
            )
            val color = colors.firstOrNull { oldHair.contains(it) }
            if (color != null) {
                out[HAIR_COLOR] = StoryPick(color)
                val rest = oldHair.replace(color, "").trim()
                if (rest.isNotEmpty()) out[HAIR] = (out[HAIR] ?: StoryPick()).copy(value = rest)
            }
        }
        return out
    }

    /**
     * 胸口有没有露出来。
     *
     * 判据只看穿着那一句：全裸、上身赤裸、只穿内衣、衣襟敞开、衣裳半褪这类算露。
     * 遮住时两格不自动抽，但面板里照样能选。
     */
    private fun chestExposed(picks: Map<String, StoryPick>, prefix: String = ""): Boolean {
        val outfit = pickValue(picks, prefix, OUTFIT)
        if (outfit.isEmpty()) return true
        val bare = listOf("全裸", "赤裸", "内衣", "没穿", "敞开", "半褪", "只围", "只剩")
        return bare.any { it in outfit }
    }

    private fun pickValue(picks: Map<String, StoryPick>, prefix: String, key: String): String =
        picks[prefix + key]?.value.orEmpty()

    /**
     * 「外套搭在手臂上」「袖口卷起」只有在身上穿着衣服时才成立。
     *
     * 成人库的衣服词条都标了 wears，空着就是真的没穿；日常库的衣服是一条纯文本、
     * 一件也没标 wears，按老判据会把日常档的穿着状态整格丢掉。
     */
    private fun stateApplies(prefix: String, picks: Map<String, StoryPick>): Boolean {
        val outfit = pickValue(picks, prefix, OUTFIT)
        if (outfit.isEmpty()) return false
        // 有可数的衣服就以它为准：「披着狐裘大氅，里面全裸」也算穿了大氅。
        if (sceneBefore(prefix + STATE, picks).worn.isNotEmpty()) return true
        val word = library.word("outfit", outfit) ?: return false
        // 标了 wears 却一件都没穿，说明身上是空的。
        if (word.wears.isNotEmpty()) return false
        return listOf("全裸", "赤裸", "一丝不挂", "未着寸缕").none { outfit.contains(it) }
    }

    /** 人物句：「她是一名二十五岁、气质冷艳的韩国女子，……」 */
    private fun formatHer(
        picks: Map<String, StoryPick>,
        prefix: String = "",
        who: String = "她"
    ): String {
        val ethnicity = pickValue(picks, prefix, ETHNICITY)
        val region = pickValue(picks, prefix, REGION).removeSuffix("面孔")
        val age = pickValue(picks, prefix, AGE)
        val look = pickValue(picks, prefix, LOOK)
        val skin = pickValue(picks, prefix, SKIN)
        val build = pickValue(picks, prefix, BUILD)
        val hair = pickValue(picks, prefix, HAIR)
        val hairColor = pickValue(picks, prefix, HAIR_COLOR)
        val bustSize = pickValue(picks, prefix, BUST_SIZE).ifEmpty { pickValue(picks, prefix, BUST) }
        val bustShape = pickValue(picks, prefix, BUST_SHAPE)
        val areola = pickValue(picks, prefix, AREOLA)
        val nipple = pickValue(picks, prefix, NIPPLE)
        val accent = pickValue(picks, prefix, ACCENT)
        val glasses = pickValue(picks, prefix, GLASSES)

        if (prefix.isNotEmpty() &&
            listOf(ethnicity, region, age, look, skin, build, hair, hairColor, bustSize, bustShape, areola, nipple, accent, glasses)
                .all { it.isEmpty() }
        ) return ""

        var race = ethnicity.ifEmpty { "女子" }
        val femaleRaces = setOf(
            "魅魔", "海妖", "狐妖", "猫妖", "狼女", "蛇女", "兔耳娘", "雪女", "花妖", "龙女",
            "牛娘", "鹿娘", "羊娘", "虎娘", "蛛娘", "精灵", "暗精灵", "半精灵", "妖精", "花精",
            "吸血鬼", "天使", "堕天使", "女武神", "美人鱼"
        )
        if (race in femaleRaces && !race.contains("女")) race = "女$race"
        else if (race in setOf("人类", "欧美人", "东亚人", "混血长相")) race = "女子"

        val raceWord = when {
            region.isEmpty() -> race
            race == "女子" -> "${region}女子"
            else -> "${region}${race}"
        }

        val body = if (build.isNotEmpty()) {
            val partWords = listOf("肩膀", "脖子", "腿", "腰", "臀", "骨架", "身形", "曲线", "罩杯", "肉感", "个子", "腹肌")
            when {
                build.contains("肩膀") -> "身量纤细、$build"
                build.startsWith("身材") || build.startsWith("个子") || build.startsWith("身量") ||
                    build.startsWith("体态") || build.endsWith("身材") ||
                    partWords.any { build.contains(it) } -> build
                else -> "身材$build"
            }
        } else ""

        val selfContainedLook = setOf(
            "熟女感", "少妇感", "纯欲风", "反差萌", "贵族气质", "狐狸精",
            "风情万种", "风情", "媚态", "风骚", "欲女"
        )
        val lookPart = when {
            look.isEmpty() -> ""
            look.startsWith("气质") || look in selfContainedLook -> look
            else -> "气质$look"
        }
        val prefixText = listOf(age, lookPart).filter { it.isNotEmpty() }.joinToString("、")
        val subject = when {
            prefixText.isNotEmpty() && body.isNotEmpty() -> "$prefixText、${body}的$raceWord"
            prefixText.isNotEmpty() -> "${prefixText}的$raceWord"
            body.isNotEmpty() -> "${body}的$raceWord"
            else -> raceWord
        }

        val details = mutableListOf<String>()
        if (skin.isNotEmpty()) {
            details += when {
                "水珠" in skin || "汗光" in skin || "红晕" in skin || "淡红" in skin -> skin
                "皮" in skin || "肤" in skin -> skin
                skin.startsWith("带") || skin.startsWith("有") -> "${skin}的肌肤"
                else -> "${skin}肌肤"
            }
        }
        val bustBits = mutableListOf<String>()
        if (bustSize.isNotEmpty()) bustBits += if (bustSize.endsWith("罩")) "${bustSize}杯" else bustSize
        if (bustShape.isNotEmpty()) {
            bustBits += when {
                "胸" in bustShape || "乳" in bustShape -> bustShape
                bustShape == "晃动" -> "胸口随动作微晃"
                bustShape in BUST_CLAUSE_SHAPES -> bustShape
                else -> "胸型$bustShape"
            }
        }
        if (areola.isNotEmpty()) bustBits += areola
        if (nipple.isNotEmpty()) {
            bustBits += if ("乳头" in nipple || "乳晕" in nipple) nipple else "乳头$nipple"
        }
        if (bustBits.isNotEmpty()) details += bustBits.joinToString("、")

        val hairIsClause = hair in HAIR_CLAUSE_WORDS || hair.endsWith("的头发")
        val hairText = when {
            hair.isEmpty() && hairColor.isEmpty() -> ""
            hairColor.isEmpty() -> hair
            hair.isEmpty() -> "${hairColor}头发"
            // 「扎着马尾」这类是动词短语，颜色当前缀会成「紫红色扎着马尾」。
            hair.startsWith("扎") || hair.startsWith("盘") || hair.startsWith("编") ||
                hair.startsWith("剪") -> "$hair，一头${hairColor}头发"
            hairIsClause -> "一头${hairColor}头发，$hair"
            else -> "$hairColor$hair"
        }
        if (hairText.isNotEmpty()) {
            // 「盘发，一头冷棕色头发」自己已经带了量词和动作，再加「留着一头」就是两个一头。
            details += if (hairIsClause || hairText.startsWith("留") || hairText.startsWith("扎") ||
                hairText.startsWith("散") || hairText.startsWith("一头") ||
                hairText.startsWith("盘") || hairText.startsWith("编") || hairText.startsWith("剪")
            ) hairText else "留着一头$hairText"
        }

        // 眼镜和配饰合成一句「戴着眼镜和耳环」；带动作的状态句（摘下眼镜叼在嘴边）或 SFW 说话神态单独写。
        fun isState(text: String): Boolean =
            library.profile == "sfw" ||
                text.startsWith("没戴") || text.startsWith("摘下") ||
                text.startsWith("眼镜") || text.startsWith("墨镜") ||
                text.startsWith("一边") || text.startsWith("镜片") ||
                text.startsWith("有") || text.startsWith("耳后") ||
                text.startsWith("嘴唇") || text.startsWith("指甲") ||
                text.startsWith("锁骨") || text.startsWith("舌") ||
                ACCENT_HABIT_KEYWORDS.any { it in text }

        fun stem(text: String): String =
            text.removePrefix("戴着").removePrefix("戴").removePrefix("有")

        val g = glasses.removePrefix("戴着")
        val a = accent.removePrefix("戴着")
        val plainGlasses = g.isNotEmpty() && !isState(g)
        val plainAccent = a.isNotEmpty() && !isState(a) && a != "锁骨很明显"
        val worn = mutableListOf<String>()
        if (plainGlasses) worn += stem(g)
        if (plainAccent) worn += stem(a)
        if (worn.isNotEmpty()) {
            details += if (worn.size == 1) "戴着${worn[0]}" else "戴着${worn[0]}和${worn[1]}"
        }
        if (glasses.isNotEmpty() && !plainGlasses) details += glasses
        if (accent.isNotEmpty() && !plainAccent) details += accent

        return "${who}是一名$subject" +
            (if (details.isNotEmpty()) "，" + details.joinToString("，") else "") + "。"
    }

    /** 穿着句：「她穿着空姐制服，罗袜，绑带高跟」。 */
    private fun formatWear(
        picks: Map<String, StoryPick>,
        prefix: String = "",
        who: String = "她"
    ): String {
        val outfit = pickValue(picks, prefix, OUTFIT)
        val socks = pickValue(picks, prefix, SOCKS)
        val shoes = pickValue(picks, prefix, SHOES)
        val state = pickValue(picks, prefix, STATE)
            .takeIf { stateApplies(prefix, picks) }
            .orEmpty()
        val clothes = mutableListOf<String>()
        if (outfit.isNotEmpty()) {
            if (outfit.startsWith("全裸")) {
                clothes += "浑身赤裸未着寸缕"
            } else {
                var c = wornClause(outfit)
                if (socks.isNotEmpty()) c += "，$socks"
                if (shoes.isNotEmpty()) c += "，$shoes"
                clothes += c
            }
        }
        if (state.isNotEmpty() && !outfit.startsWith("全裸")) clothes += state
        return if (clothes.isEmpty()) "" else "$who" + clothes.joinToString("，") + "。"
    }

    private fun formatFemaleParagraph(picks: Map<String, StoryPick>): String {
        val s1 = formatHer(picks)
        val s2 = formatWear(picks)
        val chore = picks[CHORE]?.value.orEmpty()
        val choreText = when {
            chore.isEmpty() || library.profile == "sfw" -> ""
            chore.startsWith("此时") -> "$chore。"
            chore.startsWith("正在") -> "此时她$chore。"
            else -> "此时她正在$chore。"
        }
        return listOf(s1, s2, choreText).filter { it.isNotEmpty() }.joinToString(" ")
    }

    companion object {
        const val CAST = "cast"
        const val HAIR = "hair"
        const val ETHNICITY = "ethnicity"
        const val SKIN = "skin"
        const val MAN_LOOK = "man_look"
        const val MAN_OUTFIT = "man_outfit"
        const val MAN_STATE = "man_state"
        const val BUILD = "build"
        const val BUST = "bust"
        const val REGION = "region"
        const val AGE = "age"
        const val LOOK = "look"
        const val HAIR_COLOR = "hair_color"
        const val BUST_SIZE = "bust_size"
        const val BUST_SHAPE = "bust_shape"
        const val AREOLA = "areola"
        const val NIPPLE = "nipple"
        const val WEATHER = "weather"

        /** 只披一件、身上没有可脱的正常衣物，`*` 这类通用穿脱词不该出现。 */
        private val MAN_BARE_OUTFITS = setOf(
            "全裸", "只穿浴袍", "下半身只裹着浴巾", "只穿深色平角内裤"
        )
        const val ACCENT = "accent"

        /** 眼镜 — 单独一格，便于锁成「一直戴着」或「这一格不要」。 */
        const val GLASSES = "glasses"
        const val TIME = "time"
        const val PLACE = "place"
        const val OUTFIT = "outfit"
        const val STATE = "state"
        const val SOCKS = "socks"
        const val SHOES = "shoes"
        const val CHORE = "chore"
        const val ESCALATE = "escalate"
        const val MID = "mid"

        /** 女二的分项统一带这个前缀，抽取与「看谁身上的衣服」都按它分人。 */
        const val W2 = "w2_"

        /** 女二的「过程中」：两人各有各的衣着状态，共用同一个池。 */
        const val W2_MID = "w2_mid"
        const val POSE = "pose"
        const val ACT = "act"

        /** 肛门那组，单列一个池，默认关着。 */

         /** 这些姿势里没有插入，动作必须写明认领它们才配得上。 */
         private val SPECIAL_POSES = setOf("69", "乳交", "足交", "腋交", "股间", "素股", "颜骑", "枕头")

         /** 姿势词尾的体位分类标签，例如「（老汉推车）」。面板要它，正文不要。 */
         private val POSE_FAMILY_TAG = Regex("（[^）]*）$")

         /**
          * 以这些字开头的动作已经是完整分句，前面接不上「顺势挺腰」。
          *
          * 「顺势挺腰把她的腿压到胸前」读不通：挺腰是一个动作，把腿压到胸前是另一个，
          * 中间靠「顺势」粘着，主语还是同一个人。这类直接用逗号并列。
          */
         private val VERB_LEAD_NONE = listOf(
             "把", "一手", "双手", "抓着", "按着", "压低", "边插", "在穴口", "贴在她", "让她",
             "从", "用", "整根插进", "后穴", "指尖", "手指先"
         )
        const val INTER = "inter"
        const val FINISH = "finish"
        const val MOOD = "mood"
        const val PROP = "prop"
        const val WORLD = "world"
        const val STYLE = "style"
        const val DETAIL = "detail"

        /** 痕迹 — 吻痕、指痕、抓痕；从 tag 库的 skin.mark 复制过来。 */
        const val MARK = "mark"
        const val LIGHT = "light"

        /** 多段之间用这个分隔，句子里换成逗号。挑一个中文文本里不会出现的字符。 */
        const val SEP = "|"

        private const val DEFAULT_CAST = "mf"
        private const val COMMON_WEIGHT = 2

        /** 段与段之间的过渡语，固定几句轮着用，避免每次都是同一句。 */
        /** 袜子空着的概率，百分之几。和「光着腿」叠加后大约三成无袜。 */
        private const val SOCK_SKIP_PERCENT = 25

        /** 贴场景的衣服抽中的概率，相对不贴的高三倍。 */
        private const val SCENE_WEIGHT = 3
        /** 贴年代的再乘两倍。 */
        private const val ERA_WEIGHT = 2

        /** 收尾校验最多捋几遍。下游往往依赖两层以上，一遍捋不干净；也不能无限捋。 */
        private const val REPAIR_PASSES = 3

        private val BRIDGES = listOf("把她换个姿势", "把她翻过来", "抱着她换个位置")
        /** 单人档的过渡：没有第二个人可翻。 */
        private val SOLO_BRIDGES = listOf("随后", "接着", "然后")

        /** 内搭里的贴身衣物，遇到「只穿」类穿着就不许再加。 */
        private val GARMENT_INNER = setOf("内衣", "内裤", "丝袜", "袜")

        /** 只算贴身衣物，穿着这些的时候谈不上「状态」。 */
        private val PURE_INNER = setOf("内衣", "内裤", "丝袜", "袜", "鞋", "高跟鞋")

        /** 现代/赛博题材下放宽年代校验的分项（女一与女二对齐）。 */
        private val LOOSE_ERA_KEYS = setOf(
            OUTFIT, SOCKS, SHOES, MAN_OUTFIT, ETHNICITY,
            "w2_outfit", "w2_socks", "w2_shoes", "w2_ethnicity"
        )

        /** 参与场景/年代偏好加权的穿着分项（女一与女二对齐）。 */
        private val WEIGHTED_WEAR_KEYS = setOf(
            OUTFIT, SOCKS, SHOES, "w2_outfit", "w2_socks", "w2_shoes"
        )

        /** 动作本身已写明多人配合的标志词，不再重复追加旁位半句。 */
        private val MULTI_PERSON_ACT_MARKERS = listOf(
            "两人", "轮流", "一左一右", "同时", "一起", "互相", "双头"
        )

        /** 一男两女档：单人口吻主戏时，另一位女性的配合动作（语法完整且契合三人场）。 */
        private val MFF_PARTNER_HALVES = listOf(
            "在一旁抚摸着她的乳房",
            "从侧面贴上来吻着她的脖颈",
            "跪在一旁含着他的阴茎根部配合吞吐",
            "趴在她身侧，手指探向两人的交合处抚弄",
            "在旁边张开双腿自己揉着阴蒂",
            "从背后搂住她，跟着节奏一起晃动"
        )

        /** 两女百合档：单人口吻主戏时，另一位女性的配合动作（绝不含男方/阴茎）。 */
        private val FF_PARTNER_HALVES = listOf(
            "在一旁轻揉着她的乳房",
            "贴在她身侧吻着她的耳垂与脖颈",
            "趴在她身上，用手指轻轻捻弄她的乳头",
            "从身后环住她的腰，隔着肌肤磨蹭",
            "跪在她身旁，手指探入她的腿间抽送",
            "俯身凑过去与她舌吻，手掌按在她小腹上"
        )

        /** 男主外形中属于气质/氛围名词、需补「带着…的男子」的词。 */
        private val MAN_AURA_LOOKS = setOf("少年感", "精英感", "混血感", "禁欲系", "体育生气质")

        /** 胸型格中自成描述短语、不前置「胸型」的词。 */
        private val BUST_CLAUSE_SHAPES = setOf(
            "从侧面能看见弧度", "用手挡住却没挡住", "深乳沟", "晃动"
        )

        /** 发型格中属于状态/动作短语、不能直接写成「留着一头＋发色＋该词」的词。 */
        private val HAIR_CLAUSE_WORDS = setOf(
            "刘海遮住眼睛", "内扣", "外翻", "短发利落", "长发及腰",
            "发梢微湿", "几缕碎发贴在脸颊", "碎发别在耳后"
        )

        /** 配饰/神态格中属于说话习惯或神态动作、不能前置「戴着」的关键词。 */
        private val ACCENT_HABIT_KEYWORDS = listOf(
            "说话", "尾音", "笑", "回答", "声音", "语气", "吐字", "开口",
            "呼吸", "眼神", "眨眼", "目光", "看人", "低头", "抿唇"
        )
    }
}
