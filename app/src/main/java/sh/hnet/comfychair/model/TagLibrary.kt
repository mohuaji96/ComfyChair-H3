package sh.hnet.comfychair.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * One slot of a category: an interchangeable pool of tags.
 *
 * [excluded] is a keep-but-do-not-randomize list, separate from deleting a tag.
 * Both survive export, so a round trip through a file never loses the distinction.
 */
data class TagSlot(
    val id: String,
    val label: String,
    val pool: List<String>,
    val excluded: List<String> = emptyList()
) {
    /** What the randomizer may draw from. Locked and manual values are not limited by this. */
    fun randomPool(): List<String> = if (excluded.isEmpty()) pool else pool.filterNot { it in excluded }
}

/** A display-only bucket for one slot's pool: 写实/摄影, 动漫·绘画, 三维渲染. */
data class StyleGroup(
    val label: String,
    val words: List<String>
)

data class TagCategory(
    val id: String,
    val label: String,
    /** How the slot values are stitched into one phrase: person / breast / nipple / of-color / "". */
    val join: String = "",
    val slots: List<TagSlot> = emptyList(),
    /** Section on the composer page, e.g. person / body / wear / scene. "" means ungrouped. */
    val group: String = "",
    /**
     * Draw priority, low runs first. A lower number may remove what a higher number put down
     * (a crop can hide a body part), never the other way round.
     */
    val tier: Int = 0,
    /**
     * Whose body this category describes: female / male / scene (everyone or none).
     *
     * Only data for now -- nothing reads it yet, so the draw is unchanged. It is the first step
     * of separating the two bodies in a 一男一女 scene, where "全裸" currently strips the woman's
     * clothes no matter which of them it was meant for.
     */
    val who: String = ""
)

/** A collapsible heading on the composer page. */
data class CategoryGroup(val id: String, val label: String)

/**
 * Selecting any tag in [whenAny] drops every category in [skip].
 *
 * [skipWords] is the narrow version: a cover state often wants to keep the category (so a bra
 * style can still be described) while ruling out a couple of words in it -- 只穿内衣 contradicts
 * 无胸罩, but not 蕾丝胸罩.
 */
data class TagSkipRule(
    val whenAny: List<String>,
    val skip: List<String>,
    val skipWords: List<String> = emptyList(),
    /**
     * 只认这些分类的选中值。
     *
     * 颜色词在下衣、袜子、鞋、发色里都有，规则只写「白色就关乳房形态」时，裸上身也会被
     * 下衣的颜色误触发。写上 from = ["top"] 才是「穿着上衣才遮」。
     */
    val from: List<String> = emptyList(),
    /** 这些词一旦被选中，规则不生效（外套敞开、衣衫滑到肩下）。 */
    val unless: List<String> = emptyList()
)

/**
 * 外层遮住了里层，里层就不抽。
 *
 * 白衬衫配胸罩是这类词库最容易出的错：两格各抽各的，谁也不知道对方存在。
 * visibleWhen 列的是「露出来」的那几种上衣，其余一律当遮住——列露出的是十几条，
 * 列遮住的是几十条，而且以后加新上衣默认落到遮住那边，不会漏。
 */
data class TagHiddenRule(
    val category: String,
    val coveredBy: String,
    val visibleWhen: List<String> = emptyList()
)

/**
 * Rules the flat mutex list cannot express.
 *
 * - [handCost] / [handCap]: how many hands a tag occupies, and the global budget.
 * - [posture]: standing / sitting / lying / kneeling; two different groups cannot co-exist.
 * - [requires]: a tag only makes sense when a related tag is already selected
 *   ("解开衬衫" needs a shirt).
 */
/**
 * The soft half of an anchor profile: pools the picker prefers once the main act is known.
 * Empty means "no preference", never "nothing allowed".
 */
data class SoftProfile(
    val express: List<String> = emptyList(),
    val gaze: List<String> = emptyList(),
    val fluids: List<String> = emptyList(),
    val camera: List<String> = emptyList(),
    val outfitState: List<String> = emptyList()
) {
    fun forCategory(categoryId: String): List<String> = when (categoryId) {
        "expression" -> express
        "gazeDir" -> gaze
        "fluids" -> fluids
        "camera" -> camera
        "outfitState" -> outfitState
        else -> emptyList()
    }
}

/** Which acts dominate a scene, and what each family of acts drags along with it. */
data class AnchorSet(
    val families: Map<String, SoftProfile> = emptyMap(),
    /** Act -> family id. */
    val of: Map<String, String> = emptyMap(),
    /** Acts that only decorate another act and must not anchor on their own. */
    val addOn: List<String> = emptyList()
) {
    fun soft(act: String): SoftProfile? = of[act]?.let { families[it] }
}

data class TagConstraints(
    val handCap: Int = 2,
    val handCost: Map<String, Int> = emptyMap(),
    val posture: Map<String, String> = emptyMap(),
    /** Location group; two different groups cannot co-exist, same as posture. */
    val place: Map<String, String> = emptyMap(),
    /** Where the subject looks. Two different directions cannot co-exist, same as posture. */
    val gaze: Map<String, String> = emptyMap(),
    /** How many mouths the scene can use at once; one is the honest default. */
    val mouthCap: Int = 1,
    val mouthCost: Map<String, Int> = emptyMap(),
    /** Head accessories compete with each other for the same face. */
    val headCap: Int = 99,
    val headCost: Map<String, Int> = emptyMap(),
    /**
     * Words that only work when the subject is alone in frame — 对镜自拍, 举手机自拍.
     *
     * They cannot sit next to an act that needs a second body. The list used to also hold hand
     * and head gestures (撩头发, 叉腰, 抱膝), which are things a partner can perfectly well be in
     * the room for; those blocks were wrong and were removed.
     */
    val soloPose: List<String> = emptyList(),
    /** 纯场景排布姿势（三人围坐、两人面对面站立）。动作自带体位时这些会打架。 */
    val scenePoses: List<String> = emptyList(),
    /** 发型里描述状态而非发型名的词（无刘海、中分、微卷）；「亚麻色的无刘海」不成句。 */
    val hairState: List<String> = emptyList(),
    /**
     * 一件式服装：长裙、长袍、礼服、连身制服。
     *
     * 它们整身包住，抽到就不该再抽下衣与内裤 —— 否则「婚纱 + 百褶裙」「幽冥圣女黑袍 + 迷你裙」。
     * 反过来，玩家锁了下衣或内裤时，这几件不参与抽取：锁住的格子一定是他要的。
     */
    val onePiece: List<String> = emptyList(),
    /** 下衣里的一件式（连体衣、西装套裙）：抽到就关掉上衣。 */
    val onePieceBottom: List<String> = emptyList(),
    /**
     * 只跟着玩法出现的道具：束缚绳只配捆绑、口塞球只配口塞。
     *
     * 不设这道闸，玩具格会自己抽出束缚绳，「全裸自慰，拿着束缚绳」——绳子没有用处，还和
     * 手部动作抢画面。值为空表示这个道具不需要玩法，随时可抽。
     */
    val toyActs: Map<String, List<String>> = emptyMap(),
    /** Groups of near-synonyms. Only one member of a group may be used per composition. */
    val synonyms: List<List<String>> = emptyList(),
    /** Pairs that must never appear together, e.g. 看镜头 with 背面视角. */
    val excludes: List<List<String>> = emptyList(),
    val requires: Map<String, List<String>> = emptyMap(),
    /**
     * Act -> the posture families it can physically happen in. This is a whitelist, unlike
     * [posture], which is a single-value co-existence key and therefore cannot express an act
     * that works kneeling *and* lying down.
     */
    val allowedPostures: Map<String, List<String>> = emptyMap(),

    /**
     * Word -> posture keywords it needs to coexist with.
     *
     * 从背后揉着她的乳房 only reads correctly when the body is turned away. Unlike
     * [allowedPostures], which maps an act to broad position classes, this matches the words that
     * actually appear in the chosen pose -- the vocabulary does not label orientation anywhere.
     */
    val postureHints: Map<String, List<String>> = emptyMap(),

    /**
     * Head placement: a word lists every place the head may be while it happens.
     *
     * 口交 needs the head at the groin and 仰头 turns it up, so their sets cannot overlap. That is
     * a geometry question the hand and mouth budgets cannot answer, and unlike [posture] it has to
     * allow several answers at once. Words absent from the table do not constrain the head.
     */
    val headPlacements: Map<String, List<String>> = emptyMap(),

    /**
     * What a place physically offers: 浴室 has water and a mirror, 教室 has neither.
     *
     * Absent means nothing at all. A shot that needs a water surface therefore cannot be drawn
     * in a classroom, and saying it here costs one table instead of a rule per pair.
     */
    val sceneProps: Map<String, List<String>> = emptyMap(),

    /** Props a word cannot exist without, e.g. 水面倒影 needs water. */
    val propNeeds: Map<String, List<String>> = emptyMap(),
    /** Poses that name a headcount outright; they require the crowd to match exactly. */
    val exactPeople: List<String> = emptyList(),
    /** Race -> the head features that carry it. An absent race leaves the slot unrestricted. */
    val raceFeatureHead: Map<String, List<String>> = emptyMap(),
    /** Race -> the body features that carry it. */
    val raceFeatureBody: Map<String, List<String>> = emptyMap(),
    /**
     * Race -> the half of its sentence before the person words, and the noun after them.
     *
     * 精灵 reads as 「修长尖耳，银白长发，清冷出尘的」+「二十八岁韩国可爱女」+「精灵」, so the
     * phrase has to be stored in two pieces instead of one finished string. A piece that ends
     * with ， calls for 年龄的; one that ends with 的 takes the age bare.
     */
    val raceLead: Map<String, String> = emptyMap(),
    val raceName: Map<String, String> = emptyMap(),
    /** Pose or action tag -> how many people it assumes (1 or 2). */
    val people: Map<String, Int> = emptyMap(),
    /**
     * Category -> word -> head count, overriding [people] for that category only.
     *
     * [people] is keyed by the word's text, so 全裸 meant "needs a man in frame" for the male
     * wardrobe and quietly made the female 穿脱 word unselectable in 单人. Words that exist in two
     * categories with different meanings need to say so per category.
     */
    val peopleByCategory: Map<String, Map<String, Int>> = emptyMap(),
    /** Old word -> current word, so a stored selection survives a rename. */
    val wordAliases: Map<String, String> = emptyMap(),
    /** A 人数 tag -> how many people it means. */
    val peopleCount: Map<String, Int> = emptyMap(),
    /**
     * Tags that only exist when a man is in frame. A draw says how many people, never of which
     * sex, so without this list an all-female cast can be handed 站立口交 and the model invents
     * a man nobody described — the organ arrives without an owner.
     */
    /** Place tag -> the surfaces you can actually use there (bed, floor, wall...). */
    val placeSupports: Map<String, List<String>> = emptyMap(),
    /**
     * Pose or action tag -> the surface it needs. A garage floor cannot hold a bedsheet, and a
     * window sill cannot hold someone lying down; without this the two are drawn independently.
     */
    val poseNeeds: Map<String, List<String>> = emptyMap(),
    /**
     * Garments that already name their own colour (白衬衫, 小白鞋).
     *
     * The colour slot is drawn independently of the style slot and prefixed to it, so without
     * this list the two produce 黑色的白衬衫. Listed rather than inferred from the word: 青城弟子服
     * and 青丘侍女裙 start with a colour character that is really a place name.
     */
    val selfColoured: List<String> = emptyList(),
    /** 自带图案的款式（豹纹、格纹）：再挂一个颜色就撞成「白色的豹纹内裤」。 */
    val selfPatterned: List<String> = emptyList(),
    /**
     * 服装与场地的年代。没登记的按「现代」算，只有明确标了古代/现代的场地才参与配对，
     * 其余场地（卧室、森林、纯白背景）不限，cosplay 照样成立。
     */
    /**
     * 词性：只登记「戴/拿」之外的特殊写法。
     *
     * wear 戴着、hold 拿着、attach 贴着、placed 旁边摆着、verb 自成一句不加动词。
     * 没登记的词按所在类别取默认：配饰戴、道具拿。
     */
    val pos: Map<String, String> = emptyMap(),
    val eras: Map<String, String> = emptyMap(),
    /**
     * 地域 -> 这个地域说得通的肤色。两边独立抽会出「印度，雪白皮肤」。
     * 只登记冲突明确的几组，没登记的地域不限。
     */
    val skinByRegion: Map<String, List<String>> = emptyMap(),
    val maleOnly: List<String> = emptyList(),
    /** 人数 values that carry exactly one man. Everything else counts as an all-female cast. */
    val malePeople: List<String> = emptyList(),
    /**
     * Framing -> the body regions it puts out of frame. 腰部以上特写 says where the lens points,
     * but nothing stopped the act from happening below the waist; this is the other half.
     */
    val frameHides: Map<String, List<String>> = emptyMap(),
    /** Framing -> whole categories it removes, e.g. 只露出背影 has no face to read. */
    val frameHidesCategories: Map<String, List<String>> = emptyMap(),
    /** Word -> the regions it is drawn on. Wins over [categoryRegions]. */
    val regions: Map<String, List<String>> = emptyMap(),
    /** Category -> the default regions its words live in. */
    val categoryRegions: Map<String, List<String>> = emptyMap(),
    /** Words that occupy both hands by themselves; a hand-action tag cannot join them. */
    val handBlock: List<String> = emptyList(),
    /**
     * Word -> whose body it describes, overriding [TagCategory.who].
     *
     * Empty for now: the per-category table already covers the library, and a word only needs an
     * entry where it disagrees with its neighbours (精液 inside 体液, 男装 inside 上衣).
     */
    val subjects: Map<String, String> = emptyMap(),
    /**
     * Accessory -> the spot on the head it occupies: eyes / mouth / ears / neck / head / all.
     *
     * 面纱, 眼镜 and 口塞 are three separate categories, so nothing stopped all three from being
     * drawn onto one face. "all" wins over everything, the rest are one per spot.
     */
    val faceZone: Map<String, String> = emptyMap(),
    /**
     * Word -> permanent draw weight. For the few words that should stay rare without being
     * removed from the pool. Multiplies with the recency weight, so a rare word that was also just
     * drawn is briefly near-impossible; a missing entry means 1.0.
     */
    val weight: Map<String, Double> = emptyMap()
) {
    fun weightOf(word: String): Double = weight[word] ?: 1.0

    /** True when at least one word carries a custom weight, so the draw can skip the maths. */
    val hasWeight: Boolean get() = weight.isNotEmpty()
}

/** The whole word library: categories, rules and constraints, all editable in one file. */
data class TagLibrary(
    val schemaVersion: Int = 1,
    val name: String = "",
    /** `nsfw` / `sfw`。导入时先看它判档，避免把成人词库导进 SFW。 */
    val profile: String = "",
    /** `mature` / `general`，只用于界面角标与导出提示。 */
    val rating: String = "",
    val categories: List<TagCategory> = emptyList(),
    val singletons: List<String> = emptyList(),
    val skipRules: List<TagSkipRule> = emptyList(),
    val hiddenRules: List<TagHiddenRule> = emptyList(),
    val mutex: List<List<String>> = emptyList(),
    val constraints: TagConstraints = TagConstraints(),
    val anchors: AnchorSet = AnchorSet(),
    /** Output order, outermost layer first. Lives in the catalog so the library ships alone. */
    val promptTiers: List<List<String>> = emptyList(),
    /** Browse order on the composer page. Independent of how the prompt reads. */
    val uiOrder: List<String> = emptyList(),
    /** Section headings, in display order. */
    val groups: List<CategoryGroup> = emptyList(),
    /** Categories a fresh selection starts switched off. */
    val defaultIgnored: List<String> = emptyList(),
    /** Categories pinned on first use, because a reroll should not change the visual language. */
    val defaultLocked: List<String> = emptyList(),
    /** Frequently used words per slot, moved to the front of the chip list. */
    val commonFirst: Map<String, List<String>> = emptyMap(),
    /**
     * Write the prompt as one paragraph per subject instead of one run-on list. Off by default
     * so a library without the flag keeps the old, flat output.
     */
    val sectionMode: Boolean = false,
    /**
     * Catalogue revision. A change means the built-in defaults were re-decided, so the composer
     * reapplies them once instead of leaving a stale selection in place forever.
     */
    val version: String = "",
    /** 媒介 -> 渲染指令。词库里只有一个"日式动漫"时，模型不知道它该用描边还是颗粒。 */
    val stylePacks: Map<String, List<String>> = emptyMap(),
    /**
     * 媒介 -> 一整句风格描述。
     *
     * 四段碎片是在提要求，整句是在做描述，而文本编码器对后者的服从度明显更高。句子刻意
     * 不点名具体色名和光源名：颜色归 调色 / 主色，光归 光质 / 光位，三层各说各的。
     */
    val stylePrompts: Map<String, String> = emptyMap(),
    /** 媒介 -> 它必须搭配的调色词。名字里承诺了颜色的风格不能交给随机。 */
    val styleFeel: Map<String, String> = emptyMap(),
    /**
     * 旧名 -> 新名。风格改名后，存档里还锁着旧名的那一格不该因此丢掉整句描述；
     * 查句子和查强制调色都先过这张表。
     */
    val styleAliases: Map<String, String> = emptyMap(),
    /** 媒介在界面上的分组，只影响显示顺序，不参与抽取。 */
    val styleGroups: List<StyleGroup> = emptyList(),
    /** 主色规则模板，"%s" 处填主色词，例如 主色只用%s，其余压成灰阶。 */
    val paletteRule: String = ""
) {
    val tagCount: Int get() = categories.sumOf { category -> category.slots.sumOf { it.pool.size } }

    fun category(id: String): TagCategory? = categories.firstOrNull { it.id == id }

    /** Follows a rename, so an old selection lands on the word the pool actually holds. */
    fun canonicalWord(word: String): String = constraints.wordAliases[word] ?: word

    /**
     * Head count [word] needs inside [categoryId], with the per-category override winning.
     *
     * The global map is keyed by text, so a word shared by a male and a female category carries
     * the male meaning everywhere. Callers that know which category they are drawing must use
     * this rather than looking the word up themselves.
     */
    fun peopleNeeded(categoryId: String, word: String): Int =
        constraints.peopleByCategory[categoryId]?.get(word)
            ?: constraints.people[word]
            ?: 0

    fun slot(categoryId: String, slotId: String): TagSlot? =
        category(categoryId)?.slots?.firstOrNull { it.id == slotId }

    /** Replaces one slot, keeping category order and slot order intact. */
    fun withSlot(categoryId: String, slot: TagSlot): TagLibrary = copy(
        categories = categories.map { category ->
            if (category.id != categoryId) category
            else category.copy(slots = category.slots.map { if (it.id == slot.id) slot else it })
        }
    )
}

/**
 * Reads and writes the library file.
 *
 * Parsing is strict about shape and lenient about content: a slot with an empty pool is
 * rejected rather than silently loaded, because an empty slot makes the composer produce
 * nothing at all and the failure is hard to spot from the UI.
 */
object TagLibraryJson {

    private const val MAX_SLOTS = 256
    private const val MAX_TAGS_PER_SLOT = 4096

    /**
     * 旧库兼容。
     *
     * 身份分类已经废弃，引擎不再读它；妆容也已经并进「形象」。
     * 读到旧文件时在这里顺手改掉，否则界面上会多出一行点不动的身份，
     * 以及和形象里重复的妆容分类。
     */
    private fun migrate(source: List<TagCategory>): List<TagCategory> {
        val person = source.firstOrNull { it.id == "person" } ?: return source
        val makeup = source.firstOrNull { it.id == "makeup" }
        val kept = person.slots.filter { it.id != "origin" }
        if (kept.isEmpty()) return source.filter { it.id != "person" }
        if (kept.any { it.id == "face" } || makeup == null) {
            return source.map { if (it.id == "person") it.copy(slots = kept) else it }
        }
        val face = makeup.slots.firstOrNull { it.id == "face" } ?: return source
        val merged = person.copy(
            label = "形象",
            slots = listOf(face) + kept.map {
                when (it.id) {
                    "age" -> it.copy(label = "年龄感")
                    "look" -> it.copy(label = "气质")
                    else -> it
                }
            }
        )
        return source.mapNotNull {
            when (it.id) {
                "person" -> merged
                "makeup" -> null
                else -> it
            }
        }
    }

    fun parse(text: String): TagLibrary {
        val root = JSONObject(text)
        val categoriesJson = root.optJSONArray("categories")
            ?: throw IllegalArgumentException("缺少 categories")
        val categories = migrate(
            (0 until categoriesJson.length()).mapNotNull { index ->
                categoriesJson.optJSONObject(index)?.let(::readCategory)
            }
        )
        if (categories.isEmpty()) throw IllegalArgumentException("categories 为空")

        val rules = root.optJSONObject("rules")
        val meta = root.optJSONObject("meta")
        val constraintsJson = root.optJSONObject("constraints")
        return TagLibrary(
            schemaVersion = root.optInt("schemaVersion", 1),
            anchors = readAnchors(root.optJSONObject("anchors")),
            name = root.optJSONObject("meta")?.optString("name").orEmpty(),
            profile = meta?.optString("profile").orEmpty(),
            rating = meta?.optString("rating").orEmpty(),
            categories = categories,
            singletons = rules?.strList("singletons").orEmpty(),
            skipRules = readSkipRules(rules),
            hiddenRules = readHiddenRules(rules),
            mutex = readMutex(rules),
            constraints = readConstraints(constraintsJson),
            promptTiers = readTiers(meta?.optJSONObject("promptTiers")),
            uiOrder = meta?.strList("uiOrder").orEmpty(),
            groups = readGroups(meta),
            defaultIgnored = meta?.strList("defaultIgnored").orEmpty(),
            defaultLocked = meta?.strList("defaultLocked").orEmpty(),
            commonFirst = readListMap(meta?.optJSONObject("commonFirst")),
            sectionMode = meta?.optBoolean("sectionMode", false) ?: false,
            version = meta?.optString("version").orEmpty(),
            stylePacks = readListMap(meta?.optJSONObject("stylePacks")),
            stylePrompts = readStringMap(meta?.optJSONObject("stylePrompts")),
            styleFeel = readStringMap(meta?.optJSONObject("styleFeel")),
            styleAliases = readStringMap(meta?.optJSONObject("styleAliases")),
            styleGroups = readStyleGroups(meta?.optJSONArray("styleGroups")),
            paletteRule = meta?.optString("paletteRule").orEmpty()
        )
    }

    private fun readGroups(meta: JSONObject?): List<CategoryGroup> {
        val array = meta?.optJSONArray("groups") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val node = array.optJSONObject(index) ?: return@mapNotNull null
            val id = node.optString("id").ifBlank { return@mapNotNull null }
            CategoryGroup(id = id, label = node.optString("label").ifBlank { id })
        }
    }

    private fun readTiers(json: JSONObject?): List<List<String>> {
        if (json == null) return emptyList()
        return json.keys().asSequence()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            .map { json.strList(it) }
            .filter { it.isNotEmpty() }
            .toList()
    }

    fun write(library: TagLibrary): String {
        val root = JSONObject()
        root.put("schemaVersion", library.schemaVersion)
        val meta = JSONObject()
        if (library.name.isNotBlank()) meta.put("name", library.name)
        if (library.profile.isNotBlank()) meta.put("profile", library.profile)
        if (library.rating.isNotBlank()) meta.put("rating", library.rating)
        if (library.promptTiers.isNotEmpty()) {
            val tierJson = JSONObject()
            library.promptTiers.forEachIndexed { index, tier -> tierJson.put(index.toString(), JSONArray(tier)) }
            meta.put("promptTiers", tierJson)
        }
        if (library.sectionMode) meta.put("sectionMode", true)
        if (library.version.isNotBlank()) meta.put("version", library.version)
        if (library.stylePacks.isNotEmpty()) {
            val packsJson = JSONObject()
            library.stylePacks.forEach { (medium, cues) -> packsJson.put(medium, JSONArray(cues)) }
            meta.put("stylePacks", packsJson)
        }
        if (library.stylePrompts.isNotEmpty()) {
            meta.put("stylePrompts", JSONObject(library.stylePrompts))
        }
        if (library.styleFeel.isNotEmpty()) {
            meta.put("styleFeel", JSONObject(library.styleFeel))
        }
        if (library.styleAliases.isNotEmpty()) {
            meta.put("styleAliases", JSONObject(library.styleAliases))
        }
        if (library.styleGroups.isNotEmpty()) {
            meta.put("styleGroups", JSONArray().apply {
                library.styleGroups.forEach { group ->
                    put(JSONObject().put("label", group.label).put("words", JSONArray(group.words)))
                }
            })
        }
        if (library.paletteRule.isNotBlank()) meta.put("paletteRule", library.paletteRule)
        if (library.uiOrder.isNotEmpty()) meta.put("uiOrder", JSONArray(library.uiOrder))
        if (library.groups.isNotEmpty()) {
            meta.put("groups", JSONArray().apply {
                library.groups.forEach { group ->
                    put(JSONObject().put("id", group.id).put("label", group.label))
                }
            })
        }
        if (library.defaultIgnored.isNotEmpty()) meta.put("defaultIgnored", JSONArray(library.defaultIgnored))
        if (library.defaultLocked.isNotEmpty()) meta.put("defaultLocked", JSONArray(library.defaultLocked))
        if (library.commonFirst.isNotEmpty()) {
            val firstJson = JSONObject()
            library.commonFirst.forEach { (slotKey, words) -> firstJson.put(slotKey, JSONArray(words)) }
            meta.put("commonFirst", firstJson)
        }
        root.put(
            "meta", meta
                .put(
                    "note",
                    "handCap 为全局手占用上限；handCost 为每个词占几只；posture 为姿态组；requires 为该词的成立前提。"
                )
        )
        val categories = JSONArray()
        library.categories.forEach { category ->
            val slots = JSONArray()
            category.slots.forEach { slot ->
                val slotJson = JSONObject()
                slotJson.put("id", slot.id)
                slotJson.put("label", slot.label)
                slotJson.put("pool", JSONArray(slot.pool))
                if (slot.excluded.isNotEmpty()) slotJson.put("excluded", JSONArray(slot.excluded))
                slots.put(slotJson)
            }
            val categoryJson = JSONObject()
            categoryJson.put("id", category.id)
            categoryJson.put("label", category.label)
            if (category.join.isNotBlank()) categoryJson.put("join", category.join)
            if (category.group.isNotBlank()) categoryJson.put("group", category.group)
            if (category.tier != 0) categoryJson.put("tier", category.tier)
            if (category.who.isNotBlank()) categoryJson.put("who", category.who)
            categoryJson.put("slots", slots)
            categories.put(categoryJson)
        }
        root.put("categories", categories)

        val rules = JSONObject()
        rules.put("singletons", JSONArray(library.singletons))
        val skipRules = JSONArray()
        library.skipRules.forEach { rule ->
            skipRules.put(
                JSONObject()
                    .put("whenAny", JSONArray(rule.whenAny))
                    .put("skip", JSONArray(rule.skip))
                    .apply { if (rule.skipWords.isNotEmpty()) put("skipWords", JSONArray(rule.skipWords)) }
                    .apply { if (rule.from.isNotEmpty()) put("from", JSONArray(rule.from)) }
                    .apply { if (rule.unless.isNotEmpty()) put("unless", JSONArray(rule.unless)) }
            )
        }
        rules.put("skipCategories", skipRules)
        val hiddenRules = JSONArray()
        library.hiddenRules.forEach { rule ->
            hiddenRules.put(
                JSONObject().apply {
                    put("category", rule.category)
                    put("coveredBy", rule.coveredBy)
                    if (rule.visibleWhen.isNotEmpty()) put("visibleWhen", JSONArray(rule.visibleWhen))
                }
            )
        }
        rules.put("hiddenBy", hiddenRules)
        val mutex = JSONArray()
        library.mutex.forEach { mutex.put(JSONArray(it)) }
        rules.put("mutex", mutex)
        root.put("rules", rules)

        val constraints = JSONObject()
        constraints.put("handCap", library.constraints.handCap)
        constraints.put("handCost", JSONObject(library.constraints.handCost))
        constraints.put("posture", JSONObject(library.constraints.posture))
        val requires = JSONObject()
        library.constraints.requires.forEach { (tag, need) -> requires.put(tag, JSONArray(need)) }
        constraints.put("requires", requires)
        constraints.put("place", JSONObject(library.constraints.place))
        constraints.put("gaze", JSONObject(library.constraints.gaze))
        constraints.put("soloPose", JSONArray(library.constraints.soloPose))
        constraints.put("scenePoses", JSONArray(library.constraints.scenePoses))
        constraints.put("hairState", JSONArray(library.constraints.hairState))
        constraints.put("onePiece", JSONArray(library.constraints.onePiece))
        constraints.put("onePieceBottom", JSONArray(library.constraints.onePieceBottom))
        constraints.put("toyActs", JSONObject().apply {
            library.constraints.toyActs.forEach { (key, values) -> put(key, JSONArray(values)) }
        })
        constraints.put("mouthCap", library.constraints.mouthCap)
        constraints.put("mouthCost", JSONObject(library.constraints.mouthCost))
        constraints.put("headCap", library.constraints.headCap)
        constraints.put("headCost", JSONObject(library.constraints.headCost))
        val synonyms = JSONArray()
        library.constraints.synonyms.forEach { synonyms.put(JSONArray(it)) }
        constraints.put("synonyms", synonyms)
        val excludes = JSONArray()
        library.constraints.excludes.forEach { excludes.put(JSONArray(it)) }
        constraints.put("excludes", excludes)
        constraints.put("people", JSONObject(library.constraints.people))
        if (library.constraints.wordAliases.isNotEmpty()) {
            constraints.put("wordAliases", JSONObject(library.constraints.wordAliases))
        }
        if (library.constraints.peopleByCategory.isNotEmpty()) {
            val scoped = JSONObject()
            library.constraints.peopleByCategory.forEach { (category, counts) ->
                scoped.put(category, JSONObject(counts))
            }
            constraints.put("peopleByCategory", scoped)
        }
        constraints.put("peopleCount", JSONObject(library.constraints.peopleCount))
        if (library.constraints.maleOnly.isNotEmpty()) {
            constraints.put("maleOnly", JSONArray(library.constraints.maleOnly))
        }
        listOf("placeSupports" to library.constraints.placeSupports, "poseNeeds" to library.constraints.poseNeeds)
            .forEach { (key, value) ->
                if (value.isNotEmpty()) {
                    val node = JSONObject()
                    value.forEach { (tag, axes) -> node.put(tag, JSONArray(axes)) }
                    constraints.put(key, node)
                }
            }
        if (library.constraints.malePeople.isNotEmpty()) {
            constraints.put("malePeople", JSONArray(library.constraints.malePeople))
        }
        if (library.constraints.selfColoured.isNotEmpty()) {
            constraints.put("selfColoured", JSONArray(library.constraints.selfColoured))
        }
        if (library.constraints.selfPatterned.isNotEmpty()) {
            constraints.put("selfPatterned", JSONArray(library.constraints.selfPatterned))
        }
        if (library.constraints.pos.isNotEmpty()) {
            constraints.put("pos", JSONObject().apply {
                library.constraints.pos.forEach { (word, tag) -> put(word, tag) }
            })
        }
        if (library.constraints.eras.isNotEmpty()) {
            constraints.put("eras", JSONObject().apply {
                library.constraints.eras.forEach { (word, era) -> put(word, era) }
            })
        }
        if (library.constraints.skinByRegion.isNotEmpty()) {
            constraints.put("skinByRegion", JSONObject().apply {
                library.constraints.skinByRegion.forEach { (region, tones) ->
                    put(region, JSONArray(tones))
                }
            })
        }
        if (library.constraints.handBlock.isNotEmpty()) {
            constraints.put("handBlock", JSONArray(library.constraints.handBlock))
        }
        if (library.constraints.subjects.isNotEmpty()) {
            constraints.put("subjects", JSONObject().apply {
                library.constraints.subjects.forEach { (tag, who) -> put(tag, who) }
            })
        }
        if (library.constraints.faceZone.isNotEmpty()) {
            constraints.put("faceZone", JSONObject().apply {
                library.constraints.faceZone.forEach { (tag, zone) -> put(tag, zone) }
            })
        }
        listOf(
            "frameHides" to library.constraints.frameHides,
            "frameHidesCategories" to library.constraints.frameHidesCategories,
            "regions" to library.constraints.regions,
            "categoryRegions" to library.constraints.categoryRegions
        ).forEach { (key, value) ->
            if (value.isNotEmpty()) {
                constraints.put(key, JSONObject().apply {
                    value.forEach { (tag, axes) -> put(tag, JSONArray(axes)) }
                })
            }
        }
        constraints.put("postureHints", JSONObject().apply {
            library.constraints.postureHints.forEach { (key, values) ->
                put(key, JSONArray(values))
            }
        })
        constraints.put("headPlacements", JSONObject().apply {
            library.constraints.headPlacements.forEach { (key, values) ->
                put(key, JSONArray(values))
            }
        })
        constraints.put("sceneProps", JSONObject().apply {
            library.constraints.sceneProps.forEach { (key, values) ->
                put(key, JSONArray(values))
            }
        })
        constraints.put("propNeeds", JSONObject().apply {
            library.constraints.propNeeds.forEach { (key, values) ->
                put(key, JSONArray(values))
            }
        })
        constraints.put("allowedPostures", JSONObject().apply {
            library.constraints.allowedPostures.forEach { (key, values) ->
                put(key, JSONArray(values))
            }
        })
        constraints.put("exactPeople", JSONArray(library.constraints.exactPeople))
        constraints.put("raceLead", JSONObject(library.constraints.raceLead))
        constraints.put("raceName", JSONObject(library.constraints.raceName))
        constraints.put("raceFeatureHead", JSONObject().apply {
            library.constraints.raceFeatureHead.forEach { (key, values) ->
                put(key, JSONArray(values))
            }
        })
        constraints.put("raceFeatureBody", JSONObject().apply {
            library.constraints.raceFeatureBody.forEach { (key, values) ->
                put(key, JSONArray(values))
            }
        })
        root.put("constraints", constraints)
        val anchors = JSONObject()
        val families = JSONObject()
        library.anchors.families.forEach { (id, soft) ->
            families.put(id, JSONObject()
                .put("express", JSONArray(soft.express))
                .put("gaze", JSONArray(soft.gaze))
                .put("fluids", JSONArray(soft.fluids))
                .put("camera", JSONArray(soft.camera))
                .put("outfitState", JSONArray(soft.outfitState)))
        }
        anchors.put("families", families)
        anchors.put("of", JSONObject(library.anchors.of))
        anchors.put("addOn", JSONArray(library.anchors.addOn))
        root.put("anchors", anchors)
        return root.toString(1)
    }

    private fun readCategory(json: JSONObject): TagCategory? {
        val id = json.optString("id").ifBlank { return null }
        val slotsJson = json.optJSONArray("slots") ?: return null
        val slots = (0 until minOf(slotsJson.length(), MAX_SLOTS)).mapNotNull { index ->
            val slotJson = slotsJson.optJSONObject(index) ?: return@mapNotNull null
            val slotId = slotJson.optString("id").ifBlank { return@mapNotNull null }
            val pool = slotJson.strList("pool")
                .distinct()
                .take(MAX_TAGS_PER_SLOT)
            if (pool.isEmpty()) return@mapNotNull null
            TagSlot(
                id = slotId,
                label = slotJson.optString("label").ifBlank { slotId },
                pool = pool,
                excluded = slotJson.strList("excluded").filter { it in pool }
            )
        }
        if (slots.isEmpty()) return null
        return TagCategory(
            id = id,
            label = json.optString("label").ifBlank { id },
            join = json.optString("join"),
            slots = slots,
            group = json.optString("group"),
            tier = json.optInt("tier", 0),
            who = json.optString("who")
        )
    }

    private fun readSkipRules(rules: JSONObject?): List<TagSkipRule> {
        val array = rules?.optJSONArray("skipCategories") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val rule = array.optJSONObject(index) ?: return@mapNotNull null
            val whenAny = rule.strList("whenAny")
            val skip = rule.strList("skip")
            val skipWords = rule.strList("skipWords")
            if (whenAny.isEmpty() || (skip.isEmpty() && skipWords.isEmpty())) null
            else TagSkipRule(whenAny, skip, skipWords, rule.strList("from"), rule.strList("unless"))
        }
    }

    private fun readHiddenRules(rules: JSONObject?): List<TagHiddenRule> {
        val array = rules?.optJSONArray("hiddenBy") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val rule = array.optJSONObject(index) ?: return@mapNotNull null
            val category = rule.optString("category").ifBlank { return@mapNotNull null }
            val coveredBy = rule.optString("coveredBy").ifBlank { return@mapNotNull null }
            TagHiddenRule(category, coveredBy, rule.strList("visibleWhen"))
        }
    }

    private fun readMutex(rules: JSONObject?): List<List<String>> {
        val array = rules?.optJSONArray("mutex") ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { array.optJSONArray(it) }
            .map { group -> (0 until group.length()).mapNotNull { group.optString(it).ifBlank { null } } }
            .filter { it.size >= 2 }
    }

    private fun readConstraints(json: JSONObject?): TagConstraints {
        if (json == null) return TagConstraints()
        val costJson = json.optJSONObject("handCost")
        val cost = mutableMapOf<String, Int>()
        costJson?.keys()?.forEach { key -> cost[key] = costJson.optInt(key, 0) }

        val postureJson = json.optJSONObject("posture")
        val posture = mutableMapOf<String, String>()
        postureJson?.keys()?.forEach { key ->
            val group = postureJson.optString(key)
            if (group.isNotBlank()) posture[key] = group
        }

        val requiresJson = json.optJSONObject("requires")
        val requires = mutableMapOf<String, List<String>>()
        requiresJson?.keys()?.forEach { key ->
            val need = requiresJson.strList(key)
            if (need.isNotEmpty()) requires[key] = need
        }

        return TagConstraints(
            handCap = json.optInt("handCap", 2).coerceAtLeast(0),
            handCost = cost.filterValues { it > 0 },
            posture = posture,
            place = readStringMap(json.optJSONObject("place")),
            gaze = readStringMap(json.optJSONObject("gaze")),
            soloPose = json.strList("soloPose"),
            scenePoses = json.strList("scenePoses"),
            hairState = json.strList("hairState"),
            onePiece = json.strList("onePiece"),
            onePieceBottom = json.strList("onePieceBottom"),
            toyActs = readListMap(json.optJSONObject("toyActs")),
            mouthCap = json.optInt("mouthCap", 1).coerceAtLeast(0),
            mouthCost = readIntMap(json.optJSONObject("mouthCost")),
            synonyms = readGroups(json.optJSONArray("synonyms")),
            excludes = readPairs(json.optJSONArray("excludes")),
            requires = requires,
            allowedPostures = readListMap(json.optJSONObject("allowedPostures")),
            postureHints = readListMap(json.optJSONObject("postureHints")),
            headPlacements = readListMap(json.optJSONObject("headPlacements")),
            sceneProps = readListMap(json.optJSONObject("sceneProps")),
            propNeeds = readListMap(json.optJSONObject("propNeeds")),
            exactPeople = json.strList("exactPeople"),
            raceFeatureHead = readListMap(json.optJSONObject("raceFeatureHead")),
            raceFeatureBody = readListMap(json.optJSONObject("raceFeatureBody")),
            raceLead = readStringMap(json.optJSONObject("raceLead")),
            raceName = readStringMap(json.optJSONObject("raceName")),
            people = readIntMap(json.optJSONObject("people")),
            peopleByCategory = readIntMapMap(json.optJSONObject("peopleByCategory")),
            wordAliases = readStringMap(json.optJSONObject("wordAliases")),
            peopleCount = readIntMap(json.optJSONObject("peopleCount")),
            placeSupports = readListMap(json.optJSONObject("placeSupports")),
            poseNeeds = readListMap(json.optJSONObject("poseNeeds")),
            selfColoured = json.strList("selfColoured"),
            selfPatterned = json.strList("selfPatterned"),
            pos = readStringMap(json.optJSONObject("pos")),
            eras = readStringMap(json.optJSONObject("eras")),
            skinByRegion = readListMap(json.optJSONObject("skinByRegion")),
            maleOnly = json.strList("maleOnly"),
            malePeople = json.strList("malePeople"),
            frameHides = readListMap(json.optJSONObject("frameHides")),
            frameHidesCategories = readListMap(json.optJSONObject("frameHidesCategories")),
            regions = readListMap(json.optJSONObject("regions")),
            categoryRegions = readListMap(json.optJSONObject("categoryRegions")),
            handBlock = json.strList("handBlock"),
            subjects = readFlatMap(json.optJSONObject("subjects")),
            faceZone = readFlatMap(json.optJSONObject("faceZone")),
            weight = readDoubleMap(json.optJSONObject("weight"))
        )
    }

    /** Word -> single value map (subjects, posture groups, ...). */
    private fun readFlatMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        json.keys().forEach { key ->
            val value = json.optString(key)
            if (value.isNotBlank()) out[key] = value
        }
        return out
    }

    private fun readStyleGroups(array: JSONArray?): List<StyleGroup> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val node = array.optJSONObject(index) ?: return@mapNotNull null
            val words = node.strList("words")
            if (words.isEmpty()) return@mapNotNull null
            StyleGroup(label = node.optString("label").ifBlank { "其他" }, words = words)
        }
    }

    private fun readListMap(json: JSONObject?): Map<String, List<String>> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, List<String>>()
        json.keys().forEach { key ->
            val values = json.strList(key)
            if (values.isNotEmpty()) out[key] = values
        }
        return out
    }

    private fun readIntMapMap(json: JSONObject?): Map<String, Map<String, Int>> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, Map<String, Int>>()
        json.keys().forEach { key ->
            val inner = readIntMap(json.optJSONObject(key))
            if (inner.isNotEmpty()) out[key] = inner
        }
        return out
    }

    private fun readAnchors(json: JSONObject?): AnchorSet {
        if (json == null) return AnchorSet()
        val familiesJson = json.optJSONObject("families")
        val families = mutableMapOf<String, SoftProfile>()
        familiesJson?.keys()?.forEach { key ->
            val obj = familiesJson.optJSONObject(key) ?: return@forEach
            families[key] = SoftProfile(
                express = obj.strList("express"),
                gaze = obj.strList("gaze"),
                fluids = obj.strList("fluids"),
                camera = obj.strList("camera"),
                outfitState = obj.strList("outfitState")
            )
        }
        return AnchorSet(
            families = families,
            of = readStringMap(json.optJSONObject("of")),
            addOn = json.strList("addOn")
        )
    }

    private fun readStringMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        json.keys().forEach { key ->
            val value = json.optString(key)
            if (value.isNotBlank()) out[key] = value
        }
        return out
    }

    /** 同义词组可以不止两个成员：一句里「半脱」有九种写法，只收前两个等于没收。 */
    private fun readGroups(json: JSONArray?): List<List<String>> {
        if (json == null) return emptyList()
        return (0 until json.length())
            .mapNotNull { json.optJSONArray(it) }
            .map { group -> (0 until group.length()).mapNotNull { group.optString(it).ifBlank { null } } }
            .filter { it.size >= 2 }
    }

    private fun readPairs(json: JSONArray?): List<List<String>> {
        if (json == null) return emptyList()
        return (0 until json.length())
            .mapNotNull { json.optJSONArray(it) }
            .map { pair -> (0 until pair.length()).mapNotNull { pair.optString(it).ifBlank { null } } }
            .filter { it.size == 2 }
    }

    /** Word -> draw weight. 1.0 is the default, so only the exceptions are kept. */
    private fun readDoubleMap(json: JSONObject?): Map<String, Double> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, Double>()
        json.keys().forEach { key ->
            val value = json.optDouble(key, 1.0)
            if (value > 0.0 && value != 1.0) out[key] = value
        }
        return out
    }

    private fun readIntMap(json: JSONObject?): Map<String, Int> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, Int>()
        json.keys().forEach { key ->
            val value = json.optInt(key, 0)
            if (value > 0) out[key] = value
        }
        return out
    }

    private fun JSONObject.strList(key: String): List<String> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf { text -> text.isNotBlank() } }
    }
}
