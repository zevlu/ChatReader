# CLAUDE.md — CoDriver 部署到 DGX Spark（給 Claude Code 看的交接說明）

> 回答一律用**繁體中文**；程式碼註解可以用中文。使用者是高科大學生，不熟 Linux，請一步一步說明、每個指令說清楚在做什麼。
> 寫於 2026-11-15（由 claude.ai 上的專案對話整理）。

## 1. 這是什麼專案

**CoDriver**：會主動開口、會幫你動手的 AI 副駕駛。上車後從真的 Messenger 聊天找出今天的約定 → 規劃導航 → 路上塞車時主動問「你大概會晚 8 分鐘，要幫你跟孟澤說嗎？」→ 駕駛說「送出」才真的在 Messenger 傳出去。

這個 repo（ChatReader）是 Android 專案，有兩個 Product Flavor：
- `drive` = CoDriver（**現在的重點**）
- `friend` = 交友大師（**暫停中，不要動**）
- 共用程式在 `app/src/main/`（改共用程式要兩個版本都顧到）

## 2. 架構（目前在 Windows 實驗室電腦上已驗證）

```
BeamNG.drive＋BeamNG.Earth（真實高雄地圖、AI 車流）
   │ OutGauge UDP 127.0.0.1:4444（車速、油量）
   ▼
tools/beamng/beamng_bridge.py（Python，只用標準函式庫）
   │ POST http://127.0.0.1:8765/api  ← adb forward tcp:8765 tcp:8765（USB）
   ▼
手機 Galaxy A55（序號 R5CY21RP81B）上的 CoDriver App（CarMate）
   ├ DashServer：port 8765，GET / 給網頁、GET /events SSE、POST /api
   ├ 真的 Messenger（無障礙服務操作，送出前一定要使用者確認）
   └ 雲端 LLM（學校 iAI Hub）＋ System One 路由器
   ▲
瀏覽器車機網頁 http://localhost:8765/?unit=1（只有車機；聲音用手機麥克風）
```

協定重點：
- 橋接 → 手機：`{"type":"vehicle","speed":45,"fuel":72,"game":true}`（每秒）、`{"type":"action","name":"jam"}`（低於 10 km/h 持續 20 秒）、`{"type":"action","name":"jamClear"}`
- 導航：手機在狀態裡放 `navReq {id, cmd: plan|cancel|done, dest, via, viaGas, start}`，網頁回 `{"type":"nav","id":N,"status":"planned|progress|via|arrived|failed",...}`

## 3. 這次的任務：搬到 DGX Spark（這台）

- 這台：NVIDIA DGX Spark，主機 spark-6f5b，IP 192.168.50.17，使用者 nkust（有 sudo），DGX OS（Ubuntu 24.04 **arm64**），GPU GB10，驅動 580.178.04，CUDA 13.0，Docker 29.6.2。
- 使用者從 Windows 筆電用 VS Code Remote-SSH 連進來。

### 記者會時程（Nvidia Lab DGX Spark）
| 日期 | 要交 |
| --- | --- |
| 11/15 | GitHub repo 連結（https://github.com/zevlu/ChatReader） |
| **11/20** | **最終版本，以 Docker 容器化封裝** |
| 11/23 | 展示摘要 docx |
| 11/27 | 完成 DGX 部署（展示當天每個項目 2～3 台 DGX，**AI 模型要跑在 DGX 上**） |

### 目標架構（全部在 DGX）
- 畫面：遠端看 DGX 桌面（Sunshine＋Moonlight，腳本 `seanGSISG/dgx-spark-sunshine-setup`；不要同時裝 eelbaz 的腳本，兩者都改 `/etc/X11/xorg.conf`）
- 遊戲：Steam（x86，用 FEX-Emu 轉譯）→ BeamNG.drive **原生 Linux 版**（只有 x86_64、只用 Vulkan）＋ BeamNG.Earth
- AI：Docker 跑 Ollama（或 vLLM）＋ Qwen，取代 iAI Hub
- adb：用 `sudo apt install adb`（Google platform-tools 的 Linux 版只有 x86_64）
- 車機網頁：Chromium（Google Chrome 沒有 Linux arm64 版）
- 手機用 USB 接在 DGX

### 最大風險
BeamNG 在 ARM CPU 上要轉譯，而塞車場景（15 台 AI 交通車）本來就吃 CPU。**11/17 晚上決定**：
- 跑得動 → 全部放 DGX
- 跑不動 → 備案：BeamNG 留在 Windows 實驗室電腦（HP Z2 Mini），把它的 OutGauge IP 改成 192.168.50.17，DGX 跑 AI＋橋接程式

### 階段
1. （11/15～16）基本環境：Sunshine＋Moonlight
2. （11/16～17）Steam（FEX）→ BeamNG 內建地圖 FPS → 開 15 台交通車 FPS → BeamNG.Earth（先確認它的 Linux AppImage 有沒有 arm64）
3. （11/17～18）Ollama in Docker；改手機 App 的 LLM 位址（先讀 `LlmClient.kt`／`DriveBrain.kt` 確認 API 格式，不要猜）
4. （11/18～19）docker-compose（ollama＋bridge，bridge 用 `network_mode: host`）；完整故事跑一次
5. （11/20）README 部署步驟、tag v1.0

## 4. 規則（一定要遵守）

- **不要猜**：遇到錯誤先看實際輸出／log，必要時請使用者提供（例如手機 Logcat、遊戲 log）。
- **API 金鑰**只放 `local.properties`（Android）或 `.env`（Docker），**絕對不要 commit**；已在 `.gitignore` 的不要拿出來。
- **改系統設定（xorg.conf、驅動、BIOS 類）前先說明風險、先備份**，這是實驗室共用機器。
- 改程式前先 `git status`，請使用者 commit 一次再動手；一次只做一個階段，做完提醒使用者 commit＋push。
- Messenger 送出一定要使用者確認（程式只能按說明文字完全等於「傳送」的按鈕，永遠不碰「發送👍」）。
- Android App 的編譯在使用者的 Windows 筆電（Android Studio，Build Variant `driveDebug`）；DGX 上不用編 Android。
- 互動式安裝腳本在 Claude Code 裡可能卡住，盡量用非互動參數（例如 Sunshine 腳本的 `NONINTERACTIVE=1 ... ./install.sh -y`），或請使用者在終端機自己輸入。
