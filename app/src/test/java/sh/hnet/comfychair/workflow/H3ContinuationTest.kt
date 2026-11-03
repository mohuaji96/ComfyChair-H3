package sh.hnet.comfychair.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H3ContinuationTest {

    private val structured = """
        subject_definitions:
        <Subject 1> is the woman from <Picture 1>
        <Subject 2> is the room from <Picture 3>

        summary:
        she walks in

        [Shot 1] she stops
    """.trimIndent()

    private val freeform = "A four second scene in a living room, the man stays silent."

    private val handWritten = structured.replace(
        "<Subject 2> is the room from <Picture 3>",
        "<Subject 2> is the room from <Picture 3>\n" +
            "<Picture 4> is the final frame of the previous segment, " +
            "providing the starting composition, lighting, and pose."
    )

    @Test
    fun addsOneDeclarationToTheDefinitionsSection() {
        val applied = H3Continuation.apply(structured, 4, true)
        assertTrue(applied.prompt.contains(H3Continuation.declaration(4)))
        assertEquals(4, applied.declared)
        assertTrue(applied.flagged)
        assertEquals(1, Regex("is the final frame").findAll(applied.prompt).count())
        assertTrue(applied.prompt.indexOf("<Picture 4>") > applied.prompt.indexOf("subject_definitions:"))
        assertTrue(applied.prompt.indexOf("<Picture 4>") < applied.prompt.indexOf("summary:"))
        assertTrue(applied.prompt.contains("[video continuation] she walks in"))
    }

    @Test
    fun skipsAHandWrittenClaimAndOwnsNothing() {
        val applied = H3Continuation.apply(handWritten, 4, true)
        assertEquals(handWritten, applied.prompt)
        assertEquals(H3Continuation.NONE, applied.declared)
        assertFalse(applied.flagged)
        assertFalse(applied.wrote)
    }

    @Test
    fun revertKeepsAHandWrittenClaim() {
        assertEquals(
            handWritten,
            H3Continuation.revert(handWritten, H3Continuation.NONE, false)
        )
    }

    @Test
    fun revertRemovesOurOwnClaimAndMarker() {
        val applied = H3Continuation.apply(structured, 4, true)
        val back = H3Continuation.revert(applied.prompt, applied.declared, applied.flagged)
        assertFalse(back.contains("<Picture 4>"))
        assertFalse(back.contains("[video continuation]"))
        assertTrue(back.contains("<Subject 1> is the woman from <Picture 1>"))
        assertTrue(back.contains("[Shot 1] she stops"))
    }

    @Test
    fun revertTouchesOnlyTheNumberItOwns() {
        val applied = H3Continuation.apply(structured, 6, true)
        // The user then adds a claim of their own for another picture.
        val edited = applied.prompt.replace(
            "<Subject 1> is the woman from <Picture 1>",
            "<Picture 3> is the final frame of the previous segment.\n" +
                "<Subject 1> is the woman from <Picture 1>"
        )
        val back = H3Continuation.revert(edited, 6, true)
        assertTrue(back.contains("<Picture 3> is the final frame"))
        assertFalse(back.contains("<Picture 6> is the final frame"))
    }

    @Test
    fun renameFollowsTheReferenceCount() {
        val applied = H3Continuation.apply(structured, 4, true)
        val renamed = H3Continuation.renumber(applied.prompt, 4, 6)
        assertTrue(renamed.contains("<Picture 6> is the final frame"))
        assertFalse(renamed.contains("<Picture 4> is the final frame"))
        assertEquals(1, Regex("is the final frame").findAll(renamed).count())
    }

    @Test
    fun freeformPromptGetsALeadingLineInItsOwnLanguage() {
        val en = H3Continuation.apply(freeform, 4, true)
        // Free text now names the frame too, so the model can tell which picture is the anchor.
        assertTrue(en.prompt.startsWith("<Picture 4> is the final frame of the previous segment"))
        assertTrue(en.prompt.contains("Continue from it, keeping the same framing"))
        assertEquals(4, en.declared)

        val zh = H3Continuation.apply("她推开门，走进昏暗的走廊，镜头跟随。", 4, true)
        assertTrue(zh.prompt.startsWith("<Picture 4> 是上一段结尾的画面"))
        val back = H3Continuation.revert(zh.prompt, zh.declared, zh.flagged)
        assertTrue(back.startsWith("她推开门"))
    }

    @Test
    fun disabledLeavesThePromptByteForByte() {
        val applied = H3Continuation.apply(structured, 4, false)
        assertEquals(structured, applied.prompt)
        assertFalse(applied.wrote)
    }

    @Test
    fun declaredPictureReportsWhoeverWroteIt() {
        assertEquals(4, H3Continuation.declaredPicture(handWritten))
        assertNull(H3Continuation.declaredPicture(structured))
    }

    @Test
    fun aStaleClaimForAnotherPictureIsNotAppendedTo() {
        val stale = handWritten
        val applied = H3Continuation.apply(stale, 6, true)
        assertEquals(stale, applied.prompt)
        assertFalse(applied.wrote)
    }
}
