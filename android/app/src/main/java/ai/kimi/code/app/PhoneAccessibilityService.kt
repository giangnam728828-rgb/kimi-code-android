package ai.kimi.code.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 手机操控的无障碍服务。
 *
 * DSH Go 的手机操控是它自己一套 6 万行的实现（读屏、任务编排、安全门、插件），
 * 这里只做 Kimi Code 真正需要的那个最小闭环：读当前屏幕、按文字点、按坐标点、
 * 输入、滚动、返回/主页/多任务、打开 App、读剪贴板。
 *
 * 服务本身不暴露任何跨应用入口：只有本进程的 [PhoneBridge] 能调用它，桥只绑
 * 127.0.0.1 并且要 token，容器里的 MCP 进程才知道这个 token。
 */
class PhoneAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Runtime.log("手机操控：无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        Runtime.log("手机操控：无障碍服务已断开")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- screen

    /** Flattened, model-friendly description of the screen. */
    fun screen(limit: Int = 120): String {
        val root = rootInActiveWindow ?: return "（读不到当前窗口，可能停在了系统界面）"
        val out = StringBuilder()
        val pkg = root.packageName?.toString().orEmpty()
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        out.append("应用：").append(app).append("（").append(pkg).append("）\n")
        val count = intArrayOf(0)
        walk(root, out, 0, count, limit)
        return out.toString()
    }

    private fun walk(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int, count: IntArray, limit: Int) {
        if (node == null || depth > 18 || count[0] >= limit) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = when {
            text.isNotEmpty() && desc.isNotEmpty() && text != desc -> "$text / $desc"
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }
        val editable = node.isEditable
        if (label.isNotEmpty() || editable) {
            count[0]++
            val rect = Rect()
            node.getBoundsInScreen(rect)
            out.append("  ".repeat(minOf(depth, 6)))
            out.append(if (node.isClickable) "[可点] " else "")
            if (editable) out.append("[输入框] ")
            out.append(label.take(80))
            out.append("  @(").append(rect.centerX()).append(",").append(rect.centerY()).append(")")
            out.append("\n")
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), out, depth + 1, count, limit)
            if (count[0] >= limit) return
        }
    }

    /** Finds the deepest node whose text or description equals/contains [needle]. */
    fun find(needle: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val target = needle.trim()
        if (target.isEmpty()) return null
        val exact = search(root, target, exact = true)
        return exact ?: search(root, target, exact = false)
    }

    private fun search(node: AccessibilityNodeInfo?, needle: String, exact: Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hit = if (exact) text == needle || desc == needle
        else text.contains(needle, true) || desc.contains(needle, true)
        if (hit && (node.isVisibleToUser)) return node
        for (i in 0 until node.childCount) {
            val found = search(node.getChild(i), needle, exact)
            if (found != null) return found
        }
        return null
    }

    /** Click by label; falls back to the nearest clickable ancestor, then its centre. */
    fun tapText(needle: String): String {
        val node = find(needle) ?: return "没找到「$needle」，先用 screen 看一下当前界面"
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return "已点击「$needle」"
                }
            }
            cur = cur.parent
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return if (tapXY(rect.centerX(), rect.centerY())) "已点击「$needle」（坐标）"
        else "找到了「$needle」，但点不动"
    }

    fun tapXY(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(200, 5000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 320): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(60, 3000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Types into the focused editable field, replacing whatever is there. */
    fun type(text: String): String {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findEditable(rootInActiveWindow)
            ?: return "当前没有输入框，先点一下要输入的位置"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "已输入"
        else "输入失败，可能这个输入框不接受直接赋值"
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    fun scroll(direction: String): String {
        val forward = direction != "up" && direction != "back"
        val root = rootInActiveWindow
        val target = findScrollable(root)
        if (target != null) {
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(action)) return "已滚动"
        }
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2
        val ok = if (forward) swipe(cx, (dm.heightPixels * 0.72f).toInt(), cx, (dm.heightPixels * 0.32f).toInt())
        else swipe(cx, (dm.heightPixels * 0.32f).toInt(), cx, (dm.heightPixels * 0.72f).toInt())
        return if (ok) "已滚动（手势）" else "滚不动"
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            findScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    fun key(name: String): String {
        val action = when (name.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "recent" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return "不认识的按键：$name（可用 back / home / recents / notifications）"
        }
        return if (performGlobalAction(action)) "已执行 $name" else "执行失败"
    }

    // ---------------------------------------------------------------- apps

    fun apps(query: String = "", limit: Int = 40): String {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { query.isBlank() || runCatching { pm.getApplicationLabel(it).toString().contains(query, true) }.getOrDefault(false) }
            .sortedBy { runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName) }
        if (list.isEmpty()) return "没有匹配的应用"
        return list.take(limit).joinToString("\n") { info ->
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
            "$label（${info.packageName}）"
        }
    }

    fun open(target: String): String {
        val pm = packageManager
        val direct = runCatching { pm.getLaunchIntentForPackage(target) }.getOrNull()
        if (direct != null) {
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(direct)
            return "已打开 $target"
        }
        val match = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).firstOrNull {
            val label = runCatching { pm.getApplicationLabel(it.activityInfo.applicationInfo).toString() }.getOrDefault("")
            label.equals(target, true) || label.contains(target, true)
        } ?: return "没找到应用「$target」，可以先 apps 搜一下"
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(match.activityInfo.packageName, match.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        return "已打开 ${match.activityInfo.packageName}"
    }

    // ---------------------------------------------------------------- misc

    fun clipboard(read: Boolean, text: String?): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return if (read) {
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().ifBlank { "（剪贴板是空的）" }
        } else {
            cm.setPrimaryClip(ClipData.newPlainText("kimi", text.orEmpty()))
            "已写入剪贴板"
        }
    }

    /** Android 11+ only; returns base64 PNG, capped at ~1600px wide. */
    fun screenshot(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "截图需要 Android 11 及以上"
        val latch = CountDownLatch(1)
        var result = "截图失败"
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            java.util.concurrent.Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    runCatching {
                        val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?: throw IllegalStateException("无法读取截图缓冲")
                        val scaled = scaleDown(bitmap, 1600)
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                        result = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
                    }
                    screenshot.hardwareBuffer.close()
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    result = "截图失败（错误码 $errorCode）"
                    latch.countDown()
                }
            },
        )
        latch.await(4, TimeUnit.SECONDS)
        return result
    }

    private fun scaleDown(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val ratio = maxWidth.toFloat() / src.width
        return Bitmap.createScaledBitmap(src, maxWidth, (src.height * ratio).toInt(), true)
    }

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set
    }
}
