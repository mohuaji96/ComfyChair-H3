import io, sys

P = r'G:\ComfyUI各类文件模型\app\ComfyChairH3\app\src\main\java\sh\hnet\comfychair\WorkflowManager.kt'
s = io.open(P, encoding='utf-8').read()
orig = s


def sub(old, new):
    global s
    assert old in s, old[:80]
    s = s.replace(old, new, 1)


# 1) enum + space field on Workflow
sub(
    "    data class Workflow(\n"
    "        val id: String,\n"
    "        val name: String,\n"
    "        val description: String,\n"
    "        val jsonContent: String,\n"
    "        val type: WorkflowType,\n"
    "        val isBuiltIn: Boolean,\n"
    "        val defaults: WorkflowDefaults = WorkflowDefaults()\n"
    "    )",
    "    /** Independent library: H3 templates never appear in the ordinary generation screens. */\n"
    "    enum class WorkflowSpace { NORMAL, H3 }\n"
    "\n"
    "    data class Workflow(\n"
    "        val id: String,\n"
    "        val name: String,\n"
    "        val description: String,\n"
    "        val jsonContent: String,\n"
    "        val type: WorkflowType,\n"
    "        val isBuiltIn: Boolean,\n"
    "        val defaults: WorkflowDefaults = WorkflowDefaults(),\n"
    "        val space: WorkflowSpace = WorkflowSpace.NORMAL\n"
    "    )"
)

# 2) built-in space by resource name
sub(
    "                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = true, defaults))",
    "                val space = if (resourceName.contains(\"h3\", ignoreCase = true))\n"
    "                    WorkflowSpace.H3 else WorkflowSpace.NORMAL\n"
    "                workflows.add(\n"
    "                    Workflow(id, name, description, jsonContent, type, isBuiltIn = true, defaults, space))"
)

# 3) user workflow space by content
sub(
    "                workflows.add(Workflow(id, name, description, jsonContent, type, isBuiltIn = false, defaults))",
    "                workflows.add(\n"
    "                    Workflow(id, name, description, jsonContent, type, isBuiltIn = false, defaults,\n"
    "                        spaceOf(jsonContent, type)))"
)

# 4) re-tag after every load, so imports/duplicates land in the right library
sub(
    "    private fun loadAllWorkflows() {\n"
    "        workflows.clear()\n"
    "        loadBuiltInWorkflows()\n"
    "        loadUserWorkflows()\n"
    "    }",
    "    private fun loadAllWorkflows() {\n"
    "        workflows.clear()\n"
    "        loadBuiltInWorkflows()\n"
    "        loadUserWorkflows()\n"
    "        retagSpaces()\n"
    "    }\n"
    "\n"
    "    /** H3 is decided by content, never by where the file happens to live. */\n"
    "    private fun spaceOf(jsonContent: String, type: WorkflowType): WorkflowSpace {\n"
    "        val byContent = runCatching {\n"
    "            sh.hnet.comfychair.workflow.H3Plan.resolve(jsonContent) != null\n"
    "        }.getOrDefault(false)\n"
    "        if (byContent) return WorkflowSpace.H3\n"
    "        return if (type.name.startsWith(\"TTV\") || type.name.startsWith(\"ITV\")) {\n"
    "            WorkflowSpace.NORMAL\n"
    "        } else WorkflowSpace.NORMAL\n"
    "    }\n"
    "\n"
    "    /** Re-derives the space of user workflows; built-ins keep the space from their resource. */\n"
    "    private fun retagSpaces() {\n"
    "        for (i in workflows.indices) {\n"
    "            val w = workflows[i]\n"
    "            if (w.isBuiltIn) continue\n"
    "            val space = spaceOf(w.jsonContent, w.type)\n"
    "            if (space != w.space) workflows[i] = w.copy(space = space)\n"
    "        }\n"
    "    }"
)

# 5) list API
sub(
    "    fun getAllWorkflows(): List<Workflow> = workflows.toList()",
    "    /** Ordinary screens see only NORMAL; the H3 workspace asks for H3 explicitly. */\n"
    "    fun getAllWorkflows(space: WorkflowSpace = WorkflowSpace.NORMAL): List<Workflow> =\n"
    "        workflows.filter { it.space == space }\n"
    "\n"
    "    /** Management and backup need both libraries. */\n"
    "    fun getAllWorkflowsIncludingH3(): List<Workflow> = workflows.toList()"
)

assert s != orig
io.open(P, 'w', encoding='utf-8', newline='\n').write(s)
print("patched")
