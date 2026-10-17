package id.zoomtranscriber.huawei

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pusat sinkronisasi real-time antara ke-3 Engine (Accessibility UIA, Screen Capture OCR, Browser Bridge)
 * dengan SQLite Database dan UI (MainActivity + Floating Overlay Window).
 */
object TranscriptRepository {

    interface Listener {
        fun onTranscriptUpdated(
            previewSpeaker: String,
            previewText: String,
            sourceLabel: String,
            timestamp: String,
            itemsChanged: Boolean
        )
        fun onPreviewBitmapUpdated(processedBitmap: Bitmap)
        fun onCaptureRectChanged(newRect: CaptureRect)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val listeners = mutableSetOf<Listener>()

    lateinit var db: HistoryDatabase
        private set

    var currentSessionId: Long = 1L
    var currentSessionTitle: String = "Sesi Meeting"
    var items: MutableList<CaptionItem> = mutableListOf()
    var isRecording: Boolean = true
    var captureRect: CaptureRect = CaptureRect(240, 820, 1100, 210)

    fun init(context: Context) {
        if (!::db.isInitialized) {
            db = HistoryDatabase(context)
            val dm = context.resources.displayMetrics
            val sw = dm.widthPixels
            val sh = dm.heightPixels
            val defaultRect = CaptureRect(
                x = (sw * 0.19).toInt(),
                y = (sh * 0.76).toInt(),
                w = (sw * 0.64).toInt(),
                h = (sh * 0.17).toInt()
            )
            captureRect = db.getCaptureRect(defaultRect)

            val sessions = db.listSessions()
            if (sessions.isNotEmpty() && sessions.first().itemCount == 0) {
                currentSessionId = sessions.first().id
                currentSessionTitle = sessions.first().title
            } else {
                currentSessionId = db.createSession()
                val updated = db.listSessions()
                currentSessionTitle = updated.firstOrNull()?.title ?: "Sesi Meeting"
            }
            items = db.getSessionItems(currentSessionId)
        }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun updateCaptureRect(rect: CaptureRect) {
        captureRect = rect
        if (::db.isInitialized) {
            db.saveCaptureRect(rect)
        }
        mainHandler.post {
            listeners.forEach { it.onCaptureRectChanged(rect) }
        }
    }

    fun notifyProcessedBitmap(bmp: Bitmap) {
        mainHandler.post {
            listeners.forEach { it.onPreviewBitmapUpdated(bmp) }
        }
    }

    /**
     * Port 1:1 dari `ingest_caption_items` pada `app_desktop.py`.
     */
    @Synchronized
    fun ingestCaptionItems(detectedItems: List<ParsedCaption>, sourceLabel: String) {
        if (detectedItems.isEmpty() || !::db.isInitialized) return

        val nowTime = timeFmt.format(Date())
        var changed = false

        for (det in detectedItems) {
            val spk = det.speaker.ifBlank { "Pembicara" }
            val init = det.initials.ifBlank { CaptionParser.getInitials(spk) }
            val txt = det.text.trim()
            if (txt.length < 2) continue

            // Cek 5 item terakhir apakah kalimat ini adalah update langsung dari item yang sedang berjalan
            var matchedExisting: CaptionItem? = null
            val tail = items.takeLast(5).asReversed()
            for (recent in tail) {
                val sameSpk = (
                    recent.speaker.equals(spk, ignoreCase = true) ||
                    recent.speaker == "Pembicara" ||
                    spk == "Pembicara"
                )
                if (sameSpk && CaptionParser.isSameEvolvingUtterance(recent.text, txt)) {
                    matchedExisting = recent
                    break
                }
            }

            if (!isRecording) continue

            if (matchedExisting != null) {
                if (matchedExisting.speaker == "Pembicara" && spk != "Pembicara") {
                    matchedExisting.speaker = spk
                    matchedExisting.initials = init
                    changed = true
                }
                if (matchedExisting.text != txt) {
                    matchedExisting.text = txt
                    db.updateItem(
                        matchedExisting.id,
                        currentSessionId,
                        matchedExisting.speaker,
                        matchedExisting.initials,
                        txt
                    )
                    changed = true
                }
            } else {
                val newId = db.addItem(currentSessionId, spk, init, txt, nowTime)
                items.add(
                    CaptionItem(
                        id = newId,
                        sessionId = currentSessionId,
                        speaker = spk,
                        initials = init,
                        text = txt,
                        timestamp = nowTime,
                        createdAt = System.currentTimeMillis() / 1000.0
                    )
                )
                changed = true
            }
        }

        val latest = detectedItems.last()
        val finalChanged = changed
        mainHandler.post {
            listeners.forEach {
                it.onTranscriptUpdated(
                    previewSpeaker = latest.speaker,
                    previewText = latest.text,
                    sourceLabel = sourceLabel,
                    timestamp = nowTime,
                    itemsChanged = finalChanged
                )
            }
        }
    }
}
