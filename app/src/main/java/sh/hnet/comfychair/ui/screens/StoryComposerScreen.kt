package sh.hnet.comfychair.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import android.net.Uri
import sh.hnet.comfychair.R
import sh.hnet.comfychair.BuildConfig
import sh.hnet.comfychair.model.ContentProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.hnet.comfychair.ui.theme.LocalSemanticColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.hnet.comfychair.model.StoryLibrary
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.StoryLibraryStore
import sh.hnet.comfychair.storage.StoryState
import sh.hnet.comfychair.storage.StoryStateStore
import sh.hnet.comfychair.workflow.StoryField
import sh.hnet.comfychair.workflow.StoryGenerator
import sh.hnet.comfychair.workflow.StoryPick
import sh.hnet.comfychair.workflow.StorySection

/**
 * 剧情组合。
 *
 * 骨架和词库组合一致：标题 / 一行控制 / 分组列表 / 底部提示词格。
 * 区别只有两处——抽的是句子片段不是图片词，人数是一个分项而不是页面模式。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StoryComposerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 与词库组合共用同一个档位开关：关掉 NSFW 之后剧情页也必须是 SFW 库，
    // 否则「关了开关」只对一半页面成立。
    // 不 remember：在词库页切了档再返回这里，remember 不会重算，页面会一直用旧档的库，
    // 直到退出重进。getSharedPreferences 与单例查表都很轻，每次组合读一次即可。
    val profile = AppSettings.getContentProfile(context)
    val store = StoryLibraryStore.get(context, profile)
    // 按档位建：切了档就要读目标档那一份存档，不能沿用上一档的选择。
    val stateStore = remember(profile) { StoryStateStore(context, profile) }
    val library by store.library.collectAsStateWithLifecycle()
    val generator = remember(library) { StoryGenerator(library) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var picks by remember { mutableStateOf<Map<String, StoryPick>>(emptyMap()) }
    var detail by rememberSaveable { mutableStateOf(true) }
    var dialogue by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showFull by remember { mutableStateOf(false) }
    var pairEditing by remember { mutableStateOf(false) }
    /** 整段锁住的段名。只拦「换一条」，段内单格重抽照常。 */
    var lockedSections by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    // 跟着档位重置：换了档要重新读那一档的存档，而不是继续用上一档读到的内容。
    var restored by remember(profile) { mutableStateOf(false) }
    LaunchedEffect(generator) {
        // 只在第一次进来读存档。词库一变就重读，刚点的那一下会被旧存档盖掉。
        val saved = if (restored) null else stateStore.load()
        restored = true
        if (saved != null) {
            picks = generator.migrate(saved.picks)
            detail = saved.detail
            dialogue = saved.dialogue
            lockedSections = saved.lockedSections
        } else if (picks.isEmpty()) {
            // 第一次进来：人数先给「一男一女」并锁住，省得每次换一条都换设定。
            // 人数不再锁死：SFW 库四套 casts / 四套模板都备好了，锁在 mf 等于三套白写。
    // solo 与 ff 的模板里没有 {male}，男方分项抽到也不会进句子。
    val base = generator.emptyPicks() + (StoryGenerator.CAST to StoryPick("mf"))
            picks = generator.randomize(base).first
        }
        // 词库换了以后可能多出分项，缺的那几个补抽一次。
        val missing = generator.fields.map { it.key }.filter { it !in picks }.toSet()
        if (missing.isNotEmpty()) {
            val filled = generator.randomize(picks, missing).first
            picks = picks + filled.filterKeys { it in missing }
        }
    }

    LaunchedEffect(picks, detail, dialogue, lockedSections) {
        if (picks.isNotEmpty()) stateStore.save(StoryState(picks, detail, dialogue, lockedSections))
    }

    val composed = remember(picks, detail, dialogue, generator) {
        generator.compose(picks, detail, dialogue)
    }

    /** 锁住的段里所有分项，交给 randomize 原样放行。 */
    fun heldKeys(sections: Set<String>): Set<String> =
        generator.sections
            .filter { it.label in sections }
            .flatMap { section -> section.fields.map { it.key } }
            .toSet()

    /** Re-draws [only] (everything when empty) and returns what the rules had to say about it. */
    fun reroll(only: Set<String> = emptySet(), held: Set<String> = emptySet()): List<String> {
        val (next, notes) = generator.randomize(picks, only, held)
        picks = next
        return notes
    }

    // 跨档导入先问一句：SFW 的剧情库覆盖 NSFW，一次误操作就找不回来。
    var pendingImport by remember { mutableStateOf<Pair<Uri, ContentProfile>?>(null) }
    // 读文件、解析、体检、落盘走后台线程；剧情库上千条，占着主线程就是界面顿住几秒。
    val importScope = rememberCoroutineScope()
    fun runImport(uri: Uri, confirmed: ContentProfile? = null) {
        importScope.launch {
            val result = withContext(Dispatchers.IO) { store.importFrom(uri, confirmed) }
            result
                .onSuccess { lib -> say(context.getString(R.string.st_import_done, lib.wordCount)) }
                .onFailure { err ->
                    val mismatch = err as? StoryLibraryStore.ProfileMismatch
                    // 只在下一次还问到同一个档时才弹窗；确认过的那一档已经放过，不会再来。
                    if (mismatch != null && mismatch.found != confirmed) {
                        pendingImport = uri to mismatch.found
                    } else {
                        say(err.message ?: context.getString(R.string.st_import_failed))
                    }
                }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { runImport(it) } }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            store.exportTo(it)
                .onSuccess { say(context.getString(R.string.st_export_done)) }
                .onFailure { err -> say(err.message ?: context.getString(R.string.st_export_failed)) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            StoryBottomBar(
                composed = composed,
                onOpen = { showFull = true },
                onCopy = {
                    if (composed.isNotBlank()) {
                        clipboard.setText(AnnotatedString(composed))
                        say(context.getString(R.string.st_copied_clipboard))
                    }
                },
                // 换一条是常规操作，不必每次弹提示；只有规则临时改了分项才说一句。
                onReroll = {
                    reroll(held = heldKeys(lockedSections)).firstOrNull()?.let { note -> say(note) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = 16.dp)
        ) {
            StoryHeader(
                fieldCount = generator.fields.size,
                wordCount = library.wordCount,
                version = library.version,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onBack = onBack,
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                onExport = {
                    exportLauncher.launch(
                        profile.exportFileName(context.getString(R.string.st_title), BuildConfig.VERSION_NAME)
                    )
                },
                onReset = { confirmReset = true }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TogglePill(stringResource(R.string.st_toggle_dialogue), dialogue) { dialogue = it }
                Spacer(Modifier.weight(1f))
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 8.dp)
            ) {
                // 人数里没有男性时（单人女性 / 两位女性），男主整段不显示。
                // 句子里本来就不会出现（那两套模板没有 {male}），留着三行只会让人以为选错了。
                val castHasMale = library.casts[picks[StoryGenerator.CAST]?.value.orEmpty()]
                    ?.male?.isNotBlank() == true
                // 女二只在两女及以上档显示，单人档和一男一女档不留这一整段。
                val shownSections = generator.sections.filter {
                    it.label != "女二" || picks[StoryGenerator.CAST]?.value in setOf("mff", "ff")
                }
                shownSections.forEach { section ->
                    if (section.label == "男主" && !castHasMale) return@forEach
                    // 当前档位没有这个分项的候选池时整段不渲染。SFW 库里没有「开始 / 过程中 /
                    // 动作 / 结束」，留着四个永远填不上的空行，看上去像词库坏了。
                    // 不再在这里过滤：StoryGenerator.sections 已经按池过滤过，并且豁免了人数
                    // （人数的候选在 library.casts 里，pool("cast") 恒空）。同一个判断写两份，
                    // 改一份漏一份，人数行就是这样消失过两次。
                    val fields = section.fields
                    if (fields.isEmpty()) return@forEach
                    item(key = "sec-" + section.label) {
                        SectionHead(
                            label = section.label,
                            locked = section.label in lockedSections,
                            onToggleLock = {
                                lockedSections = if (section.label in lockedSections) {
                                    lockedSections - section.label
                                } else {
                                    lockedSections + section.label
                                }
                            },
                            onReroll = {
                                // 点段头就是明确要重抽这一段，先把锁摘掉，否则点了没反应。
                                if (section.label in lockedSections) {
                                    lockedSections = lockedSections - section.label
                                }
                                val notes = reroll(fields.map { it.key }.toSet())
                                say(notes.firstOrNull() ?: context.getString(R.string.st_rerolled_section, section.label))
                            }
                        )
                    }
                    // 同组的相邻字段并成一行：形象（年龄｜气质）、发型（发型｜发色）、胸部四格。
                    val rows = mutableListOf<List<StoryField>>()
                    var cursor = 0
                    while (cursor < fields.size) {
                        val head = fields[cursor]
                        if (head.group.isEmpty()) {
                            rows += listOf(head)
                            cursor++
                        } else {
                            val same = mutableListOf(head)
                            var next = cursor + 1
                            while (next < fields.size && fields[next].group == head.group) {
                                same += fields[next]
                                next++
                            }
                            rows += same
                            cursor = next
                        }
                    }
                    items(rows.size, key = { rows[it].first().key }) { index ->
                        val group = rows[index]
                        val field = group.first()
                        val held = section.label in lockedSections
                        if (group.size == 1 && field.key == StoryGenerator.ACT) return@items
                        // 只有成人库才是「姿势/动作」合并成一行；SFW 没有动作池，姿势单独一行。
                        if (field.key == StoryGenerator.POSE &&
                            library.pool(StoryGenerator.ACT).isNotEmpty()
                        ) {
                            // 姿势和动作是一对，合成一行显示，点开也一起改。
                            FieldRow(
                                label = stringResource(R.string.st_pose_act),
                                text = pairSummary(picks, context),
                                locked = picks[StoryGenerator.POSE]?.locked == true,
                                ignored = false,
                                empty = pairIsEmpty(picks),
                                onClick = { pairEditing = true },
                                onReroll = {
                                    // 姿势与动作是一对，一起重抽，否则两边规则互相打脸
                                    val notes = reroll(setOf(StoryGenerator.POSE, StoryGenerator.ACT))
                                    say(notes.firstOrNull() ?: context.getString(R.string.st_rerolled_section, context.getString(R.string.st_pose_act)))
                                }
                            )
                            return@items
                        }
                        if (group.size > 1) {
                            FieldGroupRow(
                                label = field.groupLabel.ifEmpty { field.label },
                                cells = group.map { it to (picks[it.key] ?: StoryPick()) },
                                valueLabel = { f, value -> generator.label(f.key, value) },
                                held = held,
                                onEdit = { f -> editing = f.key },
                                onRerollOne = { f ->
                                    val notes = reroll(setOf(f.key))
                                    say(notes.firstOrNull() ?: context.getString(R.string.st_rerolled_section, f.label))
                                },
                                onRerollAll = {
                                    val notes = reroll(group.map { it.key }.toSet())
                                    say(notes.firstOrNull() ?: context.getString(R.string.st_rerolled_section, field.groupLabel))
                                }
                            )
                            return@items
                        }
                        val pick = picks[field.key] ?: StoryPick()
                        FieldRow(
                            label = field.label,
                            text = when {
                                pick.ignored -> stringResource(R.string.st_ignored)
                                pick.value.isEmpty() -> stringResource(R.string.st_not_picked)
                                else -> generator.label(field.key, pick.value)
                            },
                            locked = pick.locked,
                            ignored = pick.ignored,
                            empty = pick.value.isEmpty(),
                            held = held,
                            onClick = { editing = field.key },
                            onReroll = {
                                val notes = reroll(setOf(field.key))
                                say(notes.firstOrNull() ?: context.getString(R.string.st_rerolled_section, field.label))
                            }
                        )
                    }
                }
            }
        }
    }

    val key = editing
    if (key != null) {
        val field = generator.field(key)
        val pick = picks[key] ?: StoryPick()
        val section = generator.sections.firstOrNull { s -> s.fields.any { it.key == key } }
        StorySheet(
            sectionLabel = section?.label.orEmpty(),
            title = field?.label ?: key,
            pick = pick,
            options = generator.allowed(
                key,
                picks,
                forPick = key == StoryGenerator.AREOLA || key == StoryGenerator.NIPPLE
            ).map { it to generator.label(key, it) },
            isCommon = { generator.isCommon(key, it) },
            onDismiss = { editing = null },
            onLock = {
                // 空值锁了就永远是空的，干脆不响应。
                if (pick.locked || pick.value.isNotEmpty()) {
                    picks = picks + (key to pick.copy(locked = !pick.locked, ignored = false))
                }
            },
            onIgnore = {
                // 忽略和锁定互斥：忽略时把锁定也清掉，否则「锁」挂着却不生效。
                picks = picks + (key to pick.copy(ignored = !pick.ignored, locked = false, value = ""))
            },
            onClear = {
                picks = picks + (key to StoryPick())
            },
            multi = key == StoryGenerator.ACT || key == StoryGenerator.POSE,
            onAddCustom = { text ->
                val pool = field?.pool.orEmpty()
                if (store.addCustom(pool, text)) picks = picks + (key to pick.copy(value = text, ignored = false))
                else say(context.getString(R.string.st_duplicate_word, text))
            },
            onToggleExcluded = { text -> store.toggleExcluded(field?.pool.orEmpty(), text) },
            onDeleteWord = { text ->
                store.removeWord(field?.pool.orEmpty(), text)
                val left = pick.value.split(StoryGenerator.SEP)
                    .filter { it.isNotBlank() && it != text }
                    .joinToString(StoryGenerator.SEP)
                if (left != pick.value) picks = picks + (key to pick.copy(value = left))
            },
            isExcluded = { text -> library.isExcluded(field?.pool.orEmpty(), text) },
            allowAdd = key != StoryGenerator.CAST,
            onPick = { value ->
                // 选词只换值，不动锁定状态。要固定就点「锁定」，别让人每选一次都被暗锁。
                val next = if (key == StoryGenerator.ACT || key == StoryGenerator.POSE) toggleAct(pick.value, value) else value
                picks = picks + (key to pick.copy(value = next, ignored = false))
            }
        )
    }

    pendingImport?.let { (uri, found) ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.st_import_mismatch_title, found.label)) },
            text = {
                Text(
                    stringResource(
                        R.string.st_import_mismatch_body,
                        profile.label,
                        found.label
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    runImport(uri, confirmed = found)
                }) { Text(stringResource(R.string.st_import_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text(stringResource(R.string.st_cancel)) }
            }
        )
    }

    if (showFull && composed.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showFull = false },
            title = { Text(stringResource(R.string.st_full_story)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) { Text(composed, style = MaterialTheme.typography.bodyMedium) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("story", composed))
                    Toast.makeText(context, context.getString(R.string.st_copied), Toast.LENGTH_SHORT).show()
                    showFull = false
                }) { Text(stringResource(R.string.st_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { showFull = false }) { Text(stringResource(R.string.st_close)) }
            }
        )
    }

    if (pairEditing) {
        val poseKey = StoryGenerator.POSE
        val actKey = StoryGenerator.ACT
        val updatePair = { pList: List<String>, aList: List<String> ->
            picks = picks +
                (poseKey to (picks[poseKey] ?: StoryPick()).copy(value = pList.joinToString(StoryGenerator.SEP), ignored = false)) +
                (actKey to (picks[actKey] ?: StoryPick()).copy(value = aList.joinToString(StoryGenerator.SEP), ignored = false))
        }

        PairSheet(
            picks = picks,
            poseOptions = generator.allowed(poseKey, picks - actKey, forPick = true).map { it to it },
            actOptions = generator.allowed(actKey, picks - poseKey, forPick = true).map { it to it },
            library = library,
            isExcluded = { pool, text -> library.isExcluded(pool, text) },
            onPickPose = { idx, value ->
                val pList = picks[poseKey]?.value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
                val aList = picks[actKey]?.value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
                while (pList.size <= idx) pList.add("")
                while (aList.size <= idx) aList.add("")
                pList[idx] = value
                val poseWord = library.word("pose", value)
                val key = poseWord?.key.orEmpty()
                val curAct = aList[idx]
                val curActWord = library.word("act", curAct)
                val scopedActs = generator.allowed(actKey, picks + (poseKey to StoryPick(value)), forPick = true)
                if (curAct.isEmpty() || curAct !in scopedActs ||
                    (key.isNotEmpty() && curActWord != null && curActWord.poses.isNotEmpty() && key !in curActWord.poses)
                ) {
                    val match = scopedActs.filter { library.word("act", it)?.poses?.contains(key) == true }.randomOrNull()
                        ?: scopedActs.firstOrNull { library.word("act", it)?.common != true }
                        ?: scopedActs.firstOrNull()
                    if (match != null) aList[idx] = match
                }
                updatePair(pList, aList)
            },
            onPickAct = { idx, value ->
                val pList = picks[poseKey]?.value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
                val aList = picks[actKey]?.value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
                while (pList.size <= idx) pList.add("")
                while (aList.size <= idx) aList.add("")
                aList[idx] = value
                val actWord = library.word("act", value)
                val curPose = pList[idx]
                val curPoseWord = library.word("pose", curPose)
                val scopedPoses = generator.allowed(poseKey, picks - actKey, forPick = true)
                if (curPose.isEmpty() || curPose !in scopedPoses ||
                    (actWord != null && actWord.poses.isNotEmpty() && (curPoseWord == null || curPoseWord.key !in actWord.poses))
                ) {
                    val match = if (actWord != null && actWord.poses.isNotEmpty()) {
                        scopedPoses.filter { library.word("pose", it)?.key in actWord.poses }.randomOrNull()
                            ?: scopedPoses.firstOrNull()
                    } else scopedPoses.firstOrNull()
                    if (match != null) pList[idx] = match
                }
                updatePair(pList, aList)
            },
            onRerollPair = { idx ->
                val pList = picks[poseKey]?.value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
                val aList = picks[actKey]?.value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
                val allowedPoses = generator.allowed(poseKey, picks - actKey, forPick = true)
                val curPose = pList.getOrNull(idx).orEmpty()
                val randomPose = allowedPoses.filter { it != curPose }.randomOrNull() ?: allowedPoses.randomOrNull()
                if (randomPose != null) {
                    while (pList.size <= idx) pList.add("")
                    while (aList.size <= idx) aList.add("")
                    pList[idx] = randomPose
                    val key = library.word("pose", randomPose)?.key.orEmpty()
                    val allowedActs = generator.allowed(actKey, picks + (poseKey to StoryPick(randomPose)), forPick = true)
                    val match = allowedActs.filter { library.word("act", it)?.poses?.contains(key) == true }.randomOrNull()
                        ?: allowedActs.filter { library.word("act", it)?.common != true }.randomOrNull()
                        ?: allowedActs.randomOrNull()
                    if (match != null) aList[idx] = match
                    updatePair(pList, aList)
                }
            },
            onAddPair = {
                val pList = picks[poseKey]?.value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
                val aList = picks[actKey]?.value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
                val allowedPoses = generator.allowed(poseKey, picks - actKey, forPick = true)
                val lastPose = pList.lastOrNull().orEmpty()
                val randomPose = allowedPoses.filter { it != lastPose }.randomOrNull() ?: allowedPoses.randomOrNull()
                if (randomPose != null) {
                    pList.add(randomPose)
                    val key = library.word("pose", randomPose)?.key.orEmpty()
                    val allowedActs = generator.allowed(actKey, picks + (poseKey to StoryPick(randomPose)), forPick = true)
                    val match = allowedActs.filter { library.word("act", it)?.poses?.contains(key) == true }.randomOrNull()
                        ?: allowedActs.filter { library.word("act", it)?.common != true }.randomOrNull()
                        ?: allowedActs.randomOrNull()
                    if (match != null) aList.add(match)
                    updatePair(pList, aList)
                }
            },
            onRemovePair = { index ->
                picks = picks + (poseKey to (picks[poseKey] ?: StoryPick()).copy(value = dropAt(picks[poseKey]?.value, index)))
                picks = picks + (actKey to (picks[actKey] ?: StoryPick()).copy(value = dropAt(picks[actKey]?.value, index)))
            },
            onToggleExcluded = { pool, text -> store.toggleExcluded(pool, text) },
            onDeleteWord = { pool, text ->
                store.removeWord(pool, text)
                picks = picks + (poseKey to (picks[poseKey] ?: StoryPick()).copy(value = dropValue(picks[poseKey]?.value, text)))
                picks = picks + (actKey to (picks[actKey] ?: StoryPick()).copy(value = dropValue(picks[actKey]?.value, text)))
            },
            onAddCustom = { pool, text ->
                store.addCustom(pool, text)
                val target = if (pool == "pose") poseKey else actKey
                val current = picks[target] ?: StoryPick()
                picks = picks + (target to current.copy(value = toggleAct(current.value, text), ignored = false))
            },
            locked = picks[StoryGenerator.POSE]?.locked == true,
            onToggleLock = {
                val next = !(picks[StoryGenerator.POSE]?.locked == true)
                picks = picks + (StoryGenerator.POSE to (picks[StoryGenerator.POSE] ?: StoryPick()).copy(locked = next))
                picks = picks + (StoryGenerator.ACT to (picks[StoryGenerator.ACT] ?: StoryPick()).copy(locked = next))
            },
            onDismiss = { pairEditing = false }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.st_reset_title)) },
            text = {
                Text(
                    stringResource(R.string.st_reset_body, library.wordCount)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    val lib = store.resetToDefault()
                    say(context.getString(R.string.st_reset_done, lib.wordCount))
                }) { Text(stringResource(R.string.st_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.st_cancel)) }
            }
        )
    }
}

@Composable
private fun StoryHeader(
    fieldCount: Int,
    wordCount: Int,
    version: String,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.st_back))
            }
            Text(
                stringResource(R.string.st_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                softWrap = false
            )
            sh.hnet.comfychair.ui.components.QueueIndicatorPill(modifier = Modifier.padding(horizontal = 2.dp))
            sh.hnet.comfychair.ui.components.ServerStatusLamp()
            val navigate = sh.hnet.comfychair.ui.navigation.LocalScreenNavigator.current
            IconButton(onClick = { navigate(sh.hnet.comfychair.navigation.MainRoute.Settings) }) {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.st_settings)
                )
            }
            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.st_library_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.st_import_library)) },
                        onClick = { onMenuOpenChange(false); onImport() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.st_export_library)) },
                        onClick = { onMenuOpenChange(false); onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.st_reset_menu)) },
                        onClick = { onMenuOpenChange(false); onReset() }
                    )
                }
            }
        }
        Text(
            stringResource(R.string.st_header_counts, fieldCount, wordCount) +
                if (version.isBlank()) "" else " · $version",
            style = MaterialTheme.typography.labelSmall,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            stringResource(R.string.st_header_hint),
            style = MaterialTheme.typography.labelSmall,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TogglePill(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        // 开启态落到主题容器上，换主题就能一眼看出来；关闭态保持中性白 + 细边。
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = if (on) BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
        else BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable { onToggle(!on) }
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SectionHead(
    label: String,
    locked: Boolean = false,
    onToggleLock: () -> Unit = {},
    onReroll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (locked) "▪" else "▸",
            style = MaterialTheme.typography.labelMedium,
            color = if (locked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.2.sp,
            color = if (locked) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        // 锁和重抽分两个点击区：整行统一可点的时候，想锁住这一段很容易顺手把它换掉。
        Text(
            if (locked) stringResource(R.string.st_locked) else stringResource(R.string.st_lock),
            style = MaterialTheme.typography.labelSmall,
            color = if (locked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleLock)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.st_reroll_section),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onReroll)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FieldGroupRow(
    label: String,
    cells: List<Pair<StoryField, StoryPick>>,
    valueLabel: (StoryField, String) -> String,
    held: Boolean,
    onEdit: (StoryField) -> Unit,
    onRerollOne: (StoryField) -> Unit,
    onRerollAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onRerollAll)
                .padding(top = 4.dp, bottom = 2.dp, end = 2.dp)
        )
        Spacer(Modifier.width(6.dp))
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            cells.forEach { (field, pick) ->
                val empty = pick.value.isEmpty()
                // 与词库组合页同一套状态映射：已选走主题容器、锁定走固定琥珀、忽略走中性灰。
                val semantic = LocalSemanticColors.current
                val tint = when {
                    pick.ignored -> semantic.ignoredContainer
                    pick.locked -> semantic.lockedContainer
                    held -> MaterialTheme.colorScheme.surfaceContainer
                    empty -> MaterialTheme.colorScheme.surface
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                Surface(
                    color = tint,
                    shape = MaterialTheme.shapes.small,
                    border = when {
                        held && !pick.locked && !pick.ignored ->
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                        empty || pick.ignored ->
                            BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                        else -> null
                    },
                    modifier = Modifier.clickable { onEdit(field) }
                ) {
                    Text(
                        when {
                            pick.ignored -> stringResource(R.string.st_ignored)
                            empty -> stringResource(R.string.st_not_picked)
                            else -> valueLabel(field, pick.value)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            pick.ignored -> semantic.ignored
                            pick.locked -> semantic.locked
                            empty -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldRow(
    label: String,
    text: String,
    locked: Boolean,
    ignored: Boolean,
    empty: Boolean,
    held: Boolean = false,
    onClick: () -> Unit,
    /** Re-draws this one field only, the way a category name does in 词库组合. */
    onReroll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // The label is the entry itself, the way a category name works in 词库组合: no extra hint,
        // tapping the name re-draws just this field.
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .width(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onReroll)
                .padding(top = 4.dp, bottom = 2.dp, end = 2.dp)
        )
        Spacer(Modifier.width(6.dp))
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 段锁用描边语言，和单格锁的实心底分开：整段一条淡底，单格锁仍压在上面。
            val semantic = LocalSemanticColors.current
            val tint = when {
                ignored -> semantic.ignoredContainer
                locked -> semantic.lockedContainer
                held -> MaterialTheme.colorScheme.surfaceContainer
                empty -> MaterialTheme.colorScheme.surface
                else -> MaterialTheme.colorScheme.primaryContainer
            }
            Surface(
                color = tint,
                shape = MaterialTheme.shapes.small,
                border = when {
                    held && !locked && !ignored -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
                    empty || ignored -> BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                    else -> null
                },
                modifier = Modifier.clickable(onClick = onClick)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            ignored -> semantic.ignored
                            locked -> semantic.locked
                            empty -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (locked) {
                        Spacer(Modifier.width(4.dp))
                        Text("🔒", fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

/** 主戏是一串词，pill 上放不下就折成「第一段 +N」。 */
private fun actLabel(value: String, notPicked: String): String {
    val parts = value.split(StoryGenerator.SEP).filter { it.isNotBlank() }
    if (parts.isEmpty()) return notPicked
    return if (parts.size == 1) parts[0] else "${parts[0]} +${parts.size - 1}"
}

/** 主戏能排一串：点一下加进来，再点一下拿掉。 */

/** True when neither pose nor action has a word. */
private fun pairIsEmpty(picks: Map<String, StoryPick>): Boolean =
    picks[StoryGenerator.POSE]?.value.orEmpty().split(StoryGenerator.SEP).none { it.isNotBlank() } &&
        picks[StoryGenerator.ACT]?.value.orEmpty().split(StoryGenerator.SEP).none { it.isNotBlank() }

/** 一行里显示第一组配对，后面用 +N 组表示还有几组。 */
private fun pairSummary(picks: Map<String, StoryPick>, context: Context): String {
    val poses = picks[StoryGenerator.POSE]?.value.orEmpty()
        .split(StoryGenerator.SEP).filter { it.isNotBlank() }
    val acts = picks[StoryGenerator.ACT]?.value.orEmpty()
        .split(StoryGenerator.SEP).filter { it.isNotBlank() }
    if (poses.isEmpty() && acts.isEmpty()) return context.getString(R.string.st_not_picked)
    val head = poses.firstOrNull() ?: context.getString(R.string.st_no_pose)
    val tail = acts.firstOrNull().orEmpty()
    val text = if (tail.isEmpty()) head else "$head → $tail"
    val groups = maxOf(poses.size, acts.size)
    return if (groups > 1) context.getString(R.string.st_pairs_more, text, groups - 1) else text
}
private fun toggleAct(current: String, value: String): String {
    val parts = current.split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
    if (value in parts) parts.remove(value) else parts.add(value)
    return parts.joinToString(StoryGenerator.SEP)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StorySheet(
    sectionLabel: String,
    title: String,
    pick: StoryPick,
    options: List<Pair<String, String>>,
    isCommon: (String) -> Boolean = { false },
    multi: Boolean = false,
    onAddCustom: (String) -> Unit = {},
    onToggleExcluded: (String) -> Unit = { _ -> },
    onDeleteWord: (String) -> Unit = { _ -> },
    isExcluded: (String) -> Boolean = { false },
    allowAdd: Boolean = true,
    onDismiss: () -> Unit,
    onLock: () -> Unit,
    onIgnore: () -> Unit,
    onClear: () -> Unit,
    onPick: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var addText by remember { mutableStateOf("") }
    var managing by remember(title) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                sectionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLock) {
                    Text(if (pick.locked) stringResource(R.string.st_locked_state) else stringResource(R.string.st_lock))
                }
                OutlinedButton(onClick = onIgnore) {
                    Text(if (pick.ignored) stringResource(R.string.st_ignored) else stringResource(R.string.st_ignore))
                }
                OutlinedButton(onClick = onClear) { Text(stringResource(R.string.st_clear)) }
                OutlinedButton(onClick = { managing = !managing }) {
                    Text(if (managing) stringResource(R.string.st_done) else stringResource(R.string.st_manage))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (multi) stringResource(R.string.st_hint_multi) else stringResource(R.string.st_hint_single),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val (popular, others) = options.partition { isCommon(it.first) }
            val chosen = if (multi) {
                pick.value.split(StoryGenerator.SEP).filter { it.isNotBlank() }.toSet()
            } else {
                setOf(pick.value)
            }
            if (multi && chosen.isNotEmpty()) {
                Text(
                    chosen.joinToString(" → "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
            Chips(popular, chosen, managing, isExcluded, onPick, onToggleExcluded, onDeleteWord)
            if (popular.isNotEmpty() && others.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.st_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
            }
            Chips(others, chosen, managing, isExcluded, onPick, onToggleExcluded, onDeleteWord)
            Spacer(Modifier.height(14.dp))
            if (allowAdd) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.st_add_placeholder), style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                IconButton(
                    onClick = {
                        if (addText.isNotBlank()) {
                            onAddCustom(addText)
                            addText = ""
                        }
                    }
                ) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.st_add)) }
            }
            }
    }
    }
}

@Composable
private fun Chips(
    items: List<Pair<String, String>>,
    selected: Set<String>,
    managing: Boolean,
    isExcluded: (String) -> Boolean,
    onPick: (String) -> Unit,
    onToggleExcluded: (String) -> Unit,
    onDeleteWord: (String) -> Unit,
    highlighted: Set<String> = emptySet(),
    groupBadge: Map<String, Int> = emptyMap()
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { (value, label) ->
            val isSelected = value in selected
            val isHighlighted = value in highlighted
            val off = isExcluded(value)
            val badge = groupBadge[value]
            Surface(
                 color = when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer
                    isHighlighted -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
                    off -> LocalSemanticColors.current.ignoredContainer
                    else -> MaterialTheme.colorScheme.surface
                },
                shape = MaterialTheme.shapes.small,
                border = when {
                    isSelected -> null
                    isHighlighted -> BorderStroke(1.2.dp, MaterialTheme.colorScheme.tertiary)
                    else -> BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                modifier = Modifier.clickable { if (managing) onToggleExcluded(value) else onPick(value) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                            isHighlighted -> MaterialTheme.colorScheme.onTertiaryContainer
                            off -> LocalSemanticColors.current.ignored
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 9.dp, top = 5.dp, bottom = 5.dp, end = if (managing || badge != null) 4.dp else 9.dp)
                    )
                    if (badge != null && !managing) {
                        Text(
                            stringResource(R.string.st_group_badge, badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    if (managing) {
                        Text(
                            if (off) "🚫" else "👁",
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { onToggleExcluded(value) }
                                .padding(horizontal = 6.dp, vertical = 8.dp)
                        )
                        Text(
                            "🗑",
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { onDeleteWord(value) }
                                .padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
@Composable
private fun StoryBottomBar(
    composed: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onReroll: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(9.dp))
            if (composed.isBlank()) {
                Text(
                    stringResource(R.string.st_empty_story),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    composed,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpen)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = composed.isNotBlank(),
                    shape = RoundedCornerShape(13.dp)
                ) { Text(stringResource(R.string.st_copy)) }
                Button(
                    onClick = onReroll,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(13.dp)
                ) { Text(stringResource(R.string.st_reroll)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PairSheet(
    picks: Map<String, StoryPick>,
    poseOptions: List<Pair<String, String>>,
    actOptions: List<Pair<String, String>>,
    library: StoryLibrary,
    isExcluded: (String, String) -> Boolean,
    onPickPose: (Int, String) -> Unit,
    onPickAct: (Int, String) -> Unit,
    onRerollPair: (Int) -> Unit,
    onAddPair: () -> Unit,
    onRemovePair: (Int) -> Unit,
    onToggleExcluded: (String, String) -> Unit,
    onDeleteWord: (String, String) -> Unit,
    onAddCustom: (String, String) -> Unit,
    locked: Boolean,
    onToggleLock: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var managing by remember { mutableStateOf(false) }
    var addPose by remember { mutableStateOf("") }
    var addAct by remember { mutableStateOf("") }
    val poses = picks[StoryGenerator.POSE]?.value.orEmpty()
        .split(StoryGenerator.SEP).filter { it.isNotBlank() }
    val acts = picks[StoryGenerator.ACT]?.value.orEmpty()
        .split(StoryGenerator.SEP).filter { it.isNotBlank() }
    val pairCount = maxOf(poses.size, acts.size)
    var activeIndex by remember { mutableStateOf(if (pairCount > 0) pairCount - 1 else 0) }
    if (pairCount > 0 && activeIndex !in 0 until pairCount) {
        activeIndex = pairCount - 1
    }

    val poseGroupMap = poses.mapIndexed { idx, p -> p to idx + 1 }.toMap()
    val actGroupMap = acts.mapIndexed { idx, a -> a to idx + 1 }.toMap()

    val curPose = poses.getOrNull(activeIndex).orEmpty()
    val curAct = acts.getOrNull(activeIndex).orEmpty()
    val curPoseKey = library.word("pose", curPose)?.key.orEmpty()
    val curActWord = library.word("act", curAct)

    val highlightedActs = if (curPoseKey.isNotEmpty()) {
        library.pool("act").filter { it.poses.contains(curPoseKey) }.map { it.text }.toSet()
    } else emptySet()

    val highlightedPoses = if (curActWord != null && curActWord.poses.isNotEmpty()) {
        library.pool("pose").filter { it.key in curActWord.poses }.map { it.text }.toSet()
    } else emptySet()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            Text(stringResource(R.string.st_pairs_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.st_pairs_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleLock) {
                    Text(if (locked) stringResource(R.string.st_locked_state) else stringResource(R.string.st_lock))
                }
                OutlinedButton(onClick = { managing = !managing }) {
                    Text(if (managing) stringResource(R.string.st_done) else stringResource(R.string.st_manage))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.st_selected_groups, pairCount), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            for (index in 0 until pairCount) {
                val isFocused = index == activeIndex
                Surface(
                    color = if (isFocused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    shape = MaterialTheme.shapes.small,
                    border = if (isFocused) BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable { activeIndex = index }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                            color = if (isFocused) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            listOfNotNull(poses.getOrNull(index), acts.getOrNull(index)).joinToString(" → "),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "🎲",
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { onRerollPair(index) }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        Text(
                            "✕",
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clickable {
                                    onRemovePair(index)
                                    if (activeIndex >= maxOf(0, pairCount - 1)) {
                                        activeIndex = maxOf(0, pairCount - 2)
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    onAddPair()
                    activeIndex = pairCount
                },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(stringResource(R.string.st_add_pair))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.st_pose) + if (pairCount > 0) " " + stringResource(R.string.st_editing_group, activeIndex + 1) else "",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(6.dp))
            Chips(
                poseOptions, poses.toSet(), managing,
                { isExcluded("pose", it) },
                onPick = { onPickPose(activeIndex, it) },
                { onToggleExcluded("pose", it) },
                { onDeleteWord("pose", it) },
                highlighted = highlightedPoses,
                groupBadge = poseGroupMap
            )
            AddRow(stringResource(R.string.st_add_pose), addPose, { addPose = it }) {
                if (addPose.isNotBlank()) { onAddCustom("pose", addPose); addPose = "" }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.st_act_label) + if (pairCount > 0) " " + stringResource(R.string.st_editing_group, activeIndex + 1) else "",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(6.dp))
            Chips(
                actOptions, acts.toSet(), managing,
                { isExcluded("act", it) },
                onPick = { onPickAct(activeIndex, it) },
                { onToggleExcluded("act", it) },
                { onDeleteWord("act", it) },
                highlighted = highlightedActs,
                groupBadge = actGroupMap
            )
            AddRow(stringResource(R.string.st_add_act), addAct, { addAct = it }) {
                if (addAct.isNotBlank()) { onAddCustom("act", addAct); addAct = "" }
            }
        }
    }
}

@Composable
private fun AddRow(label: String, value: String, onChange: (String) -> Unit, onAdd: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text(label, style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall
        )
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = label) }
    }
}

/** 拿掉第 index 段。 */
private fun dropAt(value: String?, index: Int): String {
    val parts = value.orEmpty().split(StoryGenerator.SEP).filter { it.isNotBlank() }.toMutableList()
    if (index in parts.indices) parts.removeAt(index)
    return parts.joinToString(StoryGenerator.SEP)
}

/** 拿掉指定的词。 */
private fun dropValue(value: String?, text: String): String =
    value.orEmpty().split(StoryGenerator.SEP)
        .filter { it.isNotBlank() && it != text }
        .joinToString(StoryGenerator.SEP)
