package sh.hnet.comfychair.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * profile / rating 必须能完整往返。
 *
 * 这两个字段是导入时唯一的档位凭据：写丢一个，导出的 SFW 词库就会以「没有标 SFW」
 * 被自己拒绝；读丢一个，成人档词库会被当成无标记文件塞进 SFW。
 */
class TagLibraryProfileTest {

    private val sample = """
        {
          "schemaVersion": 1,
          "categories": [
            {
              "id": "people",
              "label": "人数",
              "slots": [
                {"id": "count", "label": "人数", "pool": ["单人女性", "一男一女"]}
              ]
            }
          ],
          "meta": {"profile": "sfw", "rating": "general", "version": "test-lib"}
        }
    """.trimIndent()

    @Test
    fun profileSurvivesRoundTrip() {
        val library = TagLibraryJson.parse(sample)
        assertEquals("sfw", library.profile)
        assertEquals("general", library.rating)

        val rewritten = TagLibraryJson.parse(TagLibraryJson.write(library))
        assertEquals("sfw", rewritten.profile)
        assertEquals("general", rewritten.rating)
        assertEquals(library.tagCount, rewritten.tagCount)
        assertEquals("test-lib", rewritten.version)
    }

    @Test
    fun missingProfileReadsAsEmpty() {
        val plain = """
            {
              "schemaVersion": 1,
              "categories": [
                {"id": "hair", "label": "头发", "slots": [{"id": "style", "label": "发型", "pool": ["低马尾"]}]}
              ],
              "meta": {"version": "old-lib"}
            }
        """.trimIndent()
        val library = TagLibraryJson.parse(plain)
        assertEquals("", library.profile)
        assertEquals("", library.rating)
        // 没有标记的老文件按成人档算（Conservative），不能默认成 SFW。
        assertEquals(ContentProfile.NSFW, ContentProfile.fromRating(library.rating))
    }

    @Test
    fun storyProfileSurvivesRoundTrip() {
        val text = """
            {
              "schemaVersion": 2,
              "version": "story-test",
              "profile": "sfw",
              "rating": "general",
              "casts": {"solo": {"text": "一位女性"}},
              "pools": {"place": ["靠窗的咖啡馆"]},
              "templates": {"solo": ["{time}{cast}在{place}。{female}"]}
            }
        """.trimIndent()
        val library = StoryLibraryJson.parse(text)
        assertEquals("sfw", library.profile)
        assertEquals("general", library.rating)

        val rewritten = StoryLibraryJson.parse(StoryLibraryJson.write(library))
        assertEquals("sfw", rewritten.profile)
        assertEquals("general", rewritten.rating)
        assertEquals(library.wordCount, rewritten.wordCount)
        assertTrue(rewritten.casts.containsKey("solo"))
    }
}
