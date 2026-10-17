package id.zoomtranscriber.huawei

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Antarmuka Utama Tablet Huawei (Dual-Pane Studio — Persis `ZoomTranscriberDesktopApp` v5.0):
 * - Kolom Kiri: Sidebar Riwayat Sesi Meeting (`caption_history.db`), Pencarian Lintas Sesi, Buat/Ubah/Hapus Sesi.
 * - Kolom Kanan:
 *   1. Kartu Monitor Tempat Caption Live + Pratinjau Ketajaman Hitam-Putih + Tombol Bidik Langsung 1x Sentuh.
 *   2. Statistik Sesi Aktif (Jumlah Item Caption, Jumlah Pembicara, Total Kata).
 *   3. Baris Pencarian di Sesi Ini + Filter Pembicara + Ubah Nama Pembicara Massal.
 *   4. Daftar Item Caption Real-Time (Ketuk 2x / Tekan Lama untuk Salin Item).
 *   5. Footer Tombol Ekspor: Salin Transkrip, Simpan Markdown (.MD), Simpan Teks (.TXT), Kosongkan Sesi.
 */
class MainActivity : AppCompatActivity(), TranscriptRepository.Listener {

    companion object {
        private const val REQ_MEDIA_PROJECTION = 1001
        private const val REQ_EXPORT_MD = 2001
        private const val REQ_EXPORT_TXT = 2002
    }

    private lateinit var lblEngineSub: TextView
    private lateinit var btnRecordToggle: Button
    private lateinit var chkFloatingOverlay: CheckBox
    private lateinit var edtHistorySearch: EditText
    private lateinit var historyListView: ListView
    private lateinit var lblMonStatus: TextView
    private lateinit var lblRectInfo: TextView
    private lateinit var imgLiveThumb: ImageView
    private lateinit var lblLivePreview: TextView
    private lateinit var lblActiveSessionTitle: TextView
    private lateinit var lblStatsSummary: TextView
    private lateinit var edtItemSearch: EditText
    private lateinit var spinnerSpeaker: Spinner
    private lateinit var itemsListView: ListView

    private var sessionRecords: List<SessionRecord> = emptyList()
    private var displayedItems: List<CaptionItem> = emptyList()
    private var selectedSpeakerFilter: String = "Semua Pembicara"
    private var floatingMiniWindow: FloatingMiniWindow? = null
    private var pendingPickerAfterProjection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TranscriptRepository.init(this)
        floatingMiniWindow = FloatingMiniWindow(this) {
            triggerDirectClickPicker()
        }

        setContentView(buildTabletStudioUi())
        TranscriptRepository.addListener(this)

        refreshHistorySidebar()
        refreshItemsView()

        // Jalankan layanan foreground agar Local Bridge (127.0.0.1:18765) langsung aktif
        val svcIntent = Intent(this, ScreenCaptureOcrService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }
    }

    override fun onDestroy() {
        TranscriptRepository.removeListener(this)
        super.onDestroy()
    }

    // =========================================================================
    // PEMBANGUN UI TABLET DUAL-PANE (IDENTIK DENGAN DESKTOP v5.0)
    // =========================================================================
    private fun buildTabletStudioUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── HEADER ATAS ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(28, 18, 28, 18)
            gravity = Gravity.CENTER_VERTICAL
        }

        val badgeIcon = TextView(this).apply {
            text = "🎙️"
            textSize = 20f
            setPadding(18, 10, 18, 10)
            background = roundedBg("#4F46E5", 14f)
        }
        header.addView(badgeIcon)

        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val mainTitle = TextView(this).apply {
            text = "Zoom Caption Transcriber v5.0 — Huawei Tablet Edition"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        lblEngineSub = TextView(this).apply {
            text = "🟢 Multi-Engine Aktif (Direct UI Accessibility + In-Memory OCR 0.35s + Web Bridge)"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 11f
        }
        titleWrap.addView(mainTitle)
        titleWrap.addView(lblEngineSub)
        header.addView(titleWrap)

        chkFloatingOverlay = CheckBox(this).apply {
            text = "📌 Jendela Mengambang"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 12f
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        chkFloatingOverlay.isChecked = false
                        requestOverlayPermission()
                    } else {
                        floatingMiniWindow?.show()
                    }
                } else {
                    floatingMiniWindow?.hide()
                }
            }
        }
        header.addView(chkFloatingOverlay)

        btnRecordToggle = Button(this).apply {
            text = "⏸ Jeda Perekaman"
            setTextColor(Color.parseColor("#052E16"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#10B981", 12f)
            setPadding(26, 12, 26, 12)
            setOnClickListener { toggleRecording() }
        }
        val recLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 18 }
        header.addView(btnRecordToggle, recLp)

        root.addView(header)

        // ── BODY UTAMA (DUAL-PANE LANDSCAPE TABLET) ──
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        // ================= KOLOM KIRI: SIDEBAR RIWAYAT SESI =================
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#111827", 16f, "#1E293B", 2)
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.28).toInt().coerceIn(320, 520),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { rightMargin = 20 }
        }

        val sbTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sbHeaderLbl = TextView(this).apply {
            text = "🗂️ RIWAYAT SESI"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnNewSes = Button(this).apply {
            text = "＋ Sesi Baru"
            setTextColor(Color.WHITE)
            textSize = 11f
            background = roundedBg("#4F46E5", 10f)
            setOnClickListener { createNewSessionDialog() }
        }
        sbTop.addView(sbHeaderLbl)
        sbTop.addView(btnNewSes)
        sidebar.addView(sbTop)

        edtHistorySearch = EditText(this).apply {
            hint = "🔍 Cari lintas riwayat sesi..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 12f
            background = roundedBg("#1E293B", 10f, "#334155", 2)
            setPadding(18, 12, 18, 12)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshHistorySidebar()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val histSearchLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 14; bottomMargin = 14 }
        sidebar.addView(edtHistorySearch, histSearchLp)

        historyListView = ListView(this).apply {
            dividerHeight = 8
            background = roundedBg("#0B1120", 12f, "#1E293B", 2)
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnItemClickListener { _, _, position, _ ->
                if (position in sessionRecords.indices) {
                    val chosen = sessionRecords[position]
                    if (chosen.id != TranscriptRepository.currentSessionId) {
                        TranscriptRepository.currentSessionId = chosen.id
                        TranscriptRepository.currentSessionTitle = chosen.title
                        TranscriptRepository.items = TranscriptRepository.db.getSessionItems(chosen.id)
                        lblActiveSessionTitle.text = "📌 ${chosen.title}"
                        refreshItemsView()
                        refreshHistorySidebar()
                    }
                }
            }
        }
        sidebar.addView(historyListView)

        val sbActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 14 }
        }
        val btnRenameSes = Button(this).apply {
            text = "✎ Ubah Judul"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
            background = roundedBg("#1E293B", 10f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 8
            }
            setOnClickListener { renameCurrentSessionDialog() }
        }
        val btnDeleteSes = Button(this).apply {
            text = "🗑️ Hapus Sesi"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 11f
            background = roundedBg("#1E293B", 10f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 8
            }
            setOnClickListener { deleteCurrentSessionDialog() }
        }
        sbActions.addView(btnRenameSes)
        sbActions.addView(btnDeleteSes)
        sidebar.addView(sbActions)

        body.addView(sidebar)

        // ================= KOLOM KANAN: MONITOR LIVE & DAFTAR ITEM CAPTION =================
        val mainCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        // 1. Kartu Monitor Tempat Caption Live + Pratinjau Hitam-Putih
        val monitorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#111827", 16f, "#4F46E5", 2)
            setPadding(22, 16, 22, 16)
        }

        val monTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        lblMonStatus = TextView(this).apply {
            text = "⚡ PEMINDAIAN REAL-TIME AKTIF (0.35 Detik / In-Memory)"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        lblRectInfo = TextView(this).apply {
            text = formatRectLabel(TranscriptRepository.captureRect)
            setTextColor(Color.parseColor("#93C5FD"))
            textSize = 11f
            background = roundedBg("#1E293B", 8f)
            setPadding(14, 6, 14, 6)
        }
        monTop.addView(lblMonStatus)
        monTop.addView(lblRectInfo)
        monitorCard.addView(monTop)

        val monMid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg("#090D16", 12f, "#1E293B", 2)
            setPadding(14, 12, 14, 12)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10; bottomMargin = 10 }
        }

        imgLiveThumb = ImageView(this).apply {
            setBackgroundColor(Color.parseColor("#0F172A"))
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(340, 96).apply { rightMargin = 18 }
        }
        lblLivePreview = TextView(this).apply {
            text = "Ketuk '🎯 Bidik Langsung Tempat Caption (Sentuh 1x)' untuk mengunci area teks Zoom, atau aktifkan Aksesibilitas untuk penyadapan langsung."
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        monMid.addView(imgLiveThumb)
        monMid.addView(lblLivePreview)
        monitorCard.addView(monMid)

        val monBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnDirectPick = Button(this).apply {
            text = "🎯 Bidik Langsung Tempat Caption (Sentuh 1x)"
            setTextColor(Color.parseColor("#052E16"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#10B981", 10f)
            setPadding(20, 10, 20, 10)
            setOnClickListener { triggerDirectClickPicker() }
        }
        val btnAutoSnap = Button(this).apply {
            text = "⚡ Auto-Snap Caption Bawah"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#4F46E5", 10f)
            setPadding(18, 10, 18, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
            setOnClickListener { triggerAutoSnapBottom() }
        }
        val btnAccessibility = Button(this).apply {
            text = "⚙️ Izin Aksesibilitas (Tanpa OCR)"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 11f
            background = roundedBg("#1E293B", 10f)
            setPadding(18, 10, 18, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        monBtns.addView(btnDirectPick)
        monBtns.addView(btnAutoSnap)
        monBtns.addView(btnAccessibility)
        monitorCard.addView(monBtns)

        mainCol.addView(monitorCard)

        // 2. Baris Info Sesi Aktif & Statistik
        val statsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg("#111827", 12f, "#1E293B", 2)
            setPadding(20, 12, 20, 12)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12; bottomMargin = 10 }
        }
        lblActiveSessionTitle = TextView(this).apply {
            text = "📌 ${TranscriptRepository.currentSessionTitle}"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        lblStatsSummary = TextView(this).apply {
            text = "0 Item Caption  ·  0 Pembicara  ·  0 Kata"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }
        statsBar.addView(lblActiveSessionTitle)
        statsBar.addView(lblStatsSummary)
        mainCol.addView(statsBar)

        // 3. Baris Pencarian & Filter Pembicara
        val filterBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
        }

        edtItemSearch = EditText(this).apply {
            hint = "🔍 Cari kata atau pembicara di sesi ini..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.WHITE)
            textSize = 12f
            background = roundedBg("#111827", 10f, "#1E293B", 2)
            setPadding(18, 12, 18, 12)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 12
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    refreshItemsView()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        spinnerSpeaker = Spinner(this).apply {
            background = roundedBg("#1E293B", 10f)
            setPadding(14, 8, 14, 8)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    selectedSpeakerFilter = parent?.getItemAtPosition(pos)?.toString() ?: "Semua Pembicara"
                    refreshItemsView()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val btnRenameSpeaker = Button(this).apply {
            text = "✎ Ubah Nama Pembicara"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#1E293B", 10f)
            setPadding(18, 10, 18, 10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
            setOnClickListener { renameSpeakerDialog() }
        }

        filterBar.addView(edtItemSearch)
        filterBar.addView(spinnerSpeaker)
        filterBar.addView(btnRenameSpeaker)
        mainCol.addView(filterBar)

        // 4. Daftar Item Caption Real-Time
        itemsListView = ListView(this).apply {
            dividerHeight = 6
            background = roundedBg("#0B1120", 14f, "#1E293B", 2)
            setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { bottomMargin = 12 }
            setOnItemClickListener { _, _, position, _ ->
                if (position in displayedItems.indices) {
                    val item = displayedItems[position]
                    copyToClipboard("[${item.timestamp}] ${item.speaker}: ${item.text}")
                    Toast.makeText(
                        this@MainActivity,
                        "Disalin ke Clipboard: ${item.speaker}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        mainCol.addView(itemsListView)

        // 5. Footer Tombol Ekspor & Utilitas
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnCopyAll = Button(this).apply {
            text = "📋 Salin Transkrip Sesi Ini"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#1E293B", 10f)
            setPadding(20, 12, 20, 12)
            setOnClickListener {
                if (TranscriptRepository.items.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Belum ada item caption.", Toast.LENGTH_SHORT).show()
                } else {
                    copyToClipboard(formatAsTxt())
                    Toast.makeText(this@MainActivity, "Seluruh transkrip disalin ke Clipboard!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnExportMd = Button(this).apply {
            text = "📝 Simpan Markdown (.MD)"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#4F46E5", 10f)
            setPadding(20, 12, 20, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
            setOnClickListener { startExportDocument( isMarkdown = true ) }
        }

        val btnExportTxt = Button(this).apply {
            text = "📄 Simpan Teks (.TXT)"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg("#1E293B", 10f)
            setPadding(20, 12, 20, 12)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 12 }
            setOnClickListener { startExportDocument( isMarkdown = false ) }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        val btnClearItems = Button(this).apply {
            text = "🗑️ Kosongkan Sesi Ini"
            setTextColor(Color.parseColor("#F87171"))
            textSize = 11f
            background = roundedBg("#1E293B", 10f)
            setPadding(18, 12, 18, 12)
            setOnClickListener { clearCurrentSessionDialog() }
        }

        footer.addView(btnCopyAll)
        footer.addView(btnExportMd)
        footer.addView(btnExportTxt)
        footer.addView(spacer)
        footer.addView(btnClearItems)

        mainCol.addView(footer)
        body.addView(mainCol)
        root.addView(body)

        return root
    }

    private fun roundedBg(fillHex: String, radius: Float, strokeHex: String? = null, strokeW: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = radius
            if (strokeHex != null && strokeW > 0) {
                setStroke(strokeW, Color.parseColor(strokeHex))
            }
        }
    }

    private fun formatRectLabel(r: CaptureRect): String {
        return "Area Terkunci: X=${r.x}, Y=${r.y} (${r.w}×${r.h} px)"
    }

    private fun toggleRecording() {
        TranscriptRepository.isRecording = !TranscriptRepository.isRecording
        if (TranscriptRepository.isRecording) {
            btnRecordToggle.text = "⏸ Jeda Perekaman"
            btnRecordToggle.background = roundedBg("#10B981", 12f)
            btnRecordToggle.setTextColor(Color.parseColor("#052E16"))
            lblMonStatus.text = "⚡ PEMINDAIAN REAL-TIME AKTIF"
            lblMonStatus.setTextColor(Color.parseColor("#34D399"))
        } else {
            btnRecordToggle.text = "▶ Lanjutkan Perekaman"
            btnRecordToggle.background = roundedBg("#F59E0B", 12f)
            btnRecordToggle.setTextColor(Color.parseColor("#451A03"))
            lblMonStatus.text = "⏸ PEREKAMAN DIJEDA"
            lblMonStatus.setTextColor(Color.parseColor("#FBBF24"))
        }
    }

    // =========================================================================
    // IZIN OVERLAY & MEDIA PROJECTION (TANGKAP LAYAR HUAWEI TABLET)
    // =========================================================================
    private fun requestOverlayPermission() {
        Toast.makeText(
            this,
            "Aktifkan izin 'Tampilkan di atas aplikasi lain' untuk menggunakan pembidik & jendela mengambang.",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun triggerDirectClickPicker() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        pendingPickerAfterProjection = true
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    private fun triggerAutoSnapBottom() {
        pendingPickerAfterProjection = false
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val svc = Intent(this, ScreenCaptureOcrService::class.java).apply {
                action = ScreenCaptureOcrService.ACTION_START_PROJECTION
                putExtra(ScreenCaptureOcrService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureOcrService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }

            if (pendingPickerAfterProjection) {
                // Buka Overlay Bidik 1x Sentuh
                val pickIntent = Intent(this, ScreenCaptureOcrService::class.java).apply {
                    action = ScreenCaptureOcrService.ACTION_OPEN_PICKER
                }
                startService(pickIntent)
            } else {
                val snapIntent = Intent(this, ScreenCaptureOcrService::class.java).apply {
                    action = ScreenCaptureOcrService.ACTION_AUTO_SNAP_BOTTOM
                }
                startService(snapIntent)
            }
        } else if ((requestCode == REQ_EXPORT_MD || requestCode == REQ_EXPORT_TXT) && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val content = if (requestCode == REQ_EXPORT_MD) formatAsMd() else formatAsTxt()
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    OutputStreamWriter(out, Charsets.UTF_8).use { writer ->
                        writer.write(content)
                    }
                }
                Toast.makeText(this, "Transkrip berhasil disimpan!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal menyimpan file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =========================================================================
    // MANAJEMEN SESI & DAFTAR ITEM CAPTION
    // =========================================================================
    private fun refreshHistorySidebar() {
        sessionRecords = TranscriptRepository.db.listSessions(edtHistorySearch.text.toString())
        val labels = sessionRecords.map { ses ->
            val marker = if (ses.id == TranscriptRepository.currentSessionId) "🟢" else "📄"
            "$marker ${ses.title} (${ses.itemCount} item)"
        }
        historyListView.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#E2E8F0"))
                tv.textSize = 12f
                val isCurrent = sessionRecords.getOrNull(position)?.id == TranscriptRepository.currentSessionId
                tv.setBackgroundColor(if (isCurrent) Color.parseColor("#3730A3") else Color.TRANSPARENT)
                return tv
            }
        }
    }

    private fun refreshItemsView() {
        val allItems = TranscriptRepository.items
        val speakers = listOf("Semua Pembicara") + allItems.map { it.speaker }.distinct().sorted()
        val spkAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speakers)
        if (spinnerSpeaker.adapter?.count != speakers.size) {
            spinnerSpeaker.adapter = spkAdapter
        }

        val q = edtItemSearch.text.toString().trim().lowercase(Locale.getDefault())
        displayedItems = allItems.filter { item ->
            val matchSpk = (selectedSpeakerFilter == "Semua Pembicara" || item.speaker == selectedSpeakerFilter)
            val matchQ = q.isEmpty() ||
                item.speaker.lowercase(Locale.getDefault()).contains(q) ||
                item.text.lowercase(Locale.getDefault()).contains(q)
            matchSpk && matchQ
        }

        val rows = displayedItems.mapIndexed { idx, item ->
            "#${idx + 1}  [${item.timestamp}]  [${item.initials}] ${item.speaker}:\n${item.text}"
        }

        itemsListView.adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
            rows
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(Color.parseColor("#F1F5F9"))
                tv.textSize = 13f
                tv.setPadding(16, 14, 16, 14)
                return tv
            }
        }
        if (displayedItems.isNotEmpty()) {
            itemsListView.setSelection(displayedItems.size - 1)
        }

        val uniqueSpeakers = allItems.map { it.speaker }.distinct().size
        val totalWords = allItems.sumOf { if (it.text.isBlank()) 0 else it.text.trim().split(Regex("\\s+")).size }
        lblStatsSummary.text = "${allItems.size} Item Caption  ·  $uniqueSpeakers Pembicara  ·  $totalWords Kata"
    }

    private fun createNewSessionDialog() {
        val input = EditText(this).apply {
            val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            setText("Sesi Meeting — ${fmt.format(Date())}")
        }
        AlertDialog.Builder(this)
            .setTitle("Buat Sesi Meeting Baru")
            .setView(input)
            .setPositiveButton("Buat") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) {
                    val newId = TranscriptRepository.db.createSession(title)
                    TranscriptRepository.currentSessionId = newId
                    TranscriptRepository.currentSessionTitle = title
                    TranscriptRepository.items = mutableListOf()
                    lblActiveSessionTitle.text = "📌 $title"
                    refreshHistorySidebar()
                    refreshItemsView()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renameCurrentSessionDialog() {
        val input = EditText(this).apply {
            setText(TranscriptRepository.currentSessionTitle)
        }
        AlertDialog.Builder(this)
            .setTitle("Ubah Judul Sesi")
            .setView(input)
            .setPositiveButton("Simpan") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    TranscriptRepository.currentSessionTitle = newTitle
                    TranscriptRepository.db.renameSession(TranscriptRepository.currentSessionId, newTitle)
                    lblActiveSessionTitle.text = "📌 $newTitle"
                    refreshHistorySidebar()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun deleteCurrentSessionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Hapus Sesi History")
            .setMessage("Yakin ingin menghapus sesi '${TranscriptRepository.currentSessionTitle}' beserta seluruh item caption di dalamnya?")
            .setPositiveButton("Hapus") { _, _ ->
                TranscriptRepository.db.deleteSession(TranscriptRepository.currentSessionId)
                val rem = TranscriptRepository.db.listSessions()
                if (rem.isNotEmpty()) {
                    TranscriptRepository.currentSessionId = rem.first().id
                    TranscriptRepository.currentSessionTitle = rem.first().title
                } else {
                    TranscriptRepository.currentSessionId = TranscriptRepository.db.createSession()
                    val fresh = TranscriptRepository.db.listSessions()
                    TranscriptRepository.currentSessionTitle = fresh.first().title
                }
                TranscriptRepository.items = TranscriptRepository.db.getSessionItems(TranscriptRepository.currentSessionId)
                lblActiveSessionTitle.text = "📌 ${TranscriptRepository.currentSessionTitle}"
                refreshHistorySidebar()
                refreshItemsView()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun clearCurrentSessionDialog() {
        if (TranscriptRepository.items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Kosongkan Sesi")
            .setMessage("Hapus semua item caption pada sesi aktif ini?")
            .setPositiveButton("Kosongkan") { _, _ ->
                TranscriptRepository.db.clearSessionItems(TranscriptRepository.currentSessionId)
                TranscriptRepository.items.clear()
                refreshHistorySidebar()
                refreshItemsView()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun renameSpeakerDialog() {
        val defaultOld = TranscriptRepository.items.lastOrNull()?.speaker ?: "Pembicara"
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val edtOld = EditText(this).apply {
            hint = "Nama/Inisial Pembicara Lama"
            setText(defaultOld)
        }
        val edtNew = EditText(this).apply {
            hint = "Nama Baru Pembicara"
            setText(defaultOld)
        }
        layout.addView(edtOld)
        layout.addView(edtNew)

        AlertDialog.Builder(this)
            .setTitle("Ubah Nama Pembicara Massal")
            .setView(layout)
            .setPositiveButton("Ubah") { _, _ ->
                val oldClean = edtOld.text.toString().trim()
                val newClean = edtNew.text.toString().trim()
                if (oldClean.isNotEmpty() && newClean.isNotEmpty()) {
                    val newInit = CaptionParser.getInitials(newClean)
                    TranscriptRepository.db.renameSpeakerInSession(
                        TranscriptRepository.currentSessionId,
                        oldClean,
                        newClean,
                        newInit
                    )
                    TranscriptRepository.items = TranscriptRepository.db.getSessionItems(TranscriptRepository.currentSessionId)
                    refreshItemsView()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // =========================================================================
    // EKSPOR TRANSKRIP (.MD & .TXT) — IDENTIK DENGAN DESKTOP v5.0
    // =========================================================================
    private fun formatAsTxt(): String {
        val fmt = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("TRANSKRIP ZOOM MEETING — ${TranscriptRepository.currentSessionTitle}")
        sb.appendLine("Waktu Ekspor : ${fmt.format(Date())}")
        sb.appendLine("Total Item   : ${TranscriptRepository.items.size} item caption")
        sb.appendLine("=".repeat(56))
        sb.appendLine()
        TranscriptRepository.items.forEachIndexed { idx, item ->
            sb.appendLine("[#${idx + 1} | ${item.timestamp}] ${item.speaker}:")
            sb.appendLine(item.text)
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun formatAsMd(): String {
        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("# 📝 ${TranscriptRepository.currentSessionTitle}
")
        sb.appendLine("> **Waktu Ekspor:** ${fmt.format(Date())}  ")
        sb.appendLine("> **Total Item Caption:** ${TranscriptRepository.items.size} item
")

        val stats = linkedMapOf<String, Pair<Int, Int>>()
        for (item in TranscriptRepository.items) {
            val cur = stats[item.speaker] ?: (0 to 0)
            val wCount = if (item.text.isBlank()) 0 else item.text.trim().split(Regex("\\s+")).size
            stats[item.speaker] = (cur.first + 1) to (cur.second + wCount)
        }

        if (stats.isNotEmpty()) {
            sb.appendLine("## 👥 Ringkasan Partisipan
")
            sb.appendLine("| Pembicara | Jumlah Item Caption | Total Kata |")
            sb.appendLine("| :--- | :--- | :--- |")
            for ((spk, st) in stats) {
                sb.appendLine("| **$spk** | ${st.first} item | ${st.second} kata |")
            }
            sb.appendLine("
---
")
        }

        sb.appendLine("## 💬 Daftar Item Caption
")
        TranscriptRepository.items.forEachIndexed { idx, item ->
            sb.appendLine("### #${idx + 1} · `${item.timestamp}` — **${item.speaker}**")
            sb.appendLine("${item.text}
")
        }
        return sb.toString()
    }

    private fun startExportDocument(isMarkdown: Boolean) {
        if (TranscriptRepository.items.isEmpty()) {
            Toast.makeText(this, "Belum ada item caption untuk disimpan.", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())
        val ext = if (isMarkdown) "md" else "txt"
        val mime = if (isMarkdown) "text/markdown" else "text/plain"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, "transkrip_$ts.$ext")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, if (isMarkdown) REQ_EXPORT_MD else REQ_EXPORT_TXT)
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ZoomTranscript", text))
    }

    // =========================================================================
    // CALLBACK REAL-TIME DARI TRANSCRIPT REPOSITORY
    // =========================================================================
    override fun onTranscriptUpdated(
        previewSpeaker: String,
        previewText: String,
        sourceLabel: String,
        timestamp: String,
        itemsChanged: Boolean
    ) {
        lblLivePreview.text = "🟢 Terbaca Real-Time [$sourceLabel · $timestamp] — $previewSpeaker:\n\"$previewText\""
        floatingMiniWindow?.updateLiveCaption(previewSpeaker, previewText, timestamp)
        if (itemsChanged) {
            refreshItemsView()
            refreshHistorySidebar()
        }
    }

    override fun onPreviewBitmapUpdated(processedBitmap: Bitmap) {
        imgLiveThumb.setImageBitmap(processedBitmap)
    }

    override fun onCaptureRectChanged(newRect: CaptureRect) {
        lblRectInfo.text = formatRectLabel(newRect)
        lblMonStatus.text = "✅ TEMPAT CAPTION TERKUNCI (${newRect.w}×${newRect.h} px) — REAL-TIME AKTIF"
    }
}
