package sh.hnet.comfychair

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response
import sh.hnet.comfychair.model.AuthCredentials
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OkHttp interceptor that adds authentication headers to requests.
 * Supports HTTP Basic Auth, Bearer token, and browser-session Cookie authentication.
 *
 * For Cookie auth, detects session expiry (401/403 responses, or a redirect that lands
 * outside the expected server host) and fires [onSessionExpired] at most once per session.
 * Call [resetSessionExpiredFlag] after re-authentication to re-arm detection.
 *
 * @param credentials Initial credentials to use
 * @param expectedHost Server hostname used to detect cross-domain SSO redirects;
 *                     null disables redirect-based expiry detection
 */
class AuthInterceptor(
    credentials: AuthCredentials = AuthCredentials.None,
    private val expectedHost: String? = null
) : Interceptor {

    @Volatile
    private var currentCredentials: AuthCredentials = credentials

    private val sessionExpiredFired = AtomicBoolean(false)

    /**
     * Invoked once when a Cookie-authenticated session expires.
     * Reset with [resetSessionExpiredFlag] after re-authentication.
     */
    var onSessionExpired: (() -> Unit)? = null

    fun setCredentials(newCredentials: AuthCredentials) {
        currentCredentials = newCredentials
    }

    fun getCredentials(): AuthCredentials = currentCredentials

    /**
     * Re-arm session-expiry detection after a successful re-authentication.
     */
    fun resetSessionExpiredFlag() {
        sessionExpiredFired.set(false)
    }

    private fun fireSessionExpiredOnce() {
        if (sessionExpiredFired.compareAndSet(false, true)) {
            onSessionExpired?.invoke()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val creds = currentCredentials
        val originalRequest = chain.request()

        val request = when (creds) {
            is AuthCredentials.None -> originalRequest
            is AuthCredentials.Basic -> originalRequest.newBuilder()
                .header("Authorization", Credentials.basic(creds.username, creds.password))
                .build()
            is AuthCredentials.Bearer -> originalRequest.newBuilder()
                .header("Authorization", "Bearer ${creds.token}")
                .build()
            is AuthCredentials.Cookie -> originalRequest.newBuilder()
                .header("Cookie", creds.cookies)
                .build()
        }

        val response = chain.proceed(request)

        if (creds is AuthCredentials.Cookie) {
            val isAuthFailure = response.code == 401 || response.code == 403
            // OkHttp follows redirects and sets response.request to the final URL.
            // A redirect to a different host indicates an SSO provider intercepted the request.
            val isRedirectedToSso = expectedHost != null &&
                response.request.url.host.isNotEmpty() &&
                response.request.url.host != expectedHost
            if (isAuthFailure || isRedirectedToSso) {
                fireSessionExpiredOnce()
            }
        }

        return response
    }
}
