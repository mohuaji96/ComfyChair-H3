package sh.hnet.comfychair.workflow

import org.json.JSONArray

/** Server filenames are opaque strings: keep subdirectories, case and Unicode. */
object ModelListParser {
    fun strings(array: JSONArray): List<String> {
        require((0 until array.length()).all { array.opt(it) is String }) { "Expected string model list" }
        return (0 until array.length()).map { array.getString(it) }
    }
    fun folder(body: String): List<String> = strings(JSONArray(body))
    fun options(spec: JSONArray?): JSONArray? = spec?.optJSONArray(0)
        ?: if (spec?.optString(0) == "COMBO") spec.optJSONObject(1)?.optJSONArray("options") else null
}
