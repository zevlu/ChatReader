# CoDriver on DGX Spark：部署說明

這份說明 CoDriver 展示在 NVIDIA DGX Spark 上的部署方式。DGX Spark 是 Ubuntu 24.04 arm64，GPU 為 GB10，CPU 和 GPU 共用 128 GB 統一記憶體。

## 三台 DGX 的分工

| 台 | 角色 | 跑什麼 | 部署方式 |
|---|---|---|---|
| **A 遊戲台** | 駕駛看的畫面 | Steam（背景）、BeamNG.drive 0.40.5 原生 Linux 版（FEX 轉譯）、BeamNG.Earth 1.9.1-beta 世界伺服器 | 主機層（本資料夾的腳本），**不用 Docker** |
| **B 車機台** | 手機 USB 接這台 | `codriver-llm`（vLLM，本機 LLM）、`codriver-bridge`（OutGauge → 手機） | repo 根目錄的 `docker-compose.yml` |
| **C 台** | AI 看板／展示影片／備援 | 之後的 `codriver-monitor`；需要時當 A 或 B 的備援 | — |

資料流：

```
A：BeamNG ──OutGauge UDP（車速、油量）──▶ B：codriver-bridge ──POST /api──▶ 手機 CoDriver（adb forward 8765）
                                                                         │
                                         B：codriver-llm（:8300）◀──────┘ adb reverse 8300（App 的 IAI_BASE）
```

單機模式（A＋B 在同一台，開發／測試用）：全部用 127.0.0.1。

**改成雙機只要改兩個設定：**
1. A 台 `tools/dgx/codriver-dgx.conf`：`OUTGAUGE_HOST=<B 台 IP>`，再執行 `tools/dgx/bng outgauge`。
2. B 台 `.env`：`CODRIVER_OUTGAUGE_LISTEN=0.0.0.0`，再執行 `docker compose up -d`。

B 台的防火牆（ufw）要開放 A 台送來的 UDP 4444，指令是 `sudo ufw allow from <A 台 IP> to any port 4444 proto udp`。

> Steam 同一個帳號同時只能在一台電腦跑遊戲。C 台當 A 台備援時，要先把 A 台的遊戲關掉。

---

## A 台：安裝（第一次）

### 1. 手動步驟（需要 sudo 或要人操作）

1. **遠端畫面**：用 [seanGSISG/dgx-spark-sunshine-setup](https://github.com/seanGSISG/dgx-spark-sunshine-setup) 安裝 Sunshine，非互動模式加 `-y`。
   - 這個腳本會改 `/etc/X11/xorg.conf` 和 GDM 設定，**動手前先備份**。
   - 不要同時裝 eelbaz 的腳本。
   - 1080p 是用 `~/.config/monitors.xml` 強制的。
2. **Steam**：`sudo snap install steam`。這是 Canonical 的 arm64 Steam snap，內建 FEX。
   - 第一次在桌面啟動 Steam，問設定時選 **Use default config**，然後登入。
   - 學校網路對 Steam 每條連線限速約 30 KB/s，Steam 本體更新很慢，可以預先下載更新檔。
3. **BeamNG 遊戲檔**：從已經裝好的 Windows 電腦複製，比 Steam 下載快很多。
   - 把 `steamapps\common\BeamNG.drive`（約 53 GB）和 `steamapps\appmanifest_284160.acf` 放到 `~/snap/steam/common/.local/share/Steam/steamapps/`。
   - Windows cmd 用 `sftp`，在 sftp 裡下 `put -a -r` 可以續傳；遠端要先建立 0 byte 的同名檔案。
4. **Steam 不自動更新 BeamNG**：在媒體庫對 BeamNG 按右鍵 → 內容 → 更新 → 「Wait until I launch the game」。
   - 目前版本是 0.40.5，Earth 1.9.1 需要 0.40.x。
   - 我們不從 Steam 按 Play，所以不會觸發更新。

### 2. 自動步驟

```bash
cd ~/ChatReader
tools/dgx/install_host.sh
```

這個腳本不用 sudo，可以重複執行，會做這些事：
- BeamNG 的 Linux 執行檔加上執行權限
- FEX 的 BeamNG 專用設定（TSO 關閉）
- 「使用我的設定」標記，Steam 啟動時就不會跳出詢問視窗
- BeamNG.Earth：原始碼、ARM 原生 venv、網路修正，以及用使用者層級的 Rust 編譯 `earthfast`
- 安裝 CoDriver 的 BeamNG mod

執行期的大檔案放在 `~/codriver-dgx/`，這個資料夾不進 git。

---

## A 台：操作

```bash
tools/dgx/start_codriver.sh                  # 一鍵啟動，約 40 秒到高雄（設定在 codriver-dgx.conf）
tools/dgx/start_codriver.sh 高科大建工 8      # 指定起點和交通車台數
tools/dgx/stop_codriver.sh                   # 關遊戲和世界伺服器（不要用 Steam 的 STOP）
tools/dgx/bng                                # 列出所有操作指令
```

`bng` 的常用指令：

| 指令 | 作用 |
|---|---|
| `bng place <地點>`、`bng tp <緯度> <經度>` | 傳送 |
| `bng car <車型>` | 換車 |
| `bng traffic <台數｜off>` | 叫出或移除交通車 |
| `bng jam on [公尺] [台數]`／`bng jam off` | 沿導航路線前方（預設 400 公尺）放故障車擋住同方向所有車道，交通車在後面排隊／移走、車流恢復 |
| `bng face` | 把車頭轉向導航路線（傳送後方向是隨機的） |
| `bng gps <地點>` | 遊戲 GPS 導航（App 規劃導航時橋接會自動設） |
| `bng outgauge` | 套用 OutGauge 設定 |
| `bng panel` | 叫出 Earth 資訊面板 |
| `bng road` | 卡住時回到最近的道路 |
| `bng status` | 查看目前狀態 |

遊戲裡的按鍵：**J** 暫停、**R** 重置、**C** 換鏡頭、**Shift+C** 自由鏡頭。

**CoDriver 快速鍵**（codriver mod 的 `codriverHotkeys`，展示時不用切到終端機；按下後畫面上方會出現提示）：

| 按鍵 | 作用 |
|---|---|
| **Ctrl+Alt+1** | 塞車開／關（開：導航路線前方約 450 公尺，離終點太近時自動縮短） |
| **Ctrl+Alt+2** | 回起點：停自動駕駛、清標記與塞車 → 傳送到 `START_PLACE` → GPS 設到 `START_GPS` → 車頭對準路線（約 20 秒） |
| **Ctrl+Alt+3** | 把車放回最近的道路 |
| **Ctrl+Alt+4** | 交通車重新分散到附近；沒有交通車時叫出 `START_TRAFFIC` 台 |

起點、目的地由 `start_codriver.sh` 執行 `bng hotkeys` 從 `codriver-dgx.conf` 帶進遊戲。按鍵定義在 mod 的 `lua/ge/extensions/core/input/actions/codriver.json`，預設綁定在 `settings/inputmaps/keyboard_codriver.json`（BeamNG 原本沒用到 Ctrl+Alt+數字）。操作員小抄：[OPERATOR_CHEATSHEET.md](OPERATOR_CHEATSHEET.md)。

### 為什麼沒有遊戲選單

BeamNG 內建的 CEF（Chromium 150）在 FEX 轉譯下 renderer 會 segfault。這是新版 CEF 的 FD 處理跟 FEX 不合，加 `-nosandbox` 或 FEX `Multiblock=0` 都沒用。

所以 BeamNG 用 `-noui` 執行，連帶有這些狀況：
- 3D 畫面元件改由 `start_codriver.sh` 用 Lua 建立
- 選單、HUD、小地圖、radial 選單、Earth 的大地圖（M）都不能用
- 操作全部改用 `bng`，或 Earth 的 imgui 資訊面板

`bng` 是怎麼控制遊戲的：`tools/dgx/beamng_mod/codriver` 這個 mod 每 0.5 秒讀一次使用者資料夾的 `codriver_cmd.lua`，執行完把結果寫進 `codriver_result.txt`。
- 不用 `-luastdin`：它會讓主執行緒每一幀都停下來等輸入。
- 使用者資料夾根目錄的鬆散檔案，會在啟動時被 BeamNG 的 folderCleanup 搬走，所以 mod 一定要放在 `mods/`。

---

### 展示路線（11/17 定案）

| 項目 | 內容 |
|---|---|
| 起點 | 清豐二路起點（22.735697, 120.329616） |
| 終點 | 楠梓車站麥當勞（22.727135, 120.326549），約 1.5 公里、3 分鐘 |
| 路線 | 清豐二路 →左轉 東寧路 →右轉 旗楠路 →直行 楠梓新路 →左轉 建楠路 →左轉 建楠路164巷 |

- `start_codriver.sh` 預設就是這條路線，並叫出 10 台交通車。
- 塞車：開出去後執行 `bng jam on 450`，塞車點在剛右轉進旗楠路的地方。
- 抵達：剩 350 公尺時，Earth 判定抵達的位置右側路緣會出現 3 個三角錐和一台黃色的車。抵達時畫面上方顯示「【已抵達】…」（imgui；`api.notify` 走 CEF，-noui 看不到）。
- 橋接的塞車條件：低速 20 秒，而且前方 40 公尺內至少 2 台同方向的車（`CODRIVER_JAM_CARS`）；`jam on` 中放寬成 1 台。三角錐、標記車不算前車。
- 實測：高雄＋10 台交通車＋塞車，FPS 平均 59.4（最低 43.8），GPU 37／42 W。

## B 台：docker-compose

```bash
cd ~/ChatReader
cp .env.example .env            # 第一次；依需要改 .env（金鑰只放 .env，不會進 git）
docker compose up -d
docker compose logs -f codriver-llm      # 第一次載入模型要幾分鐘
python3 tools/dgx/llm_bench.py --count 5 # 測試回答與延遲
```

- `codriver-llm` 使用 `nvcr.io/nvidia/vllm:26.10-py3`（arm64）和 `~/codriver-models/<模型>`。
- 對外的模型 id 預設是模型資料夾名稱，**現在用 `Qwen3.6-35B-A3B-FP8`**，App 設定頁的「模型名稱」填這個（填錯會 404）。備用 `Qwen3-8B`：`.env` 改 `CODRIVER_LLM_MODEL=Qwen3-8B`、`CODRIVER_LLM_GPU_MEM=0.25`，再 `docker compose up -d codriver-llm`。
- App 用 `chat_template_kwargs.enable_thinking=false` 關閉 Qwen3 的思考，vLLM 原生支援。
- 原本的 iAI Hub 模型 `Qwen/Qwen3.8-Flash-Next` 有 1800 億參數、360 GB，DGX 放不下，所以改用本機模型。
- 下載模型：`tools/dgx/fetch_hf_model.sh Qwen/Qwen3.6-35B-A3B-FP8 ~/codriver-models/Qwen3.6-35B-A3B-FP8`（35 GB；備用的 `Qwen/Qwen3-8B` 16 GB）。
  - 這支腳本用 curl 逐檔續傳，斷線或 DNS 失敗會自動重試，不會從頭來。
  - 學校網路下，Hugging Face 官方的 `hf download` 會卡在 xet，而且斷線就從頭下載。
- vLLM 映像檔約 11 GB（解壓後 39 GB）。學校網路 DNS 偶爾失敗，`docker pull` 要包在重試迴圈裡跑。

手機 USB 接 B 台，要先 `sudo apt install adb`（Google platform-tools 沒有 Linux arm64 版）：

```bash
adb forward tcp:8765 tcp:8765   # 橋接 → 手機 DashServer
adb reverse tcp:8300 tcp:8300   # 手機 → codriver-llm
```

App 的 `local.properties`：`IAI_BASE=http://127.0.0.1:8300/v1`。`IAI_KEY` 要跟 `.env` 的 `CODRIVER_LLM_KEY` 一樣；`.env` 沒設金鑰時填任意字串。iAI Hub 可以保留當備援，改回原本的 `IAI_BASE` 就能切回去。

這台 DGX 同時有 ai-truth-lab 的容器，包括它自己的 Ollama。所以：
- 本專案的容器一律叫 `codriver-*`，port 用 8300
- **不要停或刪除它們的容器**
- 重開機前要先通知對方

---

## 量測（功耗、記憶體、LLM 延遲）

```bash
python3 tools/dgx/power_mem_monitor.py --label game10 --duration 600 \
  --group 'beamng=BinLinux/BeamNG\.drive\.x64' \
  --group 'codriver_llm=vllm serve|VLLM::EngineCore'
python3 tools/dgx/llm_bench.py --duration 300 --quiet   # llm 情境時同時跑
python3 tools/dgx/llm_bench.py --duration 1200 --interval 10 --quiet    # 展示頻率：每 10 秒一句
python3 tools/dgx/llm_bench.py --duration 600 --concurrency 4 --quiet   # 壓力測試：4 個連線同時連續發
```

依展示分工一次量完（無人值守約 90 分鐘：單機全開 30 → A 台角色 15 → idle 3 → B 台角色 30，最後重新開遊戲）：

```bash
tools/dgx/measure_roles.sh            # 結果：logs/power_mem/*_summary.txt、~/codriver-dgx/logs/roles_<時間>/
```

- 一定要加 `beamng=...` 這組：Earth 伺服器的指令列也含「BeamNG」（`BeamNG.Earth/launcher.py`），不加會被算進 beamng。
- `codriver_llm=...` 這組只抓我們的 vLLM，ai-truth-lab 的 Ollama 留在原本的 `llm` 組。
- 這台沒有 hwmon 功耗感測器，`gpu_power_w` 只是 GPU 的功耗，整台要用插座電表量。
- 記憶體以 `/proc/meminfo` 為準：統一記憶體下 nvidia-smi 的 Memory-Usage 會顯示 Not Supported。

---

## 實測數據（2026-11-16～18，A＋B 單機）

| 情境 | 遊戲 FPS | GPU 平均／最大 | GPU 最高溫 | 已用記憶體（減 idle 5.4 GB） |
|---|---|---|---|---|
| idle | — | 10.5／11.1 W | 40°C | 基準 |
| 高雄，無交通車 | 60（垂直同步） | 34.5／39.0 W | 58°C | +19.3 GB |
| 高雄，10 台交通車＋塞車 | 60（最低 54） | 46.9／57.2 W | 76°C | +21.3 GB |
| llm（Qwen3-8B 連續請求，沒有遊戲） | — | 33.6／39.7 W | 59°C | +33.6 GB（vLLM 佔 28.2 GB） |
| full（60 分鐘，遊戲＋Earth＋LLM＋橋接＋手機，A＋B 同一台） | 60 | 33.7／52.0 W | 64°C | 平均 +54.8、最大 +58.4 GB |
| **full_auto（60 分鐘無人值守，11/18 01:18～02:18）**：高雄＋10 台交通車，自動駕駛 11 輪（每輪塞車 75 秒→解除→抵達），每輪 12 句 LLM 請求 | 平均 53.4（P5 32.7、最低 24） | 37.4／44.4 W | 64°C | 平均 +63.4、最大 +64.8 GB |

full_auto 期間的狀況（11/18）：
- 遊戲連續跑了 14 小時 43 分沒有當機；11 輪全部開到終點。
- LLM 132 次請求意圖 100% 正確，延遲平均約 4.5 秒（跟遊戲同一台；B 台單獨跑約 2.5 秒）。
- 橋接送出 14 次 jam。
- FPS 的低點都出在每輪開頭：傳送回起點、交通車重新分布、生成故障車。開車途中都在 55～60。
- 記憶體：BeamNG 約 9.5 GB、Earth 伺服器約 21 GB（RSS 加總）、vLLM 28 GB＋5 GB、Steam 5 GB；整台最大 70.2 GB／121.7 GB，swap 幾乎沒用。

full（11/17，手機中途斷線）期間的狀況：
- 遊戲連續跑超過 3 小時，沒有當機。
- 記憶體只在開到新區域時增加（Earth 載入新地圖：54.5 → 60.9 → 63.1 GB），停在原地時持平。
- 手機中途斷線約 40 分鐘，所以 LLM 只被呼叫 9 次，其中 2 次是 App 模型名稱填錯的 404。

codriver-llm（Qwen3-8B，vLLM 26.10）使用 App 真正的 ROUTER_PROMPT（輸入約 1070 tokens，輸出約 33 tokens）：
- **只跑 LLM（B 台的情況）**：122 次請求，意圖 100% 正確，延遲平均 2.47 秒、P95 3.1 秒
- 跟遊戲同一台（單機）：延遲平均 4.3 秒，所以展示時遊戲和 LLM 要分開在 A、B 兩台

### 依展示分工（11/18 10:00～11:34，`measure_roles.sh`，LLM＝Qwen3.6-35B-A3B-FP8）

| 角色 | 內容 | GPU 平均／最大 | GPU 最高溫 | CPU 平均／最大 | 整台記憶體最大（減 idle 5.5 GB） | FPS 平均（P5、最低） |
|---|---|---|---|---|---|---|
| idle | 遊戲、Earth、Steam、LLM 全關（別組容器照常） | 11.0／11.5 W | 44°C | 1.4／4.2% | 5.5 GB | — |
| roleA（A 台） | 遊戲＋10 台交通車，自動跑 4 輪（塞車→解除→抵達），15 分鐘 | 35.1／39.3 W | 61°C | 26.8／41.4% | 27.9（+22.4）GB | 57.5（40.6、23.9） |
| roleB（B 台） | 35B＋橋接；前 20 分鐘每 10 秒一句，後 10 分鐘 4 連線壓力 | 19.4／38.6 W（展示頻率 12.1／28.6、壓力 34.0／38.6） | 63°C | 4.0／13.9% | 57.3（+51.8）GB | — |
| full（單機備案） | 全部同一台，自動跑 7 輪，30 分鐘 | 35.9／46.6 W | 63°C | 27.2／42.2% | 78.3（+72.8）GB | 55.9（34.0、23.2） |

- LLM：roleB 展示頻率 120/120、平均 1.02 秒（P95 1.35）；roleB 壓力 987/987、平均 2.44 秒（P95 3.50）、每分鐘 98.5 句；full 84/84、約 1.63 秒。
- 記憶體分項（RSS）：BeamNG 7.4～7.7 GB、Earth 伺服器 9.5 GB、Steam 5.1 GB；vLLM 保留 44.5 GB（統一記憶體）＋行程 7.6 GB。
- 整機功耗量不到（`nvidia-smi -q -d POWER` 的 Module Power Readings 是 N/A；DGX Dashboard 只有 GPU 使用率和記憶體），以原廠變壓器額定 240 W 為上限，另用插座型電表量。
- 原始資料：`logs/power_mem/{full,roleA,idle,roleB}_20261118_*`、`~/codriver-dgx/logs/roles_1118_1000/`。

### 模型比較（11/18，遊戲同時在跑，App 真正的提示詞，temperature 0、enable_thinking=false）

| | Qwen3-8B（BF16） | **Qwen3.6-35B-A3B-FP8（MoE，現在的預設）** |
|---|---|---|
| 指令判斷 | 24/24，平均 4.28 秒、P95 5.48 秒、7.7 tok/s | 24/24，平均 **1.65 秒**、P95 2.24 秒、18.5 tok/s |
| 路由（car／app） | 8/8，0.86 秒 | 8/8，**0.51 秒** |
| 模型檔／載入 | 16 GB／約 2.5 分鐘 | 35 GB（權重佔 34.5 GiB）／約 5 分鐘 |
| `CODRIVER_LLM_GPU_MEM` | 0.25 | 0.40（KV 快取 6.8 GiB，約 19.7 萬 tokens） |
| 整台已用記憶體（遊戲＋10 台交通車＋LLM） | 約 56 GB | 約 74 GB／121.7 GB |

- 35B-A3B 是 MoE，每個 token 只動用約 30 億參數，所以比 8B 還快；回答也比較自然。
- LLM 回答的那幾秒，同一台的遊戲 FPS 會從 60 掉到 36～44（搶同一顆 GPU），回答完就回到 60。展示時遊戲在 A 台、LLM 在 B 台就沒有這個問題。

交通車數量對 FPS 的影響（west_coast_usa 地圖）：10 台約 90 FPS、12 台約 80 FPS、**15 台只剩 12～20 FPS**。所以交通車最多用到 10～12 台。
