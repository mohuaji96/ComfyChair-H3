package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * 独处词 only means "alone in frame". Hand gestures and ordinary poses used to sit on that list,
 * which is why 撩头发 could not be drawn next to 调教; and the check ran one way only, so a pose
 * drawn after the act was never re-checked.
 */
class SoloPoseTest {

    private val soloReason = "只能独自出镜的词不能带双人动作"

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun engine(library: TagLibrary) =
        TagComposerEngine(library, library.constraints.peopleCount.filterValues { it <= 1 }.keys, true)

    /** Two or more on stage, so 人数不够 stops answering first and the 独处词 guard is reached. */
    private fun crowdEngine(library: TagLibrary) =
        TagComposerEngine(library, library.constraints.peopleCount.filterValues { it >= 2 }.keys, true)

    @Test
    fun theListOnlyHoldsWordsThatMeanAlone() {
        val solo = library().constraints.soloPose.toSet()
        assertTrue("对镜自拍" in solo)
        listOf("撩头发", "叉腰", "抱膝", "双手背后", "手插口袋", "伸懒腰", "咬手指", "坐在床边晃腿").forEach {
            assertFalse("$it 有人在旁边也成立，不该算独处词", it in solo)
        }
    }

    @Test
    fun aHandGestureNoLongerBlocksTheAct() {
        val engine = engine(library())
        val picks = mutableMapOf<String, TagPick>(
            "handAction.hand" to TagPick("撩头发", locked = true)
        )
        // 调教 may still be refused for other reasons; what matters is that 撩头发 is not one.
        assertNotEquals(soloReason, engine.strictBlockedWords("sexAct", "act", picks)["调教"])
    }

    @Test
    fun aSelfieWordAndATwoPersonActCannotMeetInEitherOrder() {
        val engine = engine(library())

        val actFirst = mutableMapOf<String, TagPick>("sexAct.act" to TagPick("后入", locked = true))
        assertEquals(
            soloReason,
            engine.strictBlockedWords("gazeDir", "gazeDir", actFirst)["对镜自拍"]
        )

        val poseFirst = mutableMapOf<String, TagPick>("gazeDir.gazeDir" to TagPick("对镜自拍", locked = true))
        assertEquals(
            soloReason,
            crowdEngine(library()).strictBlockedWords("sexAct", "act", poseFirst)["后入"]
        )
    }
}
