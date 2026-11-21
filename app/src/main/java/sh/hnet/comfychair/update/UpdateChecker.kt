package sh.hnet.comfychair.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One release as the about page needs it. */
data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val versionCode: Int?,
    val notes: String,
    val pageUrl: String
)

/**
 * Asks GitHub for the newest published release.
 *
 * `releases/latest` skips drafts and pre-releases, so a half-finished tag never becomes the
 * thing the app points users at. Everything is read-only and unauthenticated; the only thing
 * that leaves the phone is this one GET.
 */
class UpdateChecker(private val client: OkHttpClient = defaultClient()) {

    fun fetchLatest(): UpdateInfo {
        val request = Request.Builder()
            .url(RELEASES_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val json = JSONObject(body)
            val tag = json.optString("tag_name").trim()
            if (tag.isEmpty()) throw IllegalStateException("no tag_name")
            val (name, code) = UpdateVersion.parseTag(tag)
            return UpdateInfo(
                tag = tag,
                versionName = name,
                versionCode = code,
                notes = json.optString("body").trim(),
                pageUrl = json.optString("html_url").trim()
            )
        }
    }

    companion object {
        const val RELEASES_LATEST =
            "https://api.github.com/repos/mohuaji96/ComfyChair-H3/releases/latest"

        private const val USER_AGENT = "ComfyChairH3"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }
}
