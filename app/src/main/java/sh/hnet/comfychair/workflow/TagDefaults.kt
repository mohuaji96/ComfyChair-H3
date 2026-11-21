package sh.hnet.comfychair.workflow

import sh.hnet.comfychair.model.TagLibrary

/**
 * The slots a library starts with switched off.
 *
 * Seeding only untouched slots is not enough: a saved selection — or one restored from a device
 * backup — outranks the default forever, so a category the library says starts off stays on for
 * anyone who ever touched it. "Default" therefore means every launch, and locking is the way to
 * keep one on.
 */
object TagDefaults {

    fun ignored(
        library: TagLibrary,
        existing: Map<String, TagPick>
    ): MutableMap<String, TagPick> {
        val out = existing.toMutableMap()
        if (library.defaultIgnored.isEmpty()) return out
        library.defaultIgnored.forEach { entry ->
            // An entry with a dot names one slot ("mood.palette"), which is how a single
            // dimension of a category starts switched off while the rest of it stays on.
            val slotKeys = if (entry.contains('.')) listOf(entry)
            else library.category(entry)?.slots?.map { "$entry.${it.id}" }.orEmpty()

            slotKeys.forEach { slotKey ->
                // A locked slot is a deliberate choice and outranks the default.
                if (out[slotKey]?.locked != true) out[slotKey] = TagPick(ignored = true)
            }
        }
        return out
    }

    /**
     * Categories whose first draw should be pinned.
     *
     * The caller has to pass the *saved* selection, never the map [ignored] just returned: a
     * category such as 风格 carries a default-ignored slot of its own, so its key exists in that
     * map and the category looked hand-edited -- it was never drawn, never pinned, and every
     * reroll changed the look.
     */
    fun lockedToSeed(library: TagLibrary, saved: Map<String, TagPick>): List<String> =
        library.defaultLocked.filter { categoryId ->
            val slots = library.category(categoryId)?.slots ?: return@filter false
            slots.none { saved["$categoryId.${it.id}"] != null }
        }
}
