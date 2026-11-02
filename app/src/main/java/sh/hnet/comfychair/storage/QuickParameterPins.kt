package sh.hnet.comfychair.storage

import android.content.Context

/** Per-workflow pinned fields. Identifiers never use unsanitized paths. */
class QuickParameterPins(context: Context) {
    private val preferences = context.getSharedPreferences("quick_parameter_pins_v1", Context.MODE_PRIVATE)
    private fun storageKey(workflowId: String): String {
        require(Regex("[a-zA-Z0-9_-]{1,128}").matches(workflowId))
        return "workflow:$workflowId"
    }
    fun read(workflowId: String): Set<String> =
        preferences.getStringSet(storageKey(workflowId), emptySet())?.toSet() ?: emptySet()

    fun setPinned(workflowId: String, fieldKey: String, pinned: Boolean) {
        require(Regex("[0-9]+:[A-Za-z0-9_.-]{1,128}").matches(fieldKey))
        val updated = read(workflowId).toMutableSet()
        if (pinned) updated.add(fieldKey) else updated.remove(fieldKey)
        check(preferences.edit().putStringSet(storageKey(workflowId), updated).commit()) {
            "Could not save quick parameter preferences"
        }
    }
}
