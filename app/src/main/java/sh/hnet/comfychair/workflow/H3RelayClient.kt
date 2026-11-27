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

    /**
     * The first plugin that runs a caller-built graph (`plan[i].graph`) instead of rebuilding
     * every segment from its own template. Below this one a chain is built from the plugin's
     * bundled model and LoRA, so a phone that paints its own graph must not send one.
     */
    const val GRAPH_MIN_VERSION = "1.1.0"

    private const val INFO_ROUTE = "/h3relay/info"
    /**
     * Where a caller-built graph wants the previous segment's last frame. It cannot name a
     * socket, because that is its own template's business, so it writes this string in the
     * image slot and the plugin swaps the real filename in before submitting.
     */
    const val TAIL_PLACEHOLDER = "h3relay_tail"

    private const val CHAINS_ROUTE = "/h3relay/chains"
    private const val CHAIN_ROUTE = "/h3relay/chain"
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

    /** The states a chain reports in `state`. */
    object ChainState {
        const val QUEUED = "queued"
        const val RUNNING = "running"
        const val PAUSED = "paused"
        const val DONE = "done"
        const val FAILED = "failed"
        const val STOPPED = "stopped"
    }

    private val ENDED = setOf(ChainState.DONE, ChainState.FAILED, ChainState.STOPPED)

    /** One segment as the caller describes it; [graph] is ignored by plugins before 1.3.0. */
    data class Segment(
        val prompt: String,
        val params: JSONObject,
        val references: List<String> = emptyList(),
        val seed: Long? = null,
        val startImage: String? = null,
        val graph: JSONObject? = null
    )

    /** What `POST /h3relay/chain` answers on acceptance. [project] is the deduped name. */
    data class Accepted(
        val chainId: String,
        /** The name the server actually files this chain under, after `-2`, `-3`… */
        val project: String,
        val segments: Int,
        val queuePosition: Int,
        val duplicate: Boolean
    )

    /**
     * Everything a chain says about itself, read on a poll. Mirrors `store.summary`, so the
     * progress bar, the queue sheet and the notification can all read one object.
     */
    data class Summary(
        val chainId: String,
        val prefix: String,
        val state: String,
        val segment: Int,
        val of: Int,
        val done: Int,
        /** The running segment's own state; null when nothing is running. */
        val stage: String?,
        val pauseRequested: Boolean,
        val templateId: String?,
        val claimed: Boolean,
        val error: String?,
        val failedAt: Int?,
        val startedAt: Long,
        val updatedAt: Long
    ) {
        /** No further segment will start on its own. */
        val ended: Boolean get() = state in ENDED
        val running: Boolean get() = state == ChainState.RUNNING

        /** 1-based number of the segment being worked on, for "第 n / N 段". */
        val current: Int get() = segment.coerceIn(1, of.coerceAtLeast(1))
    }

    /** Why a chain was refused; the caller turns this into a localized sentence. */
    enum class Failure(val wire: String) {
        TEMPLATE_UNKNOWN("template_unknown"),
        TEMPLATE_NO_RELAY("template_no_relay"),
        TEMPLATE_BROKEN("template_broken"),
        TEMPLATE_MISMATCH("template_mismatch"),
        BAD_SEGMENTS("bad_segments"),
        BAD_PLAN("bad_plan"),
        REFERENCE_MISSING("reference_missing"),
        BAD_REFERENCES("bad_references"),
        TOO_MANY_REFERENCES("too_many_references"),
        START_MISSING("start_missing"),
        DISK_FULL("disk_full"),
        BAD_NUMBERING("bad_numbering"),
        BAD_PROJECT("bad_project"),
        BAD_JSON("bad_json"),
        BAD_CHAIN_ID("bad_chain_id"),
        UNKNOWN_CHAIN("unknown_chain"),
        DISABLED("disabled"),
        UNKNOWN("");

        companion object {
            fun of(code: String?): Failure =
                entries.firstOrNull { it != UNKNOWN && it.wire == code } ?: UNKNOWN
        }
    }

    /** A refusal carries the plugin's own text and, for a reference ceiling, the slot count. */
    data class Refused(val failure: Failure, val message: String?, val slots: Int?)

    sealed interface Answer<out T> {
        data class Ok<T>(val value: T) : Answer<T>
        data class Rejected(val refused: Refused) : Answer<Nothing>
        /** The chain is gone: the plugin restarted or the record was pruned. */
        data object Gone : Answer<Nothing>
        data class Unreachable(val message: String?) : Answer<Nothing>
    }

    // ---- wire -------------------------------------------------------------------------

    /** Builds the body `POST /h3relay/chain` expects; [project] is the user's chain name. */
    fun chainBody(templateId: String, project: String, segments: List<Segment>): JSONObject {
        val plan = JSONArray()
        segments.forEach { segment ->
            plan.put(
                JSONObject()
                    .put("prompt", segment.prompt)
                    .put("params", segment.params)
                    .put("references", JSONArray(segment.references))
                    .apply {
                        segment.seed?.let { put("seed", it) }
                        segment.startImage?.let { put("startImage", it) }
                        // Only a plugin that understands a whole graph gets one; an older one
                        // would ignore it and apply its own template instead.
                        segment.graph?.let { put("graph", it) }
                    }
            )
        }
        return JSONObject()
            .put("templateId", templateId)
            .put("segments", segments.size)
            .put("plan", plan)
            .put("numbering", JSONObject().put("prefix", project))
    }

    suspend fun submitChain(client: ComfyUIClient, body: JSONObject): Answer<Accepted> =
        when (val answer = client.postJson(CHAIN_ROUTE, body.toString(), MAX_BYTES)) {
            is ComfyUIClient.TextResult.Ok -> parseAccepted(answer.text)
            is ComfyUIClient.TextResult.HttpError -> Answer.Rejected(Refused(Failure.UNKNOWN, null, null))
            is ComfyUIClient.TextResult.TooLarge -> Answer.Unreachable("answer too large")
            is ComfyUIClient.TextResult.Failed -> Answer.Unreachable(answer.message)
        }

    suspend fun chain(client: ComfyUIClient, chainId: String): Answer<Summary> =
        when (val answer = get(client, "$CHAIN_ROUTE/${quote(chainId)}") { it }) {
            is Answer.Ok -> parseSummary(answer.value)
            is Answer.Rejected -> answer
            is Answer.Gone -> Answer.Gone
            is Answer.Unreachable -> answer
        }

    suspend fun chains(client: ComfyUIClient): Answer<List<Summary>> =
        get(client, CHAINS_ROUTE) { text ->
            val list = JSONObject(text).optJSONArray("chains") ?: JSONArray()
            (0 until list.length()).mapNotNull { list.optJSONObject(it)?.toSummary() }
        }

    /** The files a chain has written so far, and whether they were already taken. */
    data class Files(val chainId: String, val claimed: Boolean, val files: List<FileRef>)

    data class FileRef(val index: Int, val kind: String, val path: String, val bytes: Long)

    suspend fun results(client: ComfyUIClient, chainId: String): Answer<Files> =
        get(client, "$CHAIN_ROUTE/${quote(chainId)}/results") { text ->
            val root = JSONObject(text)
            val list = root.optJSONArray("files") ?: JSONArray()
            Files(
                chainId = root.optString("chainId"),
                claimed = root.optBoolean("claimed"),
                files = (0 until list.length()).mapNotNull { position ->
                    val item = list.optJSONObject(position) ?: return@mapNotNull null
                    val path = item.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    FileRef(
                        index = item.optInt("index", 0),
                        kind = item.optString("kind", "video"),
                        path = path,
                        bytes = item.optLong("bytes", 0L)
                    )
                }
            )
        }

    /**
     * Marks the results as taken, so a second phone in the same account never imports the same
     * clip twice. Claiming is the last step: a chain claimed before its files are downloaded
     * would have no way back to them.
     */
    suspend fun claim(client: ComfyUIClient, chainId: String): Answer<Unit> =
        when (val answer = client.postJson("$CHAIN_ROUTE/${quote(chainId)}/claimed", "{}", MAX_BYTES)) {
            is ComfyUIClient.TextResult.Ok -> Answer.Ok(Unit)
            is ComfyUIClient.TextResult.HttpError ->
                if (answer.code == 404) Answer.Gone else Answer.Rejected(Refused(Failure.UNKNOWN, null, null))
            is ComfyUIClient.TextResult.TooLarge -> Answer.Unreachable("answer too large")
            is ComfyUIClient.TextResult.Failed -> Answer.Unreachable(answer.message)
        }

    /** pause / resume / stop; `stop` ends the chain after the segment already running. */
    suspend fun control(client: ComfyUIClient, chainId: String, action: String): Answer<Summary> =
        when (val answer = client.postJson("$CHAIN_ROUTE/${quote(chainId)}/$action", "{}", MAX_BYTES)) {
            is ComfyUIClient.TextResult.Ok -> parseSummary(answer.text)
            is ComfyUIClient.TextResult.HttpError ->
                if (answer.code == 404) Answer.Gone else Answer.Rejected(Refused(Failure.UNKNOWN, null, null))
            is ComfyUIClient.TextResult.TooLarge -> Answer.Unreachable("answer too large")
            is ComfyUIClient.TextResult.Failed -> Answer.Unreachable(answer.message)
        }

    // ---- parsing ----------------------------------------------------------------------

    fun parseSummary(text: String): Answer<Summary> = try {
        val root = JSONObject(text)
        val code = root.optString("error")
        if (code.isNotBlank()) {
            if (code == Failure.UNKNOWN_CHAIN.wire) Answer.Gone
            else Answer.Rejected(Refused(Failure.of(code), root.optString("message").ifBlank { null },
                if (root.has("slots")) root.optInt("slots", 0) else null))
        } else {
            Answer.Ok(root.toSummary())
        }
    } catch (_: Exception) {
        Answer.Unreachable("unreadable answer")
    }

    fun parseAccepted(text: String): Answer<Accepted> = try {
        val root = JSONObject(text)
        val code = root.optString("error")
        when {
            code.isNotBlank() -> Answer.Rejected(Refused(
                Failure.of(code), root.optString("message").ifBlank { null },
                if (root.has("slots")) root.optInt("slots", 0) else null
            ))
            !root.optBoolean("accepted") -> Answer.Rejected(Refused(Failure.UNKNOWN, null, null))
            else -> Answer.Ok(Accepted(
                chainId = root.optString("chainId"),
                // Echoed back because the plugin is the one that knows whether the name was
                // free; the phone never invents a `-2` of its own.
                project = root.optString("project"),
                segments = root.optInt("segments", 0),
                queuePosition = root.optInt("queuePosition", 0),
                duplicate = root.optBoolean("duplicate")
            ))
        }
    } catch (_: Exception) {
        Answer.Unreachable("unreadable answer")
    }

    private fun JSONObject.toSummary(): Summary = Summary(
        chainId = optString("chainId"),
        prefix = optString("prefix"),
        state = optString("state"),
        segment = optInt("segment", 0),
        of = optInt("of", 0),
        done = optInt("done", 0),
        stage = optString("stage").takeIf { it.isNotBlank() && it != "null" },
        pauseRequested = optBoolean("pauseRequested"),
        templateId = optString("templateId").takeIf { it.isNotBlank() },
        claimed = optBoolean("claimed"),
        error = optString("error").takeIf { it.isNotBlank() && it != "null" },
        failedAt = if (isNull("failedAt")) null else optInt("failedAt", 0),
        startedAt = optLong("startedAt", 0L),
        updatedAt = optLong("updatedAt", 0L)
    )

    private suspend fun <T> get(
        client: ComfyUIClient,
        path: String,
        read: (String) -> T
    ): Answer<T> = when (val answer = client.getText(path, MAX_BYTES)) {
        is ComfyUIClient.TextResult.Ok -> when (val refusal = parseError(answer.text)) {
            null -> try {
                Answer.Ok(read(answer.text))
            } catch (_: Exception) {
                Answer.Unreachable("unreadable answer")
            }
            else -> refusal
        }
        is ComfyUIClient.TextResult.HttpError ->
            if (answer.code == 404) Answer.Gone else Answer.Rejected(Refused(Failure.UNKNOWN, null, null))
        is ComfyUIClient.TextResult.TooLarge -> Answer.Unreachable("answer too large")
        is ComfyUIClient.TextResult.Failed -> Answer.Unreachable(answer.message)
    }

    /** A GET that came back 200 with an `error` body is still a refusal. */
    private fun parseError(text: String): Answer.Rejected? = try {
        val root = JSONObject(text)
        val code = root.optString("error")
        if (code.isBlank()) null else Answer.Rejected(Refused(
            Failure.of(code), root.optString("message").ifBlank { null },
            if (root.has("slots")) root.optInt("slots", 0) else null
        ))
    } catch (_: Exception) {
        null
    }

    /** Chain ids are 1-64 safe characters; only what is already safe is put on the path. */
    private fun quote(chainId: String): String =
        java.net.URLEncoder.encode(chainId, "UTF-8")

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
