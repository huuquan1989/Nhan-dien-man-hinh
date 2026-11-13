package com.example.screenwatcher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Dịch vụ trợ năng: thực hiện chạm / vuốt / Back / Home thay người dùng. */
class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile var instance: AutoClickService? = null
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private suspend fun gesture(p: Path, dur: Long): Boolean = suspendCancellableCoroutine { c ->
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, dur.coerceAtLeast(1)))
            .build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
            override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
        }, null)
        if (!ok && c.isActive) c.resume(false)
    }

    suspend fun tap(x: Float, y: Float, dur: Long = 60): Boolean =
        gesture(Path().apply { moveTo(x, y) }, dur)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, dur)
}

/**
 * Macro: mỗi dòng một lệnh
 *   tap_match [dx dy]        chạm vào vị trí vừa tìm thấy (có thể lệch dx, dy)
 *   tap x y                  chạm toạ độ
 *   longtap x y [ms]         giữ
 *   swipe x1 y1 x2 y2 [ms]   vuốt
 *   wait ms                  chờ
 *   back | home | recents    phím hệ thống
 *   # ghi chú
 */
object Macro {
    suspend fun run(script: String, mx: Int, my: Int) {
        val svc = AutoClickService.instance
        if (svc == null) {
            AppState.log("⚠ Chưa bật Dịch vụ trợ năng nên không thể chạm")
            return
        }
        for (raw in script.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val p = line.split(Regex("\\s+"))
            val a = p.drop(1).mapNotNull { it.toFloatOrNull() }
            when (p[0].lowercase()) {
                "tap_match" -> svc.tap(mx + (a.getOrNull(0) ?: 0f), my + (a.getOrNull(1) ?: 0f))
                "tap" -> if (a.size >= 2) svc.tap(a[0], a[1])
                "longtap" -> if (a.size >= 2) svc.tap(a[0], a[1], (a.getOrNull(2) ?: 800f).toLong())
                "swipe" -> if (a.size >= 4) svc.swipe(a[0], a[1], a[2], a[3], (a.getOrNull(4) ?: 300f).toLong())
                "wait" -> delay((a.getOrNull(0) ?: 500f).toLong())
                "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                else -> AppState.log("⚠ Lệnh macro không hợp lệ: $line")
            }
            delay(120)
        }
    }
}
