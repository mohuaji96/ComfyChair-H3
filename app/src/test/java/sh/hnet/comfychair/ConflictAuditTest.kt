package sh.hnet.comfychair

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.hnet.comfychair.model.TagLibrary
import sh.hnet.comfychair.model.TagLibraryJson
import sh.hnet.comfychair.workflow.TagComposerEngine
import sh.hnet.comfychair.workflow.TagPick
import java.io.File

/**
 * Audit, not a regression gate: it enumerates the whole head-placement matrix and fuzzes the
 * engine, then writes what it found to build/conflict-audit.txt.
 *
 * The assertions here are deliberately weak — a hard assertion would freeze today's data. The
 * value is the report, which says which combinations the engine still lets through.
 */
class ConflictAuditTest {

    private fun library(): TagLibrary {
        val file = File("src/main/assets/tag_catalog.json")
        assumeTrue(file.exists())
        return TagLibraryJson.parse(file.readText(Charsets.UTF_8))
    }

    private fun soloEngine(library: TagLibrary): TagComposerEngine {
        val solo = library.constraints.peopleCount.filterValues { it <= 1 }.keys
        return TagComposerEngine(library, solo)
    }

    @Test
    fun auditHeadPlacements() {
        val library = library()
        val engine = soloEngine(library)
        val report = StringBuilder()

        val headPoses = library.category("headPose")!!.slots.first().pool
        val acts = library.category("sexAct")!!.slots.first().pool
        val table = library.constraints.headPlacements

        report.appendLine("== A. 头部位置组合（行=性行为，列=头部姿态，X=被拦，留空=放行）==")
        report.append("行".padEnd(14))
        headPoses.forEach { report.append(it.padEnd(10)) }
        report.appendLine()

        val blockedCells = mutableListOf<String>()
        acts.forEach { act ->
            if (act !in table) return@forEach
            report.append(act.padEnd(14))
            headPoses.forEach { pose ->
                val picks = mutableMapOf(
                    "sexAct.act" to TagPick(act, locked = true),
                    "headPose.pose" to TagPick(pose, locked = false)
                )
                engine.repair(picks)
                val kept = picks["headPose.pose"]?.value == pose
                report.append((if (kept) "·" else "X").padEnd(10))
                if (!kept) blockedCells += "$act + $pose"
            }
            report.appendLine()
        }

        report.appendLine()
        report.appendLine("== B. 拦掉的组合（共 ${blockedCells.size} 组）==")
        blockedCells.chunked(4).forEach { report.appendLine(it.joinToString("  |  ")) }

        // 反查：哪些头部姿态从来没被拦过——说明它的位置太宽，等于没接线。
        val neverBlockedPoses = headPoses.filter { pose ->
            blockedCells.none { it.endsWith("+ $pose") }
        }
        report.appendLine()
        report.appendLine("== C. 标了位置但从不被拦的头部姿态（位置给得太宽）==")
        report.appendLine(neverBlockedPoses.joinToString(" / ").ifEmpty { "(无)" })

        report.appendLine()
        report.appendLine("== D. 200 次随机抽样的一致性 ==")
        var headClash = 0
        var braWithCrop = 0
        var pussyWithBackView = 0
        var handOver = 0
        var propMiss = 0
        repeat(200) {
            val picks = mutableMapOf<String, TagPick>()
            engine.randomize(picks)
            val active = picks.filter { it.value.value.isNotEmpty() }.map { it.value.value }
            val headWords = active.filter { it in table }
            for (i in headWords.indices) {
                for (j in i + 1 until headWords.size) {
                    val a = table[headWords[i]]!!.toSet()
                    val b = table[headWords[j]]!!.toSet()
                    if (a.intersect(b).isEmpty()) headClash++
                }
            }
            if (("胸部在画外" in active || "只露出背影" in active || "背面视角" in active) &&
                active.any {
                    (it.endsWith("胸罩") || it.endsWith("胸衣") || it.endsWith("胸垫")) &&
                        !it.contains("无胸罩") && !it.contains("没穿")
                }
            ) braWithCrop++
            if (("只露出背影" in active || "背面视角" in active) &&
                picks["pussy.look"]?.value?.isNotEmpty() == true
            ) pussyWithBackView++
            val spent = picks.values.sumOf { library.constraints.handCost[it.value] ?: 0 }
            if (spent > library.constraints.handCap) handOver++
            val scene = picks["scene.place"]?.value.orEmpty()
            if (scene.isNotEmpty() && active.any { w ->
                    library.constraints.propNeeds[w]?.any { it !in library.constraints.sceneProps[scene].orEmpty() } == true
                }
            ) propMiss++
        }
        report.appendLine("头部两处打架: $headClash")
        report.appendLine("画外仍出胸罩: $braWithCrop")
        report.appendLine("背影仍写私处: $pussyWithBackView")
        report.appendLine("手部超支: $handOver")
        report.appendLine("取景需要水/镜但场地没有: $propMiss")

        val out = File("build/conflict-audit.txt")
        out.parentFile.mkdirs()
        out.writeText(report.toString())
        println(report)
        assertTrue("报告已生成", out.exists())
    }
}
