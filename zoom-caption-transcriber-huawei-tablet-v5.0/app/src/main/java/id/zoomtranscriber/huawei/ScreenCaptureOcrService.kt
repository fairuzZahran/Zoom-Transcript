package id.zoomtranscriber.huawei

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * ENGINE 2 & 3:
 * - Menangkap layar Tablet Huawei secara berkala (setiap 350ms) menggunakan `MediaProjection`.
 * - Memotong area kotak caption yang dikunci (`captureRect`), memprosesnya menjadi Hitam-Putih 2.2x tajam
 *   di memori (RAM), lalu memindai teks menggunakan On-Device Text Recognition (tanpa butuh GMS).
 * - Menjalankan Local HTTP Server di `127.0.0.1:18765/sync` untuk menerima transkrip dari Ekstensi Browser.
 */
class ScreenCaptureOcrService : Service() {

    companion object {
        const val ACTION_START_PROJECTION = "ACTION_START_PROJECTION"
        const val ACTION_OPEN_PICKER = "ACTION_OPEN_PICKER"
        const val ACTION_AUTO_SNAP_BOTTOM = "ACTION_AUTO_SNAP_BOTTOM"
        const val ACTION_SCAN_ONCE = "ACTION_SCAN_ONCE"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var isLoopRunning = false
    private var bridgeServerSocket: ServerSocket? = null
    private var overlayPicker: FloatingTargetPickerOverlay? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isLoopRunning) return
            if (TranscriptRepository.isRecording) {
                performSingleOcrTick()
            }
            handler.postDelayed(this, 350L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        TranscriptRepository.init(this)
        startForegroundNotification()
        startBrowserBridgeServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    setupMediaProjection(resultCode, data)
                }
            }
            ACTION_OPEN_PICKER -> {
                openDirectClickPickerOverlay()
            }
            ACTION_AUTO_SNAP_BOTTOM -> {
                autoSnapBottomZoomCaption()
            }
            ACTION_SCAN_ONCE -> {
                performSingleOcrTick()
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "zoom_transcriber_huawei_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                "Zoom Caption Real-Time Scanner",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Zoom Caption Transcriber v5.0 Aktif")
                .setContentText("Memindai tempat caption Zoom secara real-time di memori (0.35s)")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Zoom Caption Transcriber v5.0 Aktif")
                .setContentText("Memindai tempat caption Zoom secara real-time")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        }
        startForeground(5001, notif)
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection?.stop()
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        val density = dm.densityDpi

        imageReader?.close()
        imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.release()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ZoomCaptionCapture",
            sw,
            sh,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        if (!isLoopRunning) {
            isLoopRunning = true
            handler.post(pollRunnable)
        }
    }

    private fun grabFullScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        val image = reader.acquireLatestImage() ?: return null
        return try {
            val width = image.width
            val height = image.height
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val fullBmp = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            fullBmp.copyPixelsFromBuffer(buffer)
            Bitmap.createBitmap(fullBmp, 0, 0, width, height)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun performSingleOcrTick() {
        val fullBmp = grabFullScreenBitmap() ?: return
        val r = TranscriptRepository.captureRect
        val x = r.x.coerceIn(0, max(0, fullBmp.width - 20))
        val y = r.y.coerceIn(0, max(0, fullBmp.height - 20))
        val w = min(r.w.coerceAtLeast(20), fullBmp.width - x)
        val h = min(r.h.coerceAtLeast(20), fullBmp.height - y)

        val rawCrop = Bitmap.createBitmap(fullBmp, x, y, w, h)
        val processedBmp = CaptionImagePreprocessor.preprocessCaptionBitmapForOcr(rawCrop)
        TranscriptRepository.notifyProcessedBitmap(processedBmp)

        val inputImage = InputImage.fromBitmap(processedBmp, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val ocrLines = mutableListOf<OcrLine>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox
                        val txt = line.text.trim()
                        if (txt.length >= 2) {
                            ocrLines.add(
                                OcrLine(
                                    y = box?.top ?: 0,
                                    h = (box?.height() ?: 24).coerceAtLeast(12),
                                    text = txt
                                )
                            )
                        }
                    }
                }
                if (ocrLines.isNotEmpty()) {
                    val items = CaptionParser.parseOcrLinesToItems(ocrLines)
                    if (items.isNotEmpty()) {
                        TranscriptRepository.ingestCaptionItems(items, "Live OCR")
                    }
                }
            }
    }

    private fun openDirectClickPickerOverlay() {
        val screenBmp = grabFullScreenBitmap()
        overlayPicker?.dismiss()
        overlayPicker = FloatingTargetPickerOverlay(
            context = this,
            screenSnapshot = screenBmp,
            onRectLocked = { lockedRect ->
                TranscriptRepository.updateCaptureRect(lockedRect)
                performSingleOcrTick()
            }
        )
        overlayPicker?.show()
    }

    private fun autoSnapBottomZoomCaption() {
        val screenBmp = grabFullScreenBitmap() ?: return
        val snapped = CaptionImagePreprocessor.autoSnapCaptionBox(
            screenBmp,
            (screenBmp.width * 0.45).toInt(),
            (screenBmp.height * 0.86).toInt()
        )
        TranscriptRepository.updateCaptureRect(snapped)
        performSingleOcrTick()
    }

    /**
     * Port 1:1 dari `_run_browser_bridge_server` (127.0.0.1:18765/sync) di `app_desktop.py`.
     */
    private fun startBrowserBridgeServer() {
        thread(isDaemon = true, name = "ZoomBrowserBridge") {
            try {
                val server = ServerSocket(18765, 10, InetAddress.getByName("127.0.0.1"))
                bridgeServerSocket = server
                while (!server.isClosed) {
                    val client = server.accept()
                    thread(isDaemon = true) {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                            var contentLength = 0
                            var line = reader.readLine()
                            while (!line.isNullOrEmpty()) {
                                if (line.lowercase().startsWith("content-length:")) {
                                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                                line = reader.readLine()
                            }
                            if (contentLength > 0) {
                                val bodyChars = CharArray(contentLength)
                                var readTotal = 0
                                while (readTotal < contentLength) {
                                    val r = reader.read(bodyChars, readTotal, contentLength - readTotal)
                                    if (r == -1) break
                                    readTotal += r
                                }
                                val bodyStr = String(bodyChars, 0, readTotal)
                                val json = JSONObject(bodyStr)
                                val arr = json.optJSONArray("items")
                                if (arr != null && arr.length() > 0) {
                                    val parsedList = mutableListOf<ParsedCaption>()
                                    for (i in 0 until arr.length()) {
                                        val obj = arr.getJSONObject(i)
                                        val spk = obj.optString("speaker", "Pembicara")
                                        val txt = obj.optString("text", "")
                                        if (txt.length >= 2) {
                                            parsedList.add(
                                                ParsedCaption(
                                                    speaker = spk,
                                                    initials = obj.optString("initials", CaptionParser.getInitials(spk)),
                                                    text = txt
                                                )
                                            )
                                        }
                                    }
                                    if (parsedList.isNotEmpty()) {
                                        TranscriptRepository.ingestCaptionItems(parsedList, "Zoom Web Sync")
                                    }
                                }
                            }
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: Content-Type\r\n" +
                                "Content-Type: application/json\r\n\r\n" +
                                "{\"status\":\"ok\"}"
                            client.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                            client.getOutputStream().flush()
                        } catch (_: Exception) {
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        isLoopRunning = false
        handler.removeCallbacksAndMessages(null)
        overlayPicker?.dismiss()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        try { bridgeServerSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
