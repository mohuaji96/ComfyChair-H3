import io

P = r'G:\ComfyUI各类文件模型\app\ComfyChairH3\app\src\main\java\sh\hnet\comfychair\ui\screens\H3StudioScreen.kt'
lines = io.open(P, encoding='utf-8').read().split('\n')

start = next(i for i, l in enumerate(lines) if l.strip() == '// 2. 素材')
end = next(i for i, l in enumerate(lines) if l.strip() == '// 3. 生成参数')

NEW = r'''            // 2. 素材
            item {
                H3Card(
                    if (area == H3Plan.Area.R2V) "参考素材" else "素材（都可以不选）"
                ) {
                    if (area == H3Plan.Area.R2V) {
                        // Tiles, not rows: four references used to cost four full-width previews.
                        // Order is <Picture 1..N>, so the badges follow the real submitted order.
                        val shown = draft.refs.indexOfLast { it.isNotBlank() } + 1
                        val order = draft.refs.indices.filter {
                            draft.refs[it].isNotBlank() && it !in draft.disabledRefs
                        }

                        fun appendRef(onReady: (Int) -> Unit) {
                            fun go(max: Int) {
                                if (draft.refs.size < max) {
                                    val at = draft.refs.size
                                    update(draft.copy(refs = draft.refs + ""))
                                    selectedRef = at
                                    onReady(at)
                                } else message = "服务器最多 $max 张参考图"
                            }
                            if (refCeiling > 0) go(refCeiling) else askCeiling { max -> go(max) }
                        }

                        if (shown == 0) {
                            H3RefEmptyTile(
                                enabled = !busy && client != null,
                                onClick = {
                                    appendRef { at -> pendingSlot = "ref:$at"; picker.launch("image/*") }
                                }
                            )
                        } else {
                            val cells = (0 until shown).toList() + listOf(-1)
                            cells.chunked(4).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { index ->
                                        if (index < 0) {
                                            H3RefAddTile(
                                                modifier = Modifier.weight(1f),
                                                enabled = !busy && (refCeiling == 0 || shown < refCeiling),
                                                onClick = { appendRef { } }
                                            )
                                        } else {
                                            val file = draft.refs[index]
                                            H3RefTile(
                                                modifier = Modifier.weight(1f),
                                                file = file,
                                                workflowId = workflowId,
                                                revision = previewRevision,
                                                badge = order.indexOf(index).takeIf { it >= 0 }?.plus(1),
                                                disabled = index in draft.disabledRefs,
                                                selected = selectedRef == index,
                                                enabled = !busy,
                                                onClick = {
                                                    selectedRef = if (selectedRef == index) -1 else index
                                                    if (file.isBlank() && selectedRef == index) {
                                                        pendingSlot = "ref:$index"
                                                        picker.launch("image/*")
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }

                        if (selectedRef in draft.refs.indices && draft.refs[selectedRef].isNotBlank()) {
                            val index = selectedRef
                            val disabled = index in draft.disabledRefs
                            H3RefActionBar(
                                enabled = !busy,
                                disabled = disabled,
                                canLeft = index > 0,
                                canRight = index < draft.refs.lastIndex,
                                onLeft = {
                                    val refs = draft.refs.toMutableList()
                                    val prev = refs[index - 1]
                                    refs[index - 1] = refs[index]; refs[index] = prev
                                    update(draft.copy(refs = refs))
                                    selectedRef = index - 1
                                },
                                onRight = {
                                    val refs = draft.refs.toMutableList()
                                    val next = refs[index + 1]
                                    refs[index + 1] = refs[index]; refs[index] = next
                                    update(draft.copy(refs = refs))
                                    selectedRef = index + 1
                                },
                                onReplace = { pendingSlot = "ref:$index"; picker.launch("image/*") },
                                onToggle = {
                                    update(draft.copy(
                                        disabledRefs = if (disabled) draft.disabledRefs - index
                                        else draft.disabledRefs + index
                                    ))
                                },
                                onDelete = {
                                    update(draft.copy(
                                        refs = draft.refs.filterIndexed { i, _ -> i != index },
                                        disabledRefs = draft.disabledRefs
                                            .filter { it != index }
                                            .map { if (it > index) it - 1 else it }
                                    ))
                                    selectedRef = -1
                                    previewRevision++
                                }
                            )
                        }

                        Text(
                            if (shown == 0) "点方框上传第一张；顺序即 <Picture 1..N>。"
                            else "顺序即 <Picture 1..N>；点方块选中后可前移、后移、禁用或删除。",
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        // 首帧 / 尾帧 side by side: they are one pair, and a 16:9 half-width
                        // preview is still readable.
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            H3FrameSlot(
                                label = "首帧",
                                file = draft.first,
                                workflowId = workflowId,
                                revision = previewRevision,
                                enabled = !busy && client != null,
                                modifier = Modifier.weight(1f),
                                onPick = { pendingSlot = "first"; picker.launch("image/*") },
                                onRemove = { update(draft.copy(first = "")); previewRevision++ }
                            )
                            H3FrameSlot(
                                label = "尾帧",
                                file = draft.last,
                                workflowId = workflowId,
                                revision = previewRevision,
                                enabled = !busy && client != null,
                                modifier = Modifier.weight(1f),
                                onPick = { pendingSlot = "last"; picker.launch("image/*") },
                                onRemove = { update(draft.copy(last = "")); previewRevision++ }
                            )
                        }
                        // The node stretches the first frame onto the canvas and cover-crops the
                        // last one, so a mismatched first frame arrives distorted.
                        frameAspectWarning(context, workflowId, draft.first, draft.ratio)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            when (mode) {
                                H3Plan.Mode.T2V -> "两张都空着就是文生视频，不提交任何图片。"
                                H3Plan.Mode.I2V -> "只填首帧就是图生视频；再填尾帧即变成首尾帧视频。"
                                else -> "首帧锁定开头、尾帧锁定结尾，中间自动补全。"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
'''

lines[start:end] = NEW.split('\n')
io.open(P, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
print('material section replaced')
