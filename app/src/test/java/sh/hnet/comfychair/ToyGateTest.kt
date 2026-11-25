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
 * 束缚道具只能跟着玩法出场。
 *
 * 玩具格自己抽绳子会写出「全裸自慰，拿着束缚绳」——道具没有用处，还占着画面。
 */
class ToyGateTest {

    private fun library() = TagLibraryJson.parse(
        File("src/main/assets/tag_catalog.json").readText(Charsets.UTF_8)
    )

    private fun engine(): TagComposerEngine {
        assumeTrue(File("src/main/assets/tag_catalog.json").exists())
        return TagComposerEngine(library())
    }

    @Test
    fun theCatalogueTiesRestraintsToTheirActs() {
        val tied = library().constraints.toyActs
        assertEquals(listOf("捆绑", "绳缚"), tied["束缚绳"])
        assertTrue("口塞球 要配口塞", tied["口塞球"] == listOf("口塞"))
    }

    @Test
    fun aPlainActNeverPicksUpARestraint() {
        val e = engine()
        // 「通用」登记的是润滑液这类不挑玩法的道具，不是束缚类，普通动作当然可以拿。
        val restraints = library().constraints.toyActs
            .filterValues { "通用" !in it }.keys
        repeat(40) {
            val picks = mutableMapOf(
                "people.count" to TagPick("单人女孩", locked = true),
                "outfitState.state" to TagPick("衣装半褪", locked = true),
                "sexAct.act" to TagPick("自慰", locked = true)
            )
            e.randomize(picks)
            val toy = picks["toys.item"]?.value.orEmpty()
            assertFalse("自慰不该拿到 $toy", toy in restraints)
        }
    }

    @Test
    fun aTiedActGetsItsRestraint() {
        val e = engine()
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "outfitState.state" to TagPick("衣装半褪", locked = true),
            "sexAct.act" to TagPick("捆绑", locked = true)
        )
        e.randomize(picks)
        assertEquals("束缚绳", picks["toys.item"]?.value.orEmpty())
    }
}
