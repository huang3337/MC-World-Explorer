@echo off
setlocal
cd /d "%~dp0"

where npm >nul 2>&1
if errorlevel 1 (
  echo [MC World Explorer] npm was not found. Install Node.js or add npm to PATH.
  pause
  exit /b 1
)

if not exist "node_modules\@tauri-apps\cli" (
  echo [MC World Explorer] Dependencies are missing. Run npm install once, then try again.
  pause
  exit /b 1
)

echo [MC World Explorer] Starting Tauri development mode...
call npm run tauri -- dev
set "mcwe_exit_code=%errorlevel%"

if not "%mcwe_exit_code%"=="0" (
  echo.
  echo [MC World Explorer] Development mode exited with code %mcwe_exit_code%.
  pause
)

endlocal & exit /b %mcwe_exit_code%
