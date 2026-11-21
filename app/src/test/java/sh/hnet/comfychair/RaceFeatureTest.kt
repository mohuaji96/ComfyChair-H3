package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick

/**
 * A race is a word until the matching organ is drawn with it. These pin the two halves of that:
 * a bound race narrows both feature slots, and an unbound one stays open.
 */
class RaceFeatureTest {

    private fun engine(
        head: Map<String, List<String>>,
        body: Map<String, List<String>>
    ): TagComposerEngine {
        val library = TagLibrary(
            categories = listOf(
                TagCategory("people", "人数", "", listOf(TagSlot("count", "人数", listOf("单人")))),
                TagCategory(
                    "race", "种族", "",
                    listOf(TagSlot("kind", "种族", listOf("恶魔", "精灵", "人类")))
                ),
                TagCategory(
                    "featureHead", "种族特征", "list",
                    listOf(
                        TagSlot("part", "头部", listOf("犄角", "猫耳")),
                        TagSlot("body", "身体", listOf("蝠翼", "鱼尾"))
                    )
                ),
            ),
            constraints = TagConstraints(
                people = mapOf("单人" to 1),
                raceFeatureHead = head,
                raceFeatureBody = body
            )
        )
        return TagComposerEngine(library)
    }

    @Test
    fun boundRaceOnlyDrawsItsOwnFeatures() {
        val e = engine(
            head = mapOf("恶魔" to listOf("犄角")),
            body = mapOf("恶魔" to listOf("蝠翼"))
        )
        repeat(40) {
            val picks = mutableMapOf(
                "people.count" to TagPick("单人", locked = true),
                "race.kind" to TagPick("恶魔", locked = true)
            )
            e.randomize(picks)
            assertEquals("犄角", picks["featureHead.part"]?.value.orEmpty())
            assertEquals("蝠翼", picks["featureHead.body"]?.value.orEmpty())
        }
    }

    @Test
    fun unboundRaceKeepsBothFeaturesOpen() {
        val e = engine(
            head = mapOf("恶魔" to listOf("犄角")),
            body = mapOf("恶魔" to listOf("蝠翼"))
        )
        val heads = mutableSetOf<String>()
        val bodies = mutableSetOf<String>()
        repeat(60) {
            val picks = mutableMapOf(
                "people.count" to TagPick("单人", locked = true),
                "race.kind" to TagPick("精灵", locked = true)
            )
            e.randomize(picks)
            picks["featureHead.part"]?.value?.takeIf { it.isNotEmpty() }?.let(heads::add)
            picks["featureHead.body"]?.value?.takeIf { it.isNotEmpty() }?.let(bodies::add)
        }
        assertTrue("没被表约束的种族两条头部特征都能拿，saw $heads", heads.size == 2)
        assertTrue("没被表约束的种族两条身体特征都能拿，saw $bodies", bodies.size == 2)
    }

    @Test
    fun aHumanTakesNoRaceFeature() {
        val e = engine(
            head = mapOf("恶魔" to listOf("犄角")),
            body = mapOf("恶魔" to listOf("蝠翼"))
        )
        repeat(40) {
            val picks = mutableMapOf(
                "people.count" to TagPick("单人", locked = true),
                "race.kind" to TagPick("人类", locked = true)
            )
            e.randomize(picks)
            assertEquals("人类不该长角", "", picks["featureHead.part"]?.value.orEmpty())
            assertEquals("人类不该长翅膀", "", picks["featureHead.body"]?.value.orEmpty())
        }
    }

    @Test
    fun switchingRaceDropsAFeatureThatNoLongerFits() {
        val e = engine(
            head = mapOf("恶魔" to listOf("犄角"), "精灵" to listOf("猫耳")),
            body = mapOf("恶魔" to listOf("蝠翼"))
        )
        val picks = mutableMapOf(
            "people.count" to TagPick("单人", locked = true),
            "race.kind" to TagPick("恶魔", locked = true),
            "featureHead.part" to TagPick("犄角")
        )
        e.repair(picks)
        assertEquals("犄角", picks["featureHead.part"]?.value.orEmpty())

        picks["race.kind"] = TagPick("精灵")
        e.repair(picks)
        assertEquals(
            "a horn does not survive the switch to 精灵",
            "猫耳", picks["featureHead.part"]?.value.orEmpty()
        )
    }
}
