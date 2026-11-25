package sh.hnet.comfychair.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 剧情组合用的词库，和 [TagLibrary] 完全独立。
 *
 * 结构比标签词库简单得多：一堆「桶」（地点、穿着、动作…）+ 每种人数几句句式骨架。
 * 复杂的是相容规则，全部集中在这几个字段上：
 *
 * - [groups] 场景分组，地点抽到 kitchen 时穿着、日常动作只在同类里选
 * - [casts] 人数限制，单人不会抽到「让她转过身」这种对别人说话的句子
 * - [props] 这个词带来的物件（台面/镜/床…），主要给地点用
 * - [wears] 穿上什么（围裙/内裤/丝袜…），进「在场」也进「身上」
 * - [removes] 脱掉什么，只从「身上」移除，「在场」保留——脱下来的丝袜还能挂在脚踝上
 * - [requires] 这些物件必须在场（不管穿没穿）
 * - [requiresWorn] 这些物件里至少一件还穿在身上，`*` 表示身上不能什么都没有
 * - [needs] 句子文本里必须先出现这个词，给「围裙的带子松了」这类用
 *
 * 判断不再靠字符串碰运气：物件由池子显式声明，句子只是把结果写出来。
 */
data class StoryWord(
    val text: String,
    val groups: List<String> = emptyList(),
    val casts: List<String> = emptyList(),
    val needs: String = "",
    val avoid: String = "",
    /** 姿势锚点的唯一标识，动作靠它对上号（墙 / 台面 / 抱 / 坐…）。 */
    val key: String = "",
    val poses: List<String> = emptyList(),
    val props: List<String> = emptyList(),
    val wears: List<String> = emptyList(),
    val removes: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val requiresWorn: List<String> = emptyList(),
    /** 场景反选，天气类词用它把户外排除掉。 */
    val avoidGroups: List<String> = emptyList(),
    /** 常用词。界面排前面，抽取时权重更高。 */
    val common: Boolean = false,
    /** 「只穿」「全裸」这类排他表述：内搭不许再往里塞内衣物。 */
    val exclusive: Boolean = false,
    /** 画风限定的时段，空表示不限。 */
    val times: Set<String> = emptySet(),
    /**
     * 适用的年代：ancient / modern / fantasy / cyber。
     * 空表示通用——床、浴室、树林这类本来哪个年代都有，不该挨个标。
     */
    val eras: Set<String> = emptySet(),
    /**
     * 和 removes 里的 "*" 配合：身上全清之后留着这几件。
     * 「只剩丝袜了」就是这个意思，和开场穿什么无关。
     */
    val keep: Set<String> = emptySet(),
    /**
     * 是否挺腰插入的动作。
     *
     * 句子把姿势和动作拼成「{pose}，顺势挺腰{act}」，这个连接词只对插入成立；
     * 口交、足交、夹着磨这类并进去就成了「顺势挺腰让她跪下去口交」。标 false
     * 的动作改用逗号直接接上。
     */
    val penetrating: Boolean = true
) {
    fun allowsGroup(group: String): Boolean =
        group.isEmpty() || groups.isEmpty() || "*" in groups || group in groups

    /** 题材带来的年代，和词自己的年代取交集；任何一边没标就都放行。 */
    fun allowsEra(wanted: Set<String>): Boolean =
        wanted.isEmpty() || eras.isEmpty() || "*" in eras || eras.any { it in wanted }

    fun allowsCast(cast: String): Boolean = casts.isEmpty() || cast in casts

    fun allowsAvoidGroup(group: String): Boolean =
        group.isEmpty() || avoidGroups.isEmpty() || group !in avoidGroups
}

data class StoryCast(val text: String, val male: String = "")

data class StoryLibrary(
    val version: String = "",
    /** `nsfw` / `sfw`。导入时先看它判档，避免把成人剧情库导进 SFW。 */
    val profile: String = "",
    val rating: String = "",
    val casts: Map<String, StoryCast> = emptyMap(),
    val pools: Map<String, List<StoryWord>> = emptyMap(),
    val templates: Map<String, List<String>> = emptyMap(),
    /** 用户自己加的词，按池子分组。换词库、还原内置都不会动它。 */
    val custom: Map<String, Set<String>> = emptyMap(),
    /** 被「眼睛」关掉的词：still 显示，但不参与随机。 */
    val excluded: Map<String, Set<String>> = emptyMap(),
    /**
     * 互斥词对：两个词不在同一段里同时出现（贫乳 × 深乳沟、十八岁外表 × 熟女感）。
     *
     * 写成 [[a, b], …]，双向判，谁先抽到都一样。
     */
    val mutex: List<Pair<String, String>> = emptyList()
) {
    /**
     * 把自定义词接到每个池子末尾。
     *
     * 放末尾是有意的：常用词和随包词按原来的顺序在前面，自己加的词在弹层最下面，
     * 不会被常用权重顶到前面去。
     */
    fun withCustom(
        additions: Map<String, List<String>> = emptyMap(),
        excluded: Map<String, Set<String>> = emptyMap(),
        removals: Map<String, Set<String>> = emptyMap()
    ): StoryLibrary {
        if (additions.isEmpty() && excluded.isEmpty() && removals.isEmpty()) return this
        val merged = pools.toMutableMap()
        val marks = custom.toMutableMap()
        removals.forEach { (pool, words) ->
            if (words.isNotEmpty()) merged[pool] = merged[pool].orEmpty().filter { it.text !in words }
        }
        additions.forEach { (pool, words) ->
            val existing = merged[pool].orEmpty()
            val known = existing.map { it.text }.toSet()
            val extra = words.filter { it.isNotBlank() && it !in known }.map { StoryWord(it) }
            if (extra.isNotEmpty()) merged[pool] = existing + extra
            marks[pool] = marks[pool].orEmpty() + words.filter { it.isNotBlank() }
        }
        // 必须拍快照。直接塞引用的话，旧对象也跟着变，StateFlow 比对时发现「没区别」，界面永远不刷新。
        return copy(
            pools = merged,
            custom = marks.mapValues { it.value.toSet() },
            excluded = excluded.mapValues { it.value.toSet() }
        )
    }

    /** 抽词用的池子：去掉被「眼睛」关掉的那些。 */
    fun pickablePool(name: String): List<StoryWord> {
        val off = excluded[name].orEmpty()
        return pools[name].orEmpty().filter { it.text !in off }
    }

    fun isExcluded(pool: String, text: String): Boolean = text in excluded[pool].orEmpty()
    fun pool(name: String): List<StoryWord> = pools[name].orEmpty()

    fun word(pool: String, text: String): StoryWord? =
        pools[pool]?.firstOrNull { it.text == text }

    fun isCommon(pool: String, text: String): Boolean =
        pools[pool]?.firstOrNull { it.text == text }?.common == true

    val wordCount: Int get() = pools.values.sumOf { it.size }

    companion object {
        /** 人数分项的候选，顺序就是界面上出现的顺序。 */
        val CAST_ORDER = listOf("solo", "mf", "mff", "ff")
    }
}

object StoryLibraryJson {
    private const val SUPPORTED_SCHEMA = 2

    fun parse(text: String): StoryLibrary {
        val root = JSONObject(text)
        val schema = root.optInt("schemaVersion", 1)
        require(schema <= SUPPORTED_SCHEMA) { "剧情词库版本不认识" }
        val casts = mutableMapOf<String, StoryCast>()
        root.optJSONObject("casts")?.let { obj ->
            obj.keys().forEach { id ->
                val cast = obj.getJSONObject(id)
                casts[id] = StoryCast(
                    text = cast.optString("text", id),
                    male = cast.optString("male", "")
                )
            }
        }
        val pools = mutableMapOf<String, List<StoryWord>>()
        root.optJSONObject("pools")?.let { obj ->
            obj.keys().forEach { name ->
                pools[name] = readPool(obj.getJSONArray(name))
            }
        }
        val templates = mutableMapOf<String, List<String>>()
        root.optJSONObject("templates")?.let { obj ->
            obj.keys().forEach { cast ->
                val list = obj.getJSONArray(cast)
                templates[cast] = (0 until list.length()).map {
                    list.getString(it).replace("\\n", "\n")
                }
            }
        }
        val excluded = mutableMapOf<String, Set<String>>()
        root.optJSONObject("excluded")?.let { obj ->
            obj.keys().forEach { pool ->
                val list = obj.optJSONArray(pool) ?: return@forEach
                excluded[pool] = (0 until list.length()).map { list.getString(it) }.toSet()
            }
        }
        return StoryLibrary(
            version = root.optString("version", ""),
            profile = root.optString("profile", ""),
            rating = root.optString("rating", ""),
            casts = casts,
            pools = pools,
            templates = templates,
            excluded = excluded,
            mutex = readMutex(root.optJSONArray("mutex"))
        )
    }

    private fun readPool(array: JSONArray): List<StoryWord> =
        (0 until array.length()).map { index ->
            val entry = array.opt(index)
            if (entry is String) {
                StoryWord(entry)
            } else {
                val obj = entry as JSONObject
                StoryWord(
                    text = obj.optString("text"),
                    groups = readStrings(obj.optJSONArray("groups")) +
                        listOfNotNull(obj.optString("group").takeIf { it.isNotBlank() }),
                    casts = readStrings(obj.optJSONArray("casts")),
                    needs = obj.optString("needs", ""),
                    avoid = obj.optString("avoid", ""),
                    key = obj.optString("key", ""),
                    poses = readStrings(obj.optJSONArray("poses")),
                    props = readStrings(obj.optJSONArray("props")),
                    wears = readStrings(obj.optJSONArray("wears")),
                    removes = readStrings(obj.optJSONArray("removes")),
                    requires = readStrings(obj.optJSONArray("requires")),
                    requiresWorn = readStrings(obj.optJSONArray("requiresWorn")),
                    avoidGroups = readStrings(obj.optJSONArray("avoidGroups")),
                    common = obj.optBoolean("common", false),
                    exclusive = obj.optBoolean("exclusive", false),
                    times = readStrings(obj.optJSONArray("times")).toSet(),
                    eras = readStrings(obj.optJSONArray("eras")).toSet(),
                    keep = readStrings(obj.optJSONArray("keep")).toSet(),
                    penetrating = obj.optBoolean("penetrating", true)
                )
            }
        }

    private fun readStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    fun write(library: StoryLibrary): String {
        val root = JSONObject()
        root.put("schemaVersion", SUPPORTED_SCHEMA)
        root.put("version", library.version)
        if (library.profile.isNotBlank()) root.put("profile", library.profile)
        if (library.rating.isNotBlank()) root.put("rating", library.rating)
        val casts = JSONObject()
        library.casts.forEach { (id, cast) ->
            casts.put(id, JSONObject().apply {
                put("text", cast.text)
                if (cast.male.isNotBlank()) put("male", cast.male)
            })
        }
        root.put("casts", casts)
        val pools = JSONObject()
        library.pools.forEach { (name, words) ->
            pools.put(name, JSONArray().apply {
                words.forEach { word -> put(wordJson(word)) }
            })
        }
        root.put("pools", pools)
        if (library.excluded.isNotEmpty()) {
            val off = JSONObject()
            library.excluded.forEach { (name, words) -> off.put(name, JSONArray(words.toList())) }
            root.put("excluded", off)
        }
        val templates = JSONObject()
        library.templates.forEach { (cast, lines) ->
            templates.put(cast, JSONArray(lines))
        }
        root.put("templates", templates)
        if (library.mutex.isNotEmpty()) {
            val mutex = JSONArray()
            library.mutex.forEach { (a, b) ->
                mutex.put(JSONArray().put(a).put(b))
            }
            root.put("mutex", mutex)
        }
        return root.toString(2)
    }

    private fun wordJson(word: StoryWord): Any {
        if (word.groups.isEmpty() && word.casts.isEmpty() && word.needs.isBlank() &&
            word.avoid.isBlank() && word.key.isBlank() && word.poses.isEmpty() &&
            word.props.isEmpty() && word.wears.isEmpty() && word.removes.isEmpty() &&
            word.requires.isEmpty() && word.requiresWorn.isEmpty() &&
            word.avoidGroups.isEmpty() && !word.common && !word.exclusive &&
            word.times.isEmpty() && word.eras.isEmpty() && word.keep.isEmpty() &&
            word.penetrating
        ) {
            return word.text
        }
        return JSONObject().apply {
            put("text", word.text)
            if (word.groups.isNotEmpty()) put("groups", JSONArray(word.groups))
            if (word.casts.isNotEmpty()) put("casts", JSONArray(word.casts))
            if (word.needs.isNotBlank()) put("needs", word.needs)
            if (word.avoid.isNotBlank()) put("avoid", word.avoid)
            if (word.key.isNotBlank()) put("key", word.key)
            if (word.poses.isNotEmpty()) put("poses", JSONArray(word.poses))
            if (word.props.isNotEmpty()) put("props", JSONArray(word.props))
            if (word.wears.isNotEmpty()) put("wears", JSONArray(word.wears))
            if (word.exclusive) put("exclusive", true)
            if (word.times.isNotEmpty()) put("times", JSONArray(word.times.toList()))
            if (word.removes.isNotEmpty()) put("removes", JSONArray(word.removes))
            if (word.requires.isNotEmpty()) put("requires", JSONArray(word.requires))
            if (word.requiresWorn.isNotEmpty()) put("requiresWorn", JSONArray(word.requiresWorn))
            if (word.avoidGroups.isNotEmpty()) put("avoidGroups", JSONArray(word.avoidGroups))
            if (word.eras.isNotEmpty()) put("eras", JSONArray(word.eras.toList()))
            if (word.keep.isNotEmpty()) put("keep", JSONArray(word.keep.toList()))
            if (!word.penetrating) put("penetrating", false)
            if (word.common) put("common", true)
        }
    }
}

private fun readMutex(array: JSONArray?): List<Pair<String, String>> {
    if (array == null) return emptyList()
    val out = mutableListOf<Pair<String, String>>()
    for (i in 0 until array.length()) {
        val pair = array.optJSONArray(i) ?: continue
        if (pair.length() < 2) continue
        val a = pair.optString(0).trim()
        val b = pair.optString(1).trim()
        if (a.isNotEmpty() && b.isNotEmpty()) out += a to b
    }
    return out
}
