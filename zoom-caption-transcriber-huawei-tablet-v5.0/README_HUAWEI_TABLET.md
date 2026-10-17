# 🎙️ Zoom Caption Transcriber v5.0 — Edisi Native Tablet Huawei (Android / HarmonyOS)

Proyek ini adalah hasil port penuh (*1:1 Native Port*) dari **Zoom Caption Transcriber v5.0 Desktop & Extension** menjadi aplikasi **Native Android / HarmonyOS (`.apk`)** yang dirancang khusus untuk **Tablet Huawei (MatePad Series)** tanpa ketergantungan pada Google Mobile Services (GMS).

---

## ✨ Fitur Utama Versi Tablet Huawei

1. **🎯 Bidik Langsung 1x Sentuh (`FloatingTargetPickerOverlay.kt`)**
   - Sama seperti versi Desktop v5.0: cukup ketuk tombol **🎯 Bidik Langsung Tempat Caption**, lalu **sentuh 1x** tepat pada teks caption Zoom di layar tablet (atau tarik manual dengan jari / Huawei M-Pencil).
   - Algoritma `autoSnapCaptionBox` otomatis mendeteksi ke-4 sisi batas kotak caption agar nama pembicara dan kalimat utuh tidak terpotong.
2. **⚡ 3-Engine Hybrid Penangkap Caption Real-Time**
   - **Engine 1 — Direct UI Reader (`ZoomCaptionAccessibilityService.kt`)**: Menyadap langsung teks dari komponen UI aplikasi Zoom Android (`us.zoom.videomeetings`) melalui layanan aksesibilitas HarmonyOS/EMUI. **0% beban OCR, super hemat baterai, dan 100% presisi**.
   - **Engine 2 — In-Memory Screen Capture + Filter Hitam-Putih 2.2x + On-Device OCR (`ScreenCaptureOcrService.kt` & `CaptionImagePreprocessor.kt`)**: Menangkap potongan layar setiap 0,35 detik di RAM, menghapus warna latar gelap/maroon dan avatar menjadi teks hitam tajam di atas latar putih murni, lalu memindainya secara offline di perangkat.
   - **Engine 3 — Local Browser Bridge (`127.0.0.1:18765/sync`)**: Menerima sinkronisasi langsung dari folder `ekstensi-zoom-web-tablet/` jika Anda membuka Zoom Web melalui Kiwi Browser / Lemur Browser di tablet Huawei.
3. **📌 Jendela Mini Mengambang (*Always on Top* — `FloatingMiniWindow.kt`)**
   - Menampilkan transkrip yang sedang berjalan secara mengambang tepat di atas layar Zoom penuh, bisa digeser ke sudut mana pun di layar tablet.
4. **🗂️ Studio Dual-Pane Landscape + Database SQLite (`HistoryDatabase.kt` & `MainActivity.kt`)**
   - **Sidebar Kiri**: Riwayat sesi meeting tersimpan otomatis di `caption_history.db`, lengkap dengan pencarian lintas sesi, buat sesi baru, ubah judul, dan hapus sesi.
   - **Kolom Kanan**: Pratinjau ketajaman hitam-putih live, pencarian kata di sesi aktif, filter per pembicara, ubah nama pembicara massal, ketuk item untuk salin ke Clipboard, serta ekspor ke **Markdown (`.MD`)** lengkap dengan tabel ringkasan partisipan maupun **Teks (`.TXT`)**.

---

## 🚀 Cara Mencetak File `.APK` & Menginstal di Tablet Huawei

Anda memiliki **2 cara mudah** untuk menghasilkan file `.apk` siap instal dari kode sumber ini:

### Cara A: Kompilasi Cloud Otomatis via GitHub Actions (Tanpa Instal Software Apa Pun di PC)
1. Buat repositori baru (boleh *Private* atau *Public*) di akun GitHub Anda.
2. Unggah seluruh isi folder proyek ini ke repositori tersebut.
3. Buka tab **Actions** di repositori GitHub Anda, lalu klik workflow **Build Huawei Tablet APK**.
4. Dalam ±2 menit, unduh file **`ZoomCaptionTranscriber-HuaweiTablet-v5.0-APK`** pada bagian *Artifacts*, ekstrak, lalu kirim file `.apk`-nya ke tablet Huawei Anda dan ketuk untuk menginstal.

### Cara B: Kompilasi Lokal di PC (Windows / Android Studio)
1. Jika Anda memiliki Gradle atau Android Studio di PC, klik 2x file **`KOMPILASI_APK_WINDOWS.bat`** (atau buka folder ini di Android Studio lalu pilih **Build > Build Bundle(s) / APK(s) > Build APK(s)**).
2. File **`ZoomCaptionTranscriber-Huawei-v5.0.apk`** akan langsung terbentuk dan siap dikirim ke tablet Huawei melalui Huawei Share, kabel data, atau Telegram/WhatsApp.

---

## 📱 Panduan Penggunaan di Tablet Huawei (MatePad)

1. **Instal & Buka Aplikasi**: Buka **Zoom Transcriber v5.0** di tablet Huawei Anda (disarankan posisi *Landscape* atau mode *Split-Screen / Multi-Window* berdampingan dengan Zoom).
2. **Aktifkan Mode Penangkap Pilihan Anda**:
   - **Mode Tanpa OCR (Paling Hemat Baterai)**: Ketuk tombol **⚙️ Izin Aksesibilitas (Tanpa OCR)**, lalu aktifkan **Zoom Caption Transcriber** pada menu Aksesibilitas tablet Huawei.
   - **Mode Bidik Visual OCR + Jendela Mengambang**: Centang **📌 Jendela Mengambang**, lalu ketuk **🎯 Bidik Langsung Tempat Caption (Sentuh 1x)** dan sentuh 1x pada kotak subtitle di aplikasi Zoom.
3. **Ekspor Kapan Saja**: Setelah rapat selesai, ketuk **📝 Simpan Markdown (.MD)** atau **📄 Simpan Teks (.TXT)** untuk menyimpan laporan transkrip langsung ke memori tablet Huawei Anda.
