package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * A head count says how many bodies, never of which sex. These tests pin the two halves of
 * that gap: an all-female cast must never draw a word that needs a man, and a one-man cast
 * must still be able to reach one.
 */
class MaleGateTest {

    private fun catalogText(): String? = listOf(
        File("src/main/assets/tag_catalog.json"),
        File("app/src/main/assets/tag_catalog.json")
    ).firstOrNull { it.exists() }?.readText(Charsets.UTF_8)

    @Test
    fun anAllFemaleCastNeverDrawsAMaleWord() {
        val text = catalogText()
        assumeTrue("catalog asset not reachable from this working dir", text != null)
        val library = TagLibraryJson.parse(text!!)
        val engine = TagComposerEngine(library)
        val maleOnly = library.constraints.maleOnly.toSet()
        val maleCounts = library.constraints.malePeople.toSet()
        val femaleCounts = library.constraints.peopleCount.keys.filter { it !in maleCounts }
        assertTrue("expected all-female head counts", femaleCounts.isNotEmpty())

        val leaked = mutableSetOf<String>()
        femaleCounts.forEach { tag ->
            repeat(60) {
                val picks = mutableMapOf<String, TagPick>("people.count" to TagPick(tag, locked = true))
                engine.randomize(picks)
                picks.forEach { (key, pick) ->
                    if (pick.value in maleOnly) leaked += "$tag -> $key=${pick.value}"
                }
            }
        }
        assertTrue("male words under an all-female cast: ${leaked.take(8)}", leaked.isEmpty())
    }

    @Test
    fun aOneManCastCanStillReachAMaleWord() {
        val text = catalogText()
        assumeTrue(text != null)
        val library = TagLibraryJson.parse(text!!)
        val engine = TagComposerEngine(library)
        val maleOnly = library.constraints.maleOnly.toSet()

        var reached = 0
        repeat(120) {
            val picks = mutableMapOf<String, TagPick>("people.count" to TagPick("一男两女", locked = true))
            engine.randomize(picks)
            if (picks.values.any { it.value in maleOnly }) reached++
        }
        assertTrue("the male pool must stay reachable, got $reached/120", reached > 0)
    }

    @Test
    fun wordsThatNeedAPartnerStayOutOfSoloCasts() {
        val text = catalogText()!!
        assumeTrue(text.isNotEmpty())
        val library = TagLibraryJson.parse(text)
        val engine = TagComposerEngine(library)
        val partner = setOf(
            "被舐舐", "被吸唎", "被拉拽", "被咬住",
            "被手指揉捏", "被牙齿轻咬", "被冰过",
            "刚被玩弄过", "大腿内侧互相磨蹭"
        )

        repeat(80) {
            val picks = mutableMapOf<String, TagPick>("people.count" to TagPick("单人女孩", locked = true))
            engine.randomize(picks)
            val text = engine.compose(picks)
            partner.forEach { word ->
                assertFalse("solo cast got " + word + ": " + text, text.contains(word))
            }
        }
    }

    @Test
    fun framingNeverStatesTwoExtentsAtOnce() {
        val text = catalogText()
        assumeTrue(text != null)
        val library = TagLibraryJson.parse(text!!)
        val engine = TagComposerEngine(library)
        // 裁切并进景别之后，「从上往下裁到肩」和「全身都在画里」住同一个槽，一轮只出一个值，
        // 两种画幅尺度不可能同时成立。这里守的是这个结构前提，而不再是抽样比对。
        val shotSlot = library.category("camera")!!.slots.first { it.id == "shot" }.pool.toSet()
        val tight = listOf("胸部在画外", "下半身在画外", "脚部在画外")
        val wide = listOf("全身照", "半身照", "膝上照", "下半身特写", "私处特写", "胸部特写")
        assertTrue(
            "裁切词和景别词该住同一个槽：$shotSlot",
            tight.all { it in shotSlot } && wide.all { it in shotSlot }
        )
    }

    // ---- phrase de-duplication ----

    private fun skinLibrary(): TagLibrary = TagLibrary(
        categories = listOf(
            TagCategory(
                "skin", "皮肤", "skin",
                listOf(
                    TagSlot("tone", "肤色", listOf("皮肤油亮", "透粉的皮肤", "耳根发红", "冷白皮")),
                    TagSlot("state", "状态", listOf("皮肤上挂着水珠", "皮肤带着油光")),
                    TagSlot("look", "质感", listOf("油光", "次表面透光")),
                )
            )
        )
    )

    private fun skin(tone: String, state: String, look: String = ""): String {
        val engine = TagComposerEngine(skinLibrary())
        val picks = mapOf(
            "skin.tone" to TagPick(tone),
            "skin.state" to TagPick(state),
            "skin.look" to TagPick(look),
        )
        return engine.compose(picks)
    }

    @Test
    fun aLeadingSkinInTheToneIsNotSaidTwice() {
        assertEquals("油亮的皮肤上挂着水珠。", skin("皮肤油亮", "皮肤上挂着水珠"))
    }

    @Test
    fun aTrailingSkinInTheToneIsNotSaidTwice() {
        assertEquals("透粉的皮肤上挂着水珠。", skin("透粉的皮肤", "皮肤上挂着水珠"))
    }

    @Test
    fun aStateWithoutSkinIsUntouched() {
        assertEquals("冷白皮的皮肤上挂着水珠。", skin("冷白皮", "皮肤上挂着水珠"))
    }

    @Test
    fun aLookThatTheStateAlreadyMentionsIsDropped() {
        assertEquals("耳根发红的皮肤带着油光。", skin("耳根发红", "皮肤带着油光", "油光"))
    }

    @Test
    fun aDistinctLookSurvives() {
        assertEquals("耳根发红的皮肤带着油光，次表面透光。", skin("耳根发红", "皮肤带着油光", "次表面透光"))
    }

    @Test
    fun anEmptyStateKeepsTheToneAlone() {
        assertEquals("皮肤油亮。", skin("皮肤油亮", ""))
        assertFalse(skin("", "皮肤上挂着水珠").contains("的"))
    }
}

