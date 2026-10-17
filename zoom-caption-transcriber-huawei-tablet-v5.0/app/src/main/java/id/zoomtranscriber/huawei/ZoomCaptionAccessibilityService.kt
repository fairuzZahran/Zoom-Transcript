package id.zoomtranscriber.huawei

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ENGINE 1 (Tanpa OCR / Zero-Lag untuk Tablet Huawei):
 * Membaca langsung komponen teks Closed Caption / Live Transcript dari aplikasi Zoom Android
 * (`us.zoom.videomeetings`) atau Zoom Web menggunakan layanan aksesibilitas bawaan HarmonyOS/Android.
 */
class ZoomCaptionAccessibilityService : AccessibilityService() {

    private val zoomPackages = setOf(
        "us.zoom.videomeetings",
        "us.zoom.zpcontroller",
        "com.huawei.browser",
        "com.kiwibrowser.browser",
        "com.lemurbrowser.exts",
        "com.android.chrome"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        TranscriptRepository.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !TranscriptRepository.isRecording) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in zoomPackages && !pkg.contains("zoom", ignoreCase = true)) return

        val root = rootInActiveWindow ?: return
        val extractedLines = mutableListOf<OcrLine>()
        traverseCaptionNodes(root, extractedLines)

        if (extractedLines.isNotEmpty()) {
            val parsed = CaptionParser.parseOcrLinesToItems(extractedLines)
            if (parsed.isNotEmpty()) {
                TranscriptRepository.ingestCaptionItems(parsed, "Direct UI Reader")
            }
        }
    }

    private fun traverseCaptionNodes(node: AccessibilityNodeInfo?, out: MutableList<OcrLine>) {
        if (node == null) return
        val viewId = node.viewIdResourceName ?: ""
        val clsName = node.className?.toString() ?: ""
        val rawText = node.text?.toString()?.trim() ?: ""

        // Deteksi elemen teks yang berada di area caption Zoom atau memiliki ID terkait subtitle/caption
        val isCaptionId = viewId.contains("caption", ignoreCase = true) ||
            viewId.contains("subtitle", ignoreCase = true) ||
            viewId.contains("transcript", ignoreCase = true) ||
            viewId.contains("cc_", ignoreCase = true)

        if (rawText.length >= 2 && (isCaptionId || clsName.endsWith("TextView"))) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val lock = TranscriptRepository.captureRect
            val insideLockedBox = rect.centerY() in lock.y..(lock.y + lock.h) &&
                rect.centerX() in lock.x..(lock.x + lock.w)

            if (isCaptionId || insideLockedBox) {
                out.add(
                    OcrLine(
                        y = rect.top,
                        h = rect.height().coerceAtLeast(16),
                        text = rawText
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            traverseCaptionNodes(node.getChild(i), out)
        }
    }

    override fun onInterrupt() {}
}
