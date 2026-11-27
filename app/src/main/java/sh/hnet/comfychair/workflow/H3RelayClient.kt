package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient

/**
 * Reads the server-side relay plugin.
 *
 * 连拍 is not something the phone can do on its own any more: the whole chain runs inside
 * ComfyUI, so a phone-only switch would promise chains nothing is going to run. Everything
 * here answers one question — is there a plugin that will take this chain, and how many
 * pictures does the template it drives really hold.
 */
object H3RelayClient {

    /** The chain contract (`plan`, `numbering`, per-template slots) landed in this version. */
    const val MIN_VERSION = "1.0.0"

    private const val INFO_ROUTE = "/h3relay/info"
    private const val MAX_BYTES = 256L * 1024

    /**
     * What the plugin says about itself. [reservedCapacity] is the node's own picture ceiling —
     * 9 on both reference templates — not how many sockets a template happens to wire.
     */
    data class Info(
        val version: String,
        val relayTemplates: List<String>,
        val refSlots: Map<String, Int>,
        val tailBackoffMs: List<Long>
    ) {
        fun capacity(templateId: String): Int = refSlots[templateId] ?: 0
    }

    /** The plugin, or null when this server has none, it is switched off, or it is too old. */
    suspend fun info(client: ComfyUIClient): Info? = when (
        val answer = client.getText(INFO_ROUTE, MAX_BYTES)
    ) {
        is ComfyUIClient.TextResult.Ok -> parse(answer.text)
        else -> null
    }

    fun parse(text: String): Info? = try {
        val root = JSONObject(text)
        val version = root.optString("version")
        when {
            root.optString("plugin") != "h3-relay" -> null
            !root.optBoolean("enabled", true) -> null
            !atLeast(version, MIN_VERSION) -> null
            else -> {
                val templates = root.relayTemplateIds()
                if (templates.isEmpty()) null else {
                    Info(
                        version = version,
                        relayTemplates = templates,
                        refSlots = root.optJSONObject("refSlots")?.toStringIntMap().orEmpty(),
                        tailBackoffMs = root.optJSONArray("tailBackoffMs")?.toLongList().orEmpty()
                    )
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Numeric comparison, so 1.10.0 is newer than 1.2.0 instead of alphabetically smaller. */
    fun atLeast(version: String, minimum: String): Boolean {
        val have = version.split('.').mapNotNull { it.trim().toIntOrNull() }
        val need = minimum.split('.').mapNotNull { it.trim().toIntOrNull() }
        if (have.isEmpty() || need.isEmpty()) return false
        for (index in 0 until maxOf(have.size, need.size)) {
            val a = have.getOrElse(index) { 0 }
            val b = need.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return true
    }

    /**
     * Which templates may be chained. The plugin answers with `{id: digest}` so the phone can
     * also see which fingerprint a chain would be accepted against; an older one answered with a
     * plain list. Read both, because a probe that only understands one shape reports "no plugin"
     * against a server that is in fact ready.
     */
    private fun JSONObject.relayTemplateIds(): List<String> =
        optJSONArray("relayTemplates")?.toStringList()
            ?: optJSONObject("relayTemplates")
                ?.let { table -> table.keys().asSequence().toList() }
                .orEmpty()

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }

    private fun JSONArray.toLongList(): List<Long> =
        (0 until length()).mapNotNull { optLong(it, -1L).takeIf { value -> value > 0 } }

    private fun JSONObject.toStringIntMap(): Map<String, Int> =
        keys().asSequence()
            .mapNotNull { key -> optInt(key, 0).takeIf { it > 0 }?.let { key to it } }
            .toMap()
}

/**
 * The last successful probe, kept so later screens do not have to ask again and so a chain
 * built from a stale answer can be told apart from one built against this server.
 */
object H3RelayState {
    @Volatile
    var info: H3RelayClient.Info? = null

    @Volatile
    var serverId: String? = null

    fun remember(serverId: String?, info: H3RelayClient.Info?) {
        this.serverId = serverId
        this.info = info
    }

    /** The probe result for [serverId], or null when it belongs to another server. */
    fun forServer(serverId: String?): H3RelayClient.Info? =
        info.takeIf { this.serverId != null && this.serverId == serverId }
}
