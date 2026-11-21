package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stage line is the only thing telling a user what the server is doing, so a node type that
 * falls into the wrong bucket shows the wrong word for minutes at a time.
 */
class ProgressStageTest {

    @Test
    fun `loaders report loading a model`() {
        listOf("UNETLoader", "CLIPLoader", "VAELoader", "LoraLoaderModelOnly", "CheckpointLoaderSimple")
            .forEach { assertEquals(it, ProgressStage.LOAD_MODEL, ProgressStage.of(it)) }
    }

    @Test
    fun `samplers report sampling`() {
        listOf("KSampler", "KSamplerAdvanced", "SamplerCustomAdvanced")
            .forEach { assertEquals(it, ProgressStage.SAMPLE, ProgressStage.of(it)) }
    }

    @Test
    fun `decoders and video assembly are separate stages`() {
        assertEquals(ProgressStage.DECODE, ProgressStage.of("VAEDecode"))
        assertEquals(ProgressStage.DECODE, ProgressStage.of("VAEDecodeAudio"))
        assertEquals(ProgressStage.COMPOSE, ProgressStage.of("CreateVideo"))
        assertEquals(ProgressStage.SAVE, ProgressStage.of("SaveVideo"))
        assertEquals(ProgressStage.SAVE, ProgressStage.of("SaveImage"))
    }

    /** Constants, switches and math nodes finish in milliseconds; naming each would flicker. */
    @Test
    fun `bookkeeping nodes collapse into preparation`() {
        listOf("PrimitiveInt", "PrimitiveFloat", "ComfySwitchNode", "ComfyMathExpression", "RandomNoise")
            .forEach { assertEquals(it, ProgressStage.PREPARE, ProgressStage.of(it)) }
    }

    @Test
    fun `an unknown node never invents a stage`() {
        assertEquals(ProgressStage.UNKNOWN, ProgressStage.of(null))
        assertEquals(ProgressStage.UNKNOWN, ProgressStage.of(""))
    }

    @Test
    fun `only sampling carries a real number`() {
        assertTrue(ProgressStage.SAMPLE.determinate)
        ProgressStage.entries.filter { it != ProgressStage.SAMPLE }
            .forEach { assertFalse(it.label, it.determinate) }
    }

    /**
     * The clock is pinned to the right edge by a spacer, so a long name cannot push it; what a long
     * name does do is crowd the step counter on a narrow phone. Two to four characters is the
     * budget the line was drawn against.
     */
    @Test
    fun `stage names stay within the width the line was drawn for`() {
        ProgressStage.entries.forEach {
            assertTrue("${it.name}=${it.label}", it.label.length in 2..4)
        }
    }
    /**
     * The socket only says "node 3 is running". If the submit-time graph was not indexed, every
     * looking-up fails and the bar sits on 处理中 forever.
     */
    @Test
    fun `submitted graph answers node lookups`() {
        val graph = """{"3":{"class_type":"UNETLoader","inputs":{}},"9":{"class_type":"SamplerCustomAdvanced","inputs":{}}}"""
        NodeTypeIndex.record("prompt-a", graph)
        assertEquals("UNETLoader", NodeTypeIndex.classOf("prompt-a", "3"))
        assertEquals("SamplerCustomAdvanced", NodeTypeIndex.classOf("prompt-a", "9"))
        assertEquals(ProgressStage.LOAD_MODEL, ProgressStage.of(NodeTypeIndex.classOf("prompt-a", "3")))
        assertEquals(ProgressStage.SAMPLE, ProgressStage.of(NodeTypeIndex.classOf("prompt-a", "9")))
        // A different job must not inherit this one's graph.
        assertEquals(null, NodeTypeIndex.classOf("prompt-b", "3"))
    }
}