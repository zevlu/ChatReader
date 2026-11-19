# CoDriver 展示操作員小抄（A 台遊戲／B 台車機）

> 列印版：`tools/dgx/operator_cheatsheet.html` 用瀏覽器開 → 列印 → A4 **雙面**一張（正面開場／快速鍵／指令，反面出錯怎麼辦；改內容請改這份 .md，再請 Claude 重新產生 HTML）。
> 指令都在 repo 根目錄 `~/ChatReader` 執行；`bng` = `tools/dgx/bng`。

## 開場（約 7 分鐘）

| 順序 | 在哪 | 做什麼 | 看到什麼才算好 |
|---|---|---|---|
| 1 | B 台 | `docker compose up -d` | `docker compose logs -f codriver-llm` 出現 `Application startup complete.`（約 5 分鐘） |
| 2 | B 台 | `python3 tools/dgx/llm_bench.py --rounds 1` | `意圖正確 12/12` |
| 3 | B 台 | 手機 USB 接上 → 開 CoDriver（adb forward／reverse 由 `codriver-adb-keepalive` 自動補） | `docker compose logs --tail 3 codriver-bridge` 不再出現「✗ 手機沒連上」 |
| 4 | A 台 | 第一次：`echo "RELAY_TO=<B 台 IP>" > ~/codriver-dgx/local.conf` → `tools/dgx/start_codriver.sh` | 最後一行「完成！」（約 2 分鐘） |
| 5 | A 台 | `tail -3 ~/codriver-dgx/logs/relay.log` | 「30 秒內：OutGauge 1600 左右、位置 30 左右」 |
| 6 | B 台 | Chromium 開 `http://localhost:8765/?unit=1` | 地圖上有車子的位置 |
| 7 | A 台 | `tools/dgx/codriver_check.sh`（也會檢查 B 台） | 「全部正常 ✅」 |

## 展示流程（每一輪都走加油站版，約 5 分鐘）

朝新路起點 →（0.7 km）楠都加油站 →（0.67 km）麥當勞；**塞車自動放，不用按**。

| # | 駕駛／CoDriver | 操作員 |
|---|---|---|
| 1 | 上車：副駕說油量 12% 有點低，問要去哪裡 | 每輪開始前按 **Ctrl+Alt+2**（回起點、油 12%、App 重置） |
| 2～3 | 「帶我去跟孟澤約的地方」→ 副駕找到約定、問要不要導航 →「好」 | — |
| 4 | 副駕問要不要先去順路的加油站 →「好」 | 按 **Ctrl+Alt+5** 開自動駕駛（或駕駛自己開） |
| 5 | 往加油站（約 1.5 分鐘） | — |
| 6 | 到加油站：自動加滿（100%）、副駕說「到加油站了」，箭頭換成麥當勞 | — |
| 7 | 開出加油站：前方 200 公尺**自動塞車** | 沒出現車陣才按 **Ctrl+Alt+1** |
| 8～9 | 停 20 秒以上，副駕問要不要通知孟澤 →「送出」 | 塞車 90 秒後**自動解除**；要提早解除按 Ctrl+Alt+1 |
| 10 | 抵達麥當勞：三角錐、黃車、「【已抵達】」 | 下一輪按 **Ctrl+Alt+2** |

短版（不加油，約 3 分鐘）：`bng fuel 100`，出發後自己按 Ctrl+Alt+1 放塞車。

## 遊戲內快速鍵（BeamNG 視窗在前景時）

| 按鍵 | 功能 | 什麼時候用 |
|---|---|---|
| **Ctrl+Alt+1** | 塞車 開／關 | 平常不用按；自動塞車沒出現、或要提早解除時 |
| **Ctrl+Alt+2** | 回起點（油 12%、GPS 指向加油站）＋App 重置 | 每一輪開始前；約 20 秒 |
| **Ctrl+Alt+3** | 把車放回路上 | 撞牆、卡住、翻車 |
| **Ctrl+Alt+4** | 交通車重生 | 交通車跑光了、或都擠在一起 |
| **Ctrl+Alt+5** | 自動駕駛 開／關 | 沒有人開車時讓車子沿導航自己開；要自己開或自動駕駛卡住時再按一次關掉 |

按下後畫面上方會出現一行提示。遊戲原本的鍵：**R** 原地重置、**J** 暫停。

## 常用指令（A 台終端機）

| 指令 | 用途 |
|---|---|
| `bng start` | 回起點（和 Ctrl+Alt+2 一樣，會印出結果；油量設回 12%） |
| `bng fuel 12` ／ `bng fuel` | 油量設成 12%（App 會問要不要先去加油站）／加滿 |
| `bng auto on` ／ `bng auto off` | 自動駕駛開／關（和 Ctrl+Alt+5 一樣） |
| `bng jam on` ／ `bng jam off` | 塞車開／關 |
| `bng gps 楠梓車站麥當勞` ／ `bng gps off` | 遊戲 GPS 導航／取消 |
| `bng road` ／ `bng reset` | 車放回路上／原地重置 |
| `bng traffic 10` ／ `bng traffic off` | 叫 10 台交通車／全部移除（10 台以上 FPS 會掉） |
| `bng day` | 天色變暗時固定回白天 |
| `bng status` | 位置、FPS、OutGauge 目標 |
| `tools/dgx/stop_codriver.sh` → `tools/dgx/start_codriver.sh` | 遊戲整個重開（約 2 分鐘） |

## 出錯怎麼辦

| 狀況 | 先做 | 還是不行 |
|---|---|---|
| 車卡住、翻車、開進奇怪的地方 | Ctrl+Alt+3 | Ctrl+Alt+2 回起點 |
| 路面沒有導航箭頭、或箭頭變成直線 | `bng gps 楠梓車站麥當勞`，等 10 秒 | 網路斷了：路線快取會自動接手（排練時有錄過的路線）；還是直線就在 `local.conf` 加 `EARTH_OFFLINE=1` 重開遊戲。手機熱點接 DGX 的 Wi-Fi 是主案 |
| 塞車了 CoDriver 卻沒開口 | 確認車速低於 10 km/h 已超過 20 秒，前方至少 1 台車 | B 台 `docker compose logs --tail 20 codriver-bridge` 要看到「【塞車】… → 通知副駕」；出現「✗ 手機沒連上」就是手機的問題 |
| 車機網頁的地圖不動、B 台橋接說沒收到 BeamNG 的資料 | A 台 `tail ~/codriver-dgx/logs/relay.log`：OutGauge、位置都是 0 → 轉送沒開，`bng status` 要是 `OutGauge 開 127.0.0.1:4444`，重開 `start_codriver.sh` | 轉送有數字但 B 台收不到 → B 台防火牆的來源 IP 不對（部署清單 2.6） |
| 自動駕駛卡住不動 | Ctrl+Alt+5 關掉，自己開過去 | Ctrl+Alt+3 放回路上 |
| AI 不回答、App 顯示錯誤 | B 台 `python3 tools/dgx/llm_bench.py --rounds 1`（App 會自動偵測模型名稱，不用手動填） | 有錯就 `docker compose restart codriver-llm`（約 3.5 分鐘）；手機那邊重做 `adb reverse tcp:8300 tcp:8300` |
| 不知道哪裡壞 | A 台 `tools/dgx/codriver_check.sh`，照 ❌ 下面那行修 | — |
| 兩台連不起來（轉送、防火牆、B 台壞了） | A 台 `tools/dgx/switch_mode.sh single`（A 台也跑 AI，約 5 分鐘）；筆電通道改連 A 台 | 修好後 `switch_mode.sh dual` |
| 手機斷線 | 重新插 USB；有開 adb_keepalive 會在 10 秒內自動補好 forward／reverse | 手動 `adb forward …`、`adb reverse …`；重開 CoDriver App |
| 遊戲畫面當住或關掉 | `tools/dgx/stop_codriver.sh` → `tools/dgx/start_codriver.sh` | 看 `~/codriver-dgx/logs/game_*.log` 最後幾行 |
| 畫面變暗 | `bng day` | — |

## 不要做

- 不要按 BeamNG.Earth 的「Update now」（版本凍結在 1.9.1-beta）。
- 不要在 Steam 按 STOP；關遊戲用 `stop_codriver.sh`。
- Steam 同一個帳號同時只能在一台跑遊戲：要換 C 台備援，先關 A 台的遊戲。
- **`docker compose` 只在 B 台執行**，不要在 A 台跑（A 台的轉送程式佔住 4444/4445）。誤開了：A 台 `docker compose rm -sf codriver-bridge`，轉送不受影響。
- 不要停或刪除別組（ai-truth-lab）的容器。
