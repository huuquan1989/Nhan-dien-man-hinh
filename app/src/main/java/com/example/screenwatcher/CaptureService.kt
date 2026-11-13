package com.example.screenwatcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream

/**
 * Giữ MediaProjection (chụp màn hình), vòng lặp quét luật, và lớp phủ chọn vùng.
 */
class CaptureService : Service() {

    companion object {
        const val A_START = "sw.start"
        const val A_MONITOR = "sw.monitor"
        const val A_STOP = "sw.stop"
        const val A_PICK = "sw.pick"
        const val A_TEST = "sw.test"
        private const val CH = "sw_capture"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var curW = 0
    private var curH = 0
    @Volatile private var last: Bitmap? = null
    private var overlay: SelectorOverlay? = null
    private var loop: Job? = null
    private val lastFire = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            A_START -> start()
            A_MONITOR -> {
                AppState.monitoring = i.getBooleanExtra("on", false) && projection != null
                getSystemService(NotificationManager::class.java).notify(1, notif())
                AppState.log(if (AppState.monitoring) "▶ Bắt đầu theo dõi" else "⏸ Dừng theo dõi")
            }
            A_STOP -> { stopAll(); return START_NOT_STICKY }
            A_PICK -> pick(i.getStringExtra("mode") ?: "REGIONS", i.getStringExtra("id") ?: "")
            A_TEST -> test(i.getStringExtra("id") ?: "")
        }
        return START_NOT_STICKY
    }

    /* ---------------- khởi động chụp màn hình ---------------- */

    private fun start() {
        val data = AppState.projData ?: return
        teardown()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "ScreenWatcher", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(1, notif())

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(AppState.projCode, data)
        AppState.projData = null
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopAll() } }
        }, main)
        projection = p
        setupDisplay()
        AppState.projectionReady = true
        loop = scope.launch { runLoop() }
        AppState.log("✔ Đã cấp quyền chụp màn hình")
    }

    @Suppress("DEPRECATION")
    private fun realSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    @Synchronized
    private fun setupDisplay() {
        val p = projection ?: return
        val (w, h, dpi) = realSize()
        if (vd != null && w == curW && h == curH) return
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val old = reader
        if (vd == null) {
            vd = p.createVirtualDisplay(
                "sw", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
            )
        } else {
            vd!!.surface = r.surface
            vd!!.resize(w, h, dpi)
        }
        reader = r
        old?.close()
        curW = w; curH = h
        last = null
    }

    /** Lấy khung hình mới nhất (nếu màn hình không đổi thì dùng lại khung cũ). */
    @Synchronized
    private fun grab(): Bitmap? {
        val r = reader ?: return last
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return last
        try {
            val pl = img.planes[0]
            val pad = pl.rowStride - pl.pixelStride * img.width
            val bmp = Bitmap.createBitmap(img.width + pad / pl.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(pl.buffer)
            last = if (pad == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, img.width, img.height).also { bmp.recycle() }
        } finally {
            img.close()
        }
        return last
    }

    /* ---------------- vòng lặp quét ---------------- */

    private suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                if (AppState.monitoring && !AppState.picking) {
                    setupDisplay()
                    tick()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppState.log("Lỗi: ${e.message}")
            }
            delay(RuleStore.interval(this))
        }
    }

    private suspend fun tick() {
        val frame = grab() ?: return
        for (rule in RuleStore.load(this).filter { it.enabled }) {
            val now = SystemClock.elapsedRealtime()
            if (now - (lastFire[rule.id] ?: -1_000_000L) < rule.cooldownMs) continue
            val hit = safeEval(rule, frame) ?: continue
            lastFire[rule.id] = SystemClock.elapsedRealtime()
            AppState.log("✔ '${rule.name}' khớp (${"%.2f".format(hit.score)}) tại ${hit.x},${hit.y}")
            Macro.run(rule.macro, hit.x, hit.y)
            lastFire[rule.id] = SystemClock.elapsedRealtime()
            break // chụp lại khung mới rồi mới xét tiếp
        }
    }

    private suspend fun safeEval(rule: Rule, frame: Bitmap): Hit? = try {
        evaluate(rule, frame)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppState.log("Lỗi quét '${rule.name}': ${e.message}")
        null
    }

    private suspend fun evaluate(rule: Rule, frame: Bitmap): Hit? {
        val regs = if (rule.regions.isEmpty()) listOf(Region(0, 0, frame.width, frame.height)) else rule.regions
        val tpl: Bitmap? = if (rule.type == Rule.IMAGE) {
            BitmapFactory.decodeFile(RuleStore.templateFile(this, rule.id).path) ?: return null
        } else null

        for (rg in regs) {
            val l = rg.l.coerceIn(0, frame.width - 1)
            val t = rg.t.coerceIn(0, frame.height - 1)
            val w = rg.r.coerceAtMost(frame.width) - l
            val h = rg.b.coerceAtMost(frame.height) - t
            if (w < 8 || h < 8) continue
            val crop = Bitmap.createBitmap(frame, l, t, w, h)
            val hit = if (tpl != null) TemplateMatcher.find(crop, tpl, rule.threshold)
            else TextMatcher.find(crop, rule)
            if (hit != null) return Hit(hit.x + l, hit.y + t, hit.score)
        }
        return null
    }

    /* ---------------- thử một luật ---------------- */

    private fun test(id: String) {
        val rule = RuleStore.get(this, id) ?: return
        scope.launch {
            delay(4000) // thời gian để người dùng chuyển sang màn hình cần kiểm tra
            val frame = grab()
            val msg = if (frame == null) "Chưa có khung hình" else {
                val hit = safeEval(rule, frame)
                if (hit != null) "KHỚP (${"%.2f".format(hit.score)}) tại ${hit.x},${hit.y}" else "không khớp"
            }
            AppState.log("Thử '${rule.name}': $msg")
            main.post { Toast.makeText(this@CaptureService, "Thử '${rule.name}': $msg", Toast.LENGTH_LONG).show() }
        }
    }

    /* ---------------- chọn vùng / ảnh mẫu / điểm chạm ---------------- */

    private fun pick(mode: String, id: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cần cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            return
        }
        overlay?.hide()
        AppState.picking = true
        overlay = SelectorOverlay(this, mode, onFinish = { rects, pt ->
            AppState.picking = false
            overlay = null
            val rule = RuleStore.get(this, id)
            if (rule != null) {
                when (mode) {
                    "REGIONS" -> {
                        rule.regions.addAll(rects)
                        RuleStore.upsert(this, rule)
                        AppState.log("Đã thêm ${rects.size} vùng cho '${rule.name}'")
                        reopenEditor(id)
                    }
                    "POINT" -> {
                        if (pt != null) {
                            rule.macro = rule.macro.trimEnd() + "\ntap ${pt.x} ${pt.y}"
                            RuleStore.upsert(this, rule)
                        }
                        reopenEditor(id)
                    }
                    "TEMPLATE" -> {
                        val rg = rects.lastOrNull()
                        if (rg != null) main.postDelayed({ saveTemplate(rule, rg); reopenEditor(id) }, 450)
                    }
                }
            }
        }, onCancel = {
            AppState.picking = false
            overlay = null
            reopenEditor(id)
        }).also { it.show() }
    }

    private fun saveTemplate(rule: Rule, rg: Region) {
        val f = grab()
        if (f == null) {
            Toast.makeText(this, "Không lấy được khung hình", Toast.LENGTH_LONG).show(); return
        }
        val l = rg.l.coerceIn(0, f.width - 1)
        val t = rg.t.coerceIn(0, f.height - 1)
        val w = (rg.r.coerceAtMost(f.width) - l).coerceAtLeast(1)
        val h = (rg.b.coerceAtMost(f.height) - t).coerceAtLeast(1)
        val crop = Bitmap.createBitmap(f, l, t, w, h)
        FileOutputStream(RuleStore.templateFile(this, rule.id)).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rule.type = Rule.IMAGE
        RuleStore.upsert(this, rule)
        AppState.log("Đã lưu ảnh mẫu ${w}x$h cho '${rule.name}'")
    }

    /** Có quyền vẽ trên ứng dụng khác nên được phép mở lại màn hình sửa luật từ nền. */
    private fun reopenEditor(id: String) {
        startActivity(
            Intent(this, RuleEditActivity::class.java)
                .putExtra("id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    /* ---------------- thông báo & dọn dẹp ---------------- */

    @Suppress("DEPRECATION")
    private fun notif(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(A_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ScreenWatcher")
            .setContentText(if (AppState.monitoring) "Đang theo dõi màn hình" else "Sẵn sàng (chưa theo dõi)")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Dừng hẳn", stop)
            .setOngoing(true)
            .build()
    }

    private fun teardown() {
        loop?.cancel()
        overlay?.hide(); overlay = null
        vd?.release(); vd = null
        reader?.close(); reader = null
        runCatching { projection?.stop() }
        projection = null
        curW = 0; curH = 0
        AppState.monitoring = false
        AppState.picking = false
        AppState.projectionReady = false
    }

    private fun stopAll() {
        teardown()
        AppState.log("■ Đã dừng dịch vụ")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }
}
