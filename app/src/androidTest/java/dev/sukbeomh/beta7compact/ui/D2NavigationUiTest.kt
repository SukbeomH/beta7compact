package dev.sukbeomh.beta7compact.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.accessibility.AccessibilityNodeInfo
import dev.sukbeomh.beta7compact.MainActivity
import dev.sukbeomh.beta7compact.usb.UsbAccess

/** Compose UI smoke test using the platform accessibility bridge; no new test dependency.
 * Requires a foreground, unlocked device. No user photos, identifiers or screenshots are collected. */
@Suppress("DEPRECATION")
class D2NavigationUiTest : InstrumentationTestCase() {
    private var activity: MainActivity? = null
    override fun setUp() {
        super.setUp()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
    }
    override fun tearDown() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        super.tearDown()
    }
    private fun waitText(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            val matches = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
            matches?.firstOrNull { it.isVisibleToUser }?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected UI label missing: $text")
    }
    private fun tap(text: String) {
        waitText(text)
        val matches = instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text)
        val clickable = matches.filter { it.text?.toString() == text }.firstNotNullOfOrNull { match ->
            var node: AccessibilityNodeInfo? = match
            while (node != null && !node.isClickable) node = node.parent
            node?.takeIf { it.isEnabled && it.isVisibleToUser }
        }
        assertNotNull("No clickable node for $text", clickable)
        assertTrue(clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    fun testFourTabsAndExplicitUserNavigation() {
        for (label in listOf("카메라", "사진", "레시피", "설정")) waitText(label)
        tap("레시피"); waitText("코드로 가져오기")
        tap("설정"); waitText("raw PTP로 가져오기")
        tap("카메라"); waitText("Sony 카메라"); waitText("준비 중")
        tap("사진"); waitText("미편집"); waitText("편집됨")
    }
    fun testLaunchDestinationMatchesUsbPresence() {
        val connected = runCatching { UsbAccess.device(instrumentation.targetContext) }.isSuccess
        waitText(if (connected) "Sony 카메라" else "미편집")
    }
}
