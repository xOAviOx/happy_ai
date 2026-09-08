package com.happy.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.happy.assistant.core.HappyLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The hands and eyes: global actions, and reading what is on screen.
 *
 * Deliberately passive. It subscribes to no events and does no work in the
 * background - an accessibility service that reacts to every window change is a
 * battery and privacy liability. It exists so that Happy can, on request, press
 * back, or walk the current window's node tree. Nothing is stored and nothing
 * leaves the device from here.
 *
 * The system owns the lifecycle; nothing constructs this. Note that the toggle
 * resets on every reinstall, which the setup checklist calls out.
 */
@AndroidEntryPoint
class HappyAccessibilityService : AccessibilityService() {

    @Inject lateinit var log: HappyLog

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        log.w(TAG, "accessibility service destroyed")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Walks the live window and collects everything readable. */
    private fun readScreen(): List<String> {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            log.e(TAG, "could not read the active window", t)
            null
        } ?: return emptyList()

        val out = mutableListOf<String>()
        collect(root, out, depth = 0)
        return out
    }

    private fun collect(node: AccessibilityNodeInfo?, into: MutableList<String>, depth: Int) {
        node ?: return
        if (depth > MAX_DEPTH || into.size >= MAX_NODES) return

        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        // Content description repeats the text often enough that keeping both
        // makes the screen read twice.
        val best = text.ifEmpty { description }
        if (best.isNotEmpty() && best.length <= MAX_ITEM_LENGTH && into.none { it == best }) {
            into += best
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), into, depth + 1)
        }
    }

    /**
     * Finds a clickable node by view id or by what it is described as, and clicks
     * it. View ids are exact but version-specific; descriptions survive redesigns
     * better. Trying both is what makes this survive an app update more often
     * than not.
     */
    private fun clickMatching(viewIds: List<String>, descriptions: List<String>): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            log.e(TAG, "could not read the active window", t)
            null
        } ?: return false

        for (id in viewIds) {
            val node = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isClickable }
            if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log.d(TAG, "clicked by view id $id")
                return true
            }
        }

        val wanted = descriptions.map { it.lowercase() }
        val found = mutableListOf<AccessibilityNodeInfo>()
        findClickable(root, wanted, found, 0)
        for (node in found) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log.d(TAG, "clicked by description")
                return true
            }
        }
        return false
    }

    private fun findClickable(
        node: AccessibilityNodeInfo?,
        wanted: List<String>,
        into: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        node ?: return
        if (depth > MAX_DEPTH || into.size >= MAX_MATCHES) return
        val description = node.contentDescription?.toString()?.lowercase()?.trim().orEmpty()
        if (node.isClickable && wanted.any { description == it }) into += node
        for (i in 0 until node.childCount) findClickable(node.getChild(i), wanted, into, depth + 1)
    }

    companion object {
        private const val TAG = "Accessibility"
        private const val MAX_MATCHES = 5
        private const val MAX_DEPTH = 40
        private const val MAX_NODES = 120
        private const val MAX_ITEM_LENGTH = 200

        @Volatile
        private var instance: HappyAccessibilityService? = null

        val isConnected: Boolean get() = instance != null

        /** Whether the user has switched Happy on in Settings. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return flat.split(":").any { it.startsWith("${context.packageName}/") }
        }

        fun perform(globalAction: Int): Boolean =
            instance?.performGlobalAction(globalAction) ?: false

        fun screenText(): List<String> = instance?.readScreen() ?: emptyList()

        fun click(viewIds: List<String>, descriptions: List<String>): Boolean =
            instance?.clickMatching(viewIds, descriptions) ?: false
    }
}
