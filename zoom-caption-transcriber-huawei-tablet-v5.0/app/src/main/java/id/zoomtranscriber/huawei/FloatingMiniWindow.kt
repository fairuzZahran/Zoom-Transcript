package id.zoomtranscriber.huawei

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Jendela Mini Mengambang ("Selalu di Atas / Always on Top") untuk Tablet Huawei.
 * Memungkinkan Anda memantau transkrip secara langsung di atas layar Zoom penuh tanpa perlu
 * bolak-balik berpindah aplikasi, lengkap dengan tombol Bidik 1x Sentuh.
 */
class FloatingMiniWindow(
    private val context: Context,
    private val onTriggerPicker: () -> Unit
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null
    private var tvLiveSpeaker: TextView? = null
    private var tvLiveText: TextView? = null
    var isShowing = false
        private set

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing || !Settings.canDrawOverlays(context)) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            720,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 80
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 18, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE111827"))
                cornerRadius = 22f
                setStroke(3, Color.parseColor("#4F46E5"))
            }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = "🎙️ Zoom Transcriber Live (Geser Jendela)"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPick = Button(context).apply {
            text = "🎯 Bidik"
            setTextColor(Color.parseColor("#052E16"))
            textSize = 11f
            setBackgroundColor(Color.parseColor("#10B981"))
            setOnClickListener { onTriggerPicker() }
        }

        val btnClose = Button(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 11f
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hide() }
        }

        topRow.addView(title)
        topRow.addView(btnPick)
        topRow.addView(btnClose)

        tvLiveSpeaker = TextView(context).apply {
            text = "Menunggu teks caption Zoom..."
            setTextColor(Color.parseColor("#34D399"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8, 0, 2)
        }

        tvLiveText = TextView(context).apply {
            text = "Sentuh tombol '🎯 Bidik' lalu ketuk 1x pada teks caption Zoom Anda."
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
        }

        container.addView(topRow)
        container.addView(tvLiveSpeaker)
        container.addView(tvLiveText)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        container.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    try { wm.updateViewLayout(container, params) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        rootView = container
        wm.addView(container, params)
        isShowing = true
    }

    fun updateLiveCaption(speaker: String, text: String, time: String) {
        tvLiveSpeaker?.text = "🟢 [$time] $speaker:"
        tvLiveText?.text = text
    }

    fun hide() {
        rootView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
        isShowing = false
    }
}
