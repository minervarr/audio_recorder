@echo off
setlocal enabledelayedexpansion

set PACKAGE=com.example.audio_recorder

REM ============================================================
REM  Audio Recorder logcat helper
REM
REM  Streams only the tags we care about (app + audioengine +
REM  native USB). Use the .pid sibling script if you want EVERY
REM  log line from the app process (much noisier).
REM ============================================================

REM Tag list. *:S at the end silences everything not listed.
set TAGS=MainActivity:V RecorderService:V RecordingEngine:V UsbAudioManager:V WavSink:V EncodingSink:V AppSettings:V AudioEngine:V UsbAudioDevice:V UsbAudioInput:V UsbAudioOutput:V UsbAudio:V UsbAudioJNI:V LibUsb:V AndroidRuntime:E *:S

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
echo [logcat] Device:  !SERIAL!
echo [logcat] Package: %PACKAGE%
echo [logcat] Tags:    app + audioengine + native USB + crashes
echo [logcat] Streaming (Ctrl+C to stop)...
echo ==============================================================

adb -s !SERIAL! logcat -c
adb -s !SERIAL! logcat -v threadtime %TAGS%

endlocal
