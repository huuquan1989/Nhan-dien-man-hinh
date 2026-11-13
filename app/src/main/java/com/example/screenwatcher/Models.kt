package com.example.screenwatcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Một vùng chữ nhật trên màn hình (toạ độ pixel thật). */
data class Region(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w get() = r - l
    val h get() = b - t
    fun toJson(): JSONArray = JSONArray().put(l).put(t).put(r).put(b)

    companion object {
        fun from(a: JSONArray) = Region(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
    }
}

/** Một luật: nhìn vào các vùng -> nếu thấy chữ/hình thì chạy macro. */
data class Rule(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Luật mới",
    var type: String = TEXT,
    var text: String = "",
    var fuzzy: Boolean = true,
    var threshold: Double = 0.85,
    var regions: MutableList<Region> = mutableListOf(),
    var macro: String = "tap_match",
    var cooldownMs: Long = 3000,
    var enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type).put("text", text)
        .put("fuzzy", fuzzy).put("threshold", threshold).put("macro", macro)
        .put("cooldownMs", cooldownMs).put("enabled", enabled)
        .put("regions", JSONArray().also { a -> regions.forEach { a.put(it.toJson()) } })

    companion object {
        const val TEXT = "TEXT"
        const val IMAGE = "IMAGE"

        fun from(o: JSONObject): Rule {
            val regs = mutableListOf<Region>()
            val a = o.optJSONArray("regions") ?: JSONArray()
            for (i in 0 until a.length()) regs.add(Region.from(a.getJSONArray(i)))
            return Rule(
                id = o.getString("id"),
                name = o.optString("name", "Luật"),
                type = o.optString("type", TEXT),
                text = o.optString("text", ""),
                fuzzy = o.optBoolean("fuzzy", true),
                threshold = o.optDouble("threshold", 0.85),
                regions = regs,
                macro = o.optString("macro", "tap_match"),
                cooldownMs = o.optLong("cooldownMs", 3000),
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

object RuleStore {
    private fun prefs(c: Context) = c.getSharedPreferences("sw", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Rule> {
        val a = JSONArray(prefs(c).getString("rules", "[]"))
        return MutableList(a.length()) { Rule.from(a.getJSONObject(it)) }
    }

    fun saveAll(c: Context, l: List<Rule>) {
        val a = JSONArray()
        l.forEach { a.put(it.toJson()) }
        prefs(c).edit().putString("rules", a.toString()).apply()
    }

    fun get(c: Context, id: String): Rule? = load(c).firstOrNull { it.id == id }

    fun upsert(c: Context, r: Rule) {
        val l = load(c)
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l.add(r)
        saveAll(c, l)
    }

    fun delete(c: Context, id: String) {
        saveAll(c, load(c).filter { it.id != id })
        templateFile(c, id).delete()
    }

    fun templateFile(c: Context, id: String) = File(c.filesDir, "tpl_$id.png")

    fun interval(c: Context): Long = prefs(c).getLong("interval", 700L)
    fun setInterval(c: Context, ms: Long) = prefs(c).edit().putLong("interval", ms.coerceIn(150L, 10_000L)).apply()
}

/** Trạng thái dùng chung trong tiến trình. */
object AppState {
    @Volatile var projectionReady = false
    @Volatile var monitoring = false
    @Volatile var picking = false
    @Volatile var projCode = 0
    @Volatile var projData: android.content.Intent? = null

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun log(s: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        lines.addFirst("$t  $s")
        while (lines.size > 40) lines.removeLast()
    }

    @Synchronized
    fun logText(): String = lines.joinToString("\n")
}
