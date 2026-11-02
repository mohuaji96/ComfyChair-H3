package sh.hnet.comfychair.cache

import android.content.Context

/**
 * Limits and per-item flags for the on-device media cache.
 *
 * The cache lives under `cacheDir` on purpose: it is a disposable copy that keeps the gallery
 * responsive and the last generation playable offline, not the user's library. Anything the user
 * wants to keep is downloaded to the system gallery explicitly, which survives clearing the cache.
 */
object LocalCachePolicy {

    private const val PREFS_NAME = "media_cache_policy"
    private const val KEY_LIMIT_GB = "limit_gb"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_TOMBSTONES = "tombstones"

    /** 0 means "no limit". */
    const val UNLIMITED_GB = 0L
    const val DEFAULT_LIMIT_GB = 5L

    val limitOptionsGb: List<Long> = listOf(5L, 10L, 20L, 50L, UNLIMITED_GB)

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun limitGb(context: Context): Long = prefs(context).getLong(KEY_LIMIT_GB, DEFAULT_LIMIT_GB)

    fun setLimitGb(context: Context, gb: Long) {
        prefs(context).edit().putLong(KEY_LIMIT_GB, gb).apply()
    }

    /** Byte limit, or 0 when unlimited. */
    fun limitBytes(context: Context): Long {
        val gb = limitGb(context)
        return if (gb <= 0L) 0L else gb * 1024L * 1024L * 1024L
    }

    fun favorites(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAVORITES, emptySet())?.toSet().orEmpty()

    fun isFavorite(context: Context, key: String): Boolean = key in favorites(context)

    fun setFavorite(context: Context, key: String, favorite: Boolean) {
        val p = prefs(context)
        val next = favorites(context).toMutableSet()
        if (favorite) next.add(key) else next.remove(key)
        p.edit().putStringSet(KEY_FAVORITES, next).apply()
    }

    /**
     * Items the user removed on purpose. Background prefetching skips them, so emptying the cache
     * cannot silently refill itself on the next refresh; opening the item again clears the mark.
     */
    fun isTombstoned(context: Context, key: String): Boolean =
        prefs(context).getStringSet(KEY_TOMBSTONES, emptySet())?.contains(key) == true

    fun tombstone(context: Context, keys: Collection<String>) {
        if (keys.isEmpty()) return
        val p = prefs(context)
        val next = p.getStringSet(KEY_TOMBSTONES, emptySet()).orEmpty().toMutableSet()
        next.addAll(keys)
        p.edit().putStringSet(KEY_TOMBSTONES, next).apply()
    }

    fun clearTombstone(context: Context, key: String) {
        val p = prefs(context)
        val current = p.getStringSet(KEY_TOMBSTONES, emptySet()).orEmpty()
        if (key !in current) return
        p.edit().putStringSet(KEY_TOMBSTONES, current - key).apply()
    }

    fun clearTombstones(context: Context) {
        prefs(context).edit().remove(KEY_TOMBSTONES).apply()
    }
}
