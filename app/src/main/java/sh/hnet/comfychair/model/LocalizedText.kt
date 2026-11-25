package sh.hnet.comfychair.model

import android.content.Context
import androidx.annotation.StringRes
import sh.hnet.comfychair.R

/**
 * A sentence that is finished only when it is shown.
 *
 * Classes without a context — the network client, the JSON parsers, the patch layer — used to
 * build Chinese sentences and hand them over as plain strings, which meant an English interface
 * still displayed them verbatim. They now carry the resource id plus its arguments and let the
 * screen resolve it, so the same failure reads in whichever language is active.
 */
data class LocalizedText(@StringRes val resId: Int, val args: List<Any> = emptyList()) {

    constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())

    fun format(context: Context): String =
        if (args.isEmpty()) context.getString(resId)
        else context.getString(resId, *args.toTypedArray())
}

/** Thrown by layers that cannot format their own message. */
class LocalizedException(val text: LocalizedText) : IllegalArgumentException()

/**
 * The displayable sentence for anything thrown by those layers. Anything else falls back to the
 * platform message, which is the best that can be said about an unexpected failure.
 */
fun Throwable.localizedText(context: Context, fallbackRes: Int = R.string.err_unexpected): String =
    when (this) {
        is LocalizedException -> text.format(context)
        else -> message?.takeIf { it.isNotBlank() } ?: context.getString(fallbackRes)
    }
