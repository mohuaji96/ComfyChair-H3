package sh.hnet.comfychair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.storage.PromptTemplate
import sh.hnet.comfychair.storage.PromptTemplates
import java.io.File

/**
 * 精简版 is the default now. The fallback is what a fresh install, a deleted template and a broken
 * active id all land on, so it is the one template that must never be missing.
 */
class PromptTemplateDefaultTest {

    private val builtIns = PromptTemplates.builtIns(
        "LONG BODY", "LITE BODY",
        "完整版", "精简版",
        "最细 · 难稿稳，少返工", "默认 · 判断全，出图快"
    )

    @Test
    fun theDefaultIsTheLiteTemplate() {
        val lite = builtIns.first { it.id == PromptTemplates.LITE_ID }

        assertEquals("缺 id 时回落到精简版", lite.id, PromptTemplates.resolve(builtIns, null).id)
        assertEquals(
            "生效 id 失效时回落到精简版",
            lite.id,
            PromptTemplates.resolve(builtIns, "custom_gone").id
        )
        assertEquals(
            "删掉一份自定义模板后仍留在精简版",
            lite.id,
            PromptTemplates.nextActiveAfterDelete(builtIns, PromptTemplates.LITE_ID, "custom_1")
        )
        // Deleting the one in use cannot keep it; with only the two built-ins left, 完整版 is next.
        assertEquals(
            "删掉正在用的精简版时退到剩下的那一份",
            PromptTemplates.LONG_ID,
            PromptTemplates.nextActiveAfterDelete(builtIns, PromptTemplates.LITE_ID, PromptTemplates.LITE_ID)
        )
    }

    @Test
    fun anExplicitChoiceStillWins() {
        val long = builtIns.first { it.id == PromptTemplates.LONG_ID }
        assertEquals(long.id, PromptTemplates.resolve(builtIns, long.id).id)
    }

    @Test
    fun theLongTemplateIsCalledCompleteVersion() {
        val long = builtIns.first { it.id == PromptTemplates.LONG_ID }
        val lite = builtIns.first { it.id == PromptTemplates.LITE_ID }

        assertEquals("完整版", long.name)
        assertEquals("精简版", lite.name)
        assertTrue("两个内置模板都要有说明", long.blurb.isNotEmpty() && lite.blurb.isNotEmpty())
        assertNotEquals("两条说明不能一样", long.blurb, lite.blurb)
    }

    @Test
    fun theBlurbSaysWhatTheTemplateIsFor() {
        val lite = builtIns.first { it.id == PromptTemplates.LITE_ID }
        val long = builtIns.first { it.id == PromptTemplates.LONG_ID }

        // Both lines name the upside first; the cost, if any, comes after it.
        assertTrue("精简版要说清默认身份", lite.blurb.startsWith("默认"))
        assertTrue("精简版要点出判断齐全", lite.blurb.contains("判断全"))
        assertTrue("完整版要说明适用场景", long.blurb.contains("难稿"))
    }

    @Test
    fun bothTemplatesCarryThePhysicalConsistencyRules() {
        val tokens = listOf("PHYSICAL CONSISTENCY", "Limb budget", "One posture", "Clothing", "One attribute", "Props")
        files().forEach { file ->
            assumeTrue("缺少模板文件 " + file.path, file.exists())
            val text = file.readText()
            tokens.forEach { token ->
                assertTrue("${file.name} 缺少规则：$token", text.contains(token))
            }
        }
    }

    @Test
    fun copiesInheritTheSourceNameButNotTheBlurb() {
        val copy = PromptTemplate("custom_1", "完整版·副本", "LONG BODY", builtIn = false, sourceName = "完整版")
        assertEquals("", copy.blurb)
        assertEquals("完整版", copy.sourceName)
    }

    private fun files(): List<File> = listOf(
        File("src/main/assets/prompt_expert.md"),
        File("src/main/assets/prompt_expert_lite.md"),
        File("app/src/main/assets/prompt_expert.md"),
        File("app/src/main/assets/prompt_expert_lite.md")
    ).filter { it.exists() }.distinctBy { it.name }
}
