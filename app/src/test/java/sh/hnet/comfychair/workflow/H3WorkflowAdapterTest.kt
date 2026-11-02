package sh.hnet.comfychair.workflow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class H3WorkflowAdapterTest {
    private fun source(name:String)=File("src/main/assets/h3_official/$name").readText()
    @Test fun professionalSeedEditsKeepAllLongBits() {
        val json="""{"nodes":{"1":{"class_type":"RandomNoise","inputs":{"noise_seed":0}}}}"""
        val actual=sh.hnet.comfychair.WorkflowManager.applyNodeAttributeEdits(json,mapOf("1" to mapOf("noise_seed" to 987654321012345L)))
        assertEquals(987654321012345L,JSONObject(actual).getJSONObject("nodes").getJSONObject("1").getJSONObject("inputs").getLong("noise_seed"))
    }
    @Test fun modesFollowRealConnections() {
        assertEquals(H3WorkflowAdapter.Mode.T2V,H3WorkflowAdapter.inspect(source("官方文生视频.json"))!!.mode)
        assertEquals(H3WorkflowAdapter.Mode.I2V,H3WorkflowAdapter.inspect(source("官方图生视频.json"))!!.mode)
        assertEquals(H3WorkflowAdapter.Mode.R2V,H3WorkflowAdapter.inspect(source("官方参考生视频.json"))!!.mode)
    }
    @Test fun referencesRetainSixSlotsAndPictureText() {
        val original=source("video_minimax_h3_r2v.json")
        val before=H3WorkflowAdapter.inspect(original)!!
        var edited=H3WorkflowAdapter.setReference(original,1,"replacement.png")
        edited=H3WorkflowAdapter.setReference(edited,0,null)
        val after=H3WorkflowAdapter.inspect(edited)!!
        assertEquals(setOf(1,2,3,4,5),after.referenceInputs.keys)
        assertEquals(before.bindings.single {it.kind=="prompt"}.value,after.bindings.single {it.kind=="prompt"}.value)
        assertEquals("replacement.png",after.bindings.single {it.slot==1 && it.kind=="image"}.value)
    }
    @Test fun thirdReferenceIsAddedOnlyOnUpload() {
        val source=source("官方参考生视频.json")
        assertEquals(setOf(0,1),H3WorkflowAdapter.inspect(source)!!.referenceInputs.keys)
        val changed=H3WorkflowAdapter.setReference(source,2,"third.png")
        assertEquals(setOf(0,1,2),H3WorkflowAdapter.inspect(changed)!!.referenceInputs.keys)
        assertEquals(JSONObject(source).length()+1,JSONObject(changed).length())
    }
    @Test fun explicitPatchPreservesProfessionalEditsAndLongSeed() {
        val source=source("官方文生视频.json")
        val a=H3WorkflowAdapter.inspect(source)!!
        val seed=a.bindings.single {it.input=="noise_seed"}
        val edited=H3WorkflowAdapter.change(source,seed,987654321012345L)
        val professional=JSONObject(source)
        val unet=a.bindings.single {it.input=="unet_name"}
        professional.getJSONObject(unet.nodeId).getJSONObject("inputs").put("weight_dtype","fp8_e4m3fn")
        val result=JSONObject(H3ApiPatch.apply(source,edited,professional.toString()))
        assertEquals(987654321012345L,result.getJSONObject(seed.nodeId).getJSONObject("inputs").getLong(seed.input))
        assertEquals("fp8_e4m3fn",result.getJSONObject(unet.nodeId).getJSONObject("inputs").getString("weight_dtype"))
    }
    @Test fun replacingSharedLoaderDetachesOnlyChosenSlot() {
        val source=JSONObject(source("官方参考生视频.json"))
        val a=H3WorkflowAdapter.inspect(source.toString())!!
        val core=source.getJSONObject(a.coreId).getJSONObject("inputs")
        core.put("ref_images.ref_image_1",core.getJSONArray("ref_images.ref_image_0"))
        val before=H3WorkflowAdapter.inspect(source.toString())!!
        val edited=H3WorkflowAdapter.setReference(source.toString(),0,"only_slot_zero.png")
        val after=H3WorkflowAdapter.inspect(edited)!!
        assertEquals(before.bindings.single {it.slot==1 && it.kind=="image"}.value,after.bindings.single {it.slot==1 && it.kind=="image"}.value)
        assertEquals("only_slot_zero.png",after.bindings.single {it.slot==0 && it.kind=="image"}.value)
    }
    private fun definitions(source:String): Map<String,NodeTypeDefinition> {
        val n=H3WorkflowAdapter.nodes(JSONObject(source))
        return n.keys().asSequence().map { id ->
            val node=n.getJSONObject(id);val inputs=node.getJSONObject("inputs")
            val type=node.getString("class_type")
            type to NodeTypeDefinition(type,"test",inputs.keys().asSequence().map {InputDefinition(it,"*",false)}.toList(),listOf(OutputSlot("*","output")))
        }.toMap()
    }
    @Test fun disconnectedBrokenScalerIsRetainedWithoutBlockingI2V() {
        val source=source("官方图生视频.json");val defs=definitions(source)
        val altered=defs+ ("ImageScaleToTotalPixels" to defs.getValue("ImageScaleToTotalPixels").copy(inputs=listOf(InputDefinition("image","IMAGE",true))))
        val result=H3PromptCompiler.compile(source) {altered[it]}
        assertEquals(JSONObject(source).length(),JSONObject(result).length())
    }
    @Test fun relayRewiresAndAmbiguousBypassBlocks() {
        val source=source("官方文生视频.json");val n=JSONObject(source);val a=H3WorkflowAdapter.inspect(source)!!
        val core=n.getJSONObject(a.coreId).getJSONObject("inputs");val old=core.getJSONArray("clip")
        n.put("relay",JSONObject().put("class_type","Relay").put("inputs",JSONObject().put("value",old)))
        core.put("clip",JSONArray().put("relay").put(0))
        val defs=definitions(source)
        val result=JSONObject(H3PromptCompiler.compile(n.toString()) {defs[it]})
        assertFalse(result.has("relay"));assertEquals(old.toString(),result.getJSONObject(a.coreId).getJSONObject("inputs").getJSONArray("clip").toString())
        n.getJSONObject("relay").getJSONObject("inputs").put("second",old)
        assertTrue(runCatching {H3PromptCompiler.compile(n.toString()) {defs[it]}}.isFailure)
    }
    @Test fun missingActiveBackendNodeIsReported() {
        val source=source("官方文生视频.json");val defs=definitions(source)-"MiniMaxH3ImageToVideo"
        assertTrue(runCatching {H3PromptCompiler.compile(source) {defs[it]}}.exceptionOrNull()!!.message!!.contains("MiniMaxH3ImageToVideo"))
    }
}
