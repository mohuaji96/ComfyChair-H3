package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import sh.hnet.comfychair.R
import sh.hnet.comfychair.model.LocalizedException
import sh.hnet.comfychair.model.LocalizedText

/** Explicit changes only: untouched professional edits, links and metadata survive. */
object H3ApiPatch {
    /** One applied difference, in the order it was merged. */
    data class Change(
        val nodeId: String,
        val input: String,
        val before: Any?,
        val after: Any?,
        /** "field", "node-add" or "link" (informational: links are never rewritten here). */
        val kind: String
    )
    data class PatchResult(val json: String, val changes: List<Change>, val addedNodes: List<String>)

    private fun equivalent(a: Any?,b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet()==b.keys().asSequence().toSet() && a.keys().asSequence().all {equivalent(a.opt(it),b.opt(it))}
        a is JSONArray && b is JSONArray -> a.length()==b.length() && (0 until a.length()).all {equivalent(a.opt(it),b.opt(it))}
        a is Number && b is Number -> a.toString().toBigDecimal().compareTo(b.toString().toBigDecimal())==0
        else -> a==b
    }

    fun apply(original: String, edited: String, prepared: String): String =
        applyDetailed(original, edited, prepared).json

    /**
     * Merge only the literals the user changed in the working copy.
     *
     * A changed input that is a link (JSONArray) is never written here: the H3 sheet
     * only edits literals, so a link difference means the professional editor changed
     * structure. If the prepared JSON already carries that exact link it is accepted as
     * already applied; otherwise the merge is refused instead of silently rewiring.
     */
    fun applyDetailed(original: String, edited: String, prepared: String): PatchResult {
        val origin=H3WorkflowAdapter.nodes(JSONObject(original))
        val working=H3WorkflowAdapter.nodes(JSONObject(edited))
        val doc=JSONObject(prepared);val target=H3WorkflowAdapter.nodes(doc)
        val changes=mutableListOf<Change>();val added=mutableListOf<String>()
        working.keys().asSequence().toList().forEach { nodeId ->
            val w=working.getJSONObject(nodeId);val o=origin.optJSONObject(nodeId)
            if(o==null) {
                if(target.has(nodeId)) {
                    if(!equivalent(target.getJSONObject(nodeId),w)) {
                        throw LocalizedException(LocalizedText(R.string.patch_add_conflict))
                    }
                } else {
                    target.put(nodeId,JSONObject(w.toString()))
                    added += nodeId
                    changes += Change(nodeId,"","",w.optString("class_type"),"node-add")
                }
            } else {
                val wi=w.getJSONObject("inputs");val oi=o.getJSONObject("inputs")
                val changed=(wi.keys().asSequence().toSet()+oi.keys().asSequence().toSet()).filter { !equivalent(wi.opt(it),oi.opt(it)) }
                if(changed.isNotEmpty()) {
                    val t=target.optJSONObject(nodeId)
                        ?: throw LocalizedException(LocalizedText(R.string.patch_target_deleted, nodeId))
                    if(t.optString("class_type")!=o.optString("class_type")) {
                        throw LocalizedException(LocalizedText(R.string.patch_target_type_changed))
                    }
                    val ti=t.getJSONObject("inputs")
                    changed.forEach { key ->
                        val next = if (wi.has(key)) wi.get(key) else null
                        val current = ti.opt(key)
                        when {
                            // A new/rewired link is a structural edit: accept only if already applied.
                            next is JSONArray -> {
                                if (!equivalent(current, next)) {
                                    throw LocalizedException(
                                        LocalizedText(R.string.patch_link_conflict, nodeId, key)
                                    )
                                }
                            }
                            // Explicit slot deletion (setReference(null)) removes a wire on purpose.
                            current is JSONArray && next == null -> {
                                ti.remove(key)
                                changes += Change(nodeId,key,current,null,"link-remove")
                            }
                            // Never turn a wire into a literal behind the user's back.
                            current is JSONArray -> throw LocalizedException(
                                LocalizedText(R.string.patch_link_literal, nodeId, key)
                            )
                            else -> {
                                if (wi.has(key) && next !== JSONObject.NULL) ti.put(key,next) else ti.remove(key)
                                changes += Change(nodeId,key,oi.opt(key),next,"field")
                            }
                        }
                    }
                }
            }
        }
        return PatchResult(doc.toString(2), changes, added)
    }
}
