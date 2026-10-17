package id.zoomtranscriber.huawei

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Port 1:1 dari `DirectClickTargetPicker` di `app_desktop.py` untuk Layar Sentuh Tablet Huawei:
 * - Pengguna cukup MENYENTUH 1X tepat pada teks caption Zoom di layar, dan sistem otomatis melakukan
 *   Auto-Snap membungkus ke-4 sisi kotak caption tersebut!
 * - Pengguna juga bisa menarik (drag) jari/M-Pencil secara manual jika ingin menentukan ukuran kotak custom.
 */
class FloatingTargetPickerOverlay(
    private val context: Context,
    private val screenSnapshot: Bitmap?,
    private val onRectLocked: (CaptureRect) -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var pickerView: View? = null

    fun show() {
        if (!Settings.canDrawOverlays(context)) return

        val view = PickerCanvasView(context)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        pickerView = view
        wm.addView(view, params)
    }

    fun dismiss() {
        pickerView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        pickerView = null
    }

    @SuppressLint("ViewConstructor")
    private inner class PickerCanvasView(ctx: Context) : View(ctx) {
        private val dimPaint = Paint().apply { color = Color.argb(145, 9, 13, 22) }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val bannerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.FILL
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.FILL
        }
        private val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#052E16")
            textSize = 24f
            isFakeBoldText = true
        }

        private var currentRect: CaptureRect = TranscriptRepository.captureRect
        private var pressX = 0f
        private var pressY = 0f
        private var isDragging = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

            // Banner instruksi di bagian atas layar tablet
            val bannerRect = RectF(width / 2f - 540f, 32f, width / 2f + 540f, 108f)
            canvas.drawRoundRect(bannerRect, 16f, 16f, bannerBgPaint)
            canvas.drawRoundRect(bannerRect, 16f, 16f, borderPaint)
            canvas.drawText(
                "🎯 SENTUH 1X PADA TEKS CAPTION ZOOM (Atau Tarik Manual dengan Jari / M-Pencil)",
                width / 2f,
                80f,
                bannerTextPaint
            )

            // Kotak Hijau Neon Area Terkunci
            val r = currentRect
            val boxRect = RectF(r.x.toFloat(), r.y.toFloat(), (r.x + r.w).toFloat(), (r.y + r.h).toFloat())
            canvas.drawRect(boxRect, borderPaint)

            val lblY = max(120f, r.y - 44f)
            val lblRect = RectF(r.x.toFloat(), lblY, r.x + 520f, lblY + 40f)
            canvas.drawRoundRect(lblRect, 8f, 8f, badgeBgPaint)
            canvas.drawText(
                "🎯 Area Caption (${r.w}×${r.h} px) — Lepas Sentuhan untuk Kunci",
                r.x + 16f,
                lblY + 28f,
                badgeTextPaint
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val ex = event.rawX
            val ey = event.rawY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressX = ex
                    pressY = ey
                    isDragging = false
                    if (screenSnapshot != null) {
                        currentRect = CaptionImagePreprocessor.autoSnapCaptionBox(
                            screenSnapshot,
                            ex.toInt(),
                            ey.toInt()
                        )
                    } else {
                        currentRect = CaptureRect(
                            x = max(20, ex.toInt() - 320),
                            y = max(20, ey.toInt() - 65),
                            w = 640,
                            h = 130
                        )
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = abs(ex - pressX)
                    val dy = abs(ey - pressY)
                    if (dx > 28f || dy > 28f) {
                        isDragging = true
                        val x1 = min(pressX, ex).toInt()
                        val y1 = min(pressY, ey).toInt()
                        val w = max(120, abs(ex - pressX).toInt())
                        val h = max(60, abs(ey - pressY).toInt())
                        currentRect = CaptureRect(x1, y1, w, h)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging && screenSnapshot != null) {
                        currentRect = CaptionImagePreprocessor.autoSnapCaptionBox(
                            screenSnapshot,
                            ex.toInt(),
                            ey.toInt()
                        )
                    }
                    val locked = currentRect
                    dismiss()
                    onRectLocked(locked)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
