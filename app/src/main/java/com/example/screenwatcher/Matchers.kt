package com.example.screenwatcher

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Kết quả khớp: toạ độ tâm (trong bitmap được quét) + độ giống 0..1 */
data class Hit(val x: Int, val y: Int, val score: Double)

/* ------------------------------------------------------------------ */
/*  Nhận diện CHỮ (OCR bằng ML Kit, offline)                          */
/* ------------------------------------------------------------------ */
object TextMatcher {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Chữ thường, bỏ dấu tiếng Việt, bỏ khoảng trắng/ký tự đặc biệt -> so khớp bền hơn với lỗi OCR. */
    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace("đ", "d")
        return buildString {
            for (c in n) {
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt() && c.isLetterOrDigit()) append(c)
            }
        }
    }

    /** Tìm chuỗi con gần giống nhất (Sellers). Trả về độ giống nếu >= minRatio. */
    private fun approx(text: String, pat: String, minRatio: Double): Double? {
        if (pat.isEmpty() || text.isEmpty()) return null
        if (text.contains(pat)) return 1.0
        val m = pat.length
        val allowed = floor(m * (1 - minRatio)).toInt()
        if (allowed <= 0) return null
        var prev = IntArray(m + 1) { it }
        for (c in text) {
            val cur = IntArray(m + 1)
            for (i in 1..m) {
                cur[i] = minOf(prev[i] + 1, cur[i - 1] + 1, prev[i - 1] + if (pat[i - 1] == c) 0 else 1)
            }
            if (cur[m] <= allowed) return 1.0 - cur[m].toDouble() / m
            prev = cur
        }
        return null
    }

    private suspend fun recognize(bmp: Bitmap): Text = suspendCancellableCoroutine { c ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    /** rule.text có thể chứa nhiều lựa chọn, ngăn cách bằng dấu | (khớp 1 trong số đó là đủ). */
    suspend fun find(bmp: Bitmap, rule: Rule): Hit? {
        val pats = rule.text.split("|").map { norm(it) }.filter { it.isNotEmpty() }
        if (pats.isEmpty()) return null
        val ratio = if (rule.fuzzy) rule.threshold else 1.0
        val res = recognize(bmp)
        for (line in res.textBlocks.flatMap { it.lines }) {
            val t = norm(line.text)
            for (p in pats) {
                val s = approx(t, p, ratio) ?: continue
                val r = line.boundingBox ?: continue
                return Hit(r.centerX(), r.centerY(), s)
            }
        }
        // Cụm chữ nằm vắt qua nhiều dòng
        val all = norm(res.text)
        for (p in pats) {
            val s = approx(all, p, ratio) ?: continue
            return Hit(bmp.width / 2, bmp.height / 2, s)
        }
        return null
    }
}

/* ------------------------------------------------------------------ */
/*  Nhận diện HÌNH ẢNH (template matching, NCC, thuần Kotlin)         */
/* ------------------------------------------------------------------ */
object TemplateMatcher {
    private class G(val w: Int, val h: Int, val d: FloatArray)

    private fun gray(b: Bitmap, s: Int): G {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val gw = w / s
        val gh = h / s
        val out = FloatArray(gw * gh)
        val inv = 1f / (s * s)
        for (y in 0 until gh) for (x in 0 until gw) {
            var acc = 0f
            for (dy in 0 until s) {
                val row = (y * s + dy) * w + x * s
                for (dx in 0 until s) {
                    val p = px[row + dx]
                    acc += 0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
                }
            }
            out[y * gw + x] = acc * inv
        }
        return G(gw, gh, out)
    }

    /** Trả về (x, y góc trên-trái, điểm NCC) tốt nhất. */
    private fun search(img: G, t: G): Triple<Int, Int, Double>? {
        val tw = t.w
        val th = t.h
        val n = tw * th
        if (tw < 3 || th < 3 || img.w < tw || img.h < th) return null
        var mean = 0f
        for (v in t.d) mean += v
        mean /= n
        val tz = FloatArray(n) { t.d[it] - mean }
        var tn = 0.0
        for (v in tz) tn += v * v
        tn = sqrt(tn)
        if (tn < 1e-3) return null // mẫu trơn, không có chi tiết

        val iw = img.w + 1
        val sum = DoubleArray(iw * (img.h + 1))
        val sq = DoubleArray(iw * (img.h + 1))
        for (y in 1..img.h) {
            var rs = 0.0
            var rq = 0.0
            for (x in 1..img.w) {
                val v = img.d[(y - 1) * img.w + x - 1].toDouble()
                rs += v
                rq += v * v
                sum[y * iw + x] = sum[(y - 1) * iw + x] + rs
                sq[y * iw + x] = sq[(y - 1) * iw + x] + rq
            }
        }
        var best = -2.0
        var bx = 0
        var by = 0
        for (y in 0..img.h - th) for (x in 0..img.w - tw) {
            val a = (y + th) * iw + x + tw
            val b = y * iw + x + tw
            val c = (y + th) * iw + x
            val d = y * iw + x
            val s = sum[a] - sum[b] - sum[c] + sum[d]
            val q = sq[a] - sq[b] - sq[c] + sq[d]
            val v = q - s * s / n
            if (v < 1e-3) continue
            var num = 0.0
            for (j in 0 until th) {
                val ro = (y + j) * img.w + x
                val to = j * tw
                for (i in 0 until tw) num += img.d[ro + i] * tz[to + i]
            }
            val sc = num / (sqrt(v) * tn)
            if (sc > best) {
                best = sc; bx = x; by = y
            }
        }
        return if (best < -1) null else Triple(bx, by, best)
    }

    /** Tìm [tpl] trong [region]. Thô (thu nhỏ) -> tinh chỉnh ở độ phân giải gốc. */
    fun find(region: Bitmap, tpl: Bitmap, threshold: Double): Hit? {
        if (tpl.width > region.width || tpl.height > region.height) return null
        val tmin = min(tpl.width, tpl.height)
        var s = max(1, tmin / 10)
        while (tmin / (s + 1) >= 4 &&
            (region.width / s).toLong() * (region.height / s) * ((tpl.width / s) * (tpl.height / s)) > 80_000_000L
        ) s++

        val coarse = search(gray(region, s), gray(tpl, s)) ?: return null
        if (coarse.third < threshold - 0.2) return null

        val rw = min(region.width, tpl.width + 4 * s)
        val rh = min(region.height, tpl.height + 4 * s)
        val rx = (coarse.first * s - 2 * s).coerceIn(0, region.width - rw)
        val ry = (coarse.second * s - 2 * s).coerceIn(0, region.height - rh)
        val sub = Bitmap.createBitmap(region, rx, ry, rw, rh)
        val fine = search(gray(sub, 1), gray(tpl, 1)) ?: return null
        if (fine.third < threshold) return null
        return Hit(rx + fine.first + tpl.width / 2, ry + fine.second + tpl.height / 2, fine.third)
    }
}
