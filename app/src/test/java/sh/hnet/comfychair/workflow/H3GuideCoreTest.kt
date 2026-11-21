package sh.hnet.comfychair.workflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参考生视频核心（MiniMaxH3ReferenceToVideo）的 execute 签名里没有 first_frame，
 * 给它挂首帧服务端会直接 TypeError。这类 Turbo 工作流的首尾帧走 MiniMaxH3AddGuide。
 */
class H3GuideCoreTest {

    private val guided = """
        {"nodes":{
          "1":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
            "prompt":"测试","width":[2,0],"height":[2,1],"length":[3,0],
            "clip":[4,0],"vae":[4,0],"audio_vae":[4,0],
            "ref_images.ref_image_0":[9,0],"ref_images.ref_image_1":[190,0]}},
          "2":{"class_type":"ResolutionSelector","inputs":{"aspect_ratio":"16:9","megapixels":0.5}},
          "3":{"class_type":"PrimitiveFloat","inputs":{"value":15}},
          "4":{"class_type":"CLIPLoader","inputs":{"clip_name":"a.safetensors"}},
          "9":{"class_type":"LoadImage","inputs":{"image":"sample.png"}},
          "190":{"class_type":"LoadImage","inputs":{"image":"sample_end.png"}},
          "181":{"class_type":"MiniMaxH3AddGuide","inputs":{"frame_idx":0,"positive":[1,0],"latent":[1,1],"vae":[4,0],"image":[9,0]}},
          "192":{"class_type":"MiniMaxH3AddGuide","inputs":{"frame_idx":-1,"positive":[181,0],"latent":[1,1],"vae":[4,0],"image":[190,0]}},
          "30":{"class_type":"BasicGuider","inputs":{"conditioning":[192,0],"model":[4,0]}}
        }}
    """.trimIndent()

    private val bare = """
        {"nodes":{
          "1":{"class_type":"MiniMaxH3ReferenceToVideo","inputs":{
            "prompt":"测试","width":[2,0],"height":[2,1],"length":[3,0],
            "clip":[4,0],"vae":[4,0],"audio_vae":[4,0],
            "ref_images.ref_image_0":[9,0]}},
          "2":{"class_type":"ResolutionSelector","inputs":{"aspect_ratio":"16:9","megapixels":0.5}},
          "3":{"class_type":"PrimitiveFloat","inputs":{"value":15}},
          "4":{"class_type":"CLIPLoader","inputs":{"clip_name":"a.safetensors"}},
          "9":{"class_type":"LoadImage","inputs":{"image":"sample.png"}}
        }}
    """.trimIndent()

    @Test
    fun firstFrameGoesToGuideNotToTheCore() {
        val spec = H3Plan.resolve(guided)!!
        val result = H3Plan.build(
            guided, spec,
            H3Plan.Request(mode = H3Plan.Mode.I2V, firstFrame = "head.png")
        )
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertFalse("核心节点被塞了 first_frame，服务端会 TypeError", result.json.contains("\"first_frame\""))
        assertTrue("首帧没写给引导节点", result.json.contains("head.png"))
    }

    @Test
    fun frameInputOnACoreThatCannotTakeItIsRejected() {
        val spec = H3Plan.resolve(bare)!!
        val result = H3Plan.build(
            bare, spec,
            H3Plan.Request(mode = H3Plan.Mode.I2V, firstFrame = "head.png")
        )
        assertTrue("没有引导节点又没有首帧输入时应当直接拦下", result.errors.isNotEmpty())
    }
}
