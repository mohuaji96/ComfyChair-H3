package sh.hnet.comfychair.ui.components.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Immutable tree representation of a flat model path list.
 *
 * Paths like "illustrious/v3/model.safetensors" become:
 *   Folder("illustrious", "illustrious") ->
 *     Folder("v3", "illustrious/v3") ->
 *       Leaf("model.safetensors", "illustrious/v3/model.safetensors")
 */
sealed class ModelTreeNode {
    data class Folder(
        val name: String,
        val fullPath: String,
        val children: List<ModelTreeNode>
    ) : ModelTreeNode()

    data class Leaf(
        val name: String,
        val fullPath: String
    ) : ModelTreeNode()
}

/**
 * Build an immutable tree from a flat list of model paths.
 * Within each level, folders are sorted before leaves, then both sorted alphabetically.
 * Backslashes are normalized to forward slashes.
 */
fun buildModelTree(paths: List<String>): List<ModelTreeNode> =
    buildTreeLevel(
        entries = paths.map { it.replace('\\', '/') to it },
        pathPrefix = ""
    )

private fun buildTreeLevel(
    entries: List<Pair<String, String>>,
    pathPrefix: String
): List<ModelTreeNode> {
    val folderEntries = mutableMapOf<String, MutableList<Pair<String, String>>>()
    val leaves = mutableListOf<ModelTreeNode.Leaf>()

    for ((normalized, original) in entries) {
        val slashIndex = normalized.indexOf('/')
        if (slashIndex < 0) {
            leaves.add(ModelTreeNode.Leaf(name = normalized, fullPath = original))
        } else {
            val folderName = normalized.substring(0, slashIndex)
            val remainder = normalized.substring(slashIndex + 1)
            folderEntries.getOrPut(folderName) { mutableListOf() }.add(remainder to original)
        }
    }

    val folders = folderEntries
        .map { (name, children) ->
            val fullPath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
            ModelTreeNode.Folder(
                name = name,
                fullPath = fullPath,
                children = buildTreeLevel(children, fullPath)
            )
        }
        .sortedBy { it.name.lowercase() }

    return folders + leaves.sortedBy { it.name.lowercase() }
}

/** Returns true if the top-level nodes contain at least one folder, triggering tree display. */
fun modelTreeHasFolders(nodes: List<ModelTreeNode>): Boolean =
    nodes.any { it is ModelTreeNode.Folder }

/**
 * Returns the folder portion of a model path, e.g. "illustrious/v3/model.safetensors" → "illustrious/v3".
 * Returns "" for root-level paths (no slash).
 */
fun folderPathOf(modelPath: String): String {
    val normalized = modelPath.replace('\\', '/')
    val lastSlash = normalized.lastIndexOf('/')
    return if (lastSlash >= 0) normalized.substring(0, lastSlash) else ""
}

/**
 * Renders hierarchical tree items inside an ExposedDropdownMenu.
 *
 * Accordion expand behavior: only one folder path is open at a time. Clicking a folder
 * expands it (collapsing siblings); clicking the same folder again collapses back to
 * the parent level.
 *
 * @param nodes         Children to render at the current level
 * @param expandedPath  The currently open folder's full path; "" means nothing expanded
 * @param selectedValue Full path of the currently selected model (for highlight)
 * @param depth         Nesting depth for indentation (0 = root)
 * @param onExpandFolder Invoked with the new expanded path when a folder is toggled
 * @param onSelectModel  Invoked with the model's full path when a leaf is clicked
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HierarchicalTreeItems(
    nodes: List<ModelTreeNode>,
    expandedPath: String,
    selectedValue: String,
    depth: Int,
    onExpandFolder: (String) -> Unit,
    onSelectModel: (String) -> Unit
) {
    for (node in nodes) {
        key(node) {
            when (node) {
                is ModelTreeNode.Folder -> {
                    val isExpanded = expandedPath == node.fullPath ||
                            expandedPath.startsWith("${node.fullPath}/")
                    val arrowRotation by animateFloatAsState(
                        targetValue = if (isExpanded) 90f else 0f,
                        label = "folder arrow ${node.fullPath}"
                    )

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Spacer(modifier = Modifier.width((depth * 16).dp))
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.FolderOpen
                                                  else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = node.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(arrowRotation)
                                )
                            }
                        },
                        onClick = {
                            if (isExpanded) {
                                onExpandFolder(node.fullPath.substringBeforeLast('/', ""))
                            } else {
                                onExpandFolder(node.fullPath)
                            }
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )

                    if (isExpanded) {
                        HierarchicalTreeItems(
                            nodes = node.children,
                            expandedPath = expandedPath,
                            selectedValue = selectedValue,
                            depth = depth + 1,
                            onExpandFolder = onExpandFolder,
                            onSelectModel = onSelectModel
                        )
                    }
                }

                is ModelTreeNode.Leaf -> {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Spacer(modifier = Modifier.width((depth * 16).dp))
                                Text(
                                    text = node.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (node.fullPath == selectedValue)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        onClick = { onSelectModel(node.fullPath) },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}
