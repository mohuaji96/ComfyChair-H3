package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/** 脸被手掌/枕头/床单挡住时，妆、表情、视线都不该再写。 */
class FaceCoverTest {

    private fun engine(): TagComposerEngine {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagComposerEngine(TagLibraryJson.parse(file.readText(Charsets.UTF_8)))
    }

    @Test
    fun makeupIsDroppedWhenTheFaceIsBuried() {
        val e = engine()
        val picks = mutableMapOf(
            "people.count" to TagPick("单人女孩", locked = true),
            "headPose.pose" to TagPick("把脸埋进手掌", locked = true),
            "person.face" to TagPick("烟熏妆", locked = true)
        )
        val prompt = e.compose(picks)
        assertFalse("脸埋进手掌还写了妆容：$prompt", prompt.contains("烟熏妆"))
        assertTrue("人物句还在：$prompt", prompt.contains("女孩"))
    }
}
