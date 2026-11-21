package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * assets/ is packaged wholesale, so anything sitting there ships. 0.9.8.8298 shipped a
 * `tag_catalog.json.v24.bak` that a patch script left behind; the APK grew 55 KB for nothing.
 */
class AssetsPackagingTest {

    @Test
    fun assetsHoldNothingButTheFilesTheAppReads() {
        val dir = File("src/main/assets")
        assumeTrue(dir.isDirectory)
        // tag_catalog.previous.json is deliberate: it is the snapshot the first automatic library
        // upgrade compares against, and without it a word the new file deleted cannot be told
        // apart from one the user typed.
        val allowed = setOf(
            "tag_catalog.json",
            "tag_catalog.previous.json",
            "story_catalog.json",
            // SFW 两档各自一整套：多出来的就是这两个库，和它们各自的规则表。
            "tag_catalog_sfw.json",
            "story_catalog_sfw.json",
            "forbidden_nsfw.txt",
            "allow_sfw.txt",
            "prompt_expert.md",
            "prompt_expert_lite.md"
        )
        val unexpected = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name !in allowed }
            .map { it.name }
        assertTrue("unexpected files under src/main/assets: $unexpected", unexpected.isEmpty())
    }
}
