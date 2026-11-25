package sh.hnet.comfychair.model

import android.content.Context
import sh.hnet.comfychair.R

/**
 * 运行时的「零露骨」断言。
 *
 * 构建期由 tools/check_sfw.py 把同一份禁词表跑一遍，这里再跑一次，理由只有一个：
 * 脚本管得到仓库里的文件，管不到手机上那份。用户导入、词库自动合并、老版本残留，
 * 都可能让 working 副本里混进脚本从没见过的东西。禁词表只有一份（assets 里的这份），
 * 脚本那边读的是 tools 里那份，构建时比对一致——两处永远不会各说各话。
 */
object SfwGuard {

    private const val MAX_REPORT = 20

    @Volatile
    private var cached: List<String>? = null

    @Volatile
    private var cachedAllow: Set<String>? = null

    fun forbidden(context: Context): List<String> {
        cached?.let { return it }
        val loaded = readRuleFile(context, "forbidden_nsfw.txt")
        cached = loaded
        return loaded
    }

    /**
     * 与 check_sfw.py 用的是同一份规则：先命中禁词，再看整条是否在放行名单里。
     * 口径不一致时会出现「构建过了、导入却被拒」，用户只会觉得这个开关坏了。
     */
    fun allowed(context: Context): Set<String> {
        cachedAllow?.let { return it }
        val loaded = readRuleFile(context, "allow_sfw.txt").toSet()
        cachedAllow = loaded
        return loaded
    }

    private fun readRuleFile(context: Context, name: String): List<String> = runCatching {
        context.assets.open(name).use { input ->
            input.bufferedReader(Charsets.UTF_8).readLines()
        }
    }.getOrDefault(emptyList())
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    /**
     * 导入前的整包体检。
     *
     * 只查「安全 + 结构」两类：禁词、档位、池空、分类重复、人数上限。
     * 不查 pool 条数下限、label 语言、规则引用——那些是词库质量问题，不该挡住用户
     * 导入自己精简过的库，而且完整的九条由 tools/check_sfw.py 在构建期兜。
     */
    fun validate(context: Context, library: TagLibrary): List<String> {
        val bad = mutableListOf<String>()
        if (library.categories.isEmpty()) return listOf(context.getString(R.string.err_sfw_no_categories))

        val forbidden = forbidden(context)
        val allow = allowed(context)
        val seen = mutableMapOf<String, String>()
        val ids = library.categories.map { it.id }

        ids.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
            bad += context.getString(R.string.err_sfw_duplicate_id, it)
        }

        library.categories.forEach { category ->
            category.slots.forEach { slot ->
                if (slot.pool.isEmpty()) {
                    bad += context.getString(R.string.err_sfw_empty_pool, category.label, slot.label)
                }
                slot.pool.forEach { word ->
                    val hit = forbidden.firstOrNull { it in word }
                    if (hit != null && word !in allow) {
                        bad += context.getString(R.string.err_sfw_forbidden_hit, hit, word)
                    }
                    val owner = seen.putIfAbsent(word, category.id)
                    if (owner != null && owner != category.id) {
                        bad += context.getString(
                            R.string.err_sfw_cross_duplicate, word, owner, category.id
                        )
                    }
                }
            }
        }

        library.category("people")?.slots?.forEach { slot ->
            slot.pool.filter { it.contains("两男") || it.contains("三男") || it.contains("多男") }
                .forEach { bad += context.getString(R.string.err_sfw_multi_male, it) }
        }
        library.constraints.peopleCount.forEach { (word, count) ->
            if (count > 3) bad += context.getString(R.string.err_sfw_people_over, word, count)
        }

        return bad.take(MAX_REPORT)
    }

    /** 可渲染词的并集与禁词表求交；空列表表示干净。 */
    fun violations(context: Context, library: TagLibrary): List<String> =
        collect(context, library.categories.flatMap { category ->
            category.slots.flatMap { it.pool }
        } + library.stylePrompts.values)

    fun violations(context: Context, library: StoryLibrary): List<String> =
        collect(
            context,
            library.pools.values.flatten().map { it.text } +
                library.casts.values.flatMap { listOf(it.text, it.male) }
        )

    /** 剧情词库的导入体检，口径与标签词库一致：只查安全与结构。 */
    fun validate(context: Context, library: StoryLibrary): List<String> {
        val bad = mutableListOf<String>()
        if (library.pools.isEmpty()) bad += context.getString(R.string.err_sfw_no_pools)
        if (library.casts.isEmpty()) bad += context.getString(R.string.err_sfw_no_casts)

        val forbidden = forbidden(context)
        val allow = allowed(context)
        fun check(where: String, word: String) {
            if (word.isBlank()) return
            val hit = forbidden.firstOrNull { it in word } ?: return
            if (word !in allow) bad += context.getString(R.string.err_sfw_hit_in, where, hit, word)
        }
        library.pools.forEach { (name, words) -> words.forEach { check(name, it.text) } }
        library.casts.forEach { (id, cast) ->
            check("cast.$id", cast.text)
            check("cast.$id.male", cast.male)
        }
        return bad.take(MAX_REPORT)
    }

    private fun collect(context: Context, words: List<String>): List<String> {
        val forbidden = forbidden(context)
        if (forbidden.isEmpty()) return emptyList()
        return words.asSequence()
            .mapNotNull { word ->
                val hit = forbidden.firstOrNull { it in word } ?: return@mapNotNull null
                context.getString(R.string.err_sfw_violation, hit, word)
            }
            .take(MAX_REPORT)
            .toList()
    }
}
