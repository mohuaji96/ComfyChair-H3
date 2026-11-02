package sh.hnet.comfychair.workflow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract of the two workflows that are actually used, read from the files shipped in `res/raw`.
 * Nothing here talks to a server: this is the static half of the acceptance list, and it fails
 * loudly if a future edit starts writing a value that the workflow does not read.
 */
class H3PlanTest {

    private fun raw(name: String) = File("../workflows/$name").readText()

    private val frames = raw("ttv_h3_mobile_frames.json")
    private val r2v = raw("itv_h3_mobile_r2v.json")

    @Test
    fun framesTemplateServesThreeModes() {
        val spec = H3Plan.resolve(frames)!!
        assertEquals(listOf(H3Plan.Mode.T2V, H3Plan.Mode.I2V, H3Plan.Mode.FL2V), spec.modes)
        assertEquals("MiniMaxH3ImageToVideo", spec.coreClass)
        assertTrue(spec.supportsFrames)
        assertFalse(spec.supportsReferences)
    }

    @Test
    fun framesTemplateBindsTheRealNodes() {
        val spec = H3Plan.resolve(frames)!!
        // Steps come from the BasicScheduler that feeds the sampler, not from a look-alike node.
        assertEquals(4, spec.steps?.steps)
        assertEquals("steps", spec.steps?.input)
        assertEquals(6.0, spec.duration?.seconds ?: 0.0, 0.001)
        assertNotNull("duration must carry its frames expression", spec.duration?.expression)
        assertEquals(0.5, spec.resolution?.megapixels ?: 0.0, 0.001)
        assertTrue(spec.resolution!!.aspectRatio.startsWith("16:9"))
        assertEquals(32, spec.resolution?.multiple)
        assertEquals(24, spec.fps)
        assertNotNull(spec.prompt)
        assertNotNull(spec.unet)
    }

    @Test
    fun bothLoraLoadersAreKeptIncludingThePowerLoader() {
        val spec = H3Plan.resolve(frames)!!
        assertEquals(2, spec.loras.size)
        assertTrue(spec.loras.any { it.entryKey == null && it.file.contains("4step") })
        val power = spec.loras.single { it.isPowerSlot }
        assertTrue(power.file.contains("MysticXXX"))
        assertTrue(power.enabled)
        assertEquals(1.0, power.strength, 0.001)
    }

    @Test
    fun r2vTemplateBindsNineReferenceSlotsAndTenSeconds() {
        val spec = H3Plan.resolve(r2v)!!
        assertEquals(listOf(H3Plan.Mode.R2V), spec.modes)
        assertEquals("MiniMaxH3ReferenceToVideo", spec.coreClass)
        // The template wires ref_image_0..8; the count is read from the file, never assumed.
        assertEquals((0..8).toList(), spec.refIndices)
        assertEquals(4, spec.steps?.steps)
        assertEquals(10.0, spec.duration?.seconds ?: 0.0, 0.001)
        assertEquals(0.4, spec.resolution?.megapixels ?: 0.0, 0.001)
    }

    @Test
    fun t2vSubmitsNeitherFrame() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(mode = H3Plan.Mode.T2V))
        assertTrue(out.errors.toString(), out.ok)
        val core = JSONObject(out.json).getJSONObject(spec.coreId).getJSONObject("inputs")
        assertFalse(core.has("first_frame"))
        assertFalse(core.has("last_frame"))
        // The template itself is untouched: both frames are still wired in the source.
        val originalCore = H3Plan.nodes(JSONObject(frames)).getJSONObject(spec.coreId)
            .getJSONObject("inputs")
        assertTrue(originalCore.has("first_frame") && originalCore.has("last_frame"))
    }

    @Test
    fun i2vSubmitsOnlyTheFirstFrame() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(mode = H3Plan.Mode.I2V, firstFrame = "a.png"))
        assertTrue(out.errors.toString(), out.ok)
        val core = JSONObject(out.json).getJSONObject(spec.coreId).getJSONObject("inputs")
        assertTrue(core.has("first_frame"))
        assertFalse(core.has("last_frame"))
        val loadImage = JSONObject(out.json).getJSONObject(core.getJSONArray("first_frame").getString(0))
        assertEquals("a.png", loadImage.getJSONObject("inputs").getString("image"))
    }

    @Test
    fun fl2vSubmitsBothFramesIndependently() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec,
            H3Plan.Request(mode = H3Plan.Mode.FL2V, firstFrame = "f.png", lastFrame = "l.png"))
        assertTrue(out.errors.toString(), out.ok)
        val doc = JSONObject(out.json)
        val core = doc.getJSONObject(spec.coreId).getJSONObject("inputs")
        val firstId = core.getJSONArray("first_frame").getString(0)
        val lastId = core.getJSONArray("last_frame").getString(0)
        assertTrue("the two frames must not share one loader", firstId != lastId)
        assertEquals("f.png", doc.getJSONObject(firstId).getJSONObject("inputs").getString("image"))
        assertEquals("l.png", doc.getJSONObject(lastId).getJSONObject("inputs").getString("image"))
    }

    @Test
    fun modeTheWorkflowCannotRunIsRejected() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(mode = H3Plan.Mode.R2V, references = listOf("a.png")))
        assertFalse("the frames template has no reference inputs", out.ok)
    }

    @Test
    fun clearingTheUploadKeepsTheTemplatesOwnImage() {
        val spec = H3Plan.resolve(frames)!!
        // No upload for I2V: the template's own first-frame wire is what gets submitted, so the
        // mode is still valid instead of silently producing a text-only video.
        val out = H3Plan.build(frames, spec, H3Plan.Request(mode = H3Plan.Mode.I2V))
        assertTrue(out.errors.toString(), out.ok)
        assertTrue(JSONObject(out.json).getJSONObject(spec.coreId)
            .getJSONObject("inputs").has("first_frame"))
    }

    @Test
    fun referenceOrderIsTheSlotOrderAndExtraSlotsAreDropped() {
        val spec = H3Plan.resolve(r2v)!!
        val out = H3Plan.build(r2v, spec, H3Plan.Request(
            mode = H3Plan.Mode.R2V, references = listOf("one.png", "two.png")))
        assertTrue(out.errors.toString(), out.ok)
        val doc = JSONObject(out.json)
        val core = doc.getJSONObject(spec.coreId).getJSONObject("inputs")
        assertEquals("one.png", doc.getJSONObject(core.getJSONArray("ref_images.ref_image_0").getString(0))
            .getJSONObject("inputs").getString("image"))
        assertEquals("two.png", doc.getJSONObject(core.getJSONArray("ref_images.ref_image_1").getString(0))
            .getJSONObject("inputs").getString("image"))
        assertFalse("a third image must not survive a two-image request",
            core.has("ref_images.ref_image_2"))
    }

    @Test
    fun nineReferencesFillEveryWiredSlotAndLeaveNoOrphanLoader() {
        val spec = H3Plan.resolve(r2v)!!
        val out = H3Plan.build(r2v, spec, H3Plan.Request(
            mode = H3Plan.Mode.R2V,
            prompt = (1..9).joinToString(" ") { "<Picture $it>" },
            references = (1..9).map { "p$it.png" }
        ))
        assertTrue(out.errors.toString(), out.ok)
        val doc = JSONObject(out.json)
        val core = doc.getJSONObject(spec.coreId).getJSONObject("inputs")
        (0..8).forEach { i ->
            assertTrue("ref_image_$i missing", core.has("ref_images.ref_image_$i"))
            val loader = doc.getJSONObject(core.getJSONArray("ref_images.ref_image_$i").getString(0))
            assertEquals("p${i + 1}.png", loader.getJSONObject("inputs").getString("image"))
        }
        val loaders = doc.keys().asSequence()
            .count { doc.getJSONObject(it).optString("class_type") == "LoadImage" }
        assertEquals("every wired loader is used, none is left over", 9, loaders)
    }

    @Test
    fun stepsDurationAndRatioLandOnTheNodesThatAreRead() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(
            mode = H3Plan.Mode.T2V, steps = 8, seconds = 10.0, ratio = "9:16", megapixels = 0.6))
        assertTrue(out.errors.toString(), out.ok)
        val doc = JSONObject(out.json)
        assertEquals(8, doc.getJSONObject(spec.steps!!.nodeId).getJSONObject("inputs").getInt("steps"))
        assertEquals(10.0, doc.getJSONObject(spec.duration!!.nodeId).getJSONObject("inputs").getDouble("value"), 0.001)
        val res = doc.getJSONObject(spec.resolution!!.nodeId).getJSONObject("inputs")
        assertEquals("9:16 (Portrait Widescreen)", res.getString("aspect_ratio"))
        assertEquals(0.6, res.getDouble("megapixels"), 0.001)
        // The frames expression is the workflow's own; it must still be there and still used.
        val framesNode = doc.getJSONObject(spec.coreId).getJSONObject("inputs")
            .getJSONArray("length").getString(0)
        assertTrue(doc.getJSONObject(framesNode).getString("class_type") == "ComfyMathExpression")
    }

    @Test
    fun customSizeBypassesTheRatioSelectorOnTheCopyOnly() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(
            mode = H3Plan.Mode.T2V, customWidth = 1000, customHeight = 700))
        assertTrue(out.errors.toString(), out.ok)
        val core = JSONObject(out.json).getJSONObject(spec.coreId).getJSONObject("inputs")
        assertEquals(992, core.getInt("width"))   // snapped to 32
        assertEquals(704, core.getInt("height"))
        val originalCore = H3Plan.nodes(JSONObject(frames)).getJSONObject(spec.coreId).getJSONObject("inputs")
        assertTrue("the source keeps its links", originalCore.get("width") is org.json.JSONArray)
    }

    @Test
    fun extraLoraChainIsAppendedAfterTheWorkflowsOwnLoaders() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(
            mode = H3Plan.Mode.T2V,
            extraLoras = listOf(
                H3Plan.LoraAdd("a.safetensors", 0.7),
                H3Plan.LoraAdd("b.safetensors", 1.0)
            )
        ), emptyList())
        assertTrue(out.errors.toString(), out.ok)
        val doc = JSONObject(out.json)
        val byName = doc.keys().asSequence().mapNotNull { id ->
            val node = doc.optJSONObject(id) ?: return@mapNotNull null
            if (node.optString("class_type") != "LoraLoaderModelOnly") return@mapNotNull null
            node.getJSONObject("inputs").optString("lora_name") to id
        }.toMap()
        val aId = byName["a.safetensors"]
        val bId = byName["b.safetensors"]
        assertNotNull("first extra LoRA must exist", aId)
        assertNotNull("second extra LoRA must exist", bId)
        // a hangs off the workflow's own last loader, b hangs off a.
        assertEquals("1050", doc.getJSONObject(aId!!).getJSONObject("inputs").getJSONArray("model").getString(0))
        assertEquals(aId, doc.getJSONObject(bId!!).getJSONObject("inputs").getJSONArray("model").getString(0))
        assertEquals(0.7, doc.getJSONObject(aId).getJSONObject("inputs").getDouble("strength_model"), 0.0001)

        // Whatever consumed the old tail now consumes b.
        val original = H3Plan.nodes(JSONObject(frames))
        val consumers = original.keys().asSequence().filter { id ->
            val ins = original.optJSONObject(id)?.optJSONObject("inputs") ?: return@filter false
            ins.keys().asSequence().any { k ->
                (ins.opt(k) as? org.json.JSONArray)?.optString(0) == "1050"
            }
        }.toList()
        assertTrue(consumers.isNotEmpty())
        consumers.forEach { id ->
            val ins = doc.getJSONObject(id).getJSONObject("inputs")
            ins.keys().asSequence().forEach { k ->
                val link = ins.opt(k) as? org.json.JSONArray ?: return@forEach
                if (link.optString(0) == bId) return@forEach
            }
            assertTrue("$id should now read $bId", ins.keys().asSequence().any { k ->
                (ins.opt(k) as? org.json.JSONArray)?.optString(0) == bId
            })
        }
        // And the file on disk is untouched.
        assertFalse(File("../workflows/ttv_h3_mobile_frames.json").readText().contains("b.safetensors"))
    }

    @Test
    fun alignmentMultipleCanBeOverridden() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(
            mode = H3Plan.Mode.T2V, ratio = "16:9", megapixels = 0.5, multiple = 64
        ), emptyList())
        assertTrue(out.ok)
        val rs = spec.resolution!!
        assertEquals(64, JSONObject(out.json).getJSONObject(rs.nodeId)
            .getJSONObject("inputs").getInt("multiple"))
    }

    @Test
    fun clipAndBothVaesAreBoundForTheModelCard() {
        val spec = H3Plan.resolve(frames)!!
        assertEquals("clip_name", spec.clip?.input)
        assertEquals("vae_name", spec.videoVae?.input)
        assertEquals("vae_name", spec.audioVae?.input)
        assertTrue(spec.videoVae!!.fileName.contains("minimax_h3_video_vae"))
        assertTrue(spec.audioVae!!.fileName.contains("audio_vae"))
        // The audio VAE of this template lives on the audio decoder, not on the core input.
        assertTrue(spec.audioVae!!.nodeId != spec.coreId)
        // Bound model cells must not be duplicated in advanced settings.
        assertTrue(spec.extras.none { it.input in setOf("unet_name", "clip_name", "vae_name", "lora_name") })

        // The reference template wires the audio VAE on the core itself.
        val r2vSpec = H3Plan.resolve(r2v)!!
        assertNotNull(r2vSpec.audioVae)
        assertTrue(r2vSpec.audioVae!!.fileName.contains("audio_vae"))
    }

    @Test
    fun advancedParametersComeFromTheFileAndWriteBackToTheSameCell() {
        val spec = H3Plan.resolve(frames)!!
        val labels = spec.extras.map { it.label }
        assertTrue(labels.toString(), labels.any { it.contains("Scheduler") })
        assertTrue(labels.toString(), labels.any { it.contains("Denois") })
        assertTrue(labels.toString(), labels.any { it.contains("Sampler") })
        assertTrue(labels.toString(), labels.any { it.contains("帧率") })
        val scheduler = spec.extras.single { it.input == "scheduler" }
        assertEquals("beta", scheduler.value)

        val out = H3Plan.build(frames, spec, H3Plan.Request(
            mode = H3Plan.Mode.T2V, extras = mapOf(scheduler.key to "karras")))
        assertTrue(out.errors.toString(), out.ok)
        assertEquals("karras", JSONObject(out.json).getJSONObject(scheduler.nodeId)
            .getJSONObject("inputs").getString("scheduler"))
    }

    @Test
    fun framesExpressionMatchesSixSeconds() {
        val spec = H3Plan.resolve(frames)!!
        assertEquals(158, spec.frameCount)
        assertEquals(243, ComfyExpr.frames(spec.duration!!.expression, 10.0))
    }

    @Test
    fun unknownExpressionYieldsNullInsteadOfAGuess() {
        assertNull(ComfyExpr.evaluate("a +", mapOf("a" to 1.0)))
        assertNull(ComfyExpr.frames(null, 3.0))
        assertEquals(2.0, ComfyExpr.evaluate("round(1.5)", emptyMap())!!, 0.0001) // Python ties-to-even
        assertEquals(0.0, ComfyExpr.evaluate("round(0.5)", emptyMap())!!, 0.0001)
        assertEquals(14.0, ComfyExpr.evaluate("(5 - 8) % 17", emptyMap())!!, 0.0001)
        assertEquals(15.0, ComfyExpr.evaluate("100 // 7 + 1", emptyMap())!!, 0.0001)
    }
    // ---- one window for T2V / I2V / FL2V

    @Test
    fun framesWindowDerivesTheModeFromWhatIsFilled() {
        assertEquals(H3Plan.Mode.T2V, H3Plan.Mode.forFrames(null, null))
        assertEquals(H3Plan.Mode.T2V, H3Plan.Mode.forFrames("", "  "))
        assertEquals(H3Plan.Mode.I2V, H3Plan.Mode.forFrames("a.png", null))
        assertEquals(H3Plan.Mode.I2V, H3Plan.Mode.forFrames("a.png", ""))
        assertEquals(H3Plan.Mode.FL2V, H3Plan.Mode.forFrames("a.png", "b.png"))
        // A tail without a head stays FL2V so `build` can report what is missing.
        assertEquals(H3Plan.Mode.FL2V, H3Plan.Mode.forFrames(null, "b.png"))
    }

    @Test
    fun theThreeFrameModesShareOneAreaAndR2vStaysSeparate() {
        assertEquals(H3Plan.Area.FRAMES, H3Plan.Mode.T2V.area)
        assertEquals(H3Plan.Area.FRAMES, H3Plan.Mode.I2V.area)
        assertEquals(H3Plan.Area.FRAMES, H3Plan.Mode.FL2V.area)
        assertEquals(H3Plan.Area.R2V, H3Plan.Mode.R2V.area)
        assertEquals(2, H3Plan.Area.entries.size)
    }

    // ---- orphan pruning

    @Test
    fun textToVideoSubmitsNoImageLoaderAtAll() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(
            mode = H3Plan.Mode.T2V, prompt = "a cat", references = emptyList()))
        assertTrue(out.errors.toString(), out.ok)
        val nodes = JSONObject(out.json)
        val classes = nodes.keys().asSequence().map {
            nodes.getJSONObject(it).getString("class_type")
        }.toList()
        // The template ships two LoadImage nodes with the author's own filenames. They are
        // unreachable in T2V, and an unreachable node is never validated — but pruning removes
        // the trap instead of relying on that.
        assertFalse(classes.toString(), classes.contains("LoadImage"))
        assertTrue(classes.toString(), classes.contains("MiniMaxH3ImageToVideo"))
    }

    @Test
    fun imageToVideoKeepsOnlyTheFirstFrameLoader() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(
            mode = H3Plan.Mode.I2V, prompt = "a cat", firstFrame = "head.png"))
        assertTrue(out.errors.toString(), out.ok)
        val nodes = JSONObject(out.json)
        val images = nodes.keys().asSequence()
            .map { nodes.getJSONObject(it) }
            .filter { it.getString("class_type") == "LoadImage" }
            .map { it.getJSONObject("inputs").getString("image") }
            .toList()
        assertEquals(listOf("head.png"), images)
    }

    @Test
    fun firstAndLastFrameBothSurviveWhenBothAreGiven() {
        val spec = H3Plan.resolve(frames)!!
        val out = H3Plan.build(frames, spec, H3Plan.Request(
            mode = H3Plan.Mode.FL2V, prompt = "a cat",
            firstFrame = "head.png", lastFrame = "tail.png"))
        assertTrue(out.errors.toString(), out.ok)
        val nodes = JSONObject(out.json)
        val images = nodes.keys().asSequence()
            .map { nodes.getJSONObject(it) }
            .filter { it.getString("class_type") == "LoadImage" }
            .map { it.getJSONObject("inputs").getString("image") }
            .sorted()
            .toList()
        assertEquals(listOf("head.png", "tail.png"), images)
    }

    @Test
    fun referenceCountFollowsWhatIsActuallySubmitted() {
        val spec = H3Plan.resolve(r2v)!!
        val two = H3Plan.build(r2v, spec, H3Plan.Request(
            mode = H3Plan.Mode.R2V, prompt = "<Picture 1> and <Picture 2>",
            references = listOf("a.png", "b.png"), maxReferences = 2))
        assertTrue(two.errors.toString(), two.ok)
        val core = JSONObject(two.json).getJSONObject(spec.coreId).getJSONObject("inputs")
        assertTrue(core.toString(), core.has("ref_images.ref_image_0"))
        assertTrue(core.toString(), core.has("ref_images.ref_image_1"))
        assertFalse(core.toString(), core.has("ref_images.ref_image_2"))
    }

    // ---- adding a reference never waits on a server that has not answered

    @Test
    fun theTemplatesOwnSlotsNeverAskTheServer() {
        // A cached node definition leaves AutogrowLimits empty, so knownCeiling is 0. The first
        // three slots still have to open the picker, otherwise the page has no way in at all.
        assertEquals(H3Plan.NextRef.Pick(0), H3Plan.nextRefSlot(0, 3, 0))
        assertEquals(H3Plan.NextRef.Pick(1), H3Plan.nextRefSlot(1, 3, 0))
        assertEquals(H3Plan.NextRef.Pick(2), H3Plan.nextRefSlot(2, 3, 0))
    }

    @Test
    fun theFourthSlotAsksOnlyWhenTheCeilingIsUnknown() {
        assertEquals(H3Plan.NextRef.AskServer, H3Plan.nextRefSlot(3, 3, 0))
        assertEquals(H3Plan.NextRef.Pick(3), H3Plan.nextRefSlot(3, 3, 9))
        assertEquals(H3Plan.NextRef.Pick(4), H3Plan.nextRefSlot(4, 3, 9))
        assertEquals(H3Plan.NextRef.AskServer, H3Plan.nextRefSlot(9, 3, 9))
    }

    @Test
    fun aSmallerDeclaredCeilingDoesNotUndercutTheTemplate() {
        // The file already wires three sockets; a server that reports two cannot make slot two
        // illegal, and the workflow's own count wins.
        assertEquals(H3Plan.NextRef.Pick(2), H3Plan.nextRefSlot(2, 3, 2))
    }

    @Test
    fun ratiosAreOrderedWidestToTallestAndCarryChineseLabels() {
        val ratios = H3Resolution.ratios
        assertEquals(8, ratios.size)
        val aspects = ratios.map { it.second.first.toDouble() / it.second.second }
        assertEquals(aspects.sortedDescending(), aspects)
        // Every offered ratio has a gloss, and the label still starts with the value the
        // workflow receives, so a chip can never be mistaken for a different ratio.
        ratios.forEach { (name, _) ->
            val label = H3Resolution.label(name)
            assertTrue(label.startsWith(name))
            assertTrue(label.length > name.length)
        }
        assertEquals("5:4", H3Resolution.label("5:4"))
    }

    @Test
    fun everyOfferedRatioSitsOnExactlyOneChipRow() {
        val offered = H3Resolution.ratios.map { it.first }.toSet()
        val described = H3Resolution.commonRatios + H3Resolution.extraRatios
        assertEquals("no ratio appears twice", described.size, described.toSet().size)
        assertEquals("the two rows cover the whitelist exactly", offered, described.toSet())
        assertEquals(6, H3Resolution.commonRatios.size)
        // The three everyday orientations lead: landscape, portrait, square.
        assertEquals(listOf("16:9", "9:16", "1:1"), H3Resolution.commonRatios.take(3))
    }

    // ---- reference audio: wired by the file, submitted only when picked

    @Test
    fun theTemplateExposesThreeStandaloneAudioSockets() {
        val spec = H3Plan.resolve(r2v)!!
        assertTrue("the shipped template wires ref_audios", spec.supportsAudio)
        assertEquals(
            listOf("ref_audios.ref_audio_0", "ref_audios.ref_audio_1", "ref_audios.ref_audio_2"),
            spec.audioSlots.map { it.coreInput }
        )
        // Each slot keeps its own loader, and the filename cell is found by class, not by node id.
        assertEquals(listOf("1066", "1067", "1068"), spec.audioSlots.map { it.nodeId })
        spec.audioSlots.forEach {
            assertEquals("VHS_LoadAudioUpload", it.nodeClass)
            assertEquals("audio", it.fileInput)
            assertEquals(0, it.output)
        }
        // The frames template has no audio socket, so the UI must not offer one.
        assertFalse(H3Plan.resolve(frames)!!.supportsAudio)
    }

    @Test
    fun noAudioPickedLeavesNoAudioLoaderBehind() {
        val spec = H3Plan.resolve(r2v)!!
        val out = H3Plan.build(r2v, spec, H3Plan.Request(
            mode = H3Plan.Mode.R2V, references = listOf("one.png")))
        assertTrue(out.errors.toString(), out.ok)
        val doc = JSONObject(out.json)
        val core = doc.getJSONObject(spec.coreId).getJSONObject("inputs")
        (0..2).forEach { i ->
            assertFalse("socket $i must not survive an empty pick", core.has("ref_audios.ref_audio_$i"))
        }
        assertFalse("an unused audio loader carries the author's sample and must be pruned",
            doc.keys().asSequence().any {
                doc.getJSONObject(it).optString("class_type") == "VHS_LoadAudioUpload"
            })
    }

    @Test
    fun pickedAudioReplacesTheSampleAndKeepsItsLink() {
        val spec = H3Plan.resolve(r2v)!!
        val out = H3Plan.build(r2v, spec, H3Plan.Request(
            mode = H3Plan.Mode.R2V, references = listOf("one.png"),
            refAudios = listOf("h3_take.m4a")))
        assertTrue(out.errors.toString(), out.ok)
        val doc = JSONObject(out.json)
        val core = doc.getJSONObject(spec.coreId).getJSONObject("inputs")
        val link = core.getJSONArray("ref_audios.ref_audio_0")
        val loader = doc.getJSONObject(link.getString(0))
        assertEquals("VHS_LoadAudioUpload", loader.getString("class_type"))
        assertEquals("h3_take.m4a", loader.getJSONObject("inputs").getString("audio"))
        assertEquals(0, link.getInt(1))
        // The two sockets nobody filled must be cut, or the template's dead sample would ride
        // along and the server would reject the whole prompt.
        assertFalse(core.has("ref_audios.ref_audio_1"))
        assertFalse(core.has("ref_audios.ref_audio_2"))
        assertEquals(1, doc.keys().asSequence().count {
            doc.getJSONObject(it).optString("class_type") == "VHS_LoadAudioUpload"
        })
        // Images are untouched by the audio pick.
        assertTrue(core.has("ref_images.ref_image_0"))
        assertFalse(core.has("ref_images.ref_image_1"))
    }

    @Test
    fun threeAudiosFillEverySocketInOrder() {
        val spec = H3Plan.resolve(r2v)!!
        val out = H3Plan.build(r2v, spec, H3Plan.Request(
            mode = H3Plan.Mode.R2V, references = listOf("one.png"),
            refAudios = listOf("a1.m4a", "a2.wav", "a3.mp4")))
        assertTrue(out.errors.toString(), out.ok)
        val doc = JSONObject(out.json)
        val core = doc.getJSONObject(spec.coreId).getJSONObject("inputs")
        listOf("a1.m4a", "a2.wav", "a3.mp4").forEachIndexed { i, file ->
            val link = core.getJSONArray("ref_audios.ref_audio_$i")
            val loader = doc.getJSONObject(link.getString(0))
            assertEquals(file, loader.getJSONObject("inputs").getString("audio"))
        }
        assertEquals(3, doc.keys().asSequence().count {
            doc.getJSONObject(it).optString("class_type") == "VHS_LoadAudioUpload"
        })
    }

    @Test
    fun aPartialAudioPickKeepsOnlyTheChosenOnes() {
        val spec = H3Plan.resolve(r2v)!!
        val out = H3Plan.build(r2v, spec, H3Plan.Request(
            mode = H3Plan.Mode.R2V, references = listOf("one.png"),
            refAudios = listOf("a1.m4a", "a2.wav")))
        assertTrue(out.errors.toString(), out.ok)
        val core = JSONObject(out.json).getJSONObject(spec.coreId).getJSONObject("inputs")
        assertTrue(core.has("ref_audios.ref_audio_0"))
        assertTrue(core.has("ref_audios.ref_audio_1"))
        assertFalse("the third socket was never picked", core.has("ref_audios.ref_audio_2"))
    }

    @Test
    fun referenceVideoIsNeverSubmittedEvenWhenTheTemplateWiresIt() {
        // A template that still carries clip sockets would post its author's filename, which the
        // server rejects outright. Dropping the socket is what makes the clip dormant.
        val spec0 = H3Plan.resolve(r2v)!!
        val wired = JSONObject(r2v)
        val core = wired.getJSONObject("nodes").getJSONObject(spec0.coreId).getJSONObject("inputs")
        core.put("ref_videos.ref_video_0", org.json.JSONArray().put("1066").put(0))
        core.put("ref_video_audios.ref_video_audio_0", org.json.JSONArray().put("1066").put(0))
        val source = wired.toString()
        val spec = H3Plan.resolve(source)!!
        val out = H3Plan.build(source, spec, H3Plan.Request(
            mode = H3Plan.Mode.R2V, references = listOf("one.png")))
        assertTrue(out.errors.toString(), out.ok)
        val outCore = JSONObject(out.json).getJSONObject(spec.coreId).getJSONObject("inputs")
        assertFalse(outCore.has("ref_videos.ref_video_0"))
        assertFalse(outCore.has("ref_video_audios.ref_video_audio_0"))
    }
}
