package sh.hnet.comfychair.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.ComfyUIClient
import sh.hnet.comfychair.connection.ConnectionManager
import sh.hnet.comfychair.WorkflowManager
import sh.hnet.comfychair.WorkflowType
import sh.hnet.comfychair.storage.LiteGraphSourceStore
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Android integration against a loopback HTTP capture, never a real H3 server.
 * Node definitions below are limited test fixtures, not a claimed /object_info snapshot.
 */
@RunWith(AndroidJUnit4::class)
class H3RequestIntegrationTest {
    @Test fun simpleQuickEditsReachActualHttpRequest() = verify("simple")
    @Test fun experimentalQuickEditsReachActualHttpRequest() = verify("experimental")

    private fun verify(fixture: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = instrumentation.context.assets.open("h3/$fixture.json").bufferedReader().use { it.readText() }
        WorkflowManager.ensureInitialized(context)
        val objectInfo = JSONObject()
        fun definition(type: String, vararg fields: Pair<String, String>) {
            val required = JSONObject()
            fields.forEach { (name, kind) -> required.put(name, JSONArray().put(kind)) }
            objectInfo.put(type, JSONObject().put("input", JSONObject().put("required", required))
                .put("output", JSONArray()).put("category", "AUDIT_FIXTURE_ONLY"))
        }
        definition("PrimitiveStringMultiline", "value" to "STRING")
        definition("ResolutionSelector", "aspect_ratio" to "STRING", "megapixels" to "FLOAT", "multiple" to "INT")
        definition("BasicScheduler", "scheduler" to "STRING", "steps" to "INT", "denoise" to "FLOAT")
        ConnectionManager.nodeTypeRegistry.parseObjectInfo(objectInfo)
        val converted = LiteGraphConverter { ConnectionManager.nodeTypeRegistry.getNodeDefinition(it) }
            .convert(JSONObject(source)).jsonContent
        val workflow = WorkflowManager.addUserWorkflowWithMapping(
            name = "AUDIT_${fixture}_${UUID.randomUUID()}", description = "Loopback fixture; not a generated H3 video",
            jsonContent = converted, type = WorkflowType.TTV, fieldMappings = emptyMap()
        ).getOrThrow()
        val store = LiteGraphSourceStore(context)
        try {
            store.saveOriginal(workflow.id, source)
            val newPrompt = "AUDIT_${fixture}_changed_prompt"
            store.changeWidget(workflow.id, 2, "value", newPrompt)
            store.changeWidget(workflow.id, 20, "megapixels", 0.7)
            store.changeWidget(workflow.id, 24, "steps", 5)
            store.changeWidget(workflow.id, 102, "steps", 9)
            assertEquals(source, store.exportOriginal(workflow.id))
            val working = JSONObject(store.readWorking(workflow.id)!!)
            assertEquals(JSONObject(source).getJSONArray("links").toString(), working.getJSONArray("links").toString())
            assertEquals(JSONObject(source).getJSONObject("definitions").toString(), working.getJSONObject("definitions").toString())
            val prepared = WorkflowManager.prepareVideoWorkflowById(
                workflowId = workflow.id, positivePrompt = "form value", width = 512, height = 512, length = 25
            )
            assertNotNull(WorkflowManager.lastQuickParameterError, prepared)
            val captured = AtomicReference<String>()
            val serverFailure = AtomicReference<Throwable>()
            val callbackDone = CountDownLatch(1)
            val success = AtomicReference(false)
            ServerSocket(0).use { server ->
                server.soTimeout = 30_000
                val worker = thread(name = "audit-loopback-capture") {
                    try {
                        server.accept().use { socket ->
                            socket.soTimeout = 30_000
                            val input = socket.getInputStream().buffered()
                            val header = StringBuilder()
                            while (!header.endsWith("\r\n\r\n")) {
                                val b = input.read(); check(b >= 0); header.append(b.toChar())
                            }
                            check(header.startsWith("POST /prompt "))
                            val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                                .find(header)!!.groupValues[1].toInt()
                            val bytes = ByteArray(length)
                            var read = 0
                            while (read < length) { val n = input.read(bytes, read, length - read); check(n > 0); read += n }
                            captured.set(String(bytes, Charsets.UTF_8))
                            val response = "{\"prompt_id\":\"audit-loopback-only\"}"
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n$response").toByteArray())
                        }
                    } catch (t: Throwable) { serverFailure.set(t) }
                }
                ComfyUIClient(context, "127.0.0.1", server.localPort).apply { setWorkingProtocol("http") }
                    .submitPrompt(prepared!!) { ok, _, _, _ -> success.set(ok); callbackDone.countDown() }
                assertTrue("HTTP callback timed out", callbackDone.await(40, TimeUnit.SECONDS))
                worker.join(2_000)
                assertNull(serverFailure.get())
                assertTrue("Loopback HTTP submission failed", success.get())
            }
            val request = JSONObject(captured.get())
            val nodes = request.getJSONObject("prompt")
            assertEquals(newPrompt, nodes.getJSONObject("2").getJSONObject("inputs").getString("value"))
            assertEquals(0.7, nodes.getJSONObject("20").getJSONObject("inputs").getDouble("megapixels"), 0.00001)
            assertEquals(5, nodes.getJSONObject("24").getJSONObject("inputs").getInt("steps"))
            assertEquals(9, nodes.getJSONObject("102").getJSONObject("inputs").getInt("steps"))
            val output = File(context.getExternalFilesDir(null), "audit-requests").apply { mkdirs() }
            File(output, "$fixture-loopback-request.json").writeText(request.toString(2))
            File(output, "$fixture-original.json").writeText(store.exportOriginal(workflow.id))
            File(output, "$fixture-working.json").writeText(store.readWorking(workflow.id)!!)
        } finally {
            WorkflowManager.deleteUserWorkflow(workflow.id)
            File(context.filesDir, "litegraph_sources/${workflow.id}").deleteRecursively()
        }
    }
}
