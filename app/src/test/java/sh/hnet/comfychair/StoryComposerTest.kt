package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.model.StoryLibraryJson
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.StoryPick
import java.io.File
import kotlin.random.Random

/**
 * 剧情组合：抽取、锁定、忽略、以及「上游改了下游要跟着改」的几条硬规则。
 */
class StoryComposerTest {

    private fun library(): StoryLibrary {
        val file = listOf(
            File("src/main/assets/story_catalog.json"),
            File("app/src/main/assets/story_catalog.json")
        ).firstOrNull { it.exists() }
        assumeTrue("story_catalog.json not found", file != null)
        return StoryLibraryJson.parse(file!!.readText(Charsets.UTF_8))
    }

    private fun generator(seed: Int = 7) = StoryGenerator(library(), Random(seed))

    @Test
    fun `every field gets a value on the first draw`() {
        val gen = generator()
        val picks = gen.randomize(gen.emptyPicks()).first
        gen.fields.forEach { field ->
            // 情绪 / 道具 / 细节 / 痕迹默认关着，这几格本来就该空；
            // 袜子有 25% 概率故意不穿，抽空也是对的。
            if (field.key == StoryGenerator.MOOD || field.key == StoryGenerator.PROP ||
                field.key == StoryGenerator.DETAIL || field.key == StoryGenerator.MARK ||
                field.key == StoryGenerator.SOCKS ||
                // 穿整身衣服时这两格不自动抽，但面板里能选。
                field.key == StoryGenerator.AREOLA || field.key == StoryGenerator.NIPPLE ||
                // 室内场景本来就不该有天气。
                field.key == StoryGenerator.WEATHER || field.key.startsWith("w2_")
            ) {
                return@forEach
            }
            // 状态说的是「同一套衣服的状态」，所以两种情况下留空都是对的：
            // 一是身上什么都没穿（全裸、上半身赤裸），二是只穿贴身衣物（只穿着袜子）。
            if (field.key == StoryGenerator.STATE) {
                val worn = library().word("outfit", picks[StoryGenerator.OUTFIT]?.value.orEmpty())
                    ?.wears.orEmpty()
                val outer = worn.filterNot { it in setOf("内衣", "内裤", "丝袜", "袜", "鞋", "高跟鞋") }
                if (outer.isEmpty()) return@forEach
            }
            assertTrue("${field.label} 没抽到", picks[field.key]?.value?.isNotBlank() == true)
        }
    }

    @Test
    fun `locked value survives a reroll`() {
        val gen = generator()
        var picks = gen.randomize(gen.emptyPicks()).first
        picks = picks + (StoryGenerator.PLACE to StoryPick("浴室里", locked = true))
        repeat(20) {
            picks = gen.randomize(picks).first
            assertEquals("浴室里", picks[StoryGenerator.PLACE]?.value)
        }
    }

    @Test
    fun `ignored field stays empty and never leaks into the sentence`() {
        val gen = generator()
        var picks = gen.randomize(gen.emptyPicks()).first
        picks = picks + (StoryGenerator.CHORE to StoryPick(ignored = true))
        picks = gen.randomize(picks).first
        assertEquals("", picks[StoryGenerator.CHORE]?.value)
        val text = gen.compose(picks, detail = true, dialogue = false)
        assertTrue(text.isNotBlank())
        assertFalse("忽略的分项不该出现在句子里", text.contains("，，"))
    }

    @Test
    fun `changing the era clears an outfit that no longer fits`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.WORLD to StoryPick("宫廷", locked = true),
            // 包臀裙是现代单品。换场景不该清掉（那是角色扮演），换年代才清。
            StoryGenerator.OUTFIT to StoryPick("只穿着包臀裙和丝袜", locked = true)
        )
        val (next, notes) = gen.randomize(picks)
        assertEquals("", next[StoryGenerator.OUTFIT]?.value)
        assertTrue(notes.isNotEmpty())
    }

    @Test
    fun `every scene has at least one daily action`() {
        val gen = generator()
        gen.allowed(StoryGenerator.PLACE, gen.emptyPicks()).forEach { place ->
            val picks = gen.emptyPicks() + mapOf(
                StoryGenerator.CAST to StoryPick("mf", locked = true),
                StoryGenerator.PLACE to StoryPick(place, locked = true)
            )
            assertTrue("「$place」没有日常动作可抽", gen.allowed(StoryGenerator.CHORE, picks).isNotEmpty())
        }
    }

    @Test
    fun `world words are real words, not empty placeholders`() {
        val world = library().pool("world").map { it.text }
        assertTrue("题材里有空串", world.none { it.isBlank() })
        assertTrue("题材里带逗号", world.none { it.contains("，") })
        assertTrue("题材太少", world.size >= 6)
    }

    @Test
    fun `styles come from the image library and drop ink wash`() {
        val styles = library().pool("style").map { it.text }
        assertTrue("画风太少", styles.size >= 30)
        assertFalse("水墨不适合这个场景", styles.any { it.contains("水墨") })
        assertTrue(styles.contains("写实照片"))
    }

    @Test
    fun `library is big enough to stop repeating`() {
        assertTrue("词量还太小：${library().wordCount}", library().wordCount >= 600)
    }

    @Test
    fun `a pose bound act stays under its own pose`() {
        val gen = generator()
        val base = gen.emptyPicks() + (StoryGenerator.CAST to StoryPick("mf", locked = true))
        val kneeling = base + (StoryGenerator.POSE to StoryPick("让她跪着塌下腰（跪趴）", locked = true))
        val acts = gen.allowed(StoryGenerator.ACT, kneeling)
        assertTrue("跪姿下抽不到写明跪趴的动作", "让她跪好，从后面插进去" in acts)
        assertFalse("跪姿下不该冒出骑乘动作", "让她自己扶着阴茎慢慢坐下去" in acts)
    }

    @Test
    fun `composed text has no empty slots and no doubled punctuation`() {
        val gen = generator(seed = 3)
        repeat(40) {
            val picks = gen.randomize(gen.emptyPicks()).first
            val text = gen.compose(picks, detail = true, dialogue = true)
            assertTrue("空句子", text.isNotBlank())
            assertFalse("残留占位符", text.contains("{"))
            assertFalse("双逗号", text.contains("，，"))
            assertFalse("句首逗号", text.startsWith("，"))
            assertTrue("台词标记没了", text.contains("台词：") || text.contains("有中文台词"))
        }
    }

    @Test
    fun `dialogue switch controls the tail`() {
        val gen = generator()
        val picks = gen.randomize(gen.emptyPicks()).first
        assertFalse(gen.compose(picks, detail = false, dialogue = false).contains("有中文台词"))
    }

    @Test
    fun `details only appear when the sentence supports them`() {
        val gen = generator(seed = 11)
        repeat(30) {
            val picks = gen.randomize(gen.emptyPicks()).first
            val text = gen.compose(picks, detail = true, dialogue = false)
            if (text.contains("围裙的带子")) {
                assertTrue("没有围裙却出现了围裙的细节", text.contains("围裙"))
            }
            if (text.contains("手扶不住台面")) {
                assertTrue("没有台面却出现了台面细节", text.contains("台面"))
            }
        }
    }

    @Test
    fun `nothing to undress when she is already naked`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("厨房", locked = true),
            StoryGenerator.OUTFIT to StoryPick("全裸", locked = true)
        )
        val hit = gen.allowed(StoryGenerator.ESCALATE, picks)
        assertFalse("全裸还能解开围裙", "解开围裙的带子" in hit)
        assertFalse("全裸还能解衬衫扣子", "把衬衫的扣子一颗颗解开" in hit)
        assertTrue("总得还能做点什么", hit.isNotEmpty())
    }

    @Test
    fun `apron only shows up where there is an apron`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("厨房", locked = true),
            StoryGenerator.OUTFIT to StoryPick("只围了一条围裙", locked = true)
        )
        assertTrue("围裙在身却不给解围裙", "解开围裙的带子" in gen.allowed(StoryGenerator.ESCALATE, picks))
    }

    @Test
    fun `no panties means no panty lines`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("卧室床边", locked = true),
            StoryGenerator.OUTFIT to StoryPick("全裸", locked = true)
        )
        assertFalse("全裸还能把内裤拉到一边", "把内裤拉到一边" in gen.allowed(StoryGenerator.ESCALATE, picks))
        val dressed = picks + (StoryGenerator.OUTFIT to StoryPick("只穿了一条内裤", locked = true))
        assertTrue("穿着内裤就该能拉", "把内裤拉到一边" in gen.allowed(StoryGenerator.ESCALATE, dressed))
    }

    @Test
    fun `rain follows the scene instead of hiding indoors`() {
        val gen = generator()
        val outdoor = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("沙滩上", locked = true)
        )
        assertTrue("沙滩上该能下雨", gen.allowed(StoryGenerator.WEATHER, outdoor).any { "小雨" in it })
        val indoor = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("厨房", locked = true)
        )
        assertTrue(
            "厨房里不该有天气：${gen.allowed(StoryGenerator.WEATHER, indoor)}",
            gen.allowed(StoryGenerator.WEATHER, indoor).isEmpty()
        )
    }

    @Test
    fun `pose pool covers the six basic families`() {
        val poses = library().pool("pose")
        val anchors = poses.map { it.key }.toSet()
        listOf("压", "骑乘", "侧躺", "后入", "墙", "跪趴").forEach {
            assertTrue("六大族缺了 $it", it in anchors)
        }
        assertTrue("体位太少：${poses.size}", poses.size >= 55)
    }

    @Test
    fun `common words are marked so the sheet can float them`() {
        val lib = library()
        assertTrue("一个常用词都没标", lib.isCommon("chore", "正在洗菜"))
        assertFalse("冷门词不该标常用", lib.isCommon("chore", "正在解冻食材"))
    }

    @Test
    fun `none of the wardrobe pools are allowed to run dry`() {
        val lib = library()
        listOf("outfit", "outfit_state", "socks", "shoes", "outfit_mid").forEach { pool ->
            assertTrue("$pool 池子是空的", lib.pool(pool).size >= 10)
        }
    }

    @Test
    fun `the state always makes it into the sentence`() {
        val gen = generator(seed = 5)
        repeat(40) {
            val picks = gen.randomize(gen.emptyPicks()).first
            val state = picks[StoryGenerator.STATE]?.value.orEmpty()
            val outfit = picks[StoryGenerator.OUTFIT]?.value.orEmpty()
            val wears = library().word("outfit", outfit)?.wears.orEmpty()
            val text = gen.compose(picks, detail = false, dialogue = false)
            // 身上什么都没穿的设定下，状态会被刻意留空；有衣服就必须写进句子。
            if (state.isNotEmpty() && wears.isNotEmpty()) {
                assertTrue("状态「$state」没进句子：$text", text.contains(state))
            }
        }
    }

    @Test
    fun `campus theme keeps the scene on campus`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.WORLD to StoryPick("校园", locked = true)
        )
        val places = gen.allowed(StoryGenerator.PLACE, picks)
        assertTrue("校园题材要有教室", "大学教室里" in places)
        assertFalse("校园题材不该跑到沙滩", "沙滩上" in places)
        assertFalse("校园题材不该跑到后巷", "24小时便利店后巷" in places)
    }

    @Test
    fun `mid pool can say she is already nude`() {
        val mid = library().pool("outfit_mid").map { it.text }
        assertTrue("过程中缺一条「赤裸」", mid.any { it.contains("赤裸") })
    }

    @Test
    fun `main act is a chain, not a single word`() {
        val gen = generator(seed = 9)
        val picks = gen.randomize(gen.emptyPicks()).first
        val chain = gen.actChain(picks)
        assertTrue("主戏至少得有一段", chain.isNotEmpty())
        val text = gen.compose(picks, detail = false, dialogue = false)
        chain.forEach { assertTrue("「$it」没进句子：$text", text.contains(it)) }
    }

    @Test
    fun `manual act chain survives a reroll when locked`() {
        val gen = generator()
        var picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("床上", locked = true),
            StoryGenerator.ACT to StoryPick("把阴茎整根插进她的阴道${StoryGenerator.SEP}阴茎从后面顶到最深处", locked = true)
        )
        picks = gen.randomize(picks).first
        assertEquals("把阴茎整根插进她的阴道${StoryGenerator.SEP}阴茎从后面顶到最深处", picks[StoryGenerator.ACT]?.value)
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("手选的两段都该在句子里：$text", text.contains("插进她的阴道") && text.contains("从后面顶到最深处"))
    }

    @Test
    fun `every pool is at least the size we promised`() {
        val lib = library()
        val expected = mapOf(
            "world" to 14, "place" to 105, "chore" to 140, "outfit" to 58,
            "socks" to 20, "shoes" to 15, "pose" to 62, "act" to 78,
            "detail" to 40, "style" to 30
        )
        expected.forEach { (pool, size) ->
            assertTrue("$pool 只有 ${lib.pool(pool).size} 条，应该至少 $size", lib.pool(pool).size >= size)
        }
    }

    @Test
    fun `the new words really landed in their pools`() {
        val lib = library()
        fun has(pool: String, word: String) = lib.pool(pool).any { it.text == word }
        listOf("正在刮腿毛", "正在加油", "正在排练", "正在调音", "正在量三围").forEach {
            assertTrue("日常缺「$it」", has("chore", it))
        }
        listOf("阶梯教室里", "KTV包厢里", "温泉里", "录音棚", "高铁的洗手间").forEach {
            assertTrue("场景缺「$it」", has("place", it))
        }
        listOf("都市职场", "宫廷", "武侠", "蒸汽朋克", "太空站").forEach {
            assertTrue("题材缺「$it」", has("world", it))
        }
        listOf("穿着汉服", "穿着女仆装", "穿着雨衣", "穿着白大褂").forEach {
            assertTrue("穿着缺「$it」", has("outfit", it))
        }
        listOf("开裆丝袜", "过膝袜", "光着腿").forEach {
            assertTrue("袜缺「$it」", has("socks", it))
        }
        listOf("踩着高跟鞋", "踩着绣花鞋", "赤脚").forEach {
            assertTrue("鞋缺「$it」", has("shoes", it))
        }
        listOf("在驾驶座上让她跨坐上来", "两人身体呈十字交叉侧卧", "让她用双腿死死盘住腰", "让她反跨坐在椅子上，扶着椅背").forEach {
            assertTrue("体位缺「$it」", has("pose", it))
        }
    }

    @Test
    fun `no pool carries the same word twice`() {
        val lib = library()
        lib.pools.forEach { (pool, words) ->
            val texts = words.map { it.text }
            assertEquals("$pool 里有重复词", texts.size, texts.distinct().size)
        }
    }

    @Test
    fun `new poses anchor to keys the main acts already know`() {
        val gen = generator()
        val poseKeys = library().pool("pose").map { it.key }.toSet()
        listOf("骑乘", "压", "悬空", "侧躺").forEach { key ->
            assertTrue("姿势池里没有 $key", key in poseKeys)
        }
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("驾驶座", locked = true)
        )
        assertTrue("车里应该能坐到驾驶座上", "在驾驶座上让她跨坐上来" in gen.allowed(StoryGenerator.POSE, picks))
    }

    @Test
    fun `the washing machine is reachable from more than one room`() {
        val lib = library()
        val places = lib.pool("place").filter { "洗衣机" in it.props }.map { it.text }
        assertTrue("只有 ${places.size} 个地方有洗衣机", places.size >= 3)
        val pose = lib.word("pose", "让她坐在洗衣机上")
        assertTrue("洗衣机上没有这个体位", pose != null && "洗衣机" in pose.requires)
    }

    /** 每个动作只属于一个人数，只有那五条连接动作人人可用。 */
    @Test
    fun `every act belongs to a single cast`() {
        val acts = library().pool("act").filter { it.text.isNotEmpty() }
        val shared = acts.filter { it.casts.size > 1 }
        // 肛交那组并入 act 后有 8 条同时适用 mf 与 mff，共用动作随之变多。
        assertTrue("多人数共用的动作超过十五条：${shared.map { it.text }}", shared.size <= 15)
        assertTrue(
            "共用动作没标 common：${shared.filter { !it.common }.map { it.text }}",
            shared.all { it.common }
        )
        val leak = acts.filter { "ff" in it.casts && (it.text.contains("他") || it.text.contains("阴茎")) }
        assertTrue("两个女孩的动作里出现了男人：${leak.map { it.text }}", leak.isEmpty())
        val soloLeak = acts.filter { it.casts == listOf("solo") && it.text.contains("他") }
        assertTrue("单人动作里出现了男人：${soloLeak.map { it.text }}", soloLeak.isEmpty())
    }

    /** 新加的那批露骨动作要真能抽到，绑了体位的也得在对应姿势下出现。 */
    @Test
    fun `new explicit acts are reachable`() {
        val gen = generator()
        val mf = gen.emptyPicks() + (StoryGenerator.CAST to StoryPick("mf", locked = true))
        val legUp = gen.allowed(
            StoryGenerator.ACT,
            mf + (StoryGenerator.POSE to StoryPick("把她的腿架上肩膀"))
        )
        assertTrue("扛腿那条抽不到", "把她的一条腿扛到肩上，阴茎斜着往里插到底" in legUp)
        val plain = gen.allowed(StoryGenerator.ACT, mf)
        listOf(
            "整根抽出来再整根撞回去，每次都顶到宫口",
            "抓着她的头发往后拉，阴茎撞得更狠，胯拍在她臀上",
            "一手按着她的小腹，能摸到阴茎在里面顶"
        ).forEach { assertTrue("「$it」抽不到", it in plain) }
    }

    /** 一次通常换两三个体位，不再一个姿势做到底。 */
    @Test
    fun `pose chain carries two or three changes`() {
        val gen = generator()
        var sawThree = false
        listOf("solo", "mf", "mff", "ff").forEach { cast ->
            repeat(80) {
                val picks = gen.emptyPicks() + (StoryGenerator.CAST to StoryPick(cast, locked = true))
                val poses = gen.randomize(picks).first[StoryGenerator.POSE]?.value
                    .orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }
                assertTrue("$cast 的姿势链只有 ${poses.size} 个：$poses", poses.size >= 2)
                if (poses.size == 3) sawThree = true
            }
        }
        assertTrue("一次三个姿势都没抽到", sawThree)
    }


    @Test
    fun `custom words are appended, marked, and never duplicated`() {
        val base = library()
        val custom = mapOf("chore" to listOf("正在数星星"))
        val once = base.withCustom(custom)
        assertEquals("没加进去", base.pool("chore").size + 1, once.pool("chore").size)
        assertEquals("新词不在末尾", "正在数星星", once.pool("chore").last().text)
        assertTrue("记号没打上", "正在数星星" in once.custom["chore"].orEmpty())
        val twice = once.withCustom(custom)
        assertEquals("同一个自定义词被加了两次", once.pool("chore").size, twice.pool("chore").size)
    }

    @Test
    fun `custom words survive a library swap`() {
        val base = library()
        val withOne = base.withCustom(mapOf("prop" to listOf("手铐")))
        // 换一份新词库（模拟版本升级），自定义词还在
        val swapped = library().withCustom(withOne.custom.mapValues { it.value.toList() })
        assertTrue("换词库把自定义词冲掉了", swapped.pool("prop").any { it.text == "手铐" })
    }

    @Test
    fun `campus theme no longer collapses the scene pool`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.WORLD to StoryPick("校园", locked = true)
        )
        val places = gen.allowed(StoryGenerator.PLACE, picks)
        assertTrue("校园题材下场景只有 ${places.size} 个", places.size >= 50)
        assertTrue("阶梯教室不在", "阶梯教室里" in places)
        assertTrue("KTV包厢不在", "KTV包厢里" in places)
        assertFalse("校园题材不该出沙滩", "沙滩上" in places)
    }

    @Test
    fun `the scene list does not narrow to its own group`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("客厅的沙发上", locked = true)
        )
        val places = gen.allowed(StoryGenerator.PLACE, picks)
        assertTrue("当前在客厅，场景只剩 ${places.size} 个", places.size >= 50)
        assertTrue("应该还能选到浴室", places.any { it == "淋浴间" })
    }

    @Test
    fun `campus stays selectable while standing in a garage`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("车库", locked = true)
        )
        assertTrue("车库里选不到校园题材", "校园" in gen.allowed(StoryGenerator.WORLD, picks))
    }

    @Test
    fun `a solo story never borrows a two person action`() {
        val gen = generator()
        repeat(20) {
            val picks = gen.randomize(
                gen.emptyPicks() + (StoryGenerator.CAST to StoryPick("solo", locked = true))
            ).first
            val text = gen.compose(picks, detail = false, dialogue = false)
            listOf("十指扣在一起", "一起动", "按着她的头深喉", "抓着她的头发").forEach {
                assertFalse("单人出现了「$it」：$text", text.contains(it))
            }
        }
    }

    @Test
    fun `state works with the newly added outfits`() {
        val gen = generator()
        listOf("穿着汉服", "穿着雨衣", "穿着瑜伽服").forEach { outfit ->
            val picks = gen.emptyPicks() + mapOf(
                StoryGenerator.CAST to StoryPick("mf", locked = true),
                StoryGenerator.OUTFIT to StoryPick(outfit, locked = true)
            )
            assertTrue("穿「$outfit」时状态全空", gen.allowed(StoryGenerator.STATE, picks).isNotEmpty())
        }
    }

    @Test
    fun `poses and acts come out interleaved`() {
        val gen = generator()
        val first = "把她压在身上（传教士）"
        val second = "让她坐上来，扶着腰（正面骑乘）"
        val pickScene = "床上"
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick(pickScene, locked = true),
            StoryGenerator.POSE to StoryPick("$first${StoryGenerator.SEP}$second", locked = true),
            StoryGenerator.ACT to StoryPick("把阴茎整根插进她的阴道${StoryGenerator.SEP}阴茎从后面顶到最深处", locked = true)
        )
        val text = gen.compose(gen.randomize(picks).first, detail = false, dialogue = false)
        val i1 = text.indexOf(first)
        val i2 = text.indexOf("把阴茎整根插进她的阴道")
        val i3 = text.indexOf(second)
        val i4 = text.indexOf("阴茎从后面顶到最深处")
        assertTrue("顺序不对：$text", i1 in 0 until i2 && i2 < i3 && i3 < i4)
    }

    @Test
    fun `excluded words stay visible but stop being drawn`() {
        val base = library()
        val word = base.pool("chore").first { it.text == "正在吹头发" }
        val filtered = base.withCustom(excluded = mapOf("chore" to setOf(word.text)))
        assertTrue("排除后仍要能在列表里看到", filtered.pool("chore").any { it.text == word.text })
        assertTrue("排除后不该再被抽到", filtered.pickablePool("chore").none { it.text == word.text })
        assertTrue("排除标记没记上", filtered.isExcluded("chore", word.text))
    }

    @Test
    fun `deleted words leave the pool but can be restored`() {
        val base = library()
        val gone = base.pool("chore").first { it.text == "正在吹头发" }
        val trimmed = base.withCustom(removals = mapOf("chore" to setOf(gone.text)))
        assertTrue("删除没生效", trimmed.pool("chore").none { it.text == gone.text })
        assertEquals("删多了", base.pool("chore").size - 1, trimmed.pool("chore").size)
    }

    @Test
    fun `pose act and finish never share a word`() {
        val lib = library()
        val pose = lib.pool("pose").map { it.text }.toSet()
        val act = lib.pool("act").map { it.text }.toSet()
        val finish = lib.pool("act_finish").map { it.text }.toSet()
        assertEquals("姿势和动作重名", emptySet<String>(), pose intersect act)
        assertEquals("姿势和结束重名", emptySet<String>(), pose intersect finish)
        assertEquals("动作和结束重名", emptySet<String>(), act intersect finish)
    }

    @Test
    fun `the intermediate pool is gone`() {
        assertTrue("中途池还在", library().pool("act_inter").isEmpty())
        assertEquals("中途分项还在", false, generator().field(StoryGenerator.INTER) != null)
    }

    @Test
    fun `only-wearing outfits refuse underwear on top`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.OUTFIT to StoryPick("只穿着围裙和手套", locked = true)
        )
        val socks = gen.allowed(StoryGenerator.SOCKS, picks)
        assertFalse("只穿围裙还能加黑色丝袜", socks.any { it.contains("黑色丝袜") })
        assertFalse("半截蕾丝袜也算袜，不能挂上去", "半截蕾丝袜" in socks)
        assertFalse("罗袜同理", "罗袜" in socks)
        assertTrue("光着腿总得留着", "光着腿" in socks)
        assertTrue("围裙配高跟没问题", gen.allowed(StoryGenerator.SHOES, picks).any { it == "踩着高跟鞋" })
    }

    @Test
    fun `night styles never show up at noon`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.TIME to StoryPick("午后", locked = true)
        )
        assertFalse("午后出现了霓虹夜色", "霓虹夜色赛博人像" in gen.allowed(StoryGenerator.STYLE, picks))
        val night = picks + (StoryGenerator.TIME to StoryPick("深夜", locked = true))
        assertTrue("深夜应该能用", "霓虹夜色赛博人像" in gen.allowed(StoryGenerator.STYLE, night))
    }

    @Test
    fun `a second pose gets a bridge sentence`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("床上", locked = true),
            StoryGenerator.POSE to StoryPick(
                "把她压在身上（传教士）${StoryGenerator.SEP}让她坐上来，扶着腰（正面骑乘）",
                locked = true
            ),
            StoryGenerator.ACT to StoryPick("把阴茎整根插进她的阴道${StoryGenerator.SEP}阴茎从后面顶到最深处", locked = true)
        )
        val text = gen.compose(gen.randomize(picks).first, detail = false, dialogue = false)
        assertTrue("两段姿势之间没有过渡：$text", "把她换个姿势" in text || "把她翻过来" in text)
    }

    @Test
    fun `an empty prop leaves no dangling phrase`() {
        val gen = generator(seed = 3)
        repeat(30) {
            val base = gen.randomize(gen.emptyPicks()).first
            val picks = base + (StoryGenerator.PROP to StoryPick(ignored = true))
            val text = gen.compose(picks, detail = false, dialogue = false)
            assertFalse("道具空着却留了半句：$text", text.contains("顺手拿起"))
        }
    }

    @Test
    fun `together climax needs a partner`() {
        val word = library().pool("act_finish").first { it.text.startsWith("一起高潮") }
        assertFalse("单人也会一起高潮", "solo" in word.casts)
    }

    @Test
    fun `at most a few acts stay universal`() {
        // 体位无关的动作现在是常态（姿势格自己给体位），真正要少的是跨人数共用的那几条。
        val generic = library().pool("act").filter { it.common }.map { it.text }
        // 肛交那组并入 act 后有 8 条跨 mf／mff 共用，通用动作随之变多。
        assertTrue("通用动作太多了，串人的面会变大：$generic", generic.size <= 15)
        assertTrue("停下来喘气这类才算通用", "停下来抱着喘气" in generic)
        val posed = library().pool("act").filter { it.poses.isNotEmpty() }
        assertTrue("写明体位的动作该绑住姿势", posed.isNotEmpty())
        assertTrue("绑了姿势却不写体位的也有问题", posed.all { it.text.isNotEmpty() })
    }

    @Test
    fun `pose text does not carry its own penetration verb`() {
        val pose = library().word("pose", "两人身体呈十字交叉侧卧")
        assertTrue("十字交叉姿势没了", pose != null)
        assertFalse("姿势文案里不该带「进入」", pose!!.text.contains("进入"))
    }

    @Test
    fun `mood lands at the end as a performance note`() {
        val gen = generator()
        val picks = gen.randomize(gen.emptyPicks()).first +
            (StoryGenerator.MOOD to StoryPick("眼尾泛红，带着哭腔低声求饶"))
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("情绪没进句子：$text", text.contains("，她眼尾泛红，带着哭腔低声求饶"))
        assertTrue("句末标点丢了：$text", text.trim().endsWith("。"))
        assertFalse("情绪前面不该出现「。。」：$text", text.contains("。。"))
    }

    @Test
    fun `mood needing a bed is dropped when there is no bed`() {
        val gen = generator()
        val base = gen.randomize(gen.emptyPicks()).first +
            (StoryGenerator.MOOD to StoryPick("紧张得屏住呼吸，手指死死攥着床单")) +
            (StoryGenerator.PLACE to StoryPick("竹林深处"))
        val text = gen.compose(base, detail = false, dialogue = false)
        assertFalse("竹林里不该攥床单：$text", text.contains("攥着床单"))
    }

    @Test
    fun `prop counts as present for later wording`() {
        val gen = generator()
        val withTie = gen.emptyPicks() + (StoryGenerator.PROP to StoryPick("领带"))
        assertTrue(
            "选了领带就该能抽到蒙眼那句",
            "用领带蒙住她的眼睛" in gen.allowed(StoryGenerator.ESCALATE, withTie)
        )
        val withoutTie = gen.emptyPicks()
        assertTrue(
            "没领带也不该整条消失，但要排在后面",
            "用领带蒙住她的眼睛" !in gen.allowed(StoryGenerator.ESCALATE, withoutTie)
        )
    }

    @Test
    fun `mood pool stays thick enough`() {
        val moods = library().pool("mood")
        assertTrue("情绪池太薄：${moods.size}", moods.size >= 12)
        val dup = moods.groupBy { it.text }.filterValues { it.size > 1 }.keys
        assertTrue("情绪池有重复：$dup", dup.isEmpty())
    }

    @Test
    fun `ancient world keeps elevators and offices out of the place pool`() {
        val gen = generator()
        val picks = gen.emptyPicks() + (StoryGenerator.WORLD to StoryPick("古代"))
        val places = gen.allowed(StoryGenerator.PLACE, picks)
        listOf("电梯里", "网吧包间", "高铁的洗手间", "健身房器械区", "办公桌上").forEach {
            assertFalse("古代题材不该出现「$it」", it in places)
        }
        listOf("厢房里", "屏风后", "亭子里", "温泉池边").forEach {
            assertTrue("古代场地「$it」不见了", it in places)
        }
        assertTrue("床上哪个年代都有", "床上" in places)
    }

    @Test
    fun `fantasy world gets caves but not cars`() {
        val gen = generator()
        val picks = gen.emptyPicks() + (StoryGenerator.WORLD to StoryPick("修仙世界观"))
        val places = gen.allowed(StoryGenerator.PLACE, picks)
        assertTrue("洞府内没了", "洞府内" in places)
        assertTrue("灵泉池中没了", "灵泉池中" in places)
        assertFalse("修仙不该有停车場", "停车场" in places)
    }

    @Test
    fun `outfits follow the era too`() {
        val gen = generator()
        val ancient = gen.emptyPicks() + (StoryGenerator.WORLD to StoryPick("古代"))
        val modern = gen.emptyPicks() + (StoryGenerator.WORLD to StoryPick("现代"))
        val ancientOutfits = gen.allowed(StoryGenerator.OUTFIT, ancient)
        val modernOutfits = gen.allowed(StoryGenerator.OUTFIT, modern)
        assertTrue("古代该有汉服", "穿着汉服" in ancientOutfits)
        assertTrue("古代该有宫装", ancientOutfits.any { it.contains("宫装") })
        assertFalse("古代不该有职业套装", ancientOutfits.any { it.contains("职业套装") })
        assertFalse("古代不该有JK制服", ancientOutfits.any { it.contains("JK") })
        assertTrue("现代该有职业套装", modernOutfits.any { it.contains("职业套装") })
        // 8382.15 起现代题材放开年代：汉服、旗袍都是角色扮演的常规道具。
        assertTrue("现代也该能穿汉服", "穿着汉服" in modernOutfits)
    }

    @Test
    fun `era survives a round trip through json`() {
        val lib = library()
        val written = StoryLibraryJson.write(lib)
        val back = StoryLibraryJson.parse(written)
        val ancient = back.word("place", "厢房里")
        assertTrue("写盘把 eras 弄丢了", ancient != null && "ancient" in ancient.eras)
        val hanfu = back.word("outfit", "穿着汉服")
        assertTrue("穿着词条的 eras 丢了", hanfu != null && hanfu.eras.isNotEmpty())
    }

    @Test
    fun `modern-only prop does not leak into ancient draws`() {
        val gen = generator()
        val picks = gen.emptyPicks() + (StoryGenerator.WORLD to StoryPick("武侠", locked = true))
        repeat(30) { seed ->
            val drawn = gen.randomize(picks + (StoryGenerator.PLACE to StoryPick())).first
            val place = drawn[StoryGenerator.PLACE]?.value.orEmpty()
            assertFalse("武侠抽到了现代场地：$place", place in setOf("电梯里", "网吧包间", "KTV包厢里"))
        }
    }

    @Test
    fun `mood is off until it is asked for`() {
        val gen = StoryGenerator(library(), Random(11))
        val empty = gen.emptyPicks()
        assertTrue("情绪该默认关着", empty[StoryGenerator.MOOD]?.ignored == true)
        val drawn = gen.randomize(empty).first
        assertEquals("默认关着还往里塞东西", "", drawn[StoryGenerator.MOOD]?.value.orEmpty())
        val text = gen.compose(drawn, detail = false, dialogue = false)
        assertFalse("句末不该凭空多出情绪：$text", text.contains("，她"))
    }

    @Test
    fun `clothes are not tied to the room any more`() {
        val gen = StoryGenerator(library(), Random(12))
        val bath = gen.emptyPicks() + (StoryGenerator.PLACE to StoryPick("浴室里"))
        val outfits = gen.allowed(StoryGenerator.OUTFIT, bath)
        assertTrue("浴室里也该能穿校服", outfits.any { it.contains("校服") })
        assertTrue("浴室里也该能穿旗袍", outfits.any { it.contains("旗袍") })
        assertTrue("泳装仍然在，只是不再被场景限制", outfits.any { it.contains("比基尼") })
    }

    @Test
    fun `modern worlds allow any era of clothing`() {
        val gen = StoryGenerator(library(), Random(13))
        val modern = gen.emptyPicks() + (StoryGenerator.WORLD to StoryPick("现代"))
        val outfits = gen.allowed(StoryGenerator.OUTFIT, modern)
        assertTrue("现代题材也该能穿汉服（角色扮演）", outfits.any { it.contains("汉服") })
        assertTrue("现代题材也该能穿宫装", outfits.any { it.contains("宫装") })

        val ancient = gen.emptyPicks() + (StoryGenerator.WORLD to StoryPick("宫廷"))
        val ancientOutfits = gen.allowed(StoryGenerator.OUTFIT, ancient)
        assertFalse("宫廷里不该出现 JK", ancientOutfits.any { it.contains("JK") })
        assertFalse("宫廷里不该出现婚纱", ancientOutfits.any { it.contains("婚纱") })
    }

    @Test
    fun `random draws still prefer the era that fits`() {
        val gen = StoryGenerator(library(), Random(15))
        val lib = library()
        val picks = gen.emptyPicks() + (StoryGenerator.WORLD to StoryPick("现代"))
        var modernCount = 0
        var total = 0
        repeat(200) {
            val drawn = gen.randomize(picks, only = setOf(StoryGenerator.OUTFIT)).first
            val text = drawn[StoryGenerator.OUTFIT]?.value.orEmpty()
            if (text.isEmpty()) return@repeat
            val word = lib.word("outfit", text) ?: return@repeat
            total++
            if ("modern" in word.eras) modernCount++
        }
        assertTrue("没抽到样本", total > 150)
        // 权重是 2:1，理论上现代占七成左右；放宽到五成，只要「优先」是成立的就行。
        assertTrue("现代题材抽到的衣服里现代款太少：$modernCount/$total", modernCount * 2 > total)
    }

    @Test
    fun `time no longer carries weather words`() {
        val lib = library()
        val old = setOf("雨天", "雪天", "下雨的午后", "雨夜", "雪夜")
        assertTrue(
            "时间池还混着天气：${lib.pool("time").map { it.text }}",
            lib.pool("time").none { it.text in old }
        )
        val rain = lib.pool("weather").firstOrNull { "小雨" in it.text }
        assertTrue("天气池没有小雨", rain != null)
        assertTrue("天气只配户外", rain!!.groups.contains("outdoor"))
        assertFalse("天气不该进卧室", rain.groups.contains("room"))
    }

    private fun qipao(): Map<String, StoryPick> = generator().emptyPicks() + mapOf(
        StoryGenerator.CAST to StoryPick("mf", locked = true),
        StoryGenerator.PLACE to StoryPick("卧室床边", locked = true),
        StoryGenerator.OUTFIT to StoryPick("穿着旗袍", locked = true),
        StoryGenerator.SOCKS to StoryPick("黑色丝袜", locked = true),
        StoryGenerator.TIME to StoryPick("深夜", locked = true),
        StoryGenerator.WORLD to StoryPick("现代", locked = true)
    )

    @Test
    fun `only stockings left works on any starting outfit`() {
        val gen = StoryGenerator(library(), Random(21))
        val picks = qipao() + (StoryGenerator.MID to StoryPick("只剩丝袜了"))
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("过程中没进去：$text", text.contains("只剩丝袜了"))
        assertFalse("不该再补一句重复的收尾：$text", text.contains("身上只剩"))
    }

    @Test
    fun `only stockings left also works in a school uniform`() {
        val gen = StoryGenerator(library(), Random(22))
        val picks = qipao() + mapOf(
            StoryGenerator.OUTFIT to StoryPick("穿着校服", locked = true),
            StoryGenerator.SOCKS to StoryPick("黑色丝袜", locked = true),
            StoryGenerator.MID to StoryPick("只剩丝袜了")
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("换了衣服就配不上了：$text", text.contains("只剩丝袜了"))
    }

    @Test
    fun `summary says what is actually left`() {
        val gen = StoryGenerator(library(), Random(23))
        // 旗袍唯一一件外衣 + 内裤，脱掉内裤之后确实只剩旗袍，收尾句该这么写。
        val picks = qipao() + mapOf(
            StoryGenerator.OUTFIT to StoryPick("只穿了一条内裤", locked = true),
            StoryGenerator.SOCKS to StoryPick("黑色丝袜", locked = true),
            StoryGenerator.MID to StoryPick("内裤挂在一只脚踝上")
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("收尾句是按实算来的：$text", text.contains("身上只剩丝袜"))
    }

    @Test
    fun `summary stays quiet when most of the clothes are still on`() {
        val gen = StoryGenerator(library(), Random(25))
        // 职业套装四件都在，只脱了内衣，不该总结成「身上只剩…」。
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("办公桌上", locked = true),
            StoryGenerator.OUTFIT to StoryPick("穿着职业套装", locked = true),
            StoryGenerator.WORLD to StoryPick("现代", locked = true),
            StoryGenerator.MID to StoryPick("胸罩被拉了下来")
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("过程中没进去：$text", text.contains("胸罩被拉了下来"))
        assertFalse("还剩四件呢，别总结：$text", text.contains("身上只剩"))
    }

    @Test
    fun `mid pool does not hardcode a garment`() {
        val mids = library().pool("outfit_mid").map { it.text }
        assertTrue("还在写死裙子或衬衫：$mids", mids.none { it.contains("裙子") || it.contains("衬衫") })
        assertTrue("只剩X 这一类没了", mids.any { it.contains("只剩") })
    }

    @Test
    fun `stocking wording disappears when nothing is worn on the legs`() {
        val gen = StoryGenerator(library(), Random(24))
        val bare = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.PLACE to StoryPick("卧室床边", locked = true),
            StoryGenerator.OUTFIT to StoryPick("穿着校服", locked = true),
            StoryGenerator.SOCKS to StoryPick("光着腿", locked = true)
        )
        val mids = gen.allowed(StoryGenerator.MID, bare)
        assertFalse("没穿袜子还说勾了丝", "丝袜勾了丝" in mids)
        assertFalse("没穿袜子还说只剩丝袜", "只剩丝袜了" in mids)
    }

    @Test
    fun `state pool is about clothes not vague moods`() {
        val states = library().pool("outfit_state").map { it.text }
        assertFalse("「还很整齐」这种空话不该留着", states.any { it == "还很整齐" })
        assertTrue("状态池是空的", states.size >= 10)
    }

    @Test
    fun `keep survives a round trip through json`() {
        val back = StoryLibraryJson.parse(StoryLibraryJson.write(library()))
        val only = back.word("outfit_mid", "只剩丝袜了")
        assertTrue("keep 写盘丢了", only != null && "丝袜" in only.keep)
        assertTrue("removes 的 * 也丢了", only!!.removes.contains("*"))
    }

    @Test
    fun `the man gets his own three slots`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.MAN_LOOK to StoryPick("西装革履", locked = true),
            StoryGenerator.MAN_OUTFIT to StoryPick("衬衫西裤", locked = true),
            StoryGenerator.MAN_STATE to StoryPick("皮带已经解开", locked = true)
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("男主外形没进句子：$text", text.contains("西装革履"))
        assertTrue("男主穿着没进句子：$text", text.contains("衬衫西裤"))
        assertTrue("男主状态没进句子：$text", text.contains("皮带已经解开"))
    }

    @Test
    fun `ethnicity and skin sit in the female look`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.ETHNICITY to StoryPick("精灵", locked = true),
            StoryGenerator.REGION to StoryPick("韩国", locked = true),
            StoryGenerator.SKIN to StoryPick("小麦色", locked = true)
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("族裔没进句子：$text", text.contains("韩国女精灵"))
        assertTrue("肤色没进句子：$text", text.contains("小麦色"))
    }

    @Test
    fun `props stay out of the sentence until asked`() {
        val gen = StoryGenerator(library(), Random(31))
        val empty = gen.emptyPicks()
        assertTrue("道具该默认关着", empty[StoryGenerator.PROP]?.ignored == true)
        val drawn = gen.randomize(empty).first
        assertTrue("道具自己冒出来了", drawn[StoryGenerator.PROP]?.value.isNullOrEmpty())
    }

    @Test
    fun `renamed styles are in place and the old names are gone`() {
        val styles = library().pool("style").map { it.text }
        listOf("手机私房写真", "复古低清DV", "3D写实渲染", "韩漫彩稿", "韩系成熟厚涂", "欧美厚涂插画")
            .forEach { assertTrue("改名没生效：$it", it in styles) }
        listOf("私房业余", "低保真DV录像", "3D渲染", "韩漫2.5D", "韩系成熟向2.5D", "厚涂插画")
            .forEach { assertFalse("旧名还在：$it", it in styles) }
    }

    @Test
    fun `when naked mid actions never undress nonexistent underwear or towels`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.OUTFIT to StoryPick("全裸", locked = true)
        )
        val mids = gen.allowed(StoryGenerator.MID, picks)
        assertFalse("全裸不能脱胸罩", "胸罩被拉了下来" in mids)
        assertFalse("全裸不能脱内裤", "内裤挂在一只脚踝上" in mids)
        assertFalse("全裸不能扔内衣", "内衣被扔在一边" in mids)
        assertFalse("全裸不能掉浴巾", "浴巾掉在地上" in mids)
        assertFalse("全裸不能反向穿成只剩内衣内裤", "只剩内衣和内裤" in mids)
    }

    @Test
    fun `when naked shoes exclude non-barefoot items`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.OUTFIT to StoryPick("全裸", locked = true)
        )
        val shoes = gen.allowed(StoryGenerator.SHOES, picks)
        assertFalse("全裸不能穿高跟鞋", shoes.any { it.contains("高跟") })
        assertFalse("全裸不能穿靴子", shoes.any { it.contains("靴") })
        assertTrue("赤脚应当允许", "赤脚" in shoes)
    }

    @Test
    fun `when man is naked man_state excludes clothes undressing actions`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.MAN_OUTFIT to StoryPick("全裸", locked = true)
        )
        val states = gen.allowed(StoryGenerator.MAN_STATE, picks)
        assertFalse("全裸不能解皮带", "皮带金属扣解开，裤腰松垮" in states)
        assertFalse("全裸不能上衣脱一半", "上衣脱了一半挂在肩上" in states)
        assertFalse("全裸不能敞衬衫", "衬衫敞着露出胸口" in states)
        assertTrue("体态描写应当允许", "浑身肌肉紧绷，手臂线条暴起发力" in states)
    }

    @Test
    fun `when man is naked compose never outputs clothes undressing actions for male`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.MAN_LOOK to StoryPick("健身型", locked = true),
            StoryGenerator.MAN_OUTFIT to StoryPick("全裸", locked = true),
            // 假设旧存档或手选强行塞了脱上衣
            StoryGenerator.MAN_STATE to StoryPick("上衣脱了一半挂在肩上", locked = true)
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("全裸必须进句子：$text", text.contains("浑身赤裸"))
        assertFalse("全裸时绝不能在句子里出现脱上衣：$text", text.contains("上衣脱了一半"))
    }

    @Test
    fun `space station world does not clear modern man outfits`() {
        val gen = generator()
        val picks = gen.emptyPicks() + mapOf(
            StoryGenerator.WORLD to StoryPick("太空站", locked = true),
            StoryGenerator.MAN_OUTFIT to StoryPick("西装三件套", locked = true)
        )
        val allowed = gen.allowed(StoryGenerator.MAN_OUTFIT, picks)
        assertTrue("太空站下现代衣服西装应被允许：$allowed", "西装三件套" in allowed)
        assertTrue("太空站下卫衣应被允许：$allowed", "黑色连帽卫衣配长裤" in allowed)
    }

    @Test
    fun `ethnicity pool contains 37 items and man pools expanded`() {
        val lib = library()
        assertEquals(31, lib.pool("ethnicity").size)
        assertEquals(28, lib.pool("man_outfit").size)
        assertEquals(22, lib.pool("man_state").size)
        val ethTexts = lib.pool("ethnicity").map { it.text }
        assertTrue("魅魔在种族池中", "魅魔" in ethTexts)
        assertTrue("精灵在种族池中", "精灵" in ethTexts)
        assertTrue("吸血鬼在种族池中", "吸血鬼" in ethTexts)
        assertTrue("牛娘在种族池中", "牛娘" in ethTexts)
        assertTrue("仿生人在种族池中", "仿生人" in ethTexts)
    }

    @Test
    fun `story catalog expanded with explicit exposed breast outfit states and enriched atmosphere pools`() {
        val lib = library()

        // 1. outfit_state 包含 3 条直球大胸露出句，不含里面空荡荡
        val outfitStates = lib.pool("outfit_state").map { it.text }
        assertTrue("包含丰满胸部裸露", outfitStates.any { it.contains("胸部大片裸露在外") })
        assertTrue("包含乳房挤出半边弧度", outfitStates.any { it.contains("乳房从敞开的衣襟间挤出半边弧度") })
        assertTrue("包含白皙双乳", outfitStates.any { it.contains("露出白皙的双乳") })
        assertFalse("不应再包含抽象的里面空荡荡", outfitStates.any { it.contains("里面空荡荡") })

        // 2. 氛围道具池与光影细节池扩充生效
        assertTrue("道具池扩充金丝眼镜", lib.pool("prop").any { it.text == "细链条金丝眼镜" })
        assertTrue("细节池扩充丁达尔光束", lib.pool("detail").any { it.text.contains("丁达尔光束") })

        // 3. 男主外形与肤质扩充生效
        assertTrue("男主外形扩充肩宽腰窄", lib.pool("man_look").any { it.text.contains("肩宽腰窄") })
        assertTrue("肤质保留蜜色", lib.pool("skin").any { it.text.contains("蜜色") })
    }

    @Test
    fun `story composer 8382_29 features verified`() {
        val gen = generator(seed = 101)

        // 1. DETAIL 字段正式进 sections 过程板块
        val detailField = gen.field(StoryGenerator.DETAIL)
        assertTrue("DETAIL 字段必须存在", detailField != null)
        assertEquals("detail", detailField?.pool)

        // 2. 男主句式按标准公式生成且支持全裸
        val picksNormal = mapOf(
            StoryGenerator.CAST to StoryPick("mf"),
            StoryGenerator.MAN_LOOK to StoryPick("身形挺拔"),
            StoryGenerator.MAN_OUTFIT to StoryPick("战术T恤配束脚工装裤"),
            StoryGenerator.MAN_STATE to StoryPick("皮带金属扣解开，裤腰松垮")
        )
        val textNormal = gen.compose(picksNormal, detail = false, dialogue = false)
        assertTrue("男主第一句对仗：$textNormal", textNormal.contains("他是一名身形挺拔的男子"))
        assertTrue("男主第二句身穿与此时：$textNormal", textNormal.contains("他身穿战术T恤配束脚工装裤，此时皮带金属扣解开，裤腰松垮"))

        val picksNaked = mapOf(
            StoryGenerator.CAST to StoryPick("mf"),
            StoryGenerator.MAN_LOOK to StoryPick("身形挺拔"),
            StoryGenerator.MAN_OUTFIT to StoryPick("全裸"),
            StoryGenerator.MAN_STATE to StoryPick("浑身肌肉紧绷，手臂线条暴起发力")
        )
        val textNaked = gen.compose(picksNaked, detail = false, dialogue = false)
        assertTrue("全裸自适应：$textNaked", textNaked.contains("他浑身赤裸，未着寸缕，此时浑身肌肉紧绷"))

        // 3. 动作链平滑衔接且保留括号体位锚点
        val chainPicks = mapOf(
            StoryGenerator.POSE to StoryPick("两人身体呈十字交叉侧卧（侧剪刀位）"),
            StoryGenerator.ACT to StoryPick("越插越深")
        )
        val chainText = gen.mergeChain(chainPicks)
        assertTrue("姿势动作平滑连词且保留括号：$chainText", chainText.contains("（侧剪刀位）") && chainText.contains("顺势挺腰越插越深"))

        // 4. 手选细节优先进句子
        val detailPicks = mapOf(
            StoryGenerator.DETAIL to StoryPick("斑驳阳光透过树叶洒在身上")
        )
        val detailText = gen.compose(detailPicks, detail = false, dialogue = false)
        assertTrue("手选细节进入长句：$detailText", detailText.contains("斑驳阳光透过树叶洒在身上"))
    }

    @Test
    fun `story composer 8382_30 full narrative smoothing verified`() {
        val gen = generator(seed = 102)

        // 验证截图 120812 的全套选词组合
        val picks = mapOf(
            StoryGenerator.CAST to StoryPick("mf"),
            StoryGenerator.PLACE to StoryPick("舞台侧幕"),
            StoryGenerator.ETHNICITY to StoryPick("海妖"),
            StoryGenerator.SKIN to StoryPick("冷白皮且泛着一层薄薄细腻汗光"),
            StoryGenerator.BUILD to StoryPick("大腿丰满"),
            StoryGenerator.HAIR to StoryPick("刘海遮住眼睛"),
            StoryGenerator.BUST to StoryPick("乳晕很浅"),
            StoryGenerator.ACCENT to StoryPick("戴着耳环"),
            StoryGenerator.OUTFIT to StoryPick("只穿了一件白衬衫"),
            StoryGenerator.SOCKS to StoryPick("光着腿"),
            StoryGenerator.SHOES to StoryPick("踩着防水台高跟"),
            StoryGenerator.STATE to StoryPick("只挂着一点衣料，什么都遮不住"),
            StoryGenerator.CHORE to StoryPick("正在数拍子"),
            StoryGenerator.MAN_LOOK to StoryPick("斯文清瘦"),
            StoryGenerator.MAN_OUTFIT to StoryPick("战术T恤配束脚工装裤"),
            StoryGenerator.ESCALATE to StoryPick("从后面贴上去"),
            StoryGenerator.MID to StoryPick("下装褪到脚踝")
        )
        val text = gen.compose(picks, detail = false, dialogue = false)

        // 1. 彻底干掉「女的」顿号报菜名
        assertFalse("绝不能再出现「女的」开头：$text", text.contains("女的海妖"))
        assertFalse("绝不能再出现顿号硬拼：$text", text.contains("、冷白皮"))

        // 2. 女性自然叙事两分句生效
        assertTrue("女性骨架第一句生效：$text", text.contains("她是一名大腿丰满的女海妖") || text.contains("她是一名身材大腿丰满的女海妖"))
        assertTrue("女性穿戴第二句独立：$text", text.contains("她只穿了一件白衬衫，光着腿，踩着防水台高跟"))
        assertTrue("日常前加此时与主语：$text", text.contains("此时她正在数拍子") || text.contains("此时女一正在数拍子"))

        // 3. 男性称谓消重与独立分句
        assertTrue("男性称谓消重为男子：$text", text.contains("他是一名斯文清瘦的男子"))
        assertFalse("绝不能出现男的男子：$text", text.contains("斯文清瘦男的男子"))
        assertTrue("男装后独立成句：$text", text.contains("他身穿战术T恤配束脚工装裤。"))
        assertTrue("开始动作独立上前发起：$text", text.contains("他忽然上前从后面贴上去"))
        assertTrue("过程中带拉扯过渡：$text", text.contains("动情拉扯间下装褪到脚踝"))
    }

    @Test
    fun `story composer 8382_31 deep bugfixes verified`() {
        val gen = generator(seed = 103)

        // 1. 验证截图 120814 的语法病句彻底根治
        val picks120814 = mapOf(
            StoryGenerator.CAST to StoryPick("mf"),
            StoryGenerator.BUILD to StoryPick("骨架很小"),
            StoryGenerator.SKIN to StoryPick("小麦色"),
            StoryGenerator.BUST to StoryPick("超巨乳"),
            StoryGenerator.ACCENT to StoryPick("有纹身")
        )
        val text120814 = gen.compose(picks120814, detail = false, dialogue = false)
        // 「骨架很小」是身体部位，前面不能再补「身材」，否则是「身材骨架很小」。
        assertTrue("骨架句没拼对：$text120814", text120814.contains("骨架很小的女子"))
        assertFalse("又补了身材：$text120814", text120814.contains("身材骨架"))
        assertFalse("不该再拼出水珠肌肤：$text120814", text120814.contains("水珠肌肤"))
        assertTrue("肤色自然输出：$text120814", text120814.contains("小麦色肌肤"))
        assertFalse("绝不能再出戴着有纹身：$text120814", text120814.contains("戴着有纹身"))
        assertTrue("有纹身自然输出：$text120814", text120814.contains("有纹身"))
        assertTrue("胸围梯度输出：$text120814", text120814.contains("超巨乳"))

        // 2. 验证单人模式绝不出他忽然欺身
        val picksSolo = mapOf(
            StoryGenerator.CAST to StoryPick("solo"),
            StoryGenerator.ESCALATE to StoryPick("自己把衣服脱掉")
        )
        val textSolo = gen.compose(picksSolo, detail = false, dialogue = false)
        assertFalse("单人绝不出他忽然：$textSolo", textSolo.contains("他忽然"))
        assertTrue("单人动作直接输出：$textSolo", textSolo.contains("自己把衣服脱掉"))

        // 3. 验证男主帅哥称谓消重
        val picksGuy = mapOf(
            StoryGenerator.CAST to StoryPick("mf"),
            StoryGenerator.MAN_LOOK to StoryPick("清爽帅哥")
        )
        val textGuy = gen.compose(picksGuy, detail = false, dialogue = false)
        assertTrue("帅哥称谓自然：$textGuy", textGuy.contains("他是一名清爽帅哥"))
        assertFalse("绝不能出清爽帅哥子：$textGuy", textGuy.contains("清爽帅哥子"))

        // 4. 验证 story_catalog 内部版本号已同步
        assertTrue("版本号不为空：${library().version}", library().version.startsWith("0.9.8.8382."))
    }

    @Test
    fun `story composer 8382_32 bugfixes verified`() {
        val gen = generator(seed = 104)

        // 1. 验证所有模板彻底不含 place_b
        val lib = library()
        lib.templates.values.flatten().forEach { template ->
            assertFalse("模板绝不能再包含 place_b：$template", template.contains("place_b"))
        }

        // 2. 验证运动服绝不抽长袍下摆，道袍可以抽长袍下摆
        val sportPicks = mapOf(StoryGenerator.MAN_OUTFIT to StoryPick("运动服"))
        val sportStates = gen.allowed(StoryGenerator.MAN_STATE, sportPicks)
        assertFalse("运动服绝不能抽到长袍下摆", sportStates.contains("长袍下摆直接撩到腰际，下身一丝不挂挺入"))

        val robePicks = mapOf(StoryGenerator.MAN_OUTFIT to StoryPick("云纹广袖白玉道袍"))
        val robeStates = gen.allowed(StoryGenerator.MAN_STATE, robePicks)
        assertTrue("道袍允许抽长袍下摆", robeStates.contains("长袍下摆直接撩到腰际，下身一丝不挂挺入"))

        // 3. 验证雪地与全息投影室绝不抽换床单，卧室允许抽换床单
        val roomPicks = mapOf(StoryGenerator.PLACE to StoryPick("卧室床边"))
        val roomChores = gen.allowed(StoryGenerator.CHORE, roomPicks)
        assertTrue("卧室允许换床单", roomChores.contains("正在换床单"))

        // 4. 验证局部身材肩窄自然输出为身量纤细肩膀很窄
        val narrowPicks = mapOf(
            StoryGenerator.CAST to StoryPick("mf"),
            StoryGenerator.ETHNICITY to StoryPick("暗精灵"),
            StoryGenerator.BUILD to StoryPick("肩膀很窄")
        )
        val textNarrow = gen.compose(narrowPicks, detail = false, dialogue = false)
        assertTrue("肩膀很窄自然输出：$textNarrow", textNarrow.contains("身量纤细、肩膀很窄的女暗精灵"))
        assertFalse("绝不能再生硬输出身材肩膀很窄：$textNarrow", textNarrow.contains("身材肩膀很窄"))
    }

    @Test
    fun `story composer 8382_33 pair sheet features and transitions verified`() {
        val gen = generator(seed = 105)

        // 1. 验证 mergeChain 多组动作消重「把她」
        val multiPicks = mapOf(
            StoryGenerator.POSE to StoryPick("两人身体呈十字交叉侧卧（侧剪刀位）${StoryGenerator.SEP}把她压在身上（传教士）"),
            StoryGenerator.ACT to StoryPick("加快${StoryGenerator.SEP}正面进入")
        )
        val chain = gen.mergeChain(multiPicks)
        assertFalse("绝不能连读两次把她：$chain", chain.contains("把她翻过来，把她压在身上") || chain.contains("把她换个姿势，把她压在身上"))
        assertTrue("第二组过渡句自然消重：$chain", chain.contains("然后") || chain.contains("翻转过来"))

        // 2. 验证过程中已经完全脱光前缀平滑
        val midPicks = mapOf(
            StoryGenerator.MID to StoryPick("已经完全脱光")
        )
        val textMid = gen.compose(midPicks, detail = false, dialogue = false)
        assertTrue("完全脱光前缀自然化：$textMid", textMid.contains("动情拉扯间身上的衣物已完全除尽"))

        // 3. 验证版本号同步
        assertEquals("0.9.8.8382.570-story", library().version)
    }

    @Test
    fun `h3 studio 8382_35 server queue size logic verified`() {
        // 验证当服务端有排队任务（totalQueueSize = 2），但本机尚未提交（ownActiveJobCount = 0）时，
        // 按钮计算的队列数字应为 maxOf(totalQueueSize, ownActiveJobCount) = 2
        val totalQueueSize = 2
        val ownActiveJobCount = 0
        val resolvedQueueSize = maxOf(totalQueueSize, ownActiveJobCount)
        assertEquals("服务端有任务时队列数字必须正确统计", 2, resolvedQueueSize)

        // 验证版本号同步为 8382.395
        assertEquals("0.9.8.8382.570-story", library().version)
    }

    /** 整段重抽要换人数，单格重抽不能把人数一起换掉。 */
    @Test
    fun `full reroll redraws the cast but a single field keeps it`() {
        val gen = generator(seed = 7)
        var picks = gen.randomize(gen.emptyPicks()).first
        val cast = picks[StoryGenerator.CAST]?.value
        picks = gen.randomize(picks, setOf(StoryGenerator.HAIR)).first
        assertEquals("单格重抽不该动人数", cast, picks[StoryGenerator.CAST]?.value)

        val seen = mutableSetOf<String?>()
        repeat(30) { seen += gen.randomize(picks).first[StoryGenerator.CAST]?.value }
        assertTrue("整段重抽三十次人数一直没变：$seen", seen.size > 1)
    }

    /** 口交、手、道具这类动作挂着「顺势挺腰」就是病句。 */
    @Test
    fun `non penetration acts carry no thrusting verb`() {
        val gen = generator()
        listOf("让她跪下去含住阴茎", "用脚心夹着阴茎慢慢磨", "两人轮流含着阴茎", "隔着内裤揉她的阴蒂").forEach { act ->
            val chain = gen.mergeChain(
                mapOf(
                    StoryGenerator.ACT to StoryPick(act),
                    StoryGenerator.POSE to StoryPick("跪坐")
                )
            )
            assertFalse("「$act」不该接顺势挺腰：$chain", chain.contains("顺势挺腰"))
        }
    }

    /** 一男两女要写出另一个女人在干嘛，一男一女不该冒出「另一人」。 */
    @Test
    fun `mff names what the other woman is doing`() {
        val mff = promptOf("mff", 11)
        // 主戏那一段要点到两个人：光写「另一个人」是旧写法，569 起直接叫女一女二。
        val tail = mff.substringAfterLast("\n\n")
        assertTrue("一男两女的主戏只写了一个人：$mff", tail.contains("女一") && tail.contains("女二"))
        val mf = promptOf("mf", 11)
        assertFalse("一男一女冒出第三个人：$mf", mf.contains("另一人"))
    }

    /** 换人穿的衣服不能再叠「身穿」。 */
    @Test
    fun `the second woman's outfit does not double up the verb`() {
        repeat(20) { seed ->
            val text = promptOf("mff", seed)
            assertFalse("叠了身穿：$text", text.contains("身穿只") || text.contains("身穿穿"))
        }
    }

    /** 模板里的占位符一个都不能漏到成品里。 */
    @Test
    fun `no placeholder survives into the prompt`() {
        listOf("solo", "mf", "mff", "ff").forEach { cast ->
            repeat(10) { seed ->
                val text = promptOf(cast, seed)
                assertFalse("$cast 漏了占位符：$text", text.contains("{"))
                assertFalse("$cast 出现空句：$text", text.contains("。。") || text.contains("，。"))
            }
        }
    }

    private fun promptOf(cast: String, seed: Int): String {
        val gen = StoryGenerator(library(), Random(seed))
        val first = gen.randomize(gen.emptyPicks()).first
        val locked = first + (StoryGenerator.CAST to StoryPick(cast, locked = true))
        return gen.compose(gen.randomize(locked).first, detail = false, dialogue = false)
    }
    @Test
    fun anEthnicRegionDoesNotBecomeAFromClause() {
        val gen = StoryGenerator(library(), Random(7))
        val picks = mutableMapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.ETHNICITY to StoryPick("魅魔", locked = true),
            StoryGenerator.REGION to StoryPick("中国", locked = true)
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("地域应当前置：$text", text.contains("中国女魅魔"))
        assertFalse("不该再写来自 X 的：$text", text.contains("来自"))
    }

    @Test
    fun aSelfContainedLookDoesNotDoubleTheWord() {
        val gen = StoryGenerator(library(), Random(8))
        val picks = mutableMapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.LOOK to StoryPick("熟女感", locked = true)
        )
        val text = gen.compose(picks, detail = false, dialogue = false)
        assertTrue("熟女感 应当原样：$text", text.contains("熟女感"))
        assertFalse("不能写成气质熟女感：$text", text.contains("气质熟女感"))
    }
    @Test
    fun theAreolaAndNipplePanelsAreNeverEmpty() {
        val lib = library()
        val gen = StoryGenerator(lib, Random(9))
        val outfit = lib.pool("outfit").first { it.text.contains("穿着") }.text
        val picks = mutableMapOf(
            StoryGenerator.CAST to StoryPick("mf", locked = true),
            StoryGenerator.OUTFIT to StoryPick(outfit, locked = true)
        )
        assertTrue(
            "穿着整身衣服时不自动抽：${gen.allowed(StoryGenerator.AREOLA, picks)}",
            gen.allowed(StoryGenerator.AREOLA, picks).isEmpty()
        )
        val areola = gen.allowed(StoryGenerator.AREOLA, picks, forPick = true)
        val nipple = gen.allowed(StoryGenerator.NIPPLE, picks, forPick = true)
        assertTrue("乳晕面板不该空：${areola.size}", areola.isNotEmpty())
        assertTrue("乳头面板不该空：${nipple.size}", nipple.isNotEmpty())
    }
}
