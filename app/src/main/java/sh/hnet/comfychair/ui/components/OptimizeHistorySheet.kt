package sh.hnet.comfychair.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.comfychair.R
import sh.hnet.comfychair.storage.OptimizeRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The rewrite log.
 *
 * Tapping a row fills the optimiser sheet with that pair, so a good result can be reused without
 * paying for the call again. Starring keeps an entry through the hundred-record cap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptimizeHistorySheet(
    rows: List<OptimizeRecord>,
    onDismiss: () -> Unit,
    onPick: (OptimizeRecord) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var onlyFavorites by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = if (onlyFavorites) rows.filter { it.favorite } else rows
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.hist_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { confirmClear = true }, enabled = rows.isNotEmpty()) {
                    Text(stringResource(R.string.hist_clear))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onlyFavorites = false },
                    label = { Text(stringResource(R.string.hist_all, rows.size)) }
                )
                AssistChip(
                    onClick = { onlyFavorites = true },
                    label = { Text(stringResource(R.string.hist_favorites, rows.count { it.favorite })) }
                )
            }

            Spacer(Modifier.height(4.dp))

            if (shown.isEmpty()) {
                Text(
                    if (rows.isEmpty()) stringResource(R.string.hist_empty) else stringResource(R.string.hist_empty_fav),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 28.dp)
                )
            } else {
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    shown.forEach { record ->
                        HistoryRow(
                            record = record,
                            time = formatter.format(Date(record.at)),
                            onPick = { onPick(record) },
                            onToggleFavorite = { onToggleFavorite(record.id) },
                            onDelete = { onDelete(record.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.hist_clear_title)) },
            text = { Text(stringResource(R.string.hist_clear_body, rows.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.hist_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun HistoryRow(
    record: OptimizeRecord,
    time: String,
    onPick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                time,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(0.dp))
            Text(
                "  " + record.templateName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (record.favorite) "★" else "☆",
                style = MaterialTheme.typography.titleMedium,
                color = if (record.favorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                modifier = Modifier
                    .clickable(onClick = onToggleFavorite)
                    .padding(horizontal = 10.dp)
            )
            Text(
                stringResource(R.string.common_delete),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.clickable(onClick = onDelete)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.hist_input_tag) + record.input,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            stringResource(R.string.hist_output_tag) + record.output,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.hist_chars, record.inChars, record.outChars) +
                if (record.note.isBlank()) "" else " · ${record.note}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
