package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobRecoveryTest {

    @Test fun completedJobCarriesItsOutputs() {
        val json = """
            {"abc-1":{"status":{"completed":true,"status_str":"success"},
            "outputs":{"9":{"images":[{"filename":"h3_00001.mp4"}]},
                       "12":{"gifs":[{"filename":"preview.webp"}]}}}}
        """.trimIndent()
        val s = JobRecovery.parse(json, "abc-1")
        assertEquals(JobRecovery.State.COMPLETED, s.state)
        assertEquals(setOf("h3_00001.mp4", "preview.webp"), s.outputs.toSet())
        assertNull(s.message)
    }

    @Test fun executionErrorKeepsNodeAndMessage() {
        val json = """
            {"abc-2":{"status":{"completed":false,"status_str":"error",
            "messages":[["execution_error",{"node_id":"152","exception_message":"bit_depth invalid"}]]},
            "outputs":{}}}
        """.trimIndent()
        val s = JobRecovery.parse(json, "abc-2")
        assertEquals(JobRecovery.State.FAILED, s.state)
        assertTrue(s.message!!.contains("152"))
        assertTrue(s.message!!.contains("bit_depth"))
    }

    @Test fun unknownPromptIdIsNotTreatedAsFailure() {
        val s = JobRecovery.parse("{}", "gone")
        assertEquals(JobRecovery.State.UNKNOWN, s.state)
        assertTrue(s.outputs.isEmpty())
    }

    @Test fun stillRunningJobHasNoFilesYet() {
        val json = """{"abc-3":{"status":{"completed":false,"status_str":"running"},"outputs":{}}}"""
        val s = JobRecovery.parse(json, "abc-3")
        assertEquals(JobRecovery.State.RUNNING, s.state)
        assertTrue(s.outputs.isEmpty())
    }
}
