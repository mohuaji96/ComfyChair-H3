package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两档的文件名必须完全不重叠。
 *
 * 漏掉一个派生文件（`.pinned`、`.backup`）就会让两档互相污染：SFW 档读到 NSFW 的
 * 「用户导入过」标记后，再也收不到内置更新，而界面上完全看不出来。
 */
class ContentProfileTest {

    private fun tagFiles(profile: ContentProfile) = listOf(
        "${profile.tagStem}.json",
        "${profile.tagStem}.base.json",
        "${profile.tagStem}.backup.json",
        "${profile.tagStem}.pinned",
        profile.tagAsset,
        profile.tagSeedName
    )

    private fun storyFiles(profile: ContentProfile) = listOf(
        "${profile.storyStem}.json",
        "${profile.storyStem}.backup.json",
        "${profile.storyStem}.imported",
        profile.storyAsset,
        profile.storyCustomName
    )

    @Test
    fun tagFileNamesDoNotOverlap() {
        val overlap = tagFiles(ContentProfile.NSFW).intersect(tagFiles(ContentProfile.SFW).toSet())
        assertTrue("两档共用文件：$overlap", overlap.isEmpty())
    }

    @Test
    fun storyFileNamesDoNotOverlap() {
        val overlap = storyFiles(ContentProfile.NSFW).intersect(storyFiles(ContentProfile.SFW).toSet())
        assertTrue("两档共用文件：$overlap", overlap.isEmpty())
    }

    @Test
    fun nsfwKeepsHistoricalNames() {
        // 老用户升级时词库文件必须原地命中，改名等于让他们丢一次库。
        assertEquals("tag_catalog", ContentProfile.NSFW.tagStem)
        assertEquals("tag_catalog.json", ContentProfile.NSFW.tagAsset)
        assertEquals("tag_catalog.previous.json", ContentProfile.NSFW.tagSeedName)
        assertEquals("story_catalog", ContentProfile.NSFW.storyStem)
        assertEquals("story_catalog.json", ContentProfile.NSFW.storyAsset)
        assertEquals("story_custom.json", ContentProfile.NSFW.storyCustomName)
    }

    @Test
    fun sfwNamesAreMarked() {
        assertEquals("tag_catalog_sfw.json", ContentProfile.SFW.tagAsset)
        assertEquals("tag_catalog_sfw.previous.json", ContentProfile.SFW.tagSeedName)
        assertEquals("story_catalog_sfw.json", ContentProfile.SFW.storyAsset)
        assertEquals("story_custom.sfw.json", ContentProfile.SFW.storyCustomName)
        assertTrue(ContentProfile.SFW.isSfw)
        assertTrue(!ContentProfile.NSFW.isSfw)
    }

    @Test
    fun fromRatingDefaultsToNsfw() {
        // 缺省按成人档处理是保守选择：判错方向只会让用户看到「导入被拒」，
        // 反过来会让露骨词库静默进 SFW 档。
        assertEquals(ContentProfile.NSFW, ContentProfile.fromRating(null))
        assertEquals(ContentProfile.NSFW, ContentProfile.fromRating(""))
        assertEquals(ContentProfile.NSFW, ContentProfile.fromRating("mature"))
        assertEquals(ContentProfile.SFW, ContentProfile.fromRating("general"))
    }

    @Test
    fun fromKeyFallsBackToNsfw() {
        assertEquals(ContentProfile.SFW, ContentProfile.fromKey("sfw"))
        assertEquals(ContentProfile.NSFW, ContentProfile.fromKey("nsfw"))
        assertEquals(ContentProfile.NSFW, ContentProfile.fromKey(""))
        assertEquals(ContentProfile.NSFW, ContentProfile.fromKey(null))
    }
}
