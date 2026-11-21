package sh.hnet.comfychair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.comfychair.queue.JobStatus
import sh.hnet.comfychair.queue.QueueState
import sh.hnet.comfychair.queue.TrackedJob
import sh.hnet.comfychair.viewmodel.ContentType

/**
 * Interrupting is server-wide, so "cancel current" may only be offered for our own job.
 * The queue state has to be able to tell the two cases apart.
 */
class QueueOwnershipTest {

    private fun job(promptId: String) = TrackedJob(
        promptId = promptId,
        ownerId = "TEXT_TO_IMAGE",
        contentType = ContentType.IMAGE,
        status = JobStatus.EXECUTING
    )

    @Test
    fun theRunningJobIsOursWhenWeSubmittedIt() {
        val state = QueueState(executingPromptId = "p1", ownJobs = mapOf("p1" to job("p1")))
        assertTrue(state.isExecuting)
        assertTrue(state.isOwnJobExecuting)
    }

    @Test
    fun someoneElsesRunIsExecutingButNotOurs() {
        val state = QueueState(executingPromptId = "p2", ownJobs = mapOf("p1" to job("p1")))
        assertTrue(state.isExecuting)
        assertFalse(state.isOwnJobExecuting)
    }

    @Test
    fun anIdleServerIsNeither() {
        val state = QueueState()
        assertFalse(state.isExecuting)
        assertFalse(state.isOwnJobExecuting)
    }
}
