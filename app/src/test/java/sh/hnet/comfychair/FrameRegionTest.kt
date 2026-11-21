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
 * A framing word says where the lens points; before this the content slots never asked.
 * 腰部以上特写 could carry 潮吹 and 下半身在画外 could carry 肛塞, because the crop slot and
 * the act slot were drawn independently.
 */
class FrameRegionTest {

    /**
     * groinFront is the vulva seen from the front. It exists so a back view can drop those
     * words without also dropping groin-level acts, which the back view can still show.
     */
    private val regions = setOf("head", "face", "chest", "waist", "groin", "groinFront", "legs", "feet")

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun soloEngine(library: TagLibrary): TagComposerEngine {
        val counting = library.constraints.peopleCount
        val solo = counting.filterValues { it <= 1 }.keys
        return TagComposerEngine(library, solo)
    }

    private fun regionsOf(library: TagLibrary, word: String, categoryId: String): List<String> =
        library.constraints.regions[word]
            ?: library.constraints.categoryRegions[categoryId].orEmpty()

    @Test
    fun everyDeclaredRegionIsKnown() {
        val library = library()
        val declared = library.constraints.frameHides.values.flatten().toSet() +
            library.constraints.regions.values.flatten().toSet() +
            library.constraints.categoryRegions.values.flatten().toSet()
        declared.forEach { region ->
            assertTrue("unknown region $region", region in regions)
        }
    }

    @Test
    fun framingWordsExistInTheCatalogue() {
        val library = library()
        val known = library.categories
            .flatMap { category -> category.slots.flatMap { it.pool } }
            .toSet()
        library.constraints.frameHides.keys.forEach { word ->
            assertTrue("$word is not in any pool", word in known)
        }
    }

    @Test
    fun aWaistUpCloseUpDropsTheVibrator() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "camera.crop" to TagPick("腰部以上特写", locked = true),
            "sexAct.act" to TagPick("使用按摩棒")
        )
        engine.repair(picks)
        assertFalse(
            "腰部以上特写 cannot carry 使用按摩棒",
            picks["sexAct.act"]?.value == "使用按摩棒"
        )
    }

    @Test
    fun belowTheWaistOutOfFrameDropsThePlug() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "camera.crop" to TagPick("下半身在画外", locked = true),
            "pussy.look" to TagPick("阴唇被手指分开")
        )
        engine.repair(picks)
        assertEquals("", picks["pussy.look"]?.value.orEmpty())
    }

    @Test
    fun aBackViewHasNoFaceToRead() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "camera.crop" to TagPick("只露出背影", locked = true),
            "expression.face" to TagPick("高潮失神"),
            // 妆容现在住在「形象」里，跟着脸一起被裁掉；同一类的年龄感、气质不受影响。
            "person.face" to TagPick("水光唇"),
            "person.age" to TagPick("二十八岁")
        )
        engine.repair(picks)
        assertEquals("", picks["expression.face"]?.value.orEmpty())
        assertEquals("", picks["person.face"]?.value.orEmpty())
        assertEquals("二十八岁", picks["person.age"]?.value.orEmpty())
    }

    @Test
    fun aDrawNeverPutsHiddenContentInFrame() {
        val library = library()
        val engine = soloEngine(library)
        val hides = library.constraints.frameHides
        val hidesCats = library.constraints.frameHidesCategories
        repeat(300) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            // A transient pick can still be filtered out at render time, so judge the sentence
            // the user sees, not the intermediate map. The old check reported phantoms.
            val text = engine.compose(picks)
            val framing = picks.filterValues { it.value in hides || it.value in hidesCats }
            val chosen = picks.entries.filter { it.value.value.isNotEmpty() && !it.value.ignored }
            framing.forEach { (frameKey, framePick) ->
                val cut = hides[framePick.value].orEmpty()
                val cats = hidesCats[framePick.value].orEmpty()
                chosen.forEach { entry ->
                    if (entry.key == frameKey) return@forEach
                    val categoryId = entry.key.substringBefore('.')
                    val shown = text.contains(entry.value.value)
                    val where = regionsOf(library, entry.value.value, categoryId)
                    assertFalse(
                        "${framePick.value} cannot hold ${entry.value.value}",
                        shown && (categoryId in cats || where.any { it in cut })
                    )
                }
            }
        }
    }

    @Test
    fun theCropSlotActuallyGetsDrawn() {
        val library = library()
        val engine = soloEngine(library)
        var drawn = 0
        val words = mutableSetOf<String>()
        repeat(300) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            val crop = picks["camera.crop"]?.value.orEmpty()
            if (crop.isNotEmpty()) {
                drawn++
                words += crop
            }
        }
        // The remaining ~16% is legitimate: when 构图 lands on 全身照 / 半身照 every crop word
        // is mutually exclusive with it, and a full-body shot has nothing to crop.
        assertTrue("only $drawn/300 draws had a crop word", drawn >= 240)
        // Four, not eight: the words that hide groin now stand down whenever an act is drawn,
        // so 只露出肩部 / 腰部以上特写 / 下半身在画外 / 只露出少量肌肤 only appear with 性行为 off.
        assertTrue("only ${words.size} distinct crop words: $words", words.size >= 4)
    }

    @Test
    fun theFramingWordDecidesAndTheBodyGivesWay() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "pussy.look" to TagPick("阴唇被手指分开"),
            "camera.crop" to TagPick("下半身在画外")
        )
        engine.repair(picks)
        assertEquals("下半身在画外", picks["camera.crop"]?.value.orEmpty())
        assertEquals("", picks["pussy.look"]?.value.orEmpty())
    }

    @Test
    fun theHandBudgetHoldsAcrossRandomDraws() {
        val library = library()
        val engine = soloEngine(library)
        val cost = library.constraints.handCost
        val cap = library.constraints.handCap
        repeat(300) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            val spent = picks.values
                .filter { it.value.isNotEmpty() && !it.ignored }
                .sumOf { cost[it.value] ?: 0 }
            assertTrue("hands spent $spent > $cap", spent <= cap)
        }
    }

    @Test
    fun tiedHandsNeverReachForALip() {
        val library = library()
        val engine = soloEngine(library)
        val blocked = library.constraints.handBlock.toSet()
        repeat(300) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            val bound = picks.values.any { it.value in blocked }
            if (!bound) return@repeat
            val handAction = picks.entries
                .filter { it.key.startsWith("handAction.") }
                .map { it.value.value }
                .filter { it.isNotEmpty() }
            assertTrue("restrained but still $handAction", handAction.isEmpty())
        }
    }

    @Test
    fun aRestraintEvictsTheHandActionItWasPairedWith() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "sexAct.act" to TagPick("手铐拘束", locked = true),
            "handAction.hand" to TagPick("手放自己腿上")
        )
        engine.repair(picks)
        assertEquals("", picks["handAction.hand"]?.value.orEmpty())
    }

    @Test
    fun feetOutOfFrameTakesTheShoesWithThem() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "camera.crop" to TagPick("脚部在画外", locked = true),
            "shoes.type" to TagPick("短靴")
        )
        engine.repair(picks)
        assertEquals("", picks["shoes.type"]?.value.orEmpty())
    }

    @Test
    fun aHalfBodyShotHasNoGenitalsInIt() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "camera.shot" to TagPick("贴得很近的半身", locked = true),
            "pussy.look" to TagPick("阴唇微微张开")
        )
        engine.repair(picks)
        assertEquals("", picks["pussy.look"]?.value.orEmpty())
    }

    @Test
    fun aFullBodyShotCannotCropTheFeet() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "camera.crop" to TagPick("脚部在画外", locked = true),
            "camera.shot" to TagPick("全身照")
        )
        engine.repair(picks)
        assertFalse(picks["camera.shot"]?.value == "全身照")
    }

    @Test
    fun aNamedMalePoseStaysOutOfAnAllFemaleCast() {
        val library = library()
        val female = library.constraints.peopleCount
            .filterKeys { !it.contains("男") }
            .filterValues { it >= 3 }
            .keys
        assumeTrue(female.isNotEmpty())
        repeat(200) {
            val picks = mutableMapOf<String, TagPick>()
            TagComposerEngine(library, female).randomize(picks)
            picks.values.forEach { pick ->
                assertFalse(
                    "两女一男叠在一起 needs a man",
                    pick.value == "两女一男叠在一起"
                )
            }
        }
    }

    @Test
    fun theExpressionClauseNeverRepeatsTheSubject() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mapOf(
            "expression.face" to TagPick("高潮失神"),
            "expression.compound" to TagPick("失神而满足")
        )
        val text = engine.compose(picks)
        assertFalse("repeated 失神: $text", text.contains("失神失神"))
        // 复合格独占这一句，单格不再并排。
        assertTrue("复合情绪要在句子里：$text", text.contains("失神而满足"))
        assertFalse("单格情绪不该同时出现：$text", text.contains("高潮失神"))
    }

    @Test
    fun twoHandsOfPostureLeaveNothingForTheAct() {
        val library = library()
        val engine = soloEngine(library)
        val picks = mutableMapOf<String, TagPick>(
            "pose.body" to TagPick("双手扶栏", locked = true),
            "sexAct.act" to TagPick("含住手指"),
            "handAction.hand" to TagPick("摊开手掌")
        )
        engine.repair(picks)
        val spent = picks.values.filter { !it.ignored }.sumOf { library.constraints.handCost[it.value] ?: 0 }
        assertTrue("hands spent $spent", spent <= library.constraints.handCap)
    }
}
