@echo off
title Kompilasi Otomatis APK Tablet Huawei - Zoom Caption Transcriber v5.0
color 0A
echo ===============================================================================
echo   KOMPILASI APK NATIVE HUAWEI TABLET (ANDROID / HARMONYOS) v5.0
echo ===============================================================================
echo.

where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [INFO] Menggunakan Gradle sistem untuk membuat APK...
    call gradle assembleRelease --no-daemon
) else (
    if exist gradlew.bat (
        echo [INFO] Menggunakan Gradle Wrapper lokal...
        call gradlew.bat assembleRelease --no-daemon
    ) else (
        echo [PERHATIAN] Gradle atau Android SDK belum terdeteksi di PC ini.
        echo.
        echo Cara Paling Cepat Mendapatkan File .APK Tanpa Instal Android Studio:
        echo 1. Unggah seluruh folder ini ke akun GitHub Anda (gratis).
        echo 2. Buka tab "Actions" di GitHub — file .github/workflows/build-huawei-apk.yml
        echo    akan otomatis mengompilasi proyek ini menjadi file .APK dalam 2 menit!
        echo 3. Unduh file "ZoomCaptionTranscriber-HuaweiTablet-v5.0-APK" dan instal di Tablet Huawei Anda.
        pause
        exit /b 1
    )
)

echo.
if exist "app\build\outputs\apk\release\app-release.apk" (
    copy /Y "app\build\outputs\apk\release\app-release.apk" "ZoomCaptionTranscriber-Huawei-v5.0.apk" >nul
    echo [SUKSES] File APK berhasil dibuat: ZoomCaptionTranscriber-Huawei-v5.0.apk
    echo Silakan kirim file tersebut ke Tablet Huawei Anda via kabel USB / Huawei Share / WhatsApp!
) else (
    echo [INFO] Periksa folder app\build\outputs\apk\ untuk hasil kompilasi.
)
pause
