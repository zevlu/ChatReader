# CLAUDE.md — CoDriver（給 Claude Code 看的交接說明）

> 回答一律用**繁體中文**；程式碼註解可以用中文。使用者是高科大學生，不熟 Linux，請一步一步說明、每個指令說清楚在做什麼。
> 最後整理：2026-11-27 晚上（展示當天，會場）。第一版寫於 2026-11-15。

## 1. 這是什麼專案

**CoDriver**：會主動開口、會幫你動手的 AI 副駕駛。上車後從真的 Messenger 聊天找出今天的約定 → 規劃導航 → 路上塞車時主動問「你大概會晚 8 分鐘，要幫你跟孟澤說嗎？」→ 駕駛說「送出」才真的在 Messenger 傳出去。

這個 repo（ChatReader，https://github.com/zevlu/ChatReader）是 Android 專案，有兩個 Product Flavor：
- `drive` = CoDriver（**現在的重點**）
- `friend` = 交友大師（**暫停中，不要動**）
- 共用程式在 `app/src/main/`（改共用程式要兩個版本都顧到）

DGX 部署的完整說明在 **`tools/dgx/README.md`**（安裝、操作、展示路線、量測數據），操作員小抄 `tools/dgx/OPERATOR_CHEATSHEET.md`。這份檔案只放現況和規則，細節以 README 為準。

## 2. 現在的架構（11/27 展示用：兩台 DGX Spark，網路線直連）

```
A 遊戲台 spark-7180（10.10.0.1）                         B 車機＋AI 台 nkust-dgx50（10.10.0.2）
  Steam（背景）＋ BeamNG.drive 0.40.6（FEX 轉譯，-noui）     docker compose：
  ＋ BeamNG.Earth 1.9.1-beta 世界伺服器（ARM 原生）           ├ codriver-llm（vLLM，Qwen3.6-35B-A3B-FP8，127.0.0.1:8300）
  ＋ codriver mod（bng 指令、快速鍵、位置封包）               └ codriver-bridge（network_mode: host，UDP 4444/4445）
     │ 遊戲只送 127.0.0.1（LuaSocket 只准私有位址）                 │ POST /api ← adb forward tcp:8765
     ▼                                                            ▼
  codriver_relay.py ──UDP 4444 OutGauge／4445 位置──▶ 橋接 ──▶ 手機 Galaxy A55（R5CY21RP81B，USB 接 B 台）
                    ◀── GPS 指令（回到位置封包來源）──           CoDriver App ── adb reverse tcp:8300 ──▶ codriver-llm
```

協定重點：
- 橋接 → 手機：`{"type":"vehicle","speed":45,"fuel":72,"game":true}`（每秒）、`{"type":"action","name":"jam"|"jamClear"|"reset"}`
- 導航：手機在狀態裡放 `navReq {id, cmd: plan|cancel|done, dest, via, viaGas, start}`；橋接把遊戲 GPS 先設停靠點（第一段）、遊戲回報抵達後再設終點（第二段）
- 展示路線：朝新路起點 → 楠都加油站（715.6 m）→ 楠梓車站麥當勞（671.9 m），開場油量 12%，加完油自動塞車 90 秒

## 3. 機器現況（11/27）

| 角色 | 主機 | 網路 | 備註 |
| --- | --- | --- | --- |
| A 遊戲台 | **spark-7180**（取代開不了機的 #1 spark-6f5b） | 網路線 enP7s7 **10.10.0.1/24**（nmcli 連線 `codriver-link`，不設 gateway）；上網走會場 Wi‑Fi wlP9s9 | 桌面是 **DISPLAY :1**（Xorg）；**沒有 VS Code**，Claude Code 跑在 DGX 桌面的 GNOME 終端機 |
| B 車機＋AI 台 | nkust-dgx50 | 網路線 enP7s7 **10.10.0.2/24**（`codriver-link`）；Wi‑Fi 192.168.50.161 | `.env`：`CODRIVER_OUTGAUGE_LISTEN=0.0.0.0`；手機 USB 接這台 |

共通：使用者 nkust（有 sudo），DGX OS（Ubuntu 24.04 **arm64**），GPU GB10，CPU／GPU 共用 128 GB 記憶體。

A 台設定：
- `~/codriver-dgx/local.conf`（不進 git）：`GAME_DISPLAY=:1`、`RELAY_TO=10.10.0.2`
- 已關螢幕睡眠／鎖定／自動休眠（gsettings，使用者層級）
- 快取：`~/.cache/beamng-earth/` 3.6 GB；`codriver-routes/` 2 條（起點→加油站 715.6 m、加油站→麥當勞 671.9 m）
- A → B 的 SSH 金鑰已設好（`ssh -o BatchMode=yes nkust@10.10.0.2`），`codriver_check.sh` 會連 B 台一起檢查。11/27 20:45 A＋B 全部 ✅
- git：只在這個 repo 設了 user.name／email（zevlu）；**沒有存 GitHub 登入資料**，push 要使用者在自己的終端機分頁輸入 Personal Access Token（credential cache 1 小時）。11/27 已 push 到 `d425fd3`

### A 台常用指令
```bash
tools/dgx/start_codriver.sh      # 一鍵啟動（Steam → 世界伺服器 → 遊戲 → 起點 → 轉送 → 交通車），首次約 3.5 分鐘
tools/dgx/stop_codriver.sh       # 關遊戲和世界伺服器
tools/dgx/codriver_check.sh      # 健康檢查（A＋B），結束代碼 = ❌ 的數量
tools/dgx/bng                    # 遊戲操作指令一覽（gps、place、face、auto、traffic、jam、start…）
tools/dgx/switch_mode.sh single|dual   # 單機備案（A 台自己跑 LLM＋橋接）
```
遊戲已開著時手動開轉送：`setsid -f python3 -u tools/dgx/codriver_relay.py --to 10.10.0.2 --port 4444 > ~/codriver-dgx/logs/relay.log 2>&1 < /dev/null`，再 `tools/dgx/bng outgauge 127.0.0.1 4444`。

## 4. 規則（一定要遵守）

- **不要猜**：遇到錯誤先看實際輸出／log，必要時請使用者提供（例如手機 Logcat、遊戲 log、tcpdump）。
- **API 金鑰**只放 `local.properties`（Android）或 `.env`（Docker），**絕對不要 commit**；已在 `.gitignore` 的不要拿出來。GitHub token 也不要寫進任何檔案。
- **改系統設定（xorg.conf、驅動、BIOS 類）前先說明風險、先備份**，這是實驗室共用機器。
- 改程式前先 `git status`，請使用者 commit 一次再動手；一次只做一個階段，做完提醒使用者 commit＋push。使用者在現場偏好**不改程式、用設定繞過**，非改不可時改最小、舊行為不變。
- Messenger 送出一定要使用者確認（程式只能按說明文字完全等於「傳送」的按鈕，永遠不碰「發送👍」）。
- Android App 的編譯在使用者的 Windows 筆電（Android Studio，Build Variant `driveDebug`）；DGX 上不用編 Android。
- **要 sudo 或要輸入密碼的指令（sudo、ssh-copy-id、git push）列出來讓使用者自己執行**：請他在 DGX 桌面的終端機按 Ctrl+Shift+T 開新分頁輸入（終端機貼上是 Ctrl+Shift+V）。互動式安裝腳本盡量用非互動參數。

## 5. 還沒解決／要注意

- **GPS 指令 B→A 曾整輪沒送到（原因未找到）**：11/27 20:48 那輪，B 台橋接有送兩段 GPS（目標 10.10.0.1:40672 正確），A 台 `relay.log` 卻沒有任何「🧭 轉回遊戲」，到加油站就沒導航。之後從 B 台手動送測試封包、以及 20:58 那輪都正常（兩段都到）。A 台 ufw 是關的，B→A 網路測試正常。
  - 再發生時先救場：`tools/dgx/bng gps 楠梓車站麥當勞`
  - 再查：`grep 轉回遊戲 ~/codriver-dgx/logs/relay.log | tail`、B 台 `docker logs -t --tail 1500 codriver-bridge | grep -a -E "🧭|🏁|📍"`、遊戲 log 的 `CODRIVER_GPS set`；必要時請使用者 `sudo tcpdump -ni enP7s7 'udp and src host 10.10.0.2'`
- **回起點的繞路路線**：Ctrl+Alt+2 後車頭方向隨機，朝西時 OSRM 給 990 m 繞一圈的路線，而且**會蓋掉 715 m 的快取**（同起終點同檔名）。對策：路線在起點往西北繞，就再按一次 Ctrl+Alt+2；或車頭轉好後再 `bng gps 楠都加油站`。根治要改 `codriver_routecache.py`（不要用較長路線蓋掉短的），使用者還沒決定要不要改。
- FPS：剛叫交通車、剛回起點時會掉到 20～30，穩定後 40～62；停在麥當勞（三角錐＋黃車＋堆積的交通車）也會掉。
- 查 B 台橋接 log 用 `docker logs -t --tail N codriver-bridge`：log 有 30 多萬行，不加 `--tail` 從頭 grep 經 SSH 會不完整；`docker compose logs --since` 也抓不到東西。
- 判斷轉送有沒有在跑用 `ss -uanp | grep 4445`；`pgrep -f codriver_relay.py` 會比對到下指令的 shell 自己。
- 新機器安裝的坑（已寫進 `tools/dgx/README.md`「新機器的注意事項」）：要先 `touch ~/codriver-dgx/local.conf`；Earth v1.9.1-beta 附的 `earthfast.abi3.so` 是 x86-64，要改名後重跑 `install_host.sh` 編 ARM 版；桌面不是 :0 要設 `GAME_DISPLAY`；BeamNG 0.40.6.0 實測可用。

## 6. 歷史（簡要）

- 11/15：從 Windows 實驗室電腦（BeamNG＋學校 iAI Hub 雲端 LLM）搬到 DGX Spark #1 spark-6f5b
- 11/16～18：Steam snap（內建 FEX）＋ BeamNG 原生 Linux 版（CEF 在 FEX 下會當掉，所以 `-noui`，操作改用 `bng`）；本機 LLM 改用 vLLM＋Qwen（docker compose）
- 11/18～20：雙機（#1 遊戲 → #2 nkust-dgx50 車機＋AI），加油站版路線，一鍵重置，健康檢查；11/20 交 Docker 版
- 11/27 展示當天：#1 開不了機 → A 台換 spark-7180，重裝、重建快取，A／B 改用網路線直連（10.10.0.1／10.10.0.2）
