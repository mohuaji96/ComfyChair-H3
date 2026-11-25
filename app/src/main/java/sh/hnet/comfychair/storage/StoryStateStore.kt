package sh.hnet.comfychair.storage

import android.content.Context
import org.json.JSONObject
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.StoryPick

data class StoryState(
    val picks: Map<String, StoryPick>,
    val detail: Boolean,
    val dialogue: Boolean,
    /** 整段锁住的段名（人物/男主/场景与穿着/过程/其它）。 */
    val lockedSections: Set<String> = emptySet()
)

/**
 * 剧情组合的选择存档。
 *
 * 存下来主要是为了修一个难看的问题：以前每次进页面都重新初始化为「一男一女 + 锁定」，
 * 第二次进去选择就没了。现在进来先读这份，没有才初始化。
 */
class StoryStateStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("story_composer_state", Context.MODE_PRIVATE)

    fun load(): StoryState? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val picksJson = root.optJSONObject("picks") ?: return null
            val picks = picksJson.keys().asSequence().associateWith { key ->
                val entry = picksJson.getJSONObject(key)
                StoryPick(
                    // 旧版多段用中文逗号连，现在换成竖线，读的时候顺手迁一下。
                    value = migrate(key, entry.optString("v", "")),
                    locked = entry.optBoolean("l", false),
                    ignored = entry.optBoolean("i", false)
                )
            }
            val locked = root.optJSONArray("locked_sections")
            StoryState(
                picks = upgrade(picks),
                detail = root.optBoolean("detail", true),
                dialogue = root.optBoolean("dialogue", false),
                lockedSections = buildSet {
                    for (i in 0 until (locked?.length() ?: 0)) {
                        locked.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            )
        }.getOrNull()
    }


    /**
     * 老存档搬过来时补两件事：
     *
     * 一是「内搭」那一格已经拆成袜和鞋，旧的丝袜落进袜、旧的高跟鞋落进鞋，
     * 直接丢会让人以为选择被清了。
     *
     * 二是情绪改成默认关之后，老存档里根本没有这个键，按空值算就会在重抽时
     * 自动填一句回来。这里显式补成「忽略」。
     */
    private fun upgrade(picks: Map<String, StoryPick>): Map<String, StoryPick> {
        val out = picks.toMutableMap()
        val legacy = out.remove("inner")
        if (legacy != null && legacy.value.isNotEmpty()) {
            val shoes = legacy.value.split("，", StoryGenerator.SEP)
                .filter { it.isNotBlank() }
                .filter { it in SHOE_WORDS }
            val socks = legacy.value.split("，", StoryGenerator.SEP)
                .filter { it.isNotBlank() }
                .filter { it !in SHOE_WORDS }
            if (socks.isNotEmpty() && out[StoryGenerator.SOCKS]?.value.isNullOrEmpty()) {
                out[StoryGenerator.SOCKS] = StoryPick(socks.joinToString(StoryGenerator.SEP))
            }
            if (shoes.isNotEmpty() && out[StoryGenerator.SHOES]?.value.isNullOrEmpty()) {
                out[StoryGenerator.SHOES] = StoryPick(shoes.joinToString(StoryGenerator.SEP))
            }
        }
        if (StoryGenerator.MOOD !in out) {
            out[StoryGenerator.MOOD] = StoryPick(ignored = true)
        }
        // 道具整格默认忽略：不加就不该自己冒出来。
        if (StoryGenerator.PROP !in out) {
            out[StoryGenerator.PROP] = StoryPick(ignored = true)
        }
        // 痕迹同理：默认不出，想看事中/事后的痕再点开。
        if (StoryGenerator.MARK !in out) {
            out[StoryGenerator.MARK] = StoryPick(ignored = true)
        }
        return out
    }

    /** 旧存档里姿势和动作的多段值是用「，」连的，迁到新分隔符。 */
    private fun migrate(key: String, value: String): String {
        if (key != StoryGenerator.ACT && key != StoryGenerator.POSE) return value
        if (value.contains(StoryGenerator.SEP)) return value
        return value.split("，").filter { it.isNotBlank() }.joinToString(StoryGenerator.SEP)
    }

    fun save(state: StoryState) {
        val picks = JSONObject()
        state.picks.forEach { (key, pick) ->
            picks.put(key, JSONObject().apply {
                put("v", pick.value)
                put("l", pick.locked)
                put("i", pick.ignored)
            })
        }
        val root = JSONObject().apply {
            put("detail", state.detail)
            put("dialogue", state.dialogue)
            put("picks", picks)
            put("locked_sections", org.json.JSONArray(state.lockedSections.toList()))
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "state"

        /** 旧「内搭」里属于脚上的那几件。 */
        val SHOE_WORDS = setOf("高跟鞋", "绣花鞋", "拖鞋", "赤脚", "木屐", "布鞋")
    }
}
