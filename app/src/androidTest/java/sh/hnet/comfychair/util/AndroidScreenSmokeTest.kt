package sh.hnet.comfychair.util

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import sh.hnet.comfychair.*
import sh.hnet.comfychair.storage.AppSettings
import sh.hnet.comfychair.storage.LiteGraphSourceStore
import java.io.File
import java.util.UUID

/** Offline screen smoke only; it does not claim a real server connection. */
@RunWith(AndroidJUnit4::class)
class AndroidScreenSmokeTest {
    @Test fun originalScreensAndQuickSheetRender() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val output = File(context.getExternalFilesDir(null), "audit-screens").apply { mkdirs() }
        val activities = mutableListOf<Activity>()
        fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.contains(text) == true || node.contentDescription?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
            return null
        }
        fun appWindowReady() {
            val deadline = SystemClock.uptimeMillis() + 60_000
            while (SystemClock.uptimeMillis() < deadline) {
                val window = inst.uiAutomation.rootInActiveWindow
                if (window?.packageName?.toString() == context.packageName) return
                // TCG can trigger a system-process ANR dialog. Preserve the first-run
                // evidence; choose Wait, never close or suppress errors in the app.
                if (find(window, "isn't responding") != null) {
                    find(window, "Wait")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                SystemClock.sleep(500)
            }
            fail("Application did not receive an accessible foreground window")
        }
        fun launch(intent: Intent): Activity = inst.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            .also { activities += it; inst.waitForIdleSync(); appWindowReady(); SystemClock.sleep(1_000); assertFalse(it.isFinishing) }
        fun screenshot(name: String) {
            val bitmap = inst.uiAutomation.takeScreenshot()
            assertNotNull("Screenshot unavailable", bitmap)
            File(output, "$name.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun click(text: String) {
            appWindowReady()
            var target = find(inst.uiAutomation.rootInActiveWindow, text)
            assertNotNull("Missing UI entry: $text", target)
            while (target != null && !target.isClickable) target = target.parent
            assertTrue("Could not click $text", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            inst.waitForIdleSync(); SystemClock.sleep(1_000)
        }
        WorkflowManager.ensureInitialized(context)
        val source = inst.context.assets.open("h3/simple.json").bufferedReader().use { it.readText() }
        val converted = LiteGraphConverter { null }.convert(JSONObject(source)).jsonContent
        val workflow = WorkflowManager.addUserWorkflowWithMapping(
            "AUDIT_SCREEN_${UUID.randomUUID()}", "Offline render fixture", converted, WorkflowType.TTV, emptyMap()
        ).getOrThrow()
        LiteGraphSourceStore(context).saveOriginal(workflow.id, source)
        val offlineBefore = AppSettings.isOfflineMode(context)
        try {
            launch(Intent(context, MainActivity::class.java))
            screenshot("01-login")
            AppSettings.setOfflineMode(context, true)
            launch(Intent(context, MainContainerActivity::class.java))
            screenshot("02-generation-offline")
            for ((id, name) in listOf(
                R.string.nav_image_to_image to "07-image-to-image",
                R.string.nav_text_to_video to "08-text-to-video",
                R.string.nav_image_to_video to "09-image-to-video",
                R.string.nav_text_to_image to "10-return-text-to-image"
            )) {
                click(context.getString(id))
                screenshot(name)
            }
            val gallery = launch(Intent(context, GalleryContainerActivity::class.java))
            screenshot("11-gallery-offline")
            inst.runOnMainSync { gallery.finish() }
            launch(Intent(context, SettingsContainerActivity::class.java))
            screenshot("03-workflows-offline")
            launch(WorkflowEditorActivity.createIntent(context, workflow.id))
            screenshot("04-h3-professional-editor")
            click("快捷参数")
            val sheetDeadline = SystemClock.uptimeMillis() + 30_000
            while (find(inst.uiAutomation.rootInActiveWindow, "工作副本") == null &&
                SystemClock.uptimeMillis() < sheetDeadline) SystemClock.sleep(500)
            screenshot("05-quick-parameters")
            assertNotNull("Quick parameter sheet did not open", find(inst.uiAutomation.rootInActiveWindow, "工作副本"))
            inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            inst.waitForIdleSync()
            assertFalse(activities.last().isFinishing)
            screenshot("06-editor-after-back")
        } finally {
            inst.runOnMainSync { activities.reversed().forEach { it.finish() } }
            AppSettings.setOfflineMode(context, offlineBefore)
            WorkflowManager.deleteUserWorkflow(workflow.id)
            File(context.filesDir, "litegraph_sources/${workflow.id}").deleteRecursively()
        }
    }
}
