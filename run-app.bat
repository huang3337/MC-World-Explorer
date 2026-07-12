@echo off
setlocal

cd /d "%~dp0"
call "%~dp0gradlew.bat" run

if errorlevel 1 (
    echo.
    echo Failed to start MC World Explorer.
    echo Review the error messages above, then press any key to close this window.
    pause >nul
)

endlocal
