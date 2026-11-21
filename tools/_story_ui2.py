import io
p = "app/src/main/java/sh/hnet/comfychair/ui/screens/StoryComposerScreen.kt"
s = io.open(p, encoding="utf-8").read()

if "import sh.hnet.comfychair.workflow.StoryField" not in s:
    s = s.replace("import sh.hnet.comfychair.workflow.StoryGenerator",
                  "import sh.hnet.comfychair.workflow.StoryField\nimport sh.hnet.comfychair.workflow.StoryGenerator")

comp = '''@Composable
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
            color = MaterialTheme.colorScheme.primary,
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
                val tint = when {
                    pick.ignored -> MaterialTheme.colorScheme.surfaceVariant
                    pick.locked -> MaterialTheme.colorScheme.tertiaryContainer
                    held || empty -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.secondaryContainer
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
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 一行四格只写值分不清哪格，格名用小字压在值前面。
                        Text(
                            field.label,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            when {
                                pick.ignored -> "已忽略"
                                empty -> "—"
                                else -> valueLabel(field, pick.value)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (empty || pick.ignored) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (pick.locked) {
                            Spacer(Modifier.width(4.dp))
                            Text("🔒", fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

'''
marker = "@Composable\nprivate fun FieldRow("
assert marker in s
s = s.replace(marker, comp + marker)
io.open(p, "w", encoding="utf-8", newline="\n").write(s)
print("ok")
