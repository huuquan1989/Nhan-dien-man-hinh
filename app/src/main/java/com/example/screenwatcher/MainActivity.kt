package com.example.screenwatcher

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var list: LinearLayout
    private lateinit var monitorSwitch: Switch
    private lateinit var intervalEt: EditText
    private var updating = false
    private val h = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            h.postDelayed(this, 1000)
        }
    }

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            AppState.projCode = r.resultCode
            AppState.projData = data
            ContextCompat.startForegroundService(
                this, Intent(this, CaptureService::class.java).setAction(CaptureService.A_START),
            )
        }
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { f() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply { text = "ScreenWatcher"; textSize = 24f; setTextColor(Color.BLACK) })
        root.addView(TextView(this).apply {
            text = "Quét chữ / hình ảnh trong các vùng đã chọn, khớp thì tự động chạm hoặc chạy macro."
        })
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        root.addView(status)

        root.addView(btn("1. Cấp quyền hiển thị trên ứng dụng khác") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(btn("2. Bật Dịch vụ trợ năng (ScreenWatcher)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("3. Cấp quyền chụp màn hình") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projLauncher.launch(mpm.createScreenCaptureIntent())
        })

        monitorSwitch = Switch(this).apply {
            text = "Bật theo dõi"; setPadding(0, dp(12), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                if (updating) return@setOnCheckedChangeListener
                if (on && !AppState.projectionReady) {
                    Toast.makeText(this@MainActivity, "Hãy làm bước 3 trước", Toast.LENGTH_SHORT).show()
                    updating = true; isChecked = false; updating = false
                    return@setOnCheckedChangeListener
                }
                saveInterval()
                startService(
                    Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.A_MONITOR).putExtra("on", on),
                )
            }
        }
        root.addView(monitorSwitch)

        val iv = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        iv.addView(TextView(this).apply { text = "Quét mỗi (ms): " })
        intervalEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(RuleStore.interval(this@MainActivity).toString())
            minEms = 5
        }
        iv.addView(intervalEt)
        root.addView(iv)

        root.addView(TextView(this).apply {
            text = "Danh sách luật"; textSize = 18f; setTextColor(Color.BLACK); setPadding(0, dp(16), 0, dp(4))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(btn("+ Thêm luật") {
            startActivity(Intent(this, RuleEditActivity::class.java))
        })

        root.addView(TextView(this).apply {
            text = "Nhật ký"; textSize = 18f; setTextColor(Color.BLACK); setPadding(0, dp(16), 0, dp(4))
        })
        logView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun saveInterval() {
        intervalEt.text.toString().toLongOrNull()?.let { RuleStore.setInterval(this, it) }
    }

    override fun onResume() {
        super.onResume()
        rebuildList()
        h.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        saveInterval()
        h.removeCallbacks(ticker)
    }

    private fun refreshStatus() {
        fun m(ok: Boolean) = if (ok) "✔" else "✘"
        status.text = "Hiển thị trên app khác: ${m(Settings.canDrawOverlays(this))}   " +
            "Trợ năng: ${m(AutoClickService.instance != null)}   " +
            "Chụp màn hình: ${m(AppState.projectionReady)}"
        updating = true
        monitorSwitch.isChecked = AppState.monitoring
        updating = false
        logView.text = AppState.logText()
    }

    private fun rebuildList() {
        list.removeAllViews()
        val rules = RuleStore.load(this)
        if (rules.isEmpty()) list.addView(TextView(this).apply { text = "(chưa có luật nào)" })
        for (r in rules) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, RuleEditActivity::class.java).putExtra("id", r.id))
                }
            }
            row.addView(CheckBox(this).apply {
                text = r.name
                isChecked = r.enabled
                setOnCheckedChangeListener { _, on -> r.enabled = on; RuleStore.upsert(this@MainActivity, r) }
            })
            val what = if (r.type == Rule.TEXT) "Chữ: \"${r.text}\"" else "Hình ảnh mẫu"
            val where = if (r.regions.isEmpty()) "toàn màn hình" else "${r.regions.size} vùng"
            row.addView(TextView(this).apply {
                text = "$what • $where • ${r.macro.lines().count { it.isNotBlank() }} bước • chạm để sửa"
                textSize = 12f
            })
            list.addView(row)
        }
    }
}
