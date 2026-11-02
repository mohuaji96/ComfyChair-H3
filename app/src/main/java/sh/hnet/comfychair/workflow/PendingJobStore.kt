package sh.hnet.comfychair.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Survives process death: the only way to find a job again after Android kills the app or the
 * WebSocket stays down long enough to drop the in-memory task.
 *
 * Keyed by promptId so a reconnect can ask `/history` for exactly the jobs this device started —
 * other clients' queue entries are never adopted.
 */
class PendingJobStore(context: Context) {

    data class PendingJob(
        val promptId: String,
        val serverId: String,
        val workflowName: String,
        val submittedAt: Long,
        val state: String
    )

    private val prefs = context.applicationContext
        .getSharedPreferences("h3_pending_jobs", Context.MODE_PRIVATE)

    fun save(job: PendingJob) {
        val all = load().toMutableMap()
        all[job.promptId] = job
        write(all.values)
    }

    fun updateState(promptId: String, state: String) {
        val all = load().toMutableMap()
        val existing = all[promptId] ?: return
        all[promptId] = existing.copy(state = state)
        write(all.values)
    }

    fun remove(promptId: String) {
        val all = load().toMutableMap()
        all.remove(promptId)
        write(all.values)
    }

    /** Jobs that still need a `/history` check, oldest first. */
    fun pending(): List<PendingJob> =
        load().values.filter { it.state == "QUEUED" || it.state == "RUNNING" }
            .sortedBy { it.submittedAt }

    fun load(): Map<String, PendingJob> {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, PendingJob>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("promptId")
            if (id.isEmpty()) continue
            out[id] = PendingJob(
                promptId = id,
                serverId = o.optString("serverId"),
                workflowName = o.optString("workflowName"),
                submittedAt = o.optLong("submittedAt"),
                state = o.optString("state", "QUEUED")
            )
        }
        return out
    }

    private fun write(jobs: Collection<PendingJob>) {
        val arr = JSONArray()
        jobs.forEach { job ->
            arr.put(
                JSONObject()
                    .put("promptId", job.promptId)
                    .put("serverId", job.serverId)
                    .put("workflowName", job.workflowName)
                    .put("submittedAt", job.submittedAt)
                    .put("state", job.state)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "jobs"
    }
}
