# App 修改規格：車機地圖、真實到達時間、取消傳訊息（2026-11-17）

給在 Windows 上改 App 的 Claude 讀。DGX 那一端（遊戲 mod、橋接程式）已經做好並測過，**這份只改 App**：
`CarMate.kt`、`dash.html`（drive flavor）、`MessengerAgent.kt`（共用，要用開關，不能影響 friend flavor）。

請遵守 repo 的 CLAUDE.md，重點如下：
- 回答用繁體中文。
- friend flavor（交友大師）暫停中，**行為不能變**。
- Messenger 只能按說明文字完全等於「傳送」的按鈕，永遠不碰「發送👍」。
- 不加 Gradle 依賴，不加 API 金鑰。
- 只在 `?unit=1`（只有車機）模式改網頁，其他模式維持原樣。

---

## 0. 背景

在 DGX 上，BeamNG 的 CEF 介面不能用，所以遊戲內的小地圖和 M 大地圖都沒有。駕駛只能看遊戲 GPS 的路面箭頭，轉錯方向很難發現。

現在 `dash.html` 的 `?unit=1` 有兩個限制：
- 到達時間固定用 15 分鐘估算。
- 抵達要工作人員按 Shift+D。

DGX 那一端已經做好的部分：
- **遊戲裡的 codriver mod** 每秒送出車子的經緯度、方向，以及 BeamNG.Earth GPS 的剩餘距離、剩餘時間、是否抵達。路線改變時，會送出路線座標。
- **橋接程式**（`tools/beamng/beamng_bridge.py`）把這些資料用 `POST /api` 轉給手機，格式見下一節。
- **橋接程式每秒讀手機的 `GET /state`**：
  - 看到 `nav.cmd == "plan"`，就把 `nav.dest` 地名換成座標（先查 `tools/beamng/places.json`，查不到再問 OpenStreetMap），叫遊戲把 GPS 設過去。
  - `nav.cmd` 是 `cancel` 或 `done` 時，就取消遊戲 GPS。
  - **所以 App 不用做任何事，遊戲 GPS 就會跟著 App 的導航走。**

## 1. 橋接送給手機的新訊息（POST /api，Content-Type: text/plain，JSON）

```jsonc
// 每秒一次（遊戲在跑、Earth 世界就緒時）
{"type":"pose",
 "lat":22.72497, "lon":120.29689,        // 遊戲裡車子的經緯度
 "heading":292.3,                        // 車頭方向（度，0 = 北，順時針）
 "speedKmh":35.2,
 "gps": {"name":"楠梓車站麥當勞", "lat":22.727135, "lon":120.326549,
         "remainingM":4516.9,            // 沿路線剩幾公尺
         "etaS":475.2,                   // 剩幾秒（規劃中時是 null）
         "planning":false},              // true = 路線還在規劃
        // 沒有 GPS 目的地時，gps 是 null
 "arrivedSeq":0,                         // 遊戲 GPS「抵達」的累計次數；每抵達一次 +1
 "arrivedName":""}                       // 最後一次抵達的地點名稱

// 路線改變時，之後每 30 秒重送一次
{"type":"route", "version":"4", "distM":4812, "durS":489,
 "points":[[22.72497,120.29689],[22.72510,120.29702], ...]}   // 最多約 400 點；沒有路線時 version = "none"、points = []
```

## 2. CarMate.kt（drive）

1. 新增兩個欄位：
   ```kotlin
   private var gamePose: JSONObject? = null     // 橋接送來：遊戲裡的位置＋GPS
   private var gameRoute: JSONObject? = null    // 橋接送來：遊戲 GPS 的路線
   ```
2. 在 `fun onDash(j: JSONObject)` 的 `when (j.optString("type"))` 加兩個分支。只推網頁，不要呼叫 `update()`：`update()` 會讓手機畫面每秒重畫。
   ```kotlin
   "pose" -> { gamePose = j; if (DashServer.connected) DashServer.push(stateJson()) }
   "route" -> { gameRoute = j; if (DashServer.connected) DashServer.push(stateJson()) }
   ```
3. 在 `stateJson()` 加：
   ```kotlin
   gamePose?.let { put("pose", it) }
   gameRoute?.let { put("route", it) }
   ```
4. `resetForDemo()` 裡把 `gamePose`、`gameRoute` 設回 null（選做）。

## 3. dash.html（只在 `UNIT == true` 時）

1. **載入 Leaflet**，只在 UNIT 模式。用固定版本：
   - `https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css`
   - `https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js`

   可以在 JS 裡動態加 `<link>`／`<script>`。網頁原本就用 cdnjs 載入 three.js，所以會場一樣需要網路。
2. **地圖放在左邊「🧭 導航」那個 panel**：在 `#eta` 上方加一個 `<div id="map">`，高度約佔 panel 的 6～7 成。UNIT 模式不需要 `#mini`、`#chats`，原本就隱藏。
   - 圖磚：`https://tile.openstreetmap.org/{z}/{x}/{y}.png`，attribution 寫 `© OpenStreetMap contributors`。
   - **車子**：一個會依 `pose.heading` 旋轉的箭頭圖示（`L.divIcon`，CSS `transform: rotate(...)`）。
   - **地圖跟著車子走**：zoom 約 16。使用者拖曳地圖後暫停跟隨，10 秒後恢復。
   - **路線**：`state.route.version` 改變時，用 `route.points` 重畫 `L.polyline`；`version == "none"` 時清掉。
   - **目的地**：`pose.gps` 有值時，在 `gps.lat/lon` 放一個標記。
   - 地圖初始化時 div 可能還沒有大小，記得呼叫 `map.invalidateSize()`。
3. **到達時間改用真實資料**，改 `unitEta()`／`unitNavTick()`：
   - 條件：有最近 5 秒內的 `pose`、`pose.gps` 不是 null、`gps.etaS` 不是 null，而且 `unav.active` 或 `unav.dest` 有值。
     - `minutes = Math.max(1, Math.round(gps.etaS / 60)) + unav.delay + unav.jam`。塞車延誤照原本的方式加上去，「會晚幾分鐘」才算得對。
     - `km = gps.remainingM / 1000`，四捨五入到 0.1。
     - `arrival` = 現在時間 + minutes。
   - 不符合條件就**維持原本的 15 分鐘估算**，當作備援。
   - 原本每 2 秒 `post({type:'nav', status:'progress', minutesLeft, kmLeft, arrival})` 的機制不變，只是數字換成真的。
   - `gps.planning == true` 時，畫面顯示「路線規劃中…」。
4. **自動抵達**：
   - 導航開始的那一刻（`unav.active` 從 false 變 true），記下 `unav.arrivedSeq0 = pose?.arrivedSeq ?? 0`。
   - 之後每次收到 state，如果 `unav.active && pose.arrivedSeq > unav.arrivedSeq0`，就呼叫原本的 `unitArrive()`。
   - Shift+D／Shift+V 保留，當作手動備援。
   - 中途停靠點（via）目前遊戲 GPS 不支援，只設最後目的地，停靠點仍用 Shift+V。
5. **提示文字**：把「🎮 請在 BeamNG 按 M，把 GPS 設到…」換掉。
   - `pose.gps` 有值：顯示「🗺 遊戲 GPS 已設定，照路面藍色箭頭開」。
   - 導航中但 `pose.gps` 是 null：顯示「遊戲 GPS 還沒設定（地名可能查不到）」。

## 4. 說「取消」時清掉輸入框、回到車機

目前的問題：`MessengerAgent.askConfirm()` 取消時呼叫 `finish("已取消：文字留在輸入框，你可以自己修改或送出")`，文字會留在 Messenger。CoDriver 要的是清掉文字並回到車機。

1. **MessengerAgent.kt（共用）**：新增開關，預設 false，所以 friend 不受影響。
   ```kotlin
   /** drive flavor：使用者取消傳送時，把輸入框清空（friend 維持「文字留著給使用者自己改」） */
   @Volatile var clearOnCancel = false
   ```
   在 `askConfirm()` 裡把 `cancel` 改成：
   ```kotlin
   val cancel: () -> Unit = {
       if (clearOnCancel) {
           Thread({
               guarded {
                   val e = root(service)?.let { findEdit(it) }
                   val ok = e != null && setText(e, "")      // 只清文字，不按任何按鈕
                   finish(if (ok) "已取消：已清掉輸入框，沒有送出" else "已取消：沒有送出（輸入框沒清掉，請手動刪除）")
               }
           }, "MessengerAgentCancel").start()
       } else {
           finish("已取消：文字留在輸入框，你可以自己修改或送出")
       }
   }
   ```
2. **CarMate.kt（drive）**：
   - 初始化時，也就是設定 `MessengerAgent.onAsk = ...` 的地方，加上 `MessengerAgent.clearOnCancel = true`。
   - 在 `onAgentStatus(msg)` 的 `when` 加一個分支，放在 `msg.startsWith("❌")` 前面：
     ```kotlin
     msg.startsWith("已取消") -> {
         log("✉️ 已取消，沒有送出")
         say("好，沒有送出。")
         main.postDelayed({ if (navigating && !webMode) launchPackage(MAPS) else bringCarMateFront() }, 1200)
     }
     ```

## 5. 怎麼測

DGX 那一端已經在跑：遊戲、`codriver-bridge` 容器、`codriver-llm`。手機透過筆電的 SSH 通道連進來（`adb forward 8765`、`adb reverse 8300`）。

| # | 動作 | 預期 |
|---|---|---|
| 1 | 打開 `?unit=1` | 左邊出現地圖，車子箭頭在遊戲裡的位置，方向跟遊戲一致 |
| 2 | 「帶我去跟孟澤約的地方」→ 好（規劃） | DGX 橋接 log 出現「🧭 App 導航到「…」→ 遊戲 GPS 設到 …」；地圖出現藍色路線和目的地；遊戲路面出現箭頭 |
| 3 | 好（開始導航）→ 開車 | 到達時間是真的（例如約 8 分鐘），開近就變少 |
| 4 | 停車 10 秒（或在 DGX 執行 `tools/dgx/bng jam on`） | 副駕問要不要通知對方；到達時間加上延誤 |
| 5 | 說「取消」 | Messenger 輸入框被清空，回到車機畫面 |
| 6 | 開到目的地（遊戲 GPS 結束） | **不用按 Shift+D**，網頁自動顯示已抵達、App 收到 arrived |

在 DGX 看橋接 log：`docker logs -f codriver-bridge`。
- 「🧭」：導航同步
- 「🗺」：路線送出
- 「🏁」：遊戲 GPS 抵達

如果目的地查不到，在 DGX 的 `tools/beamng/places.json` 加上這個地點，橋接不用重啟，下一次導航就會讀到。
