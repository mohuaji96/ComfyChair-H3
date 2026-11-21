package sh.hnet.comfychair.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's input folder is listed by an internal endpoint first and by LoadImage's own options
 * second. Both shapes are parsed here, because a server that answers neither leaves the source
 * sheet empty with no way to tell why.
 */
class ServerInputsTest {

    @Test
    fun listingStripsTheFolderMark() {
        val text = """["run_1.png [input]","pasted/old.jpg [input]","notes.txt"]"""
        assertEquals(listOf("run_1.png", "pasted/old.jpg", "notes.txt"), ServerInputs.parseListing(text))
    }

    @Test
    fun listingThatIsNotAJsonArrayIsReportedAsNoList() {
        assertNull(ServerInputs.parseListing("<html>not found</html>"))
    }

    @Test
    fun optionsAreReadFromAPlainList() {
        val text = """
            {"LoadImage":{"input":{"required":{"image":[["a.png","b.png"],{}]}}}}
        """.trimIndent()
        assertEquals(listOf("a.png", "b.png"), ServerInputs.parseOptions(text, "LoadImage", "image"))
    }

    @Test
    fun optionsAreReadFromTheComboShape() {
        val text = """
            {"LoadImage":{"input":{"required":{"image":["COMBO",{"options":["c.webp"]}]}}}}
        """.trimIndent()
        assertEquals(listOf("c.webp"), ServerInputs.parseOptions(text, "LoadImage", "image"))
    }

    @Test
    fun optionsMissingTheNodeAreNoList() {
        assertNull(ServerInputs.parseOptions("{}", "LoadImage", "image"))
    }

    @Test
    fun subfolderTravelsWithTheNameAsTheWorkflowTakesIt() {
        val file = ServerInputs.File("pasted/image.png")
        assertEquals("pasted", file.subfolder)
        assertEquals("image.png", file.filename)
        assertEquals("", ServerInputs.File("image.png").subfolder)
    }

    @Test
    fun workFilesAreNotSources() {
        // Inpainting's masked upload and the mask editor's own files are by-products of a job
        assertTrue(ServerInputs.isWorkFile("${ServerInputs.INPAINT_PREFIX}_1720000000.png"))
        assertTrue(ServerInputs.isWorkFile("clipspace/clipspace-painted.png"))
        assertTrue(ServerInputs.isWorkFile("clipspace-painted.png"))
        assertFalse(ServerInputs.isWorkFile("h3_0d6f.png"))
    }

    @Test
    fun videosAreToldApartByExtension() {
        assertTrue(ServerInputs.isVideoName("clip.webm"))
        assertTrue(ServerInputs.isVideoName("pasted/CLIP.MP4"))
        assertFalse(ServerInputs.isVideoName("still.png"))
    }
}
