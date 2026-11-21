package sh.hnet.comfychair.workflow

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 参考图槽位只有两个的工作流，第三张图必须被挡住，不能回落去覆盖第二张。 */
class H3RefSlotTest {

    private val twoSlot = """
        {"nodes":{
          "1":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
            "prompt":"测试","width":[2,0],"height":[2,1],"length":[3,0],
            "clip":[4,0],"vae":[4,0],"audio_vae":[4,0],
            "ref_images.ref_image_0":[9,0],"ref_images.ref_image_1":[10,0]}},
          "2":{"class_type":"ResolutionSelector","inputs":{"aspect_ratio":"16:9","megapixels":0.5}},
          "3":{"class_type":"PrimitiveFloat","inputs":{"value":5}},
          "4":{"class_type":"CLIPLoader","inputs":{"clip_name":"a.safetensors"}},
          "9":{"class_type":"LoadImage","inputs":{"image":"a.png"}},
          "10":{"class_type":"LoadImage","inputs":{"image":"b.png"}}
        }}
    """.trimIndent()

    @After
    fun resetLimits() = AutogrowLimits.clear()

    @Test
    fun thirdReferenceIsRejectedInsteadOfOverwritingTheSecond() {
        val spec = H3Plan.resolve(twoSlot)!!
        val result = H3Plan.build(
            twoSlot, spec,
            H3Plan.Request(
                mode = H3Plan.Mode.R2V,
                references = listOf("a.png", "b.png", "c.png")
            )
        )
        assertTrue("槽位不够时应当直接报错", result.errors.isNotEmpty())
        assertFalse("第三张图不该落在 ref_image_2", result.json.contains("ref_image_2"))
    }

    @Test
    fun serverCeilingNeverExceedsTheInterfacesTheFileDefines() {
        // 节点声明 max 9，可这个文件只连了两个槽。
        AutogrowLimits.record("MiniMaxH3ReferenceToVideo", "ref_images", 9)
        val spec = H3Plan.resolve(twoSlot)!!
        val result = H3Plan.build(
            twoSlot, spec,
            H3Plan.Request(
                mode = H3Plan.Mode.R2V,
                references = listOf("a.png", "b.png", "c.png")
            )
        )
        assertTrue("声明 9 个也不能超出手上这两个槽", result.errors.isNotEmpty())
    }
}
