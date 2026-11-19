# 手機 USB 接在 Windows 筆電時（筆電開 SSH 通道到 B 台）：每 10 秒檢查，adb forward／reverse 掉了就自動補上
# 用法：在 PowerShell 執行  powershell -ExecutionPolicy Bypass -File adb_keepalive.ps1   （視窗不要關，Ctrl+C 結束）
# 跟 B 台的 adb_keepalive.sh 做的事一樣；手機接哪台就在哪台跑，不要兩邊都接手機
$last = ""
function Log($m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }
Log "開始：每 10 秒檢查一次 adb forward 8765／reverse 8300"
while ($true) {
  $state = (& adb get-state 2>&1 | Out-String).Trim()
  if ($state -ne "device") {
    if ($last -ne $state) { Log "📵 手機不在（$state）" }
    $last = $state
  } else {
    if ($last -ne "device") { Log "📱 手機接上" }
    $last = "device"
    if (-not ((& adb forward --list | Out-String) -match "tcp:8765 tcp:8765")) {
      & adb forward tcp:8765 tcp:8765 | Out-Null; Log "🔧 補上 adb forward tcp:8765 tcp:8765"
    }
    if (-not ((& adb reverse --list 2>&1 | Out-String) -match "tcp:8300 tcp:8300")) {
      & adb reverse tcp:8300 tcp:8300 | Out-Null; Log "🔧 補上 adb reverse tcp:8300 tcp:8300"
    }
  }
  Start-Sleep -Seconds 10
}
