package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * The draw order is data, not code order.
 *
 * Before this, priority was implied by whichever pass ran first: moving 取景 ahead of the act
 * silently made the lens beat the act, and moving it back did the opposite. The catalog now
 * carries a tier per category and these tests pin it.
 */
class PriorityOrderTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun solo(library: TagLibrary) =
        TagComposerEngine(library, library.constraints.peopleCount.filterValues { it <= 1 }.keys)

    @Test
    fun everyCategoryDeclaresAGroupAndTier() {
        val library = library()
        val missing = library.categories.filter { it.group.isEmpty() || it.tier == 0 }
            .map { it.id }
        assertTrue("这些分类没有分组或优先级: $missing", missing.isEmpty())
        val declared = library.groups.map { it.id }.toSet()
        val unknown = library.categories.map { it.group }.filter { it !in declared }.toSet()
        assertTrue("分组没有在 meta.groups 里声明: $unknown", unknown.isEmpty())
    }

    @Test
    fun theTiersFollowTheAgreedOrder() {
        val library = library()
        val tier = library.categories.associate { it.id to it.tier }
        val chain = listOf(
            "people", "scene", "sexAct", "camera",
            "outfitState", "pose", "breasts", "top", "headwear",
            "expression", "grade"
        )
        chain.zipWithNext().forEach { (a, b) ->
            assertTrue("$a(${tier[a]}) 应该早于 $b(${tier[b]})", tier.getValue(a) < tier.getValue(b))
        }
        // 取景、出画同属一个分类，景别 / 机位 / 构图各占一格，彼此不会漂开。
        val framing = library.category("camera")!!.slots.map { it.id }.toSet()
        assertEquals("取景必须带景别、机位、构图与裁切四个槽", setOf("shot", "angle", "frame", "crop"), framing)
        assertEquals("姿势和动作必须同一层", tier["pose"], tier["activity"])
    }

    @Test
    fun aStandingActionCannotJoinAKneelingAct() {
        val library = library()
        val engine = solo(library)
        val posture = library.constraints.posture
        val allow = library.constraints.allowedPostures["后入跪趴"].orEmpty()
        assumeTrue("词库没声明后入跪趴的体位", allow.isNotEmpty())

        repeat(30) {
            val picks = mutableMapOf<String, TagPick>(
                "sexAct.act" to TagPick("后入跪趴", locked = true)
            )
            engine.randomize(picks)
            val action = picks["activity.action"]?.value.orEmpty()
            if (action.isEmpty()) return@repeat
            val group = posture[action] ?: return@repeat
            assertTrue(
                "「$action」是 $group，不该出现在只允许 $allow 的行为里",
                group in allow
            )
        }
    }

    @Test
    fun aSectionRerollLeavesLockedAndIgnoredAlone() {
        val library = library()
        val engine = solo(library)
        val picks = mutableMapOf<String, TagPick>()
        engine.randomize(picks)
        val group = library.groups.first { it.id == "body" }
        val bodyIds = library.categories.filter { it.group == group.id }.map { it.id }.toSet()

        val locked = picks.keys.first { it.substringBefore('.') in bodyIds }
        picks[locked] = picks.getValue(locked).copy(locked = true)

        val ignored = picks.keys.first { it.substringBefore('.') in bodyIds && it != locked }
        picks[ignored] = TagPick(value = "", ignored = true)

        val lockedValue = picks.getValue(locked).value

        repeat(5) { round ->
            library.categories.filter { it.group == group.id }.forEach { category ->
                engine.randomize(picks, category.id)
            }
            assertEquals("第 $round 次重抽把锁定的 $locked 改掉了", lockedValue, picks.getValue(locked).value)
            assertTrue("第 $round 次重抽把忽略的 $ignored 写回来了", picks.getValue(ignored).value.isEmpty())
        }
    }
}
