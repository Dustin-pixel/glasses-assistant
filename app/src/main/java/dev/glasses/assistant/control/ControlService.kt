package dev.glasses.assistant.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets the assistant use the phone for you by voice: swipe to the next TikTok, scroll, go back,
 * read what's on screen, tap a button by its label and type into a text box. It also tracks which
 * app is in front, so mirroring can pick the right crop. The user turns it on under Accessibility.
 * It acts only when asked; nothing on screen is stored or sent anywhere except to answer a request.
 */
class ControlService : AccessibilityService() {

    @Volatile private var front: String? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore the keyboard and system overlays; they aren't the app you're looking at.
            if (pkg != "com.android.systemui" && !pkg.contains("inputmethod") && !pkg.contains("keyboard")) front = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    val foreground: String? get() = rootInActiveWindow?.packageName?.toString() ?: front

    // ---------------------------------------------------------------- gestures

    private fun size(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private suspend fun gesture(path: Path, ms: Long): Boolean {
        val done = CompletableDeferred<Boolean>()
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val started = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(false) }
        }, null)
        return started && (withTimeoutOrNull(3000) { done.await() } ?: false)
    }

    private suspend fun doubleTap(x: Float, y: Float): Boolean {
        val done = CompletableDeferred<Boolean>()
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 50))
            .addStroke(GestureDescription.StrokeDescription(p, 140, 50))
            .build()
        val started = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(false) }
        }, null)
        return started && (withTimeoutOrNull(3000) { done.await() } ?: false)
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 280) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    /** Runs a named gesture. Returns what happened, in words. */
    suspend fun perform(action: String): String {
        val (w, h) = size()
        val cx = w / 2f
        val ok = when (action) {
            // Next item in a vertical feed (TikTok, Shorts, Reels): flick upward.
            "next" -> swipe(cx, h * 0.72f, cx, h * 0.22f, 220)
            "previous" -> swipe(cx, h * 0.25f, cx, h * 0.75f, 220)
            "scroll_down" -> swipe(cx, h * 0.70f, cx, h * 0.35f, 450)
            "scroll_up" -> swipe(cx, h * 0.35f, cx, h * 0.70f, 450)
            "swipe_left" -> swipe(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f)
            "swipe_right" -> swipe(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f)
            "tap_center" -> gesture(Path().apply { moveTo(cx, h / 2f) }, 60)
            "double_tap" -> doubleTap(cx, h / 2f)   // likes a TikTok
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> return "Unknown gesture '$action'."
        }
        return if (ok) "Done." else "The phone didn't accept that gesture."
    }

    // ---------------------------------------------------------------- reading and acting on screen

    /** The visible text on screen, top to bottom, with buttons marked. */
    fun readScreen(maxChars: Int = 4000): String {
        val root = rootInActiveWindow ?: return "Can't read the screen right now."
        val lines = mutableListOf<String>()
        val seen = HashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || !n.isVisibleToUser) return
            val label = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (label.isNotEmpty() && seen.add(label)) {
                lines += when {
                    n.isEditable -> "[text box: $label]"
                    n.isClickable && label.length < 40 -> "[button: $label]"
                    else -> label
                }
            } else if (n.isEditable && label.isEmpty()) lines += "[empty text box]"
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val app = root.packageName?.toString()?.let { appName(this, it) } ?: "an app"
        val text = lines.joinToString("\n")
        return "On screen in $app:\n" + if (text.length > maxChars) text.takeLast(maxChars) else text
    }

    private fun findAll(root: AccessibilityNodeInfo, test: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (n.isVisibleToUser && test(n)) out += n
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun labelOf(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    /** Taps the on-screen element whose label best matches [label]. */
    suspend fun tap(label: String): String {
        val root = rootInActiveWindow ?: return "Can't see the screen right now."
        val want = label.trim().lowercase()
        val matches = findAll(root) { labelOf(it).lowercase().contains(want) }
        val node = matches.minByOrNull { if (labelOf(it).lowercase() == want) 0 else labelOf(it).length }
            ?: return "Couldn't find \"$label\" on screen."
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return "Tapped \"${labelOf(node)}\"."
        val r = Rect().also { node.getBoundsInScreen(it) }
        val ok = gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60)
        return if (ok) "Tapped \"${labelOf(node)}\"." else "Found \"${labelOf(node)}\" but couldn't tap it."
    }

    /** Types into the focused text box (or the first one on screen), optionally sending it. */
    suspend fun type(text: String, send: Boolean): String {
        val root = rootInActiveWindow ?: return "Can't see the screen right now."
        val box = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: findAll(root) { it.isEditable }.firstOrNull()
            ?: return "There's no text box on screen."
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return "That text box wouldn't accept typing."
        if (!send) return "Typed it."
        kotlinx.coroutines.delay(300)
        // Most chat apps (Claude included) have a send button; otherwise press the keyboard's enter.
        val fresh = rootInActiveWindow ?: return "Typed it, but couldn't find a send button."
        val sendButton = findAll(fresh) {
            val l = labelOf(it).lowercase()
            it.isClickable && (l == "send" || l.startsWith("send ") || l == "submit" || l == "send message")
        }.firstOrNull()
        if (sendButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return "Typed and sent it."
        if (Build.VERSION.SDK_INT >= 30 &&
            box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        ) return "Typed it and pressed enter."
        return "Typed it, but couldn't find a send button."
    }

    /** Opens an app or link from here, which Android allows while this service is on. */
    fun launch(intent: Intent): Boolean = runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    companion object {
        @Volatile var instance: ControlService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return flat.split(':').any {
                ComponentName.unflattenFromString(it) == ComponentName(context, ControlService::class.java)
            }
        }

        fun appName(context: Context, pkg: String): String = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
