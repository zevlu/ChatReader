@echo off
chcp 65001 >nul
rem ─────────────────────────────────────────────────────────────
rem  CoDriver 車機（BeamNG 模式）一鍵開啟
rem  1. adb forward（手機 USB）
rem  2. 用 Chrome「App 模式」開網頁車機：沒有分頁列、網址列，固定在螢幕下方
rem  放在 C:\Users\ucl\Desktop\coDriver_testCode\ （跟 platform-tools 資料夾放一起），雙擊執行
rem  螢幕大小不同時，改下面四個數字（左上角 X、Y，寬、高）
rem ─────────────────────────────────────────────────────────────
set X=0
set Y=690
set W=1680
set H=360

cd /d "%~dp0platform-tools"
adb -s R5CY21RP81B forward tcp:8765 tcp:8765
if errorlevel 1 (
  echo.
  echo [錯誤] adb forward 失敗：手機有接上嗎？有允許 USB 偵錯嗎？（adb devices 要顯示 device）
  pause
  exit /b 1
)

set CHROME=C:\Program Files\Google\Chrome\Application\chrome.exe
if not exist "%CHROME%" set CHROME=C:\Program Files (x86)\Google\Chrome\Application\chrome.exe
if not exist "%CHROME%" set CHROME=%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe
if not exist "%CHROME%" (
  echo [錯誤] 找不到 Chrome
  pause
  exit /b 1
)

rem 用獨立的設定資料夾，視窗位置和大小才會照上面的設定（第一次開會問麥克風權限，按允許）
start "" "%CHROME%" --app=http://localhost:8765/?unit=1 --window-position=%X%,%Y% --window-size=%W%,%H% --user-data-dir="%LOCALAPPDATA%\CoDriverUnit" --no-first-run
echo 車機已開啟。接著在 Anaconda Prompt 執行：python beamng_bridge.py
timeout /t 3 >nul
