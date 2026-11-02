package sh.hnet.comfychair.connection

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Only full node definitions need a long response budget. Other APIs retain their limits. */
object NodeRequestPolicy {
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS).build()
}

/** A late response may update neither a different server nor a newer refresh. */
data class RefreshTicket(val epoch: Long, val client: Any, val serverId: String?) {
    fun matches(currentEpoch: Long, currentClient: Any?, currentServer: String?): Boolean =
        epoch == currentEpoch && client === currentClient && serverId == currentServer
}
