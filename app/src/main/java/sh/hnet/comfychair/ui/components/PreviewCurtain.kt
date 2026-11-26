package sh.hnet.comfychair.ui.components

import sh.hnet.comfychair.R
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 结果窗帘：把生成完的图片、视频从预览区撤下来，只留一行「已隐藏，点此恢复」。
 *
 * 不动文件、不动图库、不动队列，只是不再把成品挂在屏幕上——为的是有人走近时一键收屏。
 * 状态按工作区（生图 / 视频）分别记在 AppSettings 里，重启后仍然生效；下一次开始生成时
 * 自动拉开，所以「除非再生成否则一直是空的」这一条由 [GenerationPreviewArea] 触发。
 */
object PreviewCurtain {

    const val IMAGE = "image"
    const val VIDEO = "video"

    private const val PREFS = "AppSettings"
    private fun key(workspace: String) = "preview_hidden_$workspace"

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    /** Reads the stored flags once per process; cheap enough to call from composition. */
    fun sync(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _hidden.value = listOf(IMAGE, VIDEO).filter { prefs.getBoolean(key(it), false) }.toSet()
    }

    fun hide(context: Context, workspace: String) = set(context, workspace, true)

    fun show(context: Context, workspace: String) = set(context, workspace, false)

    private fun set(context: Context, workspace: String, hidden: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(workspace), hidden)
            .apply()
        _hidden.value = if (hidden) _hidden.value + workspace else _hidden.value - workspace
    }
}

/** Where the result used to be: one line saying it is still there, and how to bring it back. */
@Composable
fun HiddenResultState(onRestore: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.curtain_hidden),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRestore) { Text(stringResource(R.string.curtain_restore)) }
    }
}

/**
 * The curtain's handle, sitting in the preview's top-right corner. Translucent so it stays legible
 * on a bright photo without covering much of it.
 */
@Composable
fun HideResultButton(onHide: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shadowElevation = 2.dp,
        modifier = modifier
    ) {
        IconButton(onClick = onHide) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = stringResource(R.string.curtain_hide),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
