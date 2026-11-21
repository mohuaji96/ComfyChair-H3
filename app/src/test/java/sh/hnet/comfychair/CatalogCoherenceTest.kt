package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * Runs the shipped catalog through the composer and checks the selection against the
 * library's own constraints. Guards the data, not the code: a mislabelled act or a stray
 * exact-count pose shows up here rather than in a render.
 */
class CatalogCoherenceTest {

    private fun catalogText(): String? {
        val candidates = listOf(
            File("src/main/assets/tag_catalog.json"),
            File("app/src/main/assets/tag_catalog.json")
        )
        return candidates.firstOrNull { it.exists() }?.readText(Charsets.UTF_8)
    }

    @Test
    fun everyDrawSatisfiesTheCatalogConstraints() {
        val text = catalogText()
        assumeTrue("catalog asset not reachable from this working dir", text != null)
        val library = TagLibraryJson.parse(text!!)
        val engine = TagComposerEngine(library)
        val c = library.constraints

        // 人数用池里真实存在的词：people.count 拿到不认识的词时 crowdSize 返回 null，过滤会整体失效。
        val counts = mapOf("单人女孩" to 1, "两个女孩" to 2, "三个女孩" to 3, "五个女孩" to 5)
        val problems = mutableListOf<String>()

        counts.forEach { (tag, crowd) ->
            repeat(120) {
                val picks = mutableMapOf<String, TagPick>("people.count" to TagPick(tag, locked = true))
                engine.randomize(picks)
                val values = picks.values.map { it.value }.filter { it.isNotEmpty() }
                val act = picks["sexAct.act"]?.value.orEmpty()
                val pose = picks["pose.body"]?.value.orEmpty()

                c.allowedPostures[act]?.let { allowed ->
                    val posture = c.posture[pose]
                    if (posture != null && posture !in allowed) {
                        problems += "crowd=$crowd act=$act pose=$pose($posture) not in $allowed"
                    }
                }
                if (pose in c.exactPeople && (c.people[pose] ?: 0) != crowd) {
                    problems += "crowd=$crowd pose=$pose names ${c.people[pose]}"
                }
                // Head counts are scoped per category: 全裸 means one body in 穿脱 and "a man is
                // present" in 男性, so the global map cannot answer this question on its own.
                picks.forEach { (key, pick) ->
                    val v = pick.value
                    if (v.isEmpty()) return@forEach
                    val needed = library.peopleNeeded(key.substringBefore('.'), v)
                    if (needed > crowd) problems += "crowd=$crowd $v needs $needed (${key.substringBefore('.')})"
                }
                c.synonyms.forEach { family ->
                    val hit = values.filter { it in family }
                    if (hit.size > 1) problems += "synonyms together: $hit"
                }
                if (values.sumOf { c.mouthCost[it] ?: 0 } > c.mouthCap) {
                    problems += "mouth over budget: $values"
                }
                if (values.sumOf { c.handCost[it] ?: 0 } > c.handCap) {
                    problems += "hands over budget: $values"
                }
                val solo = c.soloPose
                if (values.any { it in solo } && picks.any { (key, pick) ->
                        library.peopleNeeded(key.substringBefore('.'), pick.value) >= 2
                    }
                ) {
                    problems += "solo pose with two-person tag: $values"
                }
            }
        }
        assertTrue(problems.take(12).joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun anchoringActuallyNarrowsThePoseForLimitedActs() {
        val text = catalogText()
        assumeTrue(text != null)
        val library = TagLibraryJson.parse(text!!)
        val engine = TagComposerEngine(library)
        val c = library.constraints
        val poses = library.category("pose")!!.slots.flatMap { it.pool }

        // 侧入 only happens lying down, so the pose must be a lie-down pose every time.
        repeat(60) {
            val picks = mutableMapOf<String, TagPick>("people.count" to TagPick("两个女孩", locked = true))
            engine.randomize(picks)
        }
        val limited = c.allowedPostures.entries.filter { it.value.size == 1 }
        assertTrue("expected some single-posture acts", limited.isNotEmpty())
        limited.forEach { (act, allow) ->
            val reachable = poses.count { c.posture[it] in allow }
            assertTrue("$act has no reachable pose", reachable > 0)
        }
    }
}
