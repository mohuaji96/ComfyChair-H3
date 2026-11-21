package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.hnet.comfychair.storage.PromptTemplate
import sh.hnet.comfychair.storage.PromptTemplates

/** The template library rules that keep a delete or a corrupt id from leaving nothing active. */
class PromptTemplateRulesTest {

    private fun tpl(id: String, name: String) = PromptTemplate(id, name, body = "x", builtIn = id.startsWith("builtin_"))

    private val long = tpl(PromptTemplates.LONG_ID, "完整版")
    private val lite = tpl(PromptTemplates.LITE_ID, "精简版")
    private val custom = tpl("custom_1", "图片用")

    @Test
    fun activeFallsBackToTheLiteTemplateWhenTheIdIsGone() {
        assertEquals(lite, PromptTemplates.resolve(listOf(long, lite, custom), "missing"))
        assertEquals(custom, PromptTemplates.resolve(listOf(long, lite, custom), "custom_1"))
    }

    @Test
    fun deletingTheActiveTemplateLeavesTheLiteOneActive() {
        val list = listOf(long, lite, custom)
        assertEquals(lite.id, PromptTemplates.nextActiveAfterDelete(list, "custom_1", "custom_1"))
    }

    @Test
    fun deletingAnotherTemplateKeepsTheCurrentOne() {
        val list = listOf(long, lite, custom)
        assertEquals("custom_1", PromptTemplates.nextActiveAfterDelete(list, "custom_1", "builtin_lite"))
    }

    @Test
    fun aCopiedNameNeverCollides() {
        val list = listOf(long, tpl("custom_1", "完整版·副本"), tpl("custom_2", "完整版·副本 2"))
        assertEquals("完整版·副本 3", PromptTemplates.uniqueName(list, "完整版·副本"))
        assertEquals("新模板", PromptTemplates.uniqueName(list, "新模板"))
    }
}
