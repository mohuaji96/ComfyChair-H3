package sh.hnet.comfychair

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.navigation.MainRoute

class TabReselectTest {

    @Test
    fun `tab reselect flow emits and collects successfully`() = runBlocking {
        val flow = MutableSharedFlow<MainRoute>(replay = 1)
        flow.tryEmit(MainRoute.H3Workspace)
        val received = flow.first()

        assertEquals(MainRoute.H3Workspace, received)
    }
}
