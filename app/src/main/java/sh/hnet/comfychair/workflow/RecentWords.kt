package sh.hnet.comfychair.workflow

/**
 * The handful of words a slot just used, so a big pool stops feeling small.
 *
 * 调色 has 36 words and 光质 has 40, yet ten rerolls kept returning the same few: a uniform draw
 * over a large pool still repeats often. A word seen in the last few draws of the same slot is
 * weighted down rather than banned, so the pool stays fully reachable -- banning would make a
 * freshly added word starve whenever it happens to land early.
 */
class RecentWords(private val keep: Int = KEEP) {

    private val seen = mutableMapOf<String, ArrayDeque<String>>()

    fun weightOf(slotKey: String, word: String): Double =
        if (word in seen[slotKey].orEmpty()) RECENT_WEIGHT else 1.0

    fun remember(slotKey: String, word: String) {
        if (slotKey.isEmpty() || word.isEmpty()) return
        val queue = seen.getOrPut(slotKey) { ArrayDeque() }
        queue.remove(word)
        queue.addFirst(word)
        while (queue.size > keep) queue.removeLast()
    }

    fun clear() = seen.clear()

    private companion object {
        const val KEEP = 6
        const val RECENT_WEIGHT = 0.25
    }
}
