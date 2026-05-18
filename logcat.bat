@echo off
setlocal enabledelayedexpansion

set PACKAGE=com.example.audio_recorder

REM ============================================================
REM  Audio Recorder logcat — focused tail
REM
REM  Tags captured by logcat:
REM    App tags (priority Info+):
REM      MainActivity, RecorderService, RecordingEngine,
REM      UsbAudioInput, UsbAudioOutput, UsbAudioDevice,
REM      UsbAudioManager, UsbAudio, UsbAudioJNI
REM    Crash / lifecycle tags:
REM      AndroidRuntime  (E)  — Java FATAL EXCEPTION on crash
REM      DEBUG           (I)  — native crash tombstones (debuggerd)
REM      ActivityManager (I)  — "Process X has died" / "Killing X"
REM                             fires when the app is closed,
REM                             swiped away, or killed by the OS
REM    USB hardware tags:
REM      UsbDeviceManager (I) — kernel/system USB attach/detach
REM      UsbHostManager   (I) — USB host events
REM
REM  Note: PID scoping is intentionally NOT used here, because
REM    (a) ActivityManager/DEBUG/UsbDeviceManager are emitted by
REM        system_server, not our app's PID, so a --pid filter
REM        would hide them, and
REM    (b) PID dies when the app crashes/closes — we want the
REM        stream to keep flowing past that point so we see the
REM        post-mortem (death reason, tombstone, etc).
REM
REM  System tags would otherwise be very noisy, so the output is
REM  post-filtered with findstr to keep only:
REM    - lines from our app's own tags
REM    - lines mentioning our package name (catches ActivityManager
REM      process-death lines about us)
REM    - lines from AndroidRuntime, DEBUG, UsbDeviceManager,
REM      UsbHostManager (intrinsically relevant when present)
REM ============================================================

set APP_TAGS=MainActivity:I RecorderService:I RecordingEngine:I UsbAudioInput:I UsbAudioOutput:I UsbAudioDevice:I UsbAudioManager:I UsbAudio:I UsbAudioJNI:I
set SYS_TAGS=AndroidRuntime:E DEBUG:I ActivityManager:I UsbDeviceManager:I UsbHostManager:I
set TAGS=%APP_TAGS% %SYS_TAGS% *:S

where adb >nul 2>&1
if errorlevel 1 (
    echo [logcat] adb is not on PATH. Install Android platform-tools.
    exit /b 1
)

set COUNT=0
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
    if "%%b"=="device" (
        set /a COUNT+=1
        set DEVICE_!COUNT!=%%a
    )
)

if !COUNT!==0 (
    echo [logcat] No devices connected.
    exit /b 1
)

if !COUNT!==1 (
    set SERIAL=!DEVICE_1!
    goto :have_serial
)

echo.
echo Multiple devices detected:
for /l %%i in (1,1,!COUNT!) do echo    %%i^) !DEVICE_%%i!
echo.
set /p CHOICE="Choose device [1-!COUNT!]: "
call set "SERIAL=%%DEVICE_!CHOICE!%%"

:have_serial
if not defined SERIAL (
    echo [logcat] Invalid choice.
    exit /b 1
)

echo.
echo [logcat] Device:   !SERIAL!
echo [logcat] Package:  %PACKAGE%
echo [logcat] Watching: app logs, Java crashes ^(AndroidRuntime^),
echo [logcat]           native crashes ^(DEBUG^), process death
echo [logcat]           ^(ActivityManager^), USB attach/detach
echo [logcat]           ^(UsbDeviceManager / UsbHostManager^)
echo [logcat] Ctrl+C to stop
echo ==============================================================

adb -s !SERIAL! logcat -c
adb -s !SERIAL! logcat -v time %TAGS% | findstr /I /C:"/MainActivity(" /C:"/RecorderService(" /C:"/RecordingEngine(" /C:"/UsbAudioInput(" /C:"/UsbAudioOutput(" /C:"/UsbAudioDevice(" /C:"/UsbAudioManager(" /C:"/UsbAudio(" /C:"/UsbAudioJNI(" /C:"/AndroidRuntime(" /C:"/DEBUG(" /C:"/UsbDeviceManager(" /C:"/UsbHostManager(" /C:"%PACKAGE%"

endlocal
