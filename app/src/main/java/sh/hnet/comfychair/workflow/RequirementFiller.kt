package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.model.TagLibrary


/** Where a missing prerequisite got filled in. */
data class RequirementFill(
    val slotKey: String,
    val categoryLabel: String,
    val word: String,
    val need: String
)

/**
 * Puts the missing half of a dependent word into the slot that can carry it.
 *
 * Words like 只穿裤子 are only meaningful next to a pair of trousers, and the library records
 * that as a `requires` entry. The draw settles those on its own; a hand-picked word is exempt
 * from repair, so tapping one used to be cancelled with a toast and read as "the chip is dead".
 *
 * Choosing what to fill follows three rules, in order: never touch a locked or ignored slot,
 * never touch something the rules would reject anyway, and prefer the plainest word that
 * satisfies the need.
 */
object RequirementFiller {

    /** `top:衬衫` -> `衬衫`; a plain entry like `男` comes back unchanged. */
    fun needWord(entry: String): String = entry.substringAfter(':', entry)

    /** The category an entry insists on, or null when it does not name one. */
    fun needCategory(entry: String): String? =
        if (entry.contains(':')) entry.substringBefore(':').ifBlank { null } else null

    fun find(
        library: TagLibrary,
        needs: List<String>,
        selfCategoryId: String,
        picks: Map<String, TagPick>,
        blocked: (categoryId: String, slotId: String) -> Map<String, String>
    ): RequirementFill? {
        var best: RequirementFill? = null
        var bestScore = Int.MAX_VALUE

        needs.forEach { need ->
            library.categories.forEach { category ->
                if (category.id == selfCategoryId) return@forEach
                needCategory(need)?.let { if (category.id != it) return@forEach }
                category.slots.forEach { slot ->
                    val slotKey = "${category.id}.${slot.id}"
                    val current = picks[slotKey]
                    if (current != null && (current.ignored || current.locked)) return@forEach
                    val currentValue = current?.value.orEmpty()
                    // Already supplied by this slot; the caller only asks when nothing does.
                    if (names(currentValue, needWord(need))) return@forEach

                    val rejected = blocked(category.id, slot.id)
                    val candidate = slot.pool
                        .filter { names(it, needWord(need)) }
                        .filter { it !in rejected }
                        .filter { word -> picks.none { it.key != slotKey && it.value.value == word } }
                        .minByOrNull { preference(library, category.id, slot.id, it) }
                        ?: return@forEach

                    // Replacing something the user picked scores worse than filling a gap, and a
                    // shorter word scores better: 需要「裤」 should land on 长裤, not 皮质热裤.
                    val score = (if (currentValue.isEmpty()) 0 else 100) + candidate.length
                    if (score < bestScore) {
                        bestScore = score
                        best = RequirementFill(slotKey, category.label, candidate, needWord(need))
                    }
                }
            }
        }
        return best
    }

    /**
     * Whether [word] is the thing [need] names.
     *
     * Substring matching pulled 裤袜 (tights) in as a pair of trousers, so the tail has to match:
     * 瑜伽裤 yes, 裤袜 no — the object there is the sock.
     */
    fun names(word: String, need: String): Boolean =
        word == need || word.endsWith(need)

    /** 0 for a word the catalog already pins to the top of that slot, else its length. */
    private fun preference(library: TagLibrary, categoryId: String, slotId: String, word: String): Int {
        val pinned = library.commonFirst["$categoryId.$slotId"].orEmpty()
        return if (word in pinned) 0 else word.length + 1
    }
    /**
     * The category that could supply the need but is locked.
     *
     * Without this the caller can only report "no word in the catalog can supply it", which
     * blames the catalog for a lock the user set themselves.
     */
    fun lockedProvider(
        library: TagLibrary,
        needs: List<String>,
        selfCategoryId: String,
        picks: Map<String, TagPick>
    ): String? {
        needs.forEach needLoop@{ need ->
            val scope = needCategory(need)
            val word = needWord(need)
            library.categories.forEach catLoop@{ category ->
                if (category.id == selfCategoryId) return@catLoop
                if (scope != null && category.id != scope) return@catLoop
                category.slots.forEach slotLoop@{ slot ->
                    val pick = picks["${category.id}.${slot.id}"] ?: return@slotLoop
                    if (pick.locked && slot.pool.any { names(it, word) }) return category.label
                }
            }
        }
        return null
    }
}
