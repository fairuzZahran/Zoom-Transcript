package id.zoomtranscriber.huawei

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Port 1:1 dari `auto_snap_caption_box` dan `preprocess_caption_image_for_ocr` di `app_desktop.py`.
 * - Mendeteksi batas ke-4 sisi kotak caption Zoom saat pengguna menyentuh layar 1x.
 * - Mengubah gambar potongan caption menjadi teks HITAM TAJAM di atas latar PUTIH MURNI (2.5x Upscale).
 */
object CaptionImagePreprocessor {

    fun autoSnapCaptionBox(screenBmp: Bitmap, clickXRaw: Int, clickYRaw: Int): CaptureRect {
        val sw = screenBmp.width
        val sh = screenBmp.height
        val clickX = clickXRaw.coerceIn(20, max(21, sw - 20))
        val clickY = clickYRaw.coerceIn(20, max(21, sh - 20))

        val rVals = mutableListOf<Int>()
        val gVals = mutableListOf<Int>()
        val bVals = mutableListOf<Int>()

        for (dy in -28..28 step 4) {
            for (dx in -90..90 step 15) {
                val sx = (clickX + dx).coerceIn(0, sw - 1)
                val sy = (clickY + dy).coerceIn(0, sh - 1)
                val c = screenBmp.getPixel(sx, sy)
                rVals.add(Color.red(c))
                gVals.add(Color.green(c))
                bVals.add(Color.blue(c))
            }
        }
        rVals.sort()
        gVals.sort()
        bVals.sort()
        val mid = rVals.size / 2
        val bgR = rVals[mid]
        val bgG = gVals[mid]
        val bgB = bVals[mid]

        fun isSameBg(pxColor: Int): Boolean {
            val dr = abs(Color.red(pxColor) - bgR)
            val dg = abs(Color.green(pxColor) - bgG)
            val db = abs(Color.blue(pxColor) - bgB)
            return (dr + dg + db) < 34
        }

        var topY = clickY
        var y = clickY
        val minTop = max(0, clickY - (sh * 0.25).toInt())
        while (y >= minTop) {
            var matches = 0
            var x = max(0, clickX - 160)
            val maxX = min(sw - 1, clickX + 160)
            while (x <= maxX) {
                if (isSameBg(screenBmp.getPixel(x, y))) matches++
                x += 10
            }
            if (matches >= 14) topY = y else break
            y -= 2
        }

        var botY = clickY
        y = clickY
        val maxBot = min(sh - 1, clickY + (sh * 0.25).toInt())
        while (y <= maxBot) {
            var matches = 0
            var x = max(0, clickX - 160)
            val maxX = min(sw - 1, clickX + 160)
            while (x <= maxX) {
                if (isSameBg(screenBmp.getPixel(x, y))) matches++
                x += 10
            }
            if (matches >= 14) botY = y else break
            y += 2
        }

        var leftX = clickX
        var xScan = clickX
        val minLeft = max(0, clickX - (sw * 0.45).toInt())
        val reqVertMatches = max(3, (botY - topY) / 18)
        while (xScan >= minLeft) {
            var matches = 0
            var py = max(0, topY + 4)
            val maxPy = min(sh - 1, botY - 4)
            while (py <= maxPy) {
                if (isSameBg(screenBmp.getPixel(xScan, py))) matches++
                py += 6
            }
            if (matches >= reqVertMatches) leftX = xScan else break
            xScan -= 4
        }

        var rightX = clickX
        xScan = clickX
        val maxRight = min(sw - 1, clickX + (sw * 0.45).toInt())
        while (xScan <= maxRight) {
            var matches = 0
            var py = max(0, topY + 4)
            val maxPy = min(sh - 1, botY - 4)
            while (py <= maxPy) {
                if (isSameBg(screenBmp.getPixel(xScan, py))) matches++
                py += 6
            }
            if (matches >= reqVertMatches) rightX = xScan else break
            xScan += 4
        }

        if ((rightX - leftX) > (sw * 0.72).toInt()) {
            leftX = (sw * 0.16).toInt()
            rightX = (sw * 0.84).toInt()
        }

        val boxH = botY - topY
        if (boxH < 95) {
            val padTop = max(35, (115 - boxH) / 2)
            val padBot = max(30, (115 - boxH) / 2)
            topY = max(0, topY - padTop)
            botY = min(sh, botY + padBot)
        } else if (boxH > (sh * 0.28).toInt()) {
            topY = max(0, clickY - (sh * 0.08).toInt())
            botY = min(sh, clickY + (sh * 0.09).toInt())
        }

        return CaptureRect(
            x = leftX,
            y = topY,
            w = max(240, rightX - leftX),
            h = max(80, botY - topY)
        )
    }

    /**
     * Memfilter latar gelap/maroon & avatar berwarna menjadi putih murni dan mengubah huruf
     * caption menjadi hitam tajam beresolusi tinggi (2.0x - 2.5x Upscale).
     */
    fun preprocessCaptionBitmapForOcr(srcBmp: Bitmap): Bitmap {
        val cw = srcBmp.width
        val ch = srcBmp.height
        val pixels = IntArray(cw * ch)
        srcBmp.getPixels(pixels, 0, cw, 0, 0, cw, ch)

        var darkCount = 0
        for (px in pixels) {
            val lum = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000
            if (lum < 135) darkCount++
        }
        val isDarkBg = (darkCount.toDouble() / max(1, pixels.size)) > 0.45

        val processedPixels = IntArray(cw * ch)
        if (isDarkBg) {
            for (i in pixels.indices) {
                val px = pixels[i]
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                // Ambil piksel terang netral (putih/abu terang teks caption), buang warna kulit avatar/maroon
                if (maxC >= 115 && (maxC - minC) <= 48) {
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    val inv = 255 - lum
                    val bin = when {
                        inv < 120 -> 0
                        inv > 185 -> 255
                        else -> ((inv - 120) * 255) / 65
                    }
                    processedPixels[i] = Color.rgb(bin, bin, bin)
                } else {
                    processedPixels[i] = Color.WHITE
                }
            }
        } else {
            for (i in pixels.indices) {
                val px = pixels[i]
                val lum = (Color.red(px) * 299 + Color.green(px) * 587 + Color.blue(px) * 114) / 1000
                val bin = if (lum < 145) 0 else 255
                processedPixels[i] = Color.rgb(bin, bin, bin)
            }
        }

        val cleanBase = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        cleanBase.setPixels(processedPixels, 0, cw, 0, 0, cw, ch)

        val scaledW = max(100, (cw * 2.2).toInt())
        val scaledH = max(40, (ch * 2.2).toInt())
        val scaled = Bitmap.createScaledBitmap(cleanBase, scaledW, scaledH, true)

        // Tambahkan border putih 20px di sekeliling gambar agar OCR tidak memotong huruf tepi
        val bordered = Bitmap.createBitmap(scaledW + 40, scaledH + 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bordered)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, 20f, 20f, Paint(Paint.FILTER_BITMAP_FLAG))
        return bordered
    }
}
