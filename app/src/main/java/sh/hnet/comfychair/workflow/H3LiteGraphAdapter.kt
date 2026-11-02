package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline LiteGraph (ComfyUI "Save" format) reader for H3.
 *
 * Flattens `definitions.subgraphs` into a flat API-shaped node map so the existing
 * [H3WorkflowAdapter] can analyse it. Widget literals come from the file itself
 * (`widgets_values_named`, then `inputs[].widget` order); `/object_info` is NOT required,
 * which is what makes offline H3 import possible.
 *
 * Read-only: never mutates the caller's document.
 */
object H3LiteGraphAdapter {
    private val REROUTE = setOf("Reroute", "ReroutePrimitive")
    private val NOTES = setOf("Note", "MarkdownNote", "workflow/note")
    private val CONTROL_VALUES = setOf("fixed", "increment", "decrement", "randomize")
    private val SEED_NAMES = setOf("seed", "noise_seed")

    /** A link as seen from its target: who feeds it, and on which output slot. */
    data class Source(val nodeId: Int, val slot: Int)

    private class Subgraph(
        val name: String,
        val inputNodeId: Int,
        val outputNodeId: Int,
        /** Promoted input port names, indexed by port slot. */
        val ports: List<String>,
        val nodes: JSONArray,
        val links: Map<Int, Source>
    )

    /** Analyse a LiteGraph document. Returns null when it is not LiteGraph or holds no H3 core. */
    fun analyze(source: String): H3WorkflowAdapter.Analysis? {
        val doc = runCatching { JSONObject(source) }.getOrNull() ?: return null
        if (doc.optJSONArray("nodes") == null) return null
        val converted = runCatching { convert(doc) }.getOrNull() ?: return null
        return H3WorkflowAdapter.inspect(converted.toString())?.copy(format = "litegraph")
    }

    /** Flatten a LiteGraph document into `{"nodes":{...}}` (API shape, literal values resolved). */
    fun convert(doc: JSONObject): JSONObject {
        val topNodes = doc.optJSONArray("nodes") ?: JSONArray()
        val topLinks = parseLinks(doc.optJSONArray("links"))
        val subgraphs = parseSubgraphs(doc)

        // Fresh ids beyond every existing id so flattened nodes can never collide.
        var maxId = 0
        for (i in 0 until topNodes.length()) {
            maxId = maxOf(maxId, topNodes.optJSONObject(i)?.optInt("id", 0) ?: 0)
        }
        for (sg in subgraphs.values) {
            for (i in 0 until sg.nodes.length()) {
                maxId = maxOf(maxId, sg.nodes.optJSONObject(i)?.optInt("id", 0) ?: 0)
            }
        }
        var next = maxId + 1
        fun alloc(): Int = next++

        val api = JSONObject()
        for (i in 0 until topNodes.length()) {
            val node = topNodes.optJSONObject(i) ?: continue
            val type = node.optString("type")
            if (type.isEmpty()) continue
            val sg = subgraphs[type]
            if (sg != null) {
                flatten(node, sg, topLinks, api) { alloc() }
                continue
            }
            if (type in REROUTE || type in NOTES) continue
            api.put(node.optInt("id").toString(), buildNode(node, topLinks, null, null, null, null))
        }
        return JSONObject().put("nodes", api)
    }

    private fun flatten(
        instance: JSONObject, sg: Subgraph, topLinks: Map<Int, Source>,
        api: JSONObject, alloc: () -> Int
    ) {
        val idMap = HashMap<Int, Int>()
        for (i in 0 until sg.nodes.length()) {
            val node = sg.nodes.optJSONObject(i) ?: continue
            val type = node.optString("type")
            if (type in REROUTE || type in NOTES) continue
            idMap[node.optInt("id")] = alloc()
        }
        // Values pushed in from the instance's own widgets, keyed by promoted port name.
        val portValues = widgetValues(instance)
        // Wires entering the instance, keyed by the promoted port name.
        val external = HashMap<String, Source>()
        val ins = instance.optJSONArray("inputs")
        if (ins != null) for (i in 0 until ins.length()) {
            val entry = ins.optJSONObject(i) ?: continue
            val link = entry.optInt("link", -1)
            if (link == -1) continue
            val name = entry.optString("name")
            topLinks[link]?.let { if (name.isNotEmpty()) external[name] = it }
        }
        for (i in 0 until sg.nodes.length()) {
            val node = sg.nodes.optJSONObject(i) ?: continue
            val type = node.optString("type")
            if (type in REROUTE || type in NOTES) continue
            val newId = idMap[node.optInt("id")] ?: continue
            api.put(newId.toString(), buildNode(node, sg.links, idMap, sg, external, portValues))
        }
    }

    private fun buildNode(
        node: JSONObject, links: Map<Int, Source>, idMap: Map<Int, Int>?,
        sg: Subgraph?, external: Map<String, Source>?, portValues: Map<String, Any>?
    ): JSONObject {
        val inputs = JSONObject()
        widgetValues(node).forEach { (key, value) -> inputs.put(key, value) }
        val entries = node.optJSONArray("inputs")
        if (entries != null) for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val name = entry.optString("name")
            val linkId = entry.optInt("link", -1)
            if (name.isEmpty() || linkId == -1) continue
            val source = links[linkId] ?: continue
            if (sg != null && source.nodeId == sg.inputNodeId) {
                val port = sg.ports.getOrNull(source.slot)
                val wired = port?.let { external?.get(it) }
                val literal = port?.let { portValues?.get(it) }
                when {
                    wired != null -> inputs.put(name, connection(wired.nodeId, wired.slot))
                    literal != null -> inputs.put(name, literal)
                    else -> { /* no wire and no literal: leave unset, never invent */ }
                }
            } else if (sg != null && source.nodeId == sg.outputNodeId) {
                // A link from the subgraph's own output node is not a real producer.
            } else {
                val mapped = idMap?.get(source.nodeId) ?: source.nodeId
                inputs.put(name, connection(mapped, source.slot))
            }
        }
        return JSONObject().apply {
            put("class_type", node.optString("type"))
            put("inputs", inputs)
            val mode = node.optInt("mode", 0)
            if (mode != 0) put("mode", mode)
        }
    }

    private fun connection(nodeId: Int, slot: Int): JSONArray =
        JSONArray().put(nodeId.toString()).put(slot)

    /**
     * Literal widget values of one node, without server definitions.
     * Priority: `widgets_values_named` → object-shaped `widgets_values` → positional order
     * against `inputs[].widget.name`. Anything unreadable is simply absent.
     */
    private fun widgetValues(node: JSONObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        node.optJSONObject("widgets_values_named")?.let { copyScalars(it, out) }
        node.optJSONObject("widgets_values")?.let { copyScalars(it, out) }
        val positional = node.optJSONArray("widgets_values")
        if (positional != null) {
            val names = widgetNames(node)
            var ni = 0
            var pi = 0
            while (ni < names.size && pi < positional.length()) {
                val name = names[ni]
                val value = positional.opt(pi)
                if (value != null && value !== JSONObject.NULL &&
                    (value is String || value is Number || value is Boolean)) {
                    if (!out.containsKey(name)) out[name] = value
                }
                ni++
                pi++
                // A seed widget is followed by a frontend-only control value.
                if (name in SEED_NAMES && pi < positional.length()) {
                    val control = positional.opt(pi)
                    if (control is String && control in CONTROL_VALUES) pi++
                }
            }
        }
        return out
    }

    /** Widget names in LiteGraph order: one per widget-backed entry of `inputs`. */
    private fun widgetNames(node: JSONObject): List<String> {
        val entries = node.optJSONArray("inputs") ?: return emptyList()
        val names = ArrayList<String>(entries.length())
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val widget = entry.optJSONObject("widget")
            if (widget != null) {
                val name = widget.optString("name").ifEmpty { entry.optString("name") }
                if (name.isNotEmpty()) names.add(name)
            }
        }
        return names
    }

    private fun copyScalars(from: JSONObject, into: MutableMap<String, Any>) {
        val keys = from.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = from.opt(key) ?: continue
            if (value === JSONObject.NULL || value is JSONObject || value is JSONArray) continue
            if (value is String || value is Number || value is Boolean) into[key] = value
        }
    }

    private fun parseLinks(array: JSONArray?): Map<Int, Source> {
        val out = HashMap<Int, Source>()
        if (array == null) return out
        for (i in 0 until array.length()) {
            val row = array.optJSONArray(i) ?: continue
            if (row.length() < 5) continue
            val id = row.optInt(0, -1)
            if (id == -1) continue
            out[id] = Source(row.optInt(1, 0), row.optInt(2, 0))
        }
        return out
    }

    private fun parseSubgraphs(doc: JSONObject): Map<String, Subgraph> {
        val out = HashMap<String, Subgraph>()
        val list = doc.optJSONObject("definitions")?.optJSONArray("subgraphs") ?: return out
        for (i in 0 until list.length()) {
            val sg = list.optJSONObject(i) ?: continue
            val id = sg.optString("id")
            if (id.isEmpty()) continue
            val ports = ArrayList<String>()
            val inputs = sg.optJSONArray("inputs")
            if (inputs != null) for (j in 0 until inputs.length()) {
                ports.add(inputs.optJSONObject(j)?.optString("name").orEmpty())
            }
            val links = HashMap<Int, Source>()
            val rows = sg.optJSONArray("links")
            if (rows != null) for (j in 0 until rows.length()) {
                val row = rows.optJSONObject(j) ?: continue
                val linkId = row.optInt("id", -1)
                if (linkId == -1) continue
                links[linkId] = Source(row.optInt("origin_id", 0), row.optInt("origin_slot", 0))
            }
            out[id] = Subgraph(
                name = sg.optString("name", "Subgraph"),
                inputNodeId = sg.optJSONObject("inputNode")?.optInt("id", -10) ?: -10,
                outputNodeId = sg.optJSONObject("outputNode")?.optInt("id", -20) ?: -20,
                ports = ports,
                nodes = sg.optJSONArray("nodes") ?: JSONArray(),
                links = links
            )
        }
        return out
    }
}
