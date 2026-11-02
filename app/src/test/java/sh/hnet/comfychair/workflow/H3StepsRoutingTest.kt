package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H3StepsRoutingTest {

    private fun workflow(switchValue: Boolean?): String {
        val flag = when (switchValue) {
            true -> "{\"class_type\":\"PrimitiveBoolean\",\"inputs\":{\"value\":true}}"
            false -> "{\"class_type\":\"PrimitiveBoolean\",\"inputs\":{\"value\":false}}"
            null -> null
        }
        val nodes = mutableListOf(
            "\"159\":{\"class_type\":\"PrimitiveInt\",\"inputs\":{\"value\":20}}",
            "\"160\":{\"class_type\":\"PrimitiveInt\",\"inputs\":{\"value\":8}}",
            "\"158\":{\"class_type\":\"ComfySwitchNode\",\"inputs\":{\"switch\":[\"161\",0]," +
                "\"on_true\":[\"160\",0],\"on_false\":[\"159\",0]}}"
        )
        if (flag != null) nodes.add("\"161\":$flag")
        return "{" + nodes.joinToString(",") + "}"
    }

    @Test fun switchOffRunsTheFalseBranch() {
        val r = H3StepsRouting.routes(workflow(false)).single()
        assertEquals("159", r.activeNodeId)
        assertEquals(20.0, r.activeSteps!!, 0.001)
        assertEquals("160", r.inactiveNodeId)
        assertEquals(8.0, r.inactiveSteps!!, 0.001)
    }

    @Test fun switchOnRunsTheTrueBranch() {
        val r = H3StepsRouting.routes(workflow(true)).single()
        assertEquals("160", r.activeNodeId)
        assertEquals(8.0, r.activeSteps!!, 0.001)
    }

    @Test fun missingSwitchValueKeepsActiveUnknown() {
        val r = H3StepsRouting.routes(workflow(null)).single()
        assertNull(r.switchValue)
        assertNull(r.activeNodeId)
        assertNull(H3StepsRouting.activeSteps(workflow(null)))
    }

    @Test fun plainWorkflowWithoutSwitchHasNoRoute() {
        val plain = "{\"1\":{\"class_type\":\"PrimitiveInt\",\"inputs\":{\"value\":20}}}"
        assertEquals(0, H3StepsRouting.routes(plain).size)
        assertNull(H3StepsRouting.activeSteps(plain))
    }
}
