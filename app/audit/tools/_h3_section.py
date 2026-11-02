import io

BASE = r'G:\ComfyUI各类文件模型\app\ComfyChairH3\app\src\main\java\sh\hnet\comfychair'


def patch(path, pairs):
    p = BASE + '\\' + path
    s = io.open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (path, old[:60])
        s = s.replace(old, new, 1)
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)
    print('patched', path)


# 1) type queries stay in the ordinary library unless asked otherwise
patch(r'WorkflowManager.kt', [(
    "    fun getWorkflowsByType(type: WorkflowType): List<Workflow> {\n"
    "        return workflows.filter { it.type == type }.sortedBy { it.name.lowercase() }",
    "    fun getWorkflowsByType(\n"
    "        type: WorkflowType,\n"
    "        space: WorkflowSpace = WorkflowSpace.NORMAL\n"
    "    ): List<Workflow> {\n"
    "        return workflows.filter { it.type == type && it.space == space }\n"
    "            .sortedBy { it.name.lowercase() }"
)])

# 2) management state gains the H3 library
patch(r'viewmodel\WorkflowManagementViewModel.kt', [
    (
        "    val itvWorkflows: List<WorkflowManager.Workflow> = emptyList(),",
        "    val itvWorkflows: List<WorkflowManager.Workflow> = emptyList(),\n"
        "    /** H3 templates and user H3 workflows, shown in their own section. */\n"
        "    val h3Workflows: List<WorkflowManager.Workflow> = emptyList(),"
    ),
    (
        "            itvWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITV)",
        "            itvWorkflows = WorkflowManager.getWorkflowsByType(WorkflowType.ITV),\n"
        "            h3Workflows = WorkflowManager.getAllWorkflows(WorkflowManager.WorkflowSpace.H3)"
    ),
])
