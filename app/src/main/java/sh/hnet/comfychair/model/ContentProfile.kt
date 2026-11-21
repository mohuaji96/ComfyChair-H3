package sh.hnet.comfychair.model

/**
 * 内容级别。两档各自一整套词库，永不混合。
 *
 * 文件名的派生规则集中在 [tagStem] / [storyStem]：NSFW 沿用历史文件名一个字符都不改，
 * 避免老用户升级时丢词库；SFW 全部带 `.sfw` / `_sfw` 标记，两档的文件天然不撞。
 *
 * 这一层必须由 profile 统一派生。逐个 if 拼文件名的写法漏掉一个派生文件（`.pinned`、
 * `.backup`）就会让两档互相污染——SFW 档读到 NSFW 的「用户导入过」标记，从此再也
 * 收不到内置更新，而界面上完全看不出来。
 */
enum class ContentProfile(
    val key: String,
    /** assets 里的标签词库文件名。 */
    val tagAsset: String,
    /** filesDir 里标签词库的文件名主干，后缀由 [tagStem] 派生。 */
    val tagStem: String,
    /** assets 里的剧情词库文件名。 */
    val storyAsset: String,
    /** filesDir 里剧情词库的文件名主干。 */
    val storyStem: String
) {
    NSFW(
        key = "nsfw",
        tagAsset = "tag_catalog.json",
        tagStem = "tag_catalog",
        storyAsset = "story_catalog.json",
        storyStem = "story_catalog"
    ),
    SFW(
        key = "sfw",
        tagAsset = "tag_catalog_sfw.json",
        tagStem = "tag_catalog.sfw",
        storyAsset = "story_catalog_sfw.json",
        storyStem = "story_catalog.sfw"
    );

    val isSfw: Boolean get() = this == SFW

    /** 界面上怎么称呼这一档。 */
    val label: String get() = if (isSfw) "SFW" else "NSFW"

    /** 导出文件名：词库组合-NSFW-0.9.8.8382.526.json。带档位和版本，回传时一眼认得出。 */
    fun exportFileName(prefix: String, version: String): String = "$prefix-$label-$version.json"

    /** 上一版内置词库，只作合并基线，不进界面。 */
    val tagSeedName: String get() = tagAsset.removeSuffix(".json") + ".previous.json"

    /** 剧情词库的自定义词文件：换库、还原内置都不清它。NSFW 侧名字与历史版本一致，不能改。 */
    val storyCustomName: String
        get() = if (isSfw) "story_custom.sfw.json" else "story_custom.json"

    companion object {
        fun fromKey(key: String?): ContentProfile = if (key == SFW.key) SFW else NSFW

        /** 导入的文件自报 `meta.rating` 时用它判档，缺省按成人档处理（保守）。 */
        fun fromRating(rating: String?): ContentProfile = if (rating == "general") SFW else NSFW
    }
}
