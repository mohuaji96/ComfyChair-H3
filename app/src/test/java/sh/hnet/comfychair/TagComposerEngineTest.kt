package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagHiddenRule
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.model.TagSkipRule
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.storage.LibraryMerge
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick

/**
 * The word library can only express "at most one of these", so the interesting behaviour
 * lives in the extra checks: hand budget, posture and requires.
 */
class TagComposerEngineTest {

    private fun library(
        activity: List<String> = listOf("站着发呆", "低头看书", "抽烟吐烟", "躺在钢琴上")
    ) = TagLibrary(
        categories = listOf(
            TagCategory("outfitState", "穿脱", "", listOf(TagSlot("state", "穿脱", listOf("全裸", "穿着整齐")))),
            TagCategory(
                "top", "上衣", "of-color",
                listOf(TagSlot("type", "类型", listOf("衬衫", "卫衣")), TagSlot("color", "颜色", listOf("白色")))
            ),
            TagCategory("pose", "姿势", "", listOf(TagSlot("body", "姿势", listOf("站立", "趴在床上", "叉腰")))),
            TagCategory("activity", "动作", "", listOf(TagSlot("doing", "动作", activity))),
            TagCategory("scene", "场景", "", listOf(TagSlot("place", "地点", listOf("卧室")))),
        ),
        singletons = listOf("全裸"),
        skipRules = listOf(TagSkipRule(listOf("全裸"), listOf("top"))),
        mutex = listOf(listOf("衬衫", "叉腰")),
        constraints = TagConstraints(
            handCap = 2,
            handCost = mapOf("叉腰" to 2, "低头看书" to 2, "抽烟吐烟" to 1),
            posture = mapOf("站立" to "stand", "趴在床上" to "lie", "躺在钢琴上" to "lie"),
            requires = mapOf("解开衬衫到胸口" to listOf("衬衫"))
        )
    )

    private fun picks(vararg pairs: Pair<String, TagPick>): MutableMap<String, TagPick> =
        mutableMapOf(*pairs)

    @Test
    fun handBudgetRejectsTagsThatNeedMoreThanTwoHands() {
        val engine = TagComposerEngine(library(activity = listOf("站着发呆", "低头看书", "抽烟吐烟")))
        repeat(40) {
            val state = picks("pose.body" to TagPick("叉腰", locked = true))
            engine.randomize(state, "activity")
            // 叉腰 already takes both hands: neither the book (2) nor the cigarette (1) fits.
            assertEquals("站着发呆", state["activity.doing"]?.value)
        }
    }

    @Test
    fun postureGroupsDoNotMix() {
        val engine = TagComposerEngine(library(activity = listOf("躺在钢琴上", "站着发呆")))
        repeat(40) {
            val state = picks("pose.body" to TagPick("站立", locked = true))
            engine.randomize(state, "activity")
            assertEquals("站着发呆", state["activity.doing"]?.value)
        }
    }

    @Test
    fun mutexStopsTheSecondTagFromBeingPicked() {
        val engine = TagComposerEngine(library())
        repeat(40) {
            val state = picks("top.type" to TagPick("衬衫", locked = true))
            engine.randomize(state, "pose")
            // 衬衫 is locked and the mutex group forbids 叉腰 next to it.
            assertFalse(state["pose.body"]?.value == "叉腰")
        }
    }

    @Test
    fun requiresOnlyPicksWhenThePrerequisiteIsPresent() {
        val engine = TagComposerEngine(library(activity = listOf("解开衬衫到胸口")))

        val withShirt = picks("top.type" to TagPick("衬衫", locked = true))
        engine.randomize(withShirt, "activity")
        assertEquals("解开衬衫到胸口", withShirt["activity.doing"]?.value)

        val withHoodie = picks("top.type" to TagPick("卫衣", locked = true))
        engine.randomize(withHoodie, "activity")
        assertEquals("", withHoodie["activity.doing"]?.value.orEmpty())
    }

    @Test
    fun singletonStopsTheWholePhrase() {
        val engine = TagComposerEngine(library())
        val state = picks(
            "outfitState.state" to TagPick("全裸", locked = true),
            "top.type" to TagPick("衬衫", locked = true)
        )
        assertEquals("全裸", engine.compose(state))
    }

    @Test
    fun skipRuleBlanksTheCategoryAndRemovesItFromThePrompt() {
        val engine = TagComposerEngine(library())
        val state = picks(
            "outfitState.state" to TagPick("全裸", locked = true),
            "top.type" to TagPick("衬衫")
        )
        engine.randomize(state)
        assertTrue("top" in engine.skipped(state))
        assertEquals("", state["top.type"]?.value.orEmpty())
        assertFalse(engine.compose(state).contains("衬衫"))
    }

    @Test
    fun composeJoinsSlotsByTheirRule() {
        val engine = TagComposerEngine(library())
        val state = picks(
            "top.type" to TagPick("衬衫", locked = true),
            "top.color" to TagPick("白色", locked = true)
        )
        // of-color puts the colour in front and joins with 的
        assertEquals("白色的衬衫", engine.compose(state))
    }

    @Test
    fun soloCountNeverGetsATwoPersonAction() {
        val library = library(activity = listOf("站着发呆", "被公主抱")).let { base ->
            base.copy(
                categories = base.categories + TagCategory(
                    "people", "人数", "", listOf(TagSlot("count", "人数", listOf("单人女孩", "两个女孩")))
                ),
                constraints = base.constraints.copy(
                    people = mapOf("站着发呆" to 1, "被公主抱" to 2),
                    peopleCount = mapOf("单人女孩" to 1, "两个女孩" to 2)
                )
            )
        }
        val engine = TagComposerEngine(library)

        repeat(40) {
            val solo = picks("people.count" to TagPick("单人女孩", locked = true))
            engine.randomize(solo, "activity")
            assertEquals("站着发呆", solo["activity.doing"]?.value)
        }

        repeat(40) {
            val pair = picks("people.count" to TagPick("两个女孩", locked = true))
            engine.randomize(pair, "activity")
            // Group shot: the two-person action is preferred while it lasts.
            assertEquals("被公主抱", pair["activity.doing"]?.value)
        }
    }

    @Test
    fun repairClearsWhatTheNewCountNoLongerAllows() {
        val base = library()
        val library = base.copy(
            categories = base.categories +
                TagCategory("people", "人数", "", listOf(TagSlot("count", "人数", listOf("单人女孩", "两个女孩")))) +
                TagCategory("relation", "关系", "", listOf(TagSlot("kind", "关系", listOf("陌生人")))),
            constraints = base.constraints.copy(
                peopleCount = mapOf("单人女孩" to 1, "两个女孩" to 2),
                requires = base.constraints.requires + ("陌生人" to listOf("两个女孩"))
            )
        )
        val engine = TagComposerEngine(library)

        // 关系 was picked while the count was two; switching to solo must clear it.
        val state = picks(
            "people.count" to TagPick("单人女孩", locked = true),
            "relation.kind" to TagPick("陌生人")
        )
        engine.repair(state)
        assertEquals("", state["relation.kind"]?.value.orEmpty())
    }

    @Test
    fun excludesKeepsGazePairsApart() {
        val base = library()
        val library = base.copy(
            categories = base.categories +
                TagCategory("camera", "构图", "", listOf(TagSlot("shot", "构图", listOf("背面视角", "平视")))),
            constraints = base.constraints.copy(excludes = listOf(listOf("看镜头", "背面视角")))
        )
        val engine = TagComposerEngine(library)
        repeat(40) {
            val state = picks("expression.face" to TagPick("看镜头", locked = true))
            engine.randomize(state, "camera")
            assertEquals("平视", state["camera.shot"]?.value)
        }
    }

    @Test
    fun libraryRoundTripsThroughJson() {
        val original = library()
        val parsed = TagLibraryJson.parse(TagLibraryJson.write(original))
        assertEquals(original.categories.size, parsed.categories.size)
        assertEquals(original.tagCount, parsed.tagCount)
        assertEquals(original.constraints.handCap, parsed.constraints.handCap)
        assertEquals(original.constraints.handCost, parsed.constraints.handCost)
        assertEquals(original.constraints.posture, parsed.constraints.posture)
        assertEquals(original.skipRules, parsed.skipRules)
        assertEquals(original.mutex, parsed.mutex)
    }

    private fun layerLibrary() = TagLibrary(
        categories = listOf(
            TagCategory(
                "top", "上衣", "of-color",
                listOf(
                    TagSlot("color", "颜色", listOf("玫红")),
                    TagSlot("type", "款式", listOf("短款卫衣", "抹胸")),
                )
            ),
            TagCategory(
                "bra", "胸罩", "of-color",
                listOf(
                    TagSlot("color", "颜色", listOf("米色")),
                    TagSlot("type", "款式", listOf("交叉绑带")),
                )
            ),
            TagCategory(
                "bottom", "下衣", "of-color",
                listOf(
                    TagSlot("color", "颜色", listOf("红色")),
                    TagSlot("type", "款式", listOf("皮裙", "比基尼下装")),
                )
            ),
            TagCategory(
                "panties", "内裤", "of-color",
                listOf(
                    TagSlot("color", "颜色", listOf("浅粉")),
                    TagSlot("type", "款式", listOf("珍珠内裤")),
                )
            ),
            TagCategory(
                "outfitState", "穿脱", "",
                listOf(TagSlot("state", "穿脱", listOf("上身赤裸", "下身赤裸", "穿着整齐")))
            ),
            TagCategory(
                "garment", "衣物细节", "",
                listOf(TagSlot("detail", "细节", listOf("领口拉开", "普通细节")))
            )
        ),
        skipRules = listOf(
            TagSkipRule(listOf("上身赤裸"), listOf("top", "bra")),
            TagSkipRule(listOf("下身赤裸"), listOf("bottom", "panties"))
        ),
        hiddenRules = listOf(
            TagHiddenRule("bra", "top", listOf("抹胸")),
            TagHiddenRule("panties", "bottom", listOf("比基尼下装"))
        ),
        constraints = TagConstraints(
            requires = mapOf("领口拉开" to listOf("top:"))
        )
    )

    @Test
    fun `a covered bra is never drawn on top of a top`() {
        val engine = TagComposerEngine(layerLibrary())
        val state = picks("top.type" to TagPick("短款卫衣", locked = true), "top.color" to TagPick("玫红", locked = true))
        engine.randomize(state)
        assertTrue("上衣在，胸罩就不该抽：${state["bra.type"]?.value}", state["bra.type"]?.value.isNullOrEmpty())
    }

    @Test
    fun `the whitelist looks at the cut, not the colour`() {
        // 抹胸走的是「款式」那一格。锁住「穿着整齐」避免随机抽到「上身赤裸」冲掉胸罩。
        val engine = TagComposerEngine(layerLibrary())
        val state = picks(
            "outfitState.state" to TagPick("穿着整齐", locked = true),
            "top.type" to TagPick("抹胸", locked = true),
            "top.color" to TagPick("玫红", locked = true)
        )
        engine.randomize(state)
        assertEquals("交叉绑带", state["bra.type"]?.value)
    }

    @Test
    fun `a working copy without the layer rules still gets them`() {
        val stale = layerLibrary().copy(name = "旧副本", hiddenRules = emptyList())
        val shipped = layerLibrary().copy(name = "内置新版")
        val merged = LibraryMerge.fold(null, stale, shipped)
        assertTrue("合并后规则不能丢", merged.hiddenRules.isNotEmpty())
    }

    @Test
    fun `covered panties are never drawn when bottom is present`() {
        val engine = TagComposerEngine(layerLibrary())
        val state = picks("bottom.type" to TagPick("皮裙", locked = true), "bottom.color" to TagPick("红色", locked = true))
        engine.randomize(state)
        assertTrue("下衣遮挡时内裤不该抽：${state["panties.type"]?.value}", state["panties.type"]?.value.isNullOrEmpty())
        assertTrue("下衣遮挡时内裤必须在 skip 集合里", "panties" in engine.skipped(state))
    }

    @Test
    fun `panties whitelist allows draw for bikini bottom`() {
        // 锁住「穿着整齐」避免随机抽到「下身赤裸」冲掉内裤。
        val engine = TagComposerEngine(layerLibrary())
        val state = picks(
            "outfitState.state" to TagPick("穿着整齐", locked = true),
            "bottom.type" to TagPick("比基尼下装", locked = true),
            "bottom.color" to TagPick("红色", locked = true)
        )
        engine.randomize(state)
        assertEquals("珍珠内裤", state["panties.type"]?.value)
        assertFalse("白名单下衣时不应跳过内裤", "panties" in engine.skipped(state))
    }

    @Test
    fun `continuous randomization with state switch never resurrects covered bra or panties`() {
        val engine = TagComposerEngine(layerLibrary())
        val state = mutableMapOf<String, TagPick>()
        // 第一次：上身赤裸
        state["outfitState.state"] = TagPick("上身赤裸", locked = true)
        engine.randomize(state)
        assertTrue(state["bra.type"]?.value.isNullOrEmpty())

        // 第二次：解除赤裸，穿上短款卫衣和皮裙（普通衣服）
        state.clear()
        state["top.type"] = TagPick("短款卫衣", locked = true)
        state["bottom.type"] = TagPick("皮裙", locked = true)
        engine.randomize(state)
        // 收尾修补程序绝不能把它们补回来
        assertTrue("多次随机后胸罩绝不能被修补程序擅自回填", state["bra.type"]?.value.isNullOrEmpty())
        assertTrue("多次随机后内裤绝不能被修补程序擅自回填", state["panties.type"]?.value.isNullOrEmpty())
        assertTrue("bra 必须在 skipped 中", "bra" in engine.skipped(state))
        assertTrue("panties 必须在 skipped 中", "panties" in engine.skipped(state))
    }

    @Test
    fun `collar detail requires top and is blocked when topless`() {
        val engine = TagComposerEngine(layerLibrary())
        val state = picks("outfitState.state" to TagPick("上身赤裸", locked = true))
        engine.randomize(state, "garment")
        assertFalse("上身赤裸不能抽出领口：${state["garment.detail"]?.value}", state["garment.detail"]?.value == "领口拉开")
    }

    @Test
    fun `male naked state never triggers female clothes skip`() {
        // 加载真实词库
        val json = java.io.File("src/main/assets/tag_catalog.json").readText()
        val realLib = TagLibraryJson.parse(json)
        val engine = TagComposerEngine(realLib)
        val state = mutableMapOf<String, TagPick>(
            "outfitState.state" to TagPick("穿着整齐", locked = true),
            "top.type" to TagPick("衬衫", locked = true),
            "maleBody.outfit" to TagPick("身上什么也没穿", locked = true)
        )
        val skip = engine.skipped(state)
        assertFalse("男方全裸绝不能把女方上衣加入 skip", "top" in skip)
        // 衬衫属于常规上衣，女方胸罩被衬衫遮挡加入 skip 是正常的，但不能因男方全裸直接 skip top
    }

    @Test
    fun `overcoats and leather jackets always skip bra`() {
        val json = java.io.File("src/main/assets/tag_catalog.json").readText()
        val realLib = TagLibraryJson.parse(json)
        val engine = TagComposerEngine(realLib)
        listOf("皮夹克", "大衣", "风衣", "乳胶上衣").forEach { coat ->
            val state = mutableMapOf<String, TagPick>(
                "outfitState.state" to TagPick("穿着整齐", locked = true),
                "top.type" to TagPick(coat, locked = true)
            )
            val skip = engine.skipped(state)
            assertTrue("穿 $coat 时胸罩必须被遮挡并进入 skip", "bra" in skip)
            engine.randomize(state)
            assertTrue("穿 $coat 时绝不抽胸罩", state["bra.type"]?.value.isNullOrEmpty())
        }
    }

    @Test
    fun `fantasy race outputs natural language phrase and skips featureHead`() {
        val json = java.io.File("src/main/assets/tag_catalog.json").readText()
        val realLib = TagLibraryJson.parse(json)
        val engine = TagComposerEngine(realLib)

        // 1. 选魅魔时，featureHead 被自动跳过
        val succubusState = mutableMapOf<String, TagPick>(
            "race.kind" to TagPick("魅魔", locked = true)
        )
        val skip = engine.skipped(succubusState)
        assertTrue("选魅魔时 featureHead 必须被跳过", "featureHead" in skip)

        // 2. 选人类时，featureHead 正常保留供人类角色扮演饰品使用
        val humanState = mutableMapOf<String, TagPick>(
            "race.kind" to TagPick("人类", locked = true)
        )
        val humanSkip = engine.skipped(humanState)
        assertFalse("选人类时 featureHead 不应被跳过", "featureHead" in humanSkip)

        // 3. 生成提示词时，魅魔直接输出完整一句话特征
        val raceCat = realLib.categories.first { it.id == "race" }
        val text = engine.phrase(raceCat, mapOf("kind" to "魅魔"))
        assertTrue("魅魔必须输出完整短语：$text", text.contains("弯曲黑色恶魔角") && text.contains("桃心形尾巴"))
    }

    @Test
    fun `featureHead renamed to cosplay accessories and camera OTS requires two people`() {
        val json = java.io.File("src/main/assets/tag_catalog.json").readText()
        val realLib = TagLibraryJson.parse(json)
        val engine = TagComposerEngine(realLib)

        // 1. featureHead 分类 label 为「扮演配饰」
        val featureHead = realLib.categories.first { it.id == "featureHead" }
        assertEquals("扮演配饰", featureHead.label)

        // 2. 5 个废弃种族已清理
        val raceKind = realLib.slot("race", "kind")!!.pool
        listOf("女武神", "暗精灵", "半精灵", "龙人", "兽人").forEach {
            assertFalse("$it 应已被剔除", it in raceKind)
        }

        // 3. 历史反向强制依赖已切断（点击尖长耳不再强制篡改种族）
        assertFalse("尖长耳的反向 requires 应已被清理", realLib.constraints.requires.containsKey("尖长耳"))
        assertFalse("狐耳的反向 requires 应已被清理", realLib.constraints.requires.containsKey("狐耳"))

        // 4. 单人场景下过肩类镜头绝不抽取
        val soloState = mutableMapOf<String, TagPick>(
            "people.people" to TagPick("单人", locked = true)
        )
        val otsWords = listOf("越过肩膀看对方", "过肩镜头", "过肩看见解开的扣子")
        otsWords.forEach { word ->
            val needed = realLib.peopleNeeded("camera", word)
            assertEquals("过肩镜头 $word 必须需要至少 2 人", 2, needed)
        }

        // 5. 双重容颜消重：巴西 + 狐妖融合成「巴西面容，一对毛茸茸...」
        val raceCat = realLib.categories.first { it.id == "race" }
        val mergedText = engine.phrase(raceCat, mapOf("kind" to "狐妖", "ethnicity" to "巴西"))
        assertTrue("必须融合为巴西面容：$mergedText", mergedText.startsWith("巴西面容，"))
        assertFalse("绝不能出现人类容颜重复：$mergedText", mergedText.contains("人类容颜"))
    }

    @Test
    fun `solo people options tightened and breast size augmented without breaking existing words`() {
        val json = java.io.File("src/main/assets/tag_catalog.json").readText()
        val realLib = TagLibraryJson.parse(json)

        // 1. people.count 包含单人女孩与单人女性，不含单人
        val peoplePool = realLib.slot("people", "count")!!.pool
        assertTrue("应包含单人女孩", "单人女孩" in peoplePool)
        assertTrue("应包含单人女性", "单人女性" in peoplePool)
        assertFalse("不应再包含光秃秃的单人", "单人" in peoplePool)

        // 2. wordAliases 别名映射表中单人映射为单人女孩
        assertEquals("单人女孩", realLib.constraints.wordAliases["单人"])

        // 3. breasts.size 只留一套梯度，罩杯字母不再混在同一格里
        val breastSize = realLib.slot("breasts", "size")!!.pool
        assertEquals(8, breastSize.size)
        assertTrue("梯度保留到超巨乳", "超巨乳" in breastSize)
        assertTrue("梯度从贫乳起", "贫乳" in breastSize)
        assertFalse("罩杯字母已移出", breastSize.any { it.endsWith("罩杯") })
        assertFalse("非尺寸词已清掉", "娇小身材配巨乳" in breastSize)
        assertFalse("姿势词已清掉", "一手遮胸伸手够毛巾" in breastSize)
    }
}
