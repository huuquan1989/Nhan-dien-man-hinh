package com.example.screenwatcher

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class RuleEditActivity : Activity() {

    private lateinit var rule: Rule
    private lateinit var name: EditText
    private lateinit var type: Spinner
    private lateinit var text: EditText
    private lateinit var fuzzy: CheckBox
    private lateinit var thr: EditText
    private lateinit var regionsTv: TextView
    private lateinit var tplIv: ImageView
    private lateinit var macro: EditText
    private lateinit var cool: EditText
    private lateinit var textGroup: LinearLayout
    private lateinit var imageGroup: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(Color.BLACK); setPadding(0, dp(12), 0, dp(2))
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { f() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        rule = intent.getStringExtra("id")?.let { RuleStore.get(this, it) } ?: Rule()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            fitsSystemWindows = true
        }

        root.addView(label("Tên luật"))
        name = EditText(this).apply { setText(rule.name) }
        root.addView(name)

        root.addView(label("Kiểu nhận diện"))
        type = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@RuleEditActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Chữ (OCR)", "Hình ảnh (mẫu)"),
            )
            setSelection(if (rule.type == Rule.TEXT) 0 else 1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = toggleGroups()
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(type)

        // --- nhóm CHỮ ---
        textGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textGroup.addView(label("Chữ cần tìm (dùng | để tách nhiều lựa chọn, vd: Bỏ qua|Skip)"))
        text = EditText(this).apply { setText(rule.text) }
        textGroup.addView(text)
        fuzzy = CheckBox(this).apply { this.text = "Cho phép sai khác nhỏ (OCR đọc nhầm vài ký tự)"; isChecked = rule.fuzzy }
        textGroup.addView(fuzzy)
        root.addView(textGroup)

        // --- nhóm HÌNH ---
        imageGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        imageGroup.addView(label("Ảnh mẫu"))
        tplIv = ImageView(this).apply { adjustViewBounds = true; maxHeight = dp(120) }
        imageGroup.addView(tplIv)
        imageGroup.addView(btn("Chọn ảnh mẫu trên màn hình") { pick("TEMPLATE") })
        root.addView(imageGroup)

        root.addView(label("Ngưỡng giống nhau (0.50 – 0.99)"))
        thr = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(rule.threshold.toString())
        }
        root.addView(thr)

        root.addView(label("Vùng quét (để trống = toàn màn hình)"))
        regionsTv = TextView(this)
        root.addView(regionsTv)
        root.addView(btn("Chọn vùng trên màn hình (thêm được nhiều vùng)") { pick("REGIONS") })
        root.addView(btn("Xoá hết vùng") { rule.regions.clear(); refreshRegions() })

        root.addView(label("Macro khi khớp (mỗi dòng 1 lệnh)"))
        macro = EditText(this).apply {
            setText(rule.macro); minLines = 3; gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(macro)
        root.addView(TextView(this).apply {
            textSize = 11f
            text = "tap_match [dx dy]  chạm vào chỗ vừa tìm thấy\n" +
                "tap x y  |  longtap x y [ms]\n" +
                "swipe x1 y1 x2 y2 [ms]\n" +
                "wait ms  |  back  |  home  |  recents\n" +
                "# ghi chú"
        })
        root.addView(btn("Thêm điểm chạm bằng cách chạm lên màn hình") { pick("POINT") })

        root.addView(label("Thời gian nghỉ giữa 2 lần kích hoạt (ms)"))
        cool = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setText(rule.cooldownMs.toString())
        }
        root.addView(cool)

        root.addView(btn("Thử luật (quét sau 4 giây, chưa chạy macro)") {
            collect(); RuleStore.upsert(this, rule)
            if (!AppState.projectionReady) {
                Toast.makeText(this, "Hãy cấp quyền chụp màn hình ở màn hình chính trước", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Chuyển sang màn hình cần kiểm tra trong 4 giây…", Toast.LENGTH_LONG).show()
                startService(
                    Intent(this, CaptureService::class.java).setAction(CaptureService.A_TEST).putExtra("id", rule.id),
                )
            }
        })
        root.addView(btn("Lưu") { collect(); RuleStore.upsert(this, rule); finish() })
        root.addView(btn("Xoá luật") { RuleStore.delete(this, rule.id); finish() })

        setContentView(ScrollView(this).apply { addView(root) })
        toggleGroups()
        refreshRegions()
        refreshTemplate()
    }

    private fun toggleGroups() {
        val isText = type.selectedItemPosition == 0
        textGroup.visibility = if (isText) View.VISIBLE else View.GONE
        imageGroup.visibility = if (isText) View.GONE else View.VISIBLE
    }

    private fun refreshRegions() {
        regionsTv.text = if (rule.regions.isEmpty()) "Toàn màn hình"
        else rule.regions.withIndex().joinToString("\n") { (i, r) -> "${i + 1}. (${r.l},${r.t}) – (${r.r},${r.b})" }
    }

    private fun refreshTemplate() {
        val f = RuleStore.templateFile(this, rule.id)
        if (f.exists()) tplIv.setImageBitmap(BitmapFactory.decodeFile(f.path)) else tplIv.setImageDrawable(null)
    }

    private fun collect() {
        rule.name = name.text.toString().ifBlank { "Luật" }
        rule.type = if (type.selectedItemPosition == 0) Rule.TEXT else Rule.IMAGE
        rule.text = text.text.toString()
        rule.fuzzy = fuzzy.isChecked
        rule.threshold = (thr.text.toString().toDoubleOrNull() ?: 0.85).coerceIn(0.5, 0.99)
        rule.macro = macro.text.toString()
        rule.cooldownMs = cool.text.toString().toLongOrNull() ?: 3000L
    }

    /** Lưu luật rồi giao cho CaptureService hiện lớp phủ chọn trên màn hình thật. */
    private fun pick(mode: String) {
        collect()
        RuleStore.upsert(this, rule)
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Cần cấp quyền hiển thị trên ứng dụng khác (bước 1)", Toast.LENGTH_LONG).show(); return
        }
        if (!AppState.projectionReady) {
            Toast.makeText(this, "Cần cấp quyền chụp màn hình (bước 3) trước", Toast.LENGTH_LONG).show(); return
        }
        startService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.A_PICK).putExtra("mode", mode).putExtra("id", rule.id),
        )
        Toast.makeText(this, "Mở màn hình cần chọn rồi kéo trên lớp phủ. Bấm ✋ để thao tác app, ✏ để vẽ lại.", Toast.LENGTH_LONG).show()
        finish()
    }
}
