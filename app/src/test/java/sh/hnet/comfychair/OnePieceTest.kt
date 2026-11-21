package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 一件式服装（婚纱、长袍、连身制服）。
 *
 * 抽到就整身包住，下衣与内裤一起让位；玩家锁了下衣时反过来——连身件不参与抽取，
 * 手选的那一件不会被系统悄悄关掉。
 */
class OnePieceTest {

    private fun library() = TagLibraryJson.parse(
        File("src/main/assets/tag_catalog.json").readText(Charsets.UTF_8)
    )

    private fun engine(): TagComposerEngine {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagComposerEngine(library())
    }

    /** 单人 + 一个不触发任何规则的状态，免得穿脱抽到「下身赤裸」把下衣关掉。 */
    private fun base() = mutableMapOf(
        "people.count" to TagPick("单人女孩", locked = true),
        "outfitState.state" to TagPick("衣装半褪", locked = true)
    )

    @Test
    fun theCatalogueDeclaresOnePieceWords() {
        val lib = library()
        assertTrue("onePiece 不能为空", lib.constraints.onePiece.size > 50)
        assertTrue("婚纱 必须在一件式里", "婚纱" in lib.constraints.onePiece)
        assertTrue("连体衣 属于下衣一件式", "连体衣" in lib.constraints.onePieceBottom)
        // 半身件不该混进来。
        listOf("衬衫", "T恤", "百褶裙", "围裙").forEach {
            assertFalse("$it 不是一件式", it in lib.constraints.onePiece)
        }
    }

    @Test
    fun aLockedBottomKeepsOnePieceOut() {
        val e = engine()
        val onePiece = library().constraints.onePiece
        repeat(40) {
            val picks = base()
            picks["bottom.type"] = TagPick("百褶裙", locked = true)
            e.randomize(picks)
            val top = picks["top.type"]?.value.orEmpty()
            assertFalse("锁了下衣还抽到一件式 $top", top in onePiece)
        }
    }

    @Test
    fun aLockedOnePieceClosesBottomAndPanties() {
        val e = engine()
        val picks = base()
        picks["top.type"] = TagPick("婚纱", locked = true)
        e.randomize(picks)
        assertTrue("下衣要关掉", "bottom" in e.skipped(picks))
        assertTrue("内裤也要关掉", "panties" in e.skipped(picks))
    }

    @Test
    fun aDrawnOnePieceClosesBottomAndPanties() {
        val e = engine()
        val picks = base()
        picks["top.type"] = TagPick("幽冥圣女黑袍", locked = true)
        e.repair(picks)
        val text = e.compose(picks)
        assertTrue("长袍还在：$text", text.contains("幽冥圣女黑袍"))
        assertTrue("下衣要关掉", "bottom" in e.skipped(picks))
        assertTrue("内裤要关掉", "panties" in e.skipped(picks))
    }

    @Test
    fun aOnePieceBottomClosesTheTop() {
        val e = engine()
        val picks = base()
        picks["bottom.type"] = TagPick("连体衣", locked = true)
        e.randomize(picks)
        assertTrue("连体衣带了上半身", "top" in e.skipped(picks))
    }

    @Test
    fun bothLockedKeepsBoth() {
        val e = engine()
        val picks = base()
        picks["top.type"] = TagPick("婚纱", locked = true)
        picks["bottom.type"] = TagPick("百褶裙", locked = true)
        e.randomize(picks)
        assertFalse("玩家两边都锁了，不替他把下衣关掉", "bottom" in e.skipped(picks))
        val text = e.compose(picks)
        assertTrue("两边都要出现：$text", text.contains("婚纱") && text.contains("百褶裙"))
    }

    @Test
    fun theBlockReasonSaysWhy() {
        val e = engine()
        val picks = base()
        picks["bottom.type"] = TagPick("百褶裙", locked = true)
        val reason = e.blockedWords("top", "type", picks)["婚纱"]
        assertEquals("下衣已锁定，一件式上衣本次不参与", reason)
    }
}
