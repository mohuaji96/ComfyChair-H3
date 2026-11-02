package sh.hnet.comfychair

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sh.hnet.comfychair.ui.screens.BrowserAuthScreen
import sh.hnet.comfychair.ui.theme.ComfyChairTheme

/**
 * Activity that presents a WebView for browser-based SSO authentication.
 *
 * Expects [EXTRA_SERVER_URL] in the launching Intent.
 * On success, returns [Activity.RESULT_OK] with [RESULT_COOKIES] set to the captured
 * cookie string for the server URL. On cancellation (back press or close icon),
 * returns [Activity.RESULT_CANCELED].
 */
class BrowserAuthActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_SERVER_URL = "server_url"
        const val RESULT_COOKIES = "cookies"

        /**
         * Build a launch intent for this activity.
         * @param context Calling context
         * @param serverUrl Full server URL to load (e.g. "https://myserver.example.com:8188")
         */
        fun createIntent(context: Context, serverUrl: String): Intent =
            Intent(context, BrowserAuthActivity::class.java)
                .putExtra(EXTRA_SERVER_URL, serverUrl)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)
        if (serverUrl.isNullOrEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            ComfyChairTheme {
                BrowserAuthScreen(
                    serverUrl = serverUrl,
                    onAuthComplete = { cookies ->
                        val resultIntent = Intent().putExtra(RESULT_COOKIES, cookies)
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }
}
