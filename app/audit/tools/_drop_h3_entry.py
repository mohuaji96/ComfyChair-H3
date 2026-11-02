import io

BASE = r'G:\ComfyUI各类文件模型\app\ComfyChairH3\app\src\main\java\sh\hnet\comfychair\ui\screens'

OLD_TTV = '''    val h3Workflow=remember(uiState.selectedWorkflow,uiState.availableWorkflows) {
        uiState.availableWorkflows.find {it.name==uiState.selectedWorkflow}?.takeIf {
            runCatching {sh.hnet.comfychair.workflow.H3WorkflowAdapter.inspect(WorkflowManager.getWorkflowById(it.id)?.jsonContent ?: "{}")!=null}.getOrDefault(false)
        }
    }
    var h3Front by remember(h3Workflow?.id) { mutableStateOf(false) }
    var showH3 by remember(h3Workflow?.id) { mutableStateOf(h3Workflow!=null) }
    if(showH3 && h3Workflow!=null) sh.hnet.comfychair.ui.components.H3ParameterSheet(
        workflowId=h3Workflow.id,generationViewModel=generationViewModel,front=h3Front,onDismiss={showH3=false})
'''

NEW = '''    // 0.9.5: H3 templates live in their own library and are edited only in the H3 workspace,
    // so this screen no longer opens the H3 panel or sees H3 workflows.
    val h3Workflow: WorkflowManager.Workflow? = null
    var h3Front by remember { mutableStateOf(false) }
    var showH3 by remember { mutableStateOf(false) }
'''

for name in ('TextToVideoScreen.kt', 'ImageToVideoScreen.kt'):
    path = BASE + '\\' + name
    s = io.open(path, encoding='utf-8').read()
    assert OLD_TTV in s, name
    s = s.replace(OLD_TTV, NEW, 1)
    io.open(path, 'w', encoding='utf-8', newline='\n').write(s)
    print('patched', name)
