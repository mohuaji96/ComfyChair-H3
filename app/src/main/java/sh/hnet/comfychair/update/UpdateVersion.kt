package sh.hnet.comfychair.update

/**
 * Version comparison for the update check.
 *
 * The public release line and the private builds do not share a numbering scheme — GitHub's
 * newest release can be `0.9.8.668` (versionCode 78) while a local build says
 * `0.9.8.8382.570` (versionCode 320). Comparing those as strings would either miss a real
 * update or invent one, so the versionCode wins whenever the release tag carries one.
 */
object UpdateVersion {

    private val CODE_TAG = Regex("[-_ ]vc(\\d+)", RegexOption.IGNORE_CASE)

    /** `v0.9.8.668-vc78` → (`0.9.8.668`, 78). A tag without `-vc` yields a null code. */
    fun parseTag(tag: String): Pair<String, Int?> {
        val trimmed = tag.trim().removePrefix("v").removePrefix("V")
        val marker = CODE_TAG.find(trimmed)
        val code = marker?.groupValues?.getOrNull(1)?.toIntOrNull()
        // 后缀（`-vc78`、`-h3`、`-beta`）一律不进版本号；比大小只认数字段。
        val name = trimmed.substringBefore('-').substringBefore('_').substringBefore(' ').trim()
        return name to code
    }

    /** True when [remote] is newer than [local] by dotted segments. Unparsable input is never newer. */
    fun isNewerName(remote: String, local: String): Boolean {
        val a = segments(remote) ?: return false
        val b = segments(local) ?: return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    /**
     * Whether the release is worth telling the user about.
     *
     * @param remoteCode the `-vcNNN` from the tag, or null when the tag carries none.
     * @param localCode BuildConfig.VERSION_CODE of the running build.
     */
    fun isNewer(remoteName: String, remoteCode: Int?, localName: String, localCode: Int): Boolean {
        if (remoteCode != null && remoteCode > 0 && localCode > 0) return remoteCode > localCode
        return isNewerName(remoteName, localName)
    }

    private fun segments(name: String): List<Int>? {
        val core = name.substringBefore('-').substringBefore('_').trim()
        if (core.isEmpty()) return null
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        return parts.mapNotNull { it.toIntOrNull() }
    }
}
