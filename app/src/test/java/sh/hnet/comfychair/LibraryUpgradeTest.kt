package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagCategory
import sh.hnet.comfychair.model.TagConstraints
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.model.TagSlot
import sh.hnet.comfychair.storage.LibraryMerge
import java.io.File

/**
 * The library now refreshes itself on launch. The one thing it must never do is throw away a word
 * the user typed in, and the one thing it must do is drop what the new file dropped.
 */
class LibraryUpgradeTest {

    private fun lib(version: String, slot: List<String>, people: Map<String, Int> = emptyMap()) = TagLibrary(
        categories = listOf(
            TagCategory(id = "grade", label = "光影色调", slots = listOf(TagSlot("tone", "光质", slot)))
        ),
        constraints = TagConstraints(people = people),
        version = version
    )

    @Test
    fun aWordTheUserAddedComesAlongAndADroppedOneStaysDropped() {
        val base = lib("1", listOf("霓虹灯光", "粉色霓虹", "烛光"))
        val local = base.copy(
            categories = listOf(
                TagCategory(
                    "grade", "光影色调",
                    slots = listOf(TagSlot("tone", "光质", listOf("霓虹灯光", "粉色霓虹", "烛光", "我加的词")))
                )
            )
        )
        val incoming = lib("2", listOf("霓虹灯光", "烛光", "钨丝灯泡"))

        val merged = LibraryMerge.fold(base, local, incoming)
        val pool = merged.category("grade")!!.slots.first().pool
        assertEquals(listOf("霓虹灯光", "烛光", "钨丝灯泡", "我加的词"), pool)
        assertEquals("2", merged.version)
    }

    @Test
    fun aWordTheUserDeletedIsNotResurrected() {
        val base = lib("1", listOf("霓虹灯光", "粉色霓虹", "烛光"))
        val local = lib("1", listOf("霓虹灯光", "烛光"))
        val incoming = lib("2", listOf("霓虹灯光", "粉色霓虹", "烛光", "钨丝灯泡"))

        val pool = LibraryMerge.fold(base, local, incoming).category("grade")!!.slots.first().pool
        assertFalse("删除过的词不能被合并复活", "粉色霓虹" in pool)
        assertTrue("烛光" in pool)
    }

    @Test
    fun aSlotTheUserBuiltSurvivesEvenWhenTheNewFileNeverHeardOfIt() {
        val base = lib("1", listOf("烛光"))
        val local = base.copy(
            categories = base.categories + TagCategory("mine", "自建", slots = listOf(TagSlot("own", "自建", listOf("自造词"))))
        )
        val merged = LibraryMerge.fold(base, local, lib("2", listOf("烛光")))
        assertEquals(listOf("自造词"), merged.category("mine")?.slots?.first()?.pool)
    }

    @Test
    fun withoutABaseTheRulesFollowTheNewFileAndOnlyTheWordsAreKept() {
        // First launch after the feature ships: no base file, so a word that is merely absent
        // from the new file cannot be told apart from one the user typed. Pools keep it; rules
        // must not, or the cleanup this release is for would undo itself.
        val local = TagLibrary(
            categories = listOf(TagCategory("grade", "光影色调", slots = listOf(TagSlot("tone", "光质", listOf("粉色霓虹", "烛光"))))),
            constraints = TagConstraints(soloPose = listOf("对镜自拍", "叉腰", "撩头发")),
            version = "1"
        )
        val incoming = TagLibrary(
            categories = listOf(TagCategory("grade", "光影色调", slots = listOf(TagSlot("tone", "光质", listOf("烛光"))))),
            constraints = TagConstraints(soloPose = listOf("对镜自拍")),
            version = "2"
        )

        val merged = LibraryMerge.fold(null, local, incoming)
        assertEquals(listOf("对镜自拍"), merged.constraints.soloPose)
        assertTrue(merged.category("grade")!!.slots.first().pool.contains("烛光"))
    }

    @Test
    fun aConstraintTheUserEditedWinsOverTheShippedOne() {
        val base = lib("1", listOf("烛光"), people = mapOf("全裸" to 2, "只穿内裤" to 2))
        val local = base.copy(constraints = base.constraints.copy(people = mapOf("全裸" to 1, "只穿内裤" to 2)))
        val incoming = lib("2", listOf("烛光"), people = mapOf("全裸" to 2))

        val people = LibraryMerge.fold(base, local, incoming).constraints.people
        assertEquals(1, people["全裸"])
        assertFalse("被本地删掉的标注不该跟着新词库回来", people.containsKey("只穿内裤"))
    }

    @Test
    fun theShippedLibraryMatchesTheCleanupItAnnounces() {
        val catalog = File("src/main/assets/tag_catalog.json").takeIf { it.exists() }
            ?: File("app/src/main/assets/tag_catalog.json")
        assumeTrue(catalog.exists())
        val library = TagLibraryJson.parse(catalog.readText(Charsets.UTF_8))
        val tone = library.slot("grade", "tone")!!.pool

        // 光质 41 条里又删掉 10 条道具化的灯，剩下 31 条；连同光位并进来的 4 条，一共 35。
        assertEquals(35, tone.size)
        listOf(
            "粉色霓虹", "蓝色霓虹", "紫色氛围灯", "浴室雾气里的顶灯", "床头灯只够看清轮廓",
            "只开一盏暖灯", "钨丝灯泡", "荧光灯管", "浴室顶灯", "床头灯",
            "台灯侧光", "车灯照射", "氛围灯", "屏幕光", "手机屏幕照亮下颌",
        ).forEach {
            assertFalse("$it 应该已经删掉", it in tone)
        }
        // 光位并进光质以后，这几条方向词应该都在。
        listOf("低角度侧光", "右上逆光", "侧逆光", "地面返光", "环境冷色返光").forEach {
            assertTrue("$it 应该在池里", it in tone)
        }
        // 删掉的词不能再留别名：别名指向空词，老存档会拿到一个不存在的说法。
        listOf("暖黄灯光", "冷白灯光", "红色氛围灯", "紫色氛围灯", "浴室雾气里的顶灯", "床头灯只够看清轮廓")
            .forEach {
                assertFalse("$it 的别名该一起删", library.constraints.wordAliases.containsKey(it))
            }
        assertTrue("斑驳树影 还留着", library.constraints.requires.containsKey("斑驳树影"))
        assertFalse(
            "车灯照射 已经删了，场景前提也该跟着走",
            library.constraints.requires.containsKey("车灯照射")
        )
        // 调色 + 主色并成一格，光质 + 光位并成一格。
        assertEquals(listOf("feel", "tone"), library.category("grade")!!.slots.map { it.id })
    }
}
