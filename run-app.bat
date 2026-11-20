@echo off
setlocal
for /f "tokens=2 delims=:" %%P in ('chcp') do set "previousCodePage=%%P"
chcp 65001 >nul

cd /d "%~dp0"
if /i "%~1"=="--online" goto online

echo 正在使用本机 Gradle 缓存启动 MC World Explorer…
call "%~dp0gradlew.bat" --offline --console=plain prepareWindowsClient
set "launchResult=%errorlevel%"
if "%launchResult%"=="0" goto launch
echo.
echo 如果 Gradle 提示本机缺少依赖，可以联网重试。
choice /c YN /n /m "联网重试请按 Y，退出请按 N [Y/N] "
if errorlevel 2 goto done
if errorlevel 1 goto online
goto done

:online
echo 正在联网检查 Gradle 依赖并启动 MC World Explorer…
call "%~dp0gradlew.bat" --console=plain prepareWindowsClient
set "launchResult=%errorlevel%"
if not "%launchResult%"=="0" goto failed

:launch
set "client=%~dp0build\windows-client"
"%client%\runtime\bin\MCWE.exe" --module-path "%client%\fx" --add-modules javafx.controls,javafx.fxml --add-exports=javafx.graphics/com.sun.prism=ALL-UNNAMED --add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED --add-opens=javafx.graphics/com.sun.prism.d3d=ALL-UNNAMED "-Dmcwe.projectDir=%CD%" -cp "%client%\app\*" com.mcworldexplorer.Launcher
set "launchResult=%errorlevel%"
if "%launchResult%"=="0" goto done
:failed
echo 启动失败，请查看上方错误。按任意键退出。
pause >nul

:done
if defined previousCodePage chcp %previousCodePage% >nul
endlocal & exit /b %launchResult%
