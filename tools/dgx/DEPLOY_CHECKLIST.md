# 會場部署清單（CoDriver on DGX Spark）

本清單在 2026-11-16 整理。「⏳」代表還沒測過，11/19 全環境測試前要完成。

## 1. 外接硬碟要帶的資料

會場的 DGX 是全新的，而且使用者名稱可能不是 nkust。**venv 和腳本裡的路徑會因此不同**，所以建議到現場重跑 `install_host.sh`，不要直接複製 venv。

### A 台（遊戲台）

| 資料夾 | 大小（實測） | 內容 | 必要 |
|---|---|---|---|
| `~/snap/steam/common/.local/share/Steam/steamapps/common/BeamNG.drive` | 53 GB | BeamNG 0.40.5 遊戲檔（Steam 下載太慢，一定要帶） | ✅ |
| `~/snap/steam/common/.local/share/Steam/steamapps/appmanifest_284160.acf` | 1 KB | 讓 Steam 認得已經安裝 | ✅ |
| `~/.cache/beamng-earth/` | 6 GB（11/18 實測，上限 20 GB；`logs/` 不用帶） | **Earth 地圖快取**（高雄的地形、衛星影像、道路、建築） | ✅ |
| `~/codriver-dgx/BeamNG.Earth/` | 0.8 GB | Earth 1.9.1-beta 原始碼＋已編好的 `server/earthfast.abi3.so`（aarch64） | ✅（沒網路時無法 git clone） |
| Steam 本體更新檔 `~/snap/steam/common/.local/share/Steam/package/` | 約 1 GB | Steam 第一次啟動要下載的更新（學校網路限速） | 建議 |
| `~/snap/steam/common/.local/share/BeamNG/BeamNG.drive/current/settings/` | 很小 | 遊戲設定（解析度、垂直同步、OutGauge） | 建議 |
| ChatReader repo | 小 | 腳本、mod、docker-compose | ✅ |

如果要在會場重建 earth-venv，也要帶 Python 套件：`pip download -r server/requirements.txt -d wheels/`。

### B 台（車機台）

| 資料 | 大小 | 內容 | 必要 |
|---|---|---|---|
| `~/codriver-models/Qwen3.6-35B-A3B-FP8/`＋`Qwen3.6-35B-A3B-FP8.sha256` | 37.5 GB | 本機 LLM 模型（11/18 起的預設，Apache-2.0）；用 `tools/dgx/fetch_hf_model.sh` 下載，43 個檔案已和 Hugging Face 的 SHA256 比對過 | ✅ |
| `~/codriver-models/Qwen3-8B/`＋`Qwen3-8B.sha256` | 16.4 GB | 備用模型（35B 出問題時換回來） | 建議 |
| `~/codriver-deploy/codriver-images.tar.zst`＋`.sha256` | 11.1 GB | vLLM 26.10＋python:3.12-slim 映像檔 | ✅ |
| ChatReader repo＋`.env` | 小 | `.env` 不在 git 裡，**要另外帶**（裡面可能有金鑰，不要放公開的地方） | ✅ |
| `adb` 的 .deb（`apt download adb android-libadb …`） | 小 | 會場沒網路時無法 `apt install` | 建議 |

**sha256 怎麼做、怎麼驗**（模型 35 GB、映像檔 11 GB，複製到外接硬碟和從硬碟複製回來都可能壞掉，所以兩邊都要驗）：

```bash
# 出發前（在這台）：產生清單（模型的清單已經做好就不用重做）
cd ~/codriver-models && find Qwen3.6-35B-A3B-FP8 -type f | sort | xargs sha256sum > Qwen3.6-35B-A3B-FP8.sha256
# 複製到外接硬碟後、以及在會場複製到新機器後：驗證（全部 OK 才算數）
cd <放模型的資料夾> && sha256sum -c --quiet Qwen3.6-35B-A3B-FP8.sha256 && echo 模型 OK
cd <放映像檔的資料夾> && sha256sum -c codriver-images.tar.zst.sha256
# 載入映像檔（約 5 分鐘）
zstd -dc codriver-images.tar.zst | docker load
```

`.env` 的 `CODRIVER_MODELS_DIR` 要改成新機器上放模型的資料夾。

### 新機器的使用者名稱不同時（venv 要重建）

`earth-venv` 裡的 Python 路徑是寫死的（例如 `/home/nkust/codriver-dgx/earth-venv/bin/python`），**換了使用者名稱或資料夾，直接複製過去的 venv 會不能用**。腳本本身都用 `$HOME`，不用改。做法：

1. 外接硬碟要帶：`BeamNG.Earth/`（含已編好的 `server/earthfast.abi3.so`，aarch64、abi3，不用重新編譯，也就不用 Rust）、`BeamNG.Earth/wheels/`（Python 套件）。**不要帶 `earth-venv/`。**
2. 出發前在這台準備 wheels（要網路）：
   ```bash
   cd ~/codriver-dgx/BeamNG.Earth && ~/codriver-dgx/earth-venv/bin/pip download -r server/requirements.txt -d wheels
   ```
3. 在新機器上：把 `BeamNG.Earth/` 放到 `~/codriver-dgx/BeamNG.Earth/`，再執行 `tools/dgx/install_host.sh`。
   - 它看到 `wheels/` 就會用 `pip install --no-index --find-links wheels` **離線**建立 venv。
   - 網路修正（`codriver_netfix`）、mod 也會一起裝好。
4. 驗證：`~/codriver-dgx/earth-venv/bin/python -c "import numpy, PIL, duckdb; print('OK')"`，再執行 `tools/dgx/start_codriver.sh`。

Earth 地圖快取 `~/.cache/beamng-earth/`（索引用相對路徑）和 BeamNG 使用者資料夾的設定裡沒有寫死使用者名稱（11/18 檢查過，只有 log 檔有），直接複製就可以。

## 2. 出發前要先測的項目

| # | 測試 | 怎麼測 | 狀態 |
|---|---|---|---|
| 1 | **Steam 離線模式能不能啟動遊戲** | Steam → Steam 選單 → Go Offline，重開 Steam，再執行 `start_codriver.sh` | ⏳ |
| 2 | **Earth 沒有網路時能不能用快取載入高雄** | `run_earth_server.sh --offline`，再執行 `start_codriver.sh` | ✅ 11/18：起點、終點的地形、建築、樹、衛星影像都從快取載入；畫面上方會出現黃色提示「internet data sources unreachable」。**但導航路線（OSRM）要網路**：離線時遊戲箭頭變成直線指向目的地，車機網頁的路線、真實到達時間、轉彎提示退回 15 分鐘備援 |
| 3 | 展示路線整條都有快取 | 有網路時沿著展示路線開一遍，讓 Earth 把沿途資料抓進快取 | ⏳ |
| 4 | 雙機 OutGauge＋位置（A → B） | A 台設 `OUTGAUGE_HOST=<B IP>`，B 台 `.env` 設 `CODRIVER_OUTGAUGE_LISTEN=0.0.0.0`。防火牆：B 台開 UDP 4444（OutGauge）、4445（位置／GPS）給 A；A 台開 UDP 4446（GPS 指令）給 B | 單台模擬已通過；兩台⏳ |
| 5 | 手機接 B 台：`adb forward 8765`＋`adb reverse 8300`，App 連本機 LLM | 跑完整故事 | ⏳ |
| 6 | 60 分鐘穩定性（`full` 情境） | `power_mem_monitor.py --label full --duration 3600` | ⏳ |
| 7 | 新的 DGX 從零安裝一遍（照 README） | 計時，確認沒有漏掉的步驟 | ⏳ |

## 3. 會場當天的開機順序

1. B 台：`docker compose up -d` → 用 `docker compose logs -f codriver-llm` 等到 ready → 執行 `python3 tools/dgx/llm_bench.py --count 3`
2. B 台：手機 USB 接上 → 執行 `adb forward tcp:8765 tcp:8765`、`adb reverse tcp:8300 tcp:8300` → 開 CoDriver
3. A 台：`tools/dgx/start_codriver.sh` → 執行 `bng status`，確認 OutGauge 指向 B 台
4. B 台：`docker compose logs -f codriver-bridge`，要看到「📥 收到 <A 台 IP> 送來的 OutGauge」和「✅ 手機收到了」
5. 車機網頁：`http://localhost:8765/?unit=1`（Chromium）

## 4. 已知限制與注意事項

- BeamNG 沒有遊戲內選單：常用操作用遊戲內快速鍵（Ctrl+Alt+1 塞車開關、2 回起點、3 車放回路上、4 交通車重生），其他用 `bng`。現場操作員要先熟悉 [OPERATOR_CHEATSHEET.md](OPERATOR_CHEATSHEET.md)（列印版 `operator_cheatsheet.html`）。
- 交通車最多 10～12 台，15 台會掉到 15 FPS。
- 版本要凍結：BeamNG 0.40.5、Earth 1.9.1-beta（已經有 1.10.1，**不要按 Update now**）、vLLM 26.10。
- Steam 同一個帳號同時只能在一台跑遊戲，C 台當備援時要先關 A 台。
- 不要動 ai-truth-lab 的容器（如果會場的機器也有的話）。
- **遊戲 GPS 的路線規劃要網路**：BeamNG.Earth 用線上的 OSRM（routing.openstreetmap.de）。沒網路時路面箭頭會改成直線指向目的地。
  - 車機地圖的圖磚（OpenStreetMap）和 Leaflet 也要網路。
  - 地名換座標先查 `tools/beamng/places.json`（不用網路），展示會用到的地點要事先加進去。
- **離線備案**（11/18 實測）：地圖可以只用快取，但導航路線一定要網路。會場網路不穩時的選項：
  - (a) DGX 的 Wi-Fi（wlP9s9）接手機熱點，只給路線規劃用，流量很小
  - (b) 自架 OSRM（docker osrm-backend＋台灣地圖），把 Earth 的 OSRM_URL 指到本機。比較費工，要另外評估
- **網路不能依賴**：實驗室的 DNS（1.1.1.1／8.8.8.8）在學校網路下偶爾整段失敗，2026-11-16 曾讓所有下載中斷。
  - 模型、映像檔、遊戲檔、Earth 快取都要事先放在外接硬碟。
  - 在現場只能做「載入」，不能做「下載」。
