package sh.hnet.comfychair.util

import android.app.Activity
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import sh.hnet.comfychair.WorkflowEditorActivity
import java.io.File

/** Offline rendering acceptance only; an obstructing system dialog causes a failure. */
@RunWith(AndroidJUnit4::class)
class OfficialH3PanelSmokeTest {
    @Test fun threePanelsOpenWithoutNodeDefinitions() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val output=File(context.getExternalFilesDir(null),"official-h3-screens").apply {mkdirs()}
        fun find(node:AccessibilityNodeInfo?,text:String):AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.text?.toString()?.contains(text)==true) return node
            for(i in 0 until node.childCount) find(node.getChild(i),text)?.let {return it}
            return null
        }
        fun waitFor(text:String):AccessibilityNodeInfo {
            val end=SystemClock.uptimeMillis()+90_000
            while(SystemClock.uptimeMillis()<end) {
                val root=inst.uiAutomation.rootInActiveWindow
                if(root?.packageName?.toString()==context.packageName) find(root,text)?.let{return it}
                SystemClock.sleep(500)
            }
            error("App content not visible: $text (system overlay also fails acceptance)")
        }
        for((resource,mode) in listOf("ttv_h3_official_t2v" to "T2V","itv_h3_official_i2v" to "I2V","itv_h3_official_r2v" to "R2V")) {
            val intent=WorkflowEditorActivity.createIntent(context,UuidUtils.generateDeterministicId(resource))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            try {
                var button=waitFor("H3 参数 / 生成")
                while(!button.isClickable && button.parent!=null) button=button.parent
                assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitFor("H3 $mode 参数")
                val screenshot=inst.uiAutomation.takeScreenshot();assertNotNull(screenshot)
                File(output,"$mode.png").outputStream().use {screenshot!!.compress(Bitmap.CompressFormat.PNG,100,it)}
                if(mode=="R2V") {waitFor("参考图 1");waitFor("参考图 2");waitFor("参考图 3")}
            } finally {
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
        }
    }
}
