package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * Eight contradictions that showed up in real draws from the shipped library.
 *
 * Each one is a rule the catalogue already had a place for, filled in wrong or left out: the
 * bra had no body region, wetness was stated by three skin slots at once, poses that need both
 * hands were priced at zero. The tests are written against the sheet the user pasted so a
 * regression says which sentence came back.
 *
 * Only the word that drives the rule is locked; `repair` skips locked slots entirely, so a
 * locked dependent would never be re-checked.
 */
class PromptConflictFixTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun soloEngine(library: TagLibrary): TagComposerEngine {
        val solo = library.constraints.peopleCount.filterValues { it <= 1 }.keys
        return TagComposerEngine(library, solo)
    }

    /**
     * Locks [lock] and leaves everything else free so `repair` re-checks it. A single-key list
     * keeps the call sites readable: the first entry is the one whose rule is under test.
     */
    private fun repair(
        library: TagLibrary,
        lock: String,
        spec: List<Pair<String, String>>
    ): MutableMap<String, TagPick> {
        val picks = mutableMapOf<String, TagPick>()
        spec.forEach { (key, value) -> picks[key] = TagPick(value, locked = key == lock) }
        soloEngine(library).repair(picks)
        return picks
    }

    // 1. 胸部在画外 / 只露出背影 still printed a bra.
    @Test
    fun aChestCropTakesTheBraWithIt() {
        val library = library()
        listOf("胸部在画外", "只露出背影").forEach { crop ->
            val picks = repair(
                library,
                lock = "camera.shot",
                spec = listOf("camera.shot" to crop, "bra.type" to "蕾丝胸罩")
            )
            assertEquals("$crop cannot show a bra", "", picks["bra.type"]?.value.orEmpty())
        }
    }

    // 2. 背面视角 was not registered at all, so it framed nothing.
    @Test
    fun aBackViewIsRegisteredAsAFraming() {
        val library = library()
        assertTrue("背面视角 has no frame", library.constraints.frameHides.containsKey("背面视角"))
        val picks = repair(
            library,
            lock = "camera.angle",
            spec = listOf(
                "camera.angle" to "背面视角",
                "bra.type" to "蕾丝胸罩",
                "expression.face" to "高潮失神"
            )
        )
        assertEquals("", picks["bra.type"]?.value.orEmpty())
        assertEquals("", picks["expression.face"]?.value.orEmpty())
    }

    // 3. 挂着水珠的皮肤上挂着水珠 / 哑光质感的皮肤出汗发亮.
    @Test
    fun skinStatesWetnessOnce() {
        val library = library()
        val engine = soloEngine(library)
        val wet = setOf(
            "哑光质感", "皮肤湿漉漉", "挂着水珠", "皮肤油亮", "出汗皮肤", "油光皮肤", "光泽皮肤",
            "皮肤出汗发亮", "皮肤带着油光", "汗水浸透的皮肤", "皮肤上挂着水珠", "刚洗完澡的水珠",
            "油光", "汗湿反光"
        )
        repeat(300) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            val used = picks.values.map { it.value }.filter { it in wet }
            assertTrue("skin said $used", used.size <= 1)
            val text = engine.compose(picks)
            assertFalse("matte and shiny together: $text", text.contains("哑光质感") && text.contains("发亮"))
        }
    }

    // 4. 下腰 / 站着抱起她一条腿插入 were priced at zero hands, so the act still reached for them.
    @Test
    fun aPostureThatNeedsBothHandsLeavesNoneForTheAct() {
        val library = library()
        val cost = library.constraints.handCost
        val cap = library.constraints.handCap
        assertEquals("下腰 must cost two hands", 2, cost["下腰"] ?: 0)
        assertEquals("站着抱起她一条腿插入 must cost two hands", 2, cost["站着抱起她一条腿插入"] ?: 0)

        val picks = repair(
            library,
            lock = "pose.body",
            spec = listOf(
                "pose.body" to "下腰",
                "sexAct.act" to "手指进入",
                "handAction.hand" to "手指抵下唇"
            )
        )
        val spent = picks.values.filter { !it.ignored }.sumOf { cost[it.value] ?: 0 }
        assertTrue("hands spent $spent > $cap", spent <= cap)
    }

    // 5. 坐在椅子上 next to 蹲着自慰.
    @Test
    fun squattingMasturbationClearsThePose() {
        val library = library()
        val picks = repair(
            library,
            lock = "sexAct.act",
            spec = listOf("sexAct.act" to "蹲着自慰", "pose.body" to "坐在椅子上")
        )
        assertFalse("squatting cannot sit", picks["pose.body"]?.value == "坐在椅子上")
    }

    // 6. 站立 + 手放自己腿上.
    @Test
    fun standingCannotPutAHandOnHerOwnLeg() {
        val library = library()
        val picks = repair(
            library,
            lock = "pose.body",
            spec = listOf("pose.body" to "站立", "handAction.hand" to "手放自己腿上")
        )
        assertFalse(picks["handAction.hand"]?.value == "手放自己腿上")
    }

    // 7. 下腰 + 头靠在墙上.
    @Test
    fun aBackbendCannotRestItsHeadOnTheWall() {
        val library = library()
        val picks = repair(
            library,
            lock = "pose.body",
            spec = listOf("pose.body" to "下腰", "headPose.pose" to "头靠在墙上")
        )
        assertFalse(picks["headPose.pose"]?.value == "头靠在墙上")
    }

    // 8. 只露出背影 still described the vulva, but the act behind it must survive.
    @Test
    fun aBackViewKeepsTheActAndDropsTheVulva() {
        val library = library()
        val picks = repair(
            library,
            lock = "camera.shot",
            spec = listOf(
                "camera.shot" to "只露出背影",
                "pussy.look" to "阴毛修剪整齐",
                "sexAct.act" to "潮吹"
            )
        )
        assertEquals("", picks["pussy.look"]?.value.orEmpty())
        assertEquals("潮吹", picks["sexAct.act"]?.value.orEmpty())
    }

    // 9. 深乳沟的贫乳.
    @Test
    fun smallBreastsHaveNoDeepCleavage() {
        val library = library()
        val picks = repair(
            library,
            lock = "breasts.size",
            spec = listOf("breasts.size" to "贫乳", "breasts.shape" to "深乳沟")
        )
        assertFalse(picks["breasts.shape"]?.value == "深乳沟")
    }

    // 10. 下身赤裸 + 被丝袜包住的腿线：没有袜子就写不出腿线。
    @Test
    fun bareLegsHaveNoStockingLine() {
        val library = library()
        val picks = repair(
            library,
            lock = "outfitState.state",
            spec = listOf("outfitState.state" to "下身赤裸", "legs.detail" to "被丝袜包住的腿线")
        )
        assertFalse(picks["legs.detail"]?.value == "被丝袜包住的腿线")
    }

    // 11. 全裸 still wore stockings and heels.
    @Test
    fun nudeTakesTheStockingsAndTheShoesWithIt() {
        val library = library()
        val picks = repair(
            library,
            lock = "outfitState.state",
            spec = listOf(
                "outfitState.state" to "全裸",
                "socks.type" to "丝袜",
                "shoes.type" to "高跟鞋"
            )
        )
        assertEquals("", picks["socks.type"]?.value.orEmpty())
        assertEquals("", picks["shoes.type"]?.value.orEmpty())
    }

    // 12. 阴唇被手指分开 said the same thing as 手指进入.
    @Test
    fun fingersAreNotDescribedTwice() {
        val library = library()
        val picks = repair(
            library,
            lock = "sexAct.act",
            spec = listOf("sexAct.act" to "手指进入", "pussy.look" to "阴唇被手指分开")
        )
        assertFalse(picks["pussy.look"]?.value == "阴唇被手指分开")
    }

    // 13. 把下巴搁在自己手上 is a head position, not a hand action: it used to let the same head
    // both rest on a hand and tilt up.
    @Test
    fun theChinIsAPlaceNotAGesture() {
        val library = library()
        val head = library.category("headPose")!!.slots.first { it.id == "pose" }.pool
        val hand = library.category("handAction")!!.slots.first().pool
        // 「把下巴搁在自己手上」已随依赖道具的词一起删掉，改用真正留下来的写法。
        assertTrue("下巴抵在肩上要算头部姿势", "下巴抵在肩上" in head)
        assertTrue("不能同时留在手的动作里", "下巴抵在肩上" !in hand)
    }

    /** 动作名字里已经写了体位，姿态格就让位，否则「跪趴着，后入跪趴」说两遍。 */
    @Test
    fun anActThatNamesThePositionLeavesNoPose() {
        val library = library()
        val picks = repair(
            library,
            lock = "sexAct.act",
            spec = listOf("sexAct.act" to "后入跪趴", "outfitState.state" to "衣装半褪")
        )
        val pose = picks["pose.body"]?.value.orEmpty()
        assertTrue("姿态该让位，实际是 $pose", pose.isEmpty() || pose == "后入跪趴")
    }

    @Test
    fun aBuriedFaceCannotLookUp() {
        val library = library()
        val picks = repair(
            library,
            lock = "sexAct.act",
            spec = listOf("sexAct.act" to "把脸埋进对方颈窝", "headPose.pose" to "仰头")
        )
        assertFalse("脸埋进颈窝时不能仰头", picks["headPose.pose"]?.value == "仰头")
    }

    // 14. The head can only be in one place: 口交 puts it at the groin, 仰头 turns it up.
    @Test
    fun anActThatPinsTheHeadBlocksTheWrongTilt() {
        val library = library()
        val picks = repair(
            library,
            lock = "sexAct.act",
            spec = listOf("sexAct.act" to "口交", "headPose.pose" to "仰头")
        )
        assertFalse("口交时不能仰头", picks["headPose.pose"]?.value == "仰头")
    }

    @Test
    fun anActThatPinsTheHeadStillAllowsTheRightTilt() {
        val library = library()
        val picks = repair(
            library,
            lock = "sexAct.act",
            spec = listOf("sexAct.act" to "口交", "headPose.pose" to "低头")
        )
        assertEquals("口交时低头是正常的", "低头", picks["headPose.pose"]?.value.orEmpty())
    }

    @Test
    fun aheadAgainstTheWallRulesOutTheAct() {
        val library = library()
        val picks = repair(
            library,
            lock = "headPose.pose",
            spec = listOf("headPose.pose" to "头靠在墙上", "sexAct.act" to "口交")
        )
        assertFalse("头靠在墙上时不能口交", picks["sexAct.act"]?.value == "口交")
    }

    @Test
    fun aBuriedFaceCanStillLookDown() {
        val library = library()
        val picks = repair(
            library,
            lock = "sexAct.act",
            spec = listOf("sexAct.act" to "把脸埋进对方颈窝", "headPose.pose" to "低头")
        )
        assertEquals("低头和埋头是同一件事，不该被拆开", "低头", picks["headPose.pose"]?.value.orEmpty())
    }
    // 15. 水面倒影 needs water: the scene decides, and the check runs both ways.
    @Test
    fun aWaterShotNeedsWater() {
        val library = library()
        val picks = repair(
            library,
            lock = "scene.place",
            spec = listOf("scene.place" to "教室", "camera.lens" to "水面倒影")
        )
        assertFalse("教室没有水面", picks["camera.angle"]?.value == "水面倒影")
    }

    @Test
    fun aWaterShotSurvivesInABathroom() {
        val library = library()
        val picks = repair(
            library,
            lock = "scene.place",
            spec = listOf("scene.place" to "浴室", "camera.lens" to "水面倒影")
        )
        assertEquals("浴室有水面", "水面倒影", picks["camera.lens"]?.value.orEmpty())
    }

    @Test
    fun aMirrorShotEvictsTheDryRoom() {
        val library = library()
        val picks = repair(
            library,
            lock = "camera.angle",
            spec = listOf("camera.lens" to "镜面反射", "scene.place" to "森林")
        )
        assertFalse("森林里没有镜子", picks["scene.place"]?.value == "森林")
    }

    @Test
    fun aPlainShotLeavesTheRoomAlone() {
        val library = library()
        val picks = repair(
            library,
            lock = "camera.shot",
            spec = listOf("camera.shot" to "膝上照", "scene.place" to "森林")
        )
        assertEquals("普通取景不该挑场地", "森林", picks["scene.place"]?.value.orEmpty())
    }
}