package sh.hnet.comfychair.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.storage.H3SourceStore
import sh.hnet.comfychair.workflow.H3WorkflowAdapter as A
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import kotlin.concurrent.thread

/** Android HTTP contract capture only. Fixture server does not run ComfyUI or generate videos. */
@RunWith(AndroidJUnit4::class)
class OfficialH3RequestTest {
    @Test fun fiveApiImportsPreserveExactOriginalWithoutDefinitions() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WorkflowManager.ensureInitialized(context)
        for(name in listOf("官方文生视频.json","官方图生视频.json","官方参考生视频.json","官方首尾帧生视频.json","video_minimax_h3_r2v.json")) {
            val source=context.assets.open("h3_official/$name").bufferedReader().use {it.readText()}
            val workflow=WorkflowManager.importH3Workflow(source,"IMPORT_"+java.util.UUID.randomUUID(),"Android import fixture").getOrThrow()
            try {
                assertEquals(source,H3SourceStore(context).original(workflow.id))
                assertFalse(workflow.jsonContent.contains("{{positive_prompt}}"))
                assertEquals(A.inspect(source)!!.mode,A.inspect(workflow.jsonContent)!!.mode)
            } finally {WorkflowManager.deleteUserWorkflow(workflow.id)}
        }
    }
    @Test fun officialT2VEditsReachPrompt()=verify("ttv_h3_official_t2v")
    @Test fun officialI2VUploadReachesPrompt()=verify("itv_h3_official_i2v")
    @Test fun officialR2VThreeUploadsReachOrderedInputs()=verify("itv_h3_official_r2v")
    @Test fun officialFL2VTwoUploadsReachBothFrames()=verify("itv_h3_official_fl2v")
    private fun verify(resource:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WorkflowManager.ensureInitialized(context)
        val id=UuidUtils.generateDeterministicId(resource)
        val workflow=WorkflowManager.getWorkflowById(id)!!
        val store=H3SourceStore(context)
        val origin=workflow.jsonContent
        var working=store.open(id,origin)
        val analysis=A.inspect(working)!!
        val prompt=analysis.bindings.single {it.kind=="prompt"}
        working=A.change(working,prompt,"Android loopback <Picture 1> <Picture 2> \"quoted\"\nsecond line")
        working=A.change(working,analysis.bindings.single {it.input=="noise_seed"},987654321012345L)
        val requests=mutableListOf<String>();val capture=AtomicReference<String>();val failure=AtomicReference<Throwable>()
        ServerSocket(0).use { server ->
            server.soTimeout=60_000
            val imageCount=when(analysis.mode) {A.Mode.T2V -> 0;A.Mode.I2V -> 1;A.Mode.FL2V -> 2;A.Mode.R2V -> 3;A.Mode.AMBIGUOUS -> 0}
            val worker=thread(isDaemon=true,name="h3-android-loopback") {
                try { repeat(imageCount+1) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout=60_000
                        val input=socket.getInputStream().buffered();val header=StringBuilder()
                        while(!header.endsWith("\r\n\r\n")) {val b=input.read();check(b>=0);header.append(b.toChar())}
                        val size=Regex("Content-Length: (\\d+)",RegexOption.IGNORE_CASE).find(header)!!.groupValues[1].toInt()
                        val bytes=ByteArray(size);var pos=0
                        while(pos<size) {val n=input.read(bytes,pos,size-pos);check(n>0);pos+=n}
                        val path=header.toString().substringBefore("\r\n").split(' ')[1];requests.add(path)
                        val response=if(path=="/upload/image") """{"name":"uploaded_$index.png","subfolder":"android-fixture","type":"input"}"""
                            else {check(path=="/prompt");capture.set(bytes.toString(Charsets.UTF_8));"""{"prompt_id":"android-loopback-only"}"""}
                        val body=response.toByteArray();socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()+body)
                    }
                } } catch(t:Throwable) {failure.set(t)}
            }
            val client=ComfyUIClient(context,"127.0.0.1",server.localPort).apply {setWorkingProtocol("http")}
            val png=android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3X8AAAAASUVORK5CYII=",0)
            repeat(imageCount) {slot ->
                val done=CountDownLatch(1);val name=AtomicReference<String>()
                client.uploadImage(png,"slot_$slot.png",overwrite=false,returnFullPath=true) {ok,file,error,_ ->if(!ok) failure.set(Exception(error));name.set(file);done.countDown()}
                assertTrue(done.await(40,TimeUnit.SECONDS));assertNull(failure.get())
                assertEquals("android-fixture/uploaded_$slot.png",name.get())
                working=if(analysis.mode==A.Mode.R2V) A.setReference(working,slot,name.get()) else A.change(working,analysis.bindings.single {it.kind=="image"},name.get())
            }
            store.save(id,working)
            val prepared=WorkflowManager.prepareVideoWorkflowById(workflowId=id,positivePrompt="unused form",width=512,height=512,length=25)
            assertNotNull(WorkflowManager.lastQuickParameterError,prepared)
            val done=CountDownLatch(1);val returned=AtomicReference<String>()
            client.submitPrompt(prepared!!) {ok,pid,error,_ ->if(!ok) failure.set(Exception(error));returned.set(pid);done.countDown()}
            assertTrue(done.await(40,TimeUnit.SECONDS));worker.join(1000);assertNull(failure.get())
            assertEquals("android-loopback-only",returned.get())
            val request=JSONObject(capture.get());val sent=A.inspect(request.getJSONObject("prompt").toString())!!
            assertEquals("Android loopback <Picture 1> <Picture 2> \"quoted\"\nsecond line",sent.bindings.single {it.kind=="prompt"}.value)
            assertEquals(987654321012345L,(sent.bindings.single {it.input=="noise_seed"}.value as Number).toLong())
            repeat(imageCount) {slot ->assertEquals("android-fixture/uploaded_$slot.png",sent.bindings.single {it.kind=="image" && it.slot==slot}.value)}
            assertEquals(imageCount,requests.count {it=="/upload/image"});assertEquals(1,requests.count {it=="/prompt"})
            val output=File(context.getExternalFilesDir(null),"official-h3-loopback").apply {mkdirs()}
            File(output,"${analysis.mode}-request.json").writeText(request.toString(2))
            File(output,"${analysis.mode}-original.json").writeText(origin)
            File(output,"${analysis.mode}-working.json").writeText(working)
        }
        store.reset(id)
    }
}
