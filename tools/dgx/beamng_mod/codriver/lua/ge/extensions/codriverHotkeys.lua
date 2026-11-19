-- CoDriver：操作員快速鍵（展示時不用切到終端機打 bng）
--   Ctrl+Alt+1  塞車 開／關（開：導航路線前方擋住車道、交通車排隊；關：移走故障車）
--   Ctrl+Alt+2  回起點（停自動駕駛、清標記與塞車、油量設成 START_FUEL → 傳送 → 等地圖 → 設 GPS → 等路線 → 車頭對準 → 關面板）
--   Ctrl+Alt+3  把車放回路上（卡住、翻車時）
--   Ctrl+Alt+4  交通車重生（重新分散到玩家附近；沒有交通車就叫出 10 台）
--   Ctrl+Alt+5  自動駕駛 開／關（BeamNG.Earth 的自動駕駛沿 GPS 路線開；關掉就是駕駛自己開）
-- 按鍵定義：core/input/actions/codriver.json；預設綁定：settings/inputmaps/keyboard_codriver.json
-- 起點、目的地預設是展示路線；start_codriver.sh 會用 codriver-dgx.conf 的值呼叫 M.config（bng hotkeys）
local M = {}

local cfg = {
  start = { lat = 22.735697, lon = 120.329616, name = '清豐二路起點' },
  dest  = { lat = 22.727135, lon = 120.326549, name = '楠梓車站麥當勞' },
  jamDist = 450, jamCars = 5, traffic = 10,
  fuel = 12,                       -- 回起點時的油量（%；展示開場「油不夠」，100 = 不改）
}

local function api() return rawget(_G, 'beamngEarth_api') end

-- ---- 畫面上方的小提示（imgui；-noui 下 CEF 的訊息看不到）----
local toastText, toastLeft = nil, 0
local function toast(text, sec)
  toastText, toastLeft = text, sec or 3
  log('I', 'codriverHotkeys', 'CODRIVER_HOTKEY ' .. tostring(text))
end
local function drawToast(dtReal)
  if not toastText or toastLeft <= 0 then return end
  toastLeft = toastLeft - (dtReal or 0)
  local im = rawget(_G, 'ui_imgui')
  if not im then return end
  local vp = im.GetMainViewport()
  if vp then
    im.SetNextWindowPos(im.ImVec2(vp.Pos.x + vp.Size.x * 0.5, vp.Pos.y + vp.Size.y * 0.06), im.Cond_Always, im.ImVec2(0.5, 0.5))
  end
  im.SetNextWindowBgAlpha(0.6)
  local flags = im.WindowFlags_NoTitleBar + im.WindowFlags_AlwaysAutoResize + im.WindowFlags_NoSavedSettings
    + im.WindowFlags_NoInputs + im.WindowFlags_NoFocusOnAppearing
  if im.Begin('##codriverHotkeyToast', nil, flags) then
    im.SetWindowFontScale(1.6)
    im.TextUnformatted(toastText)
    im.SetWindowFontScale(1)
  end
  im.End()
end

-- ---- 1. 塞車 開／關 ----
function M.jamToggle()
  local J = rawget(_G, 'codriverJam')
  if not J then toast('塞車功能沒有載入（codriverJam）') return end
  if J.isActive() then
    J.off()
    toast('塞車 關：故障車移走、車流恢復')
  else
    local d = cfg.jamDist
    local A = api()
    local ok, g = pcall(function() return A and A.gps() end)
    if ok and g and g.remaining then              -- 離終點太近時往前放的距離縮短，不要放到終點後面
      d = math.max(80, math.min(d, g.remaining - 200))
    end
    local ok2, msg = pcall(J.on, d, cfg.jamCars)
    toast(ok2 and ('塞車 開：前方約 ' .. math.floor(d) .. ' 公尺') or ('塞車失敗：' .. tostring(msg)))
  end
end

-- ---- 2. 回起點（每幀推進的小流程，不卡住遊戲）----
local job = nil     -- { step, wait, left }
local function startStep(step, wait) job.step, job.wait = step, wait or 0 end
function M.backToStart()
  local A = api()
  if not A then toast('BeamNG.Earth 還沒載入') return end
  pcall(function() require('beamngEarth/gps').setAutodrive(false) end)
  local D, J = rawget(_G, 'codriverDest'), rawget(_G, 'codriverJam')
  if D then D.clear() end
  if D and D.setFuel and cfg.fuel and cfg.fuel < 100 then D.setFuel(cfg.fuel / 100) end
  if J and J.isActive() then J.off() end
  local ok, why = A.teleport(cfg.start.lat, cfg.start.lon, cfg.start.name)
  if not ok then toast('回起點失敗：' .. tostring(why)) return end
  job = { left = 0 }
  startStep('waitReady', 5)                       -- 傳送後先等 5 秒再問地圖好了沒
  toast('回起點：' .. cfg.start.name .. '（約 20 秒）', 20)
end

local function runJob(dtReal)
  if not job then return end
  job.wait = job.wait - dtReal
  if job.wait > 0 then return end
  job.wait = 1                                    -- 之後每秒檢查一次
  job.left = job.left + 1
  local A = api()
  if not A then job = nil return end
  if job.step == 'waitReady' then
    if A.ready() or job.left > 60 then
      if cfg.dest then
        A.gpsSet(cfg.dest.lat, cfg.dest.lon, cfg.dest.name)
        job.left = 0; startStep('waitRoute', 2)
      else
        job.left = 0; startStep('done', 0)
      end
    end
  elseif job.step == 'waitRoute' then
    local g = A.gps()
    if (g and not g.planning) or job.left > 60 then job.left = 0; startStep('face', 0) end
  elseif job.step == 'face' then
    local J = rawget(_G, 'codriverJam')
    local msg = J and J.faceRoute() or ''
    if msg:find('車頭轉向', 1, true) or job.left > 10 then job.left = 0; startStep('done', 0) else job.wait = 2 end
  elseif job.step == 'done' then
    pcall(function() require('beamngEarth/ui').visible = false end)
    toast('已回到起點，導航 → ' .. (cfg.dest and cfg.dest.name or '（無）'), 4)
    job = nil
  end
end

-- ---- 3. 把車放回路上 ----
function M.toRoad()
  spawn.teleportToLastRoad()
  toast('車子放回最近的道路')
end

-- ---- 4. 交通車重生 ----
function M.trafficRespawn()
  if not gameplay_traffic then toast('交通車模組沒有載入') return end
  local J = rawget(_G, 'codriverJam')
  if J and J.isActive() then J.off() end          -- 塞車時重生是關掉的
  if (gameplay_traffic.getNumOfTraffic() or 0) == 0 then
    settings.setValue('trafficSimpleVehicles', true)
    settings.setValue('trafficAmount', cfg.traffic)
    gameplay_traffic.setupTraffic(cfg.traffic)
    toast('叫出 ' .. cfg.traffic .. ' 台交通車（前 30 秒會比較卡）', 5)
  else
    gameplay_traffic.scatterTraffic()
    toast('交通車重新分散到附近')
  end
end

-- ---- 5. 自動駕駛 開／關 ----
function M.autoToggle()
  local ok, gps = pcall(require, 'beamngEarth/gps')
  if not ok or not gps or not gps.setAutodrive then toast('BeamNG.Earth 還沒載入') return end
  local on = gps.setAutodrive(not gps.autodrive())
  toast(on and '自動駕駛 開：沿導航路線自己開（再按一次換手動）' or '自動駕駛 關：換你自己開', 4)
end

-- start_codriver.sh／bng hotkeys：{start={lat,lon,name}, dest={lat,lon,name}|false, jamDist, jamCars, traffic}
function M.config(c)
  for k, v in pairs(c or {}) do cfg[k] = v end
  if cfg.dest == false then cfg.dest = nil end
  return string.format('快速鍵設定：起點 %s，目的地 %s，塞車 %d 公尺／%d 台，交通車 %d 台，回起點油量 %d%%',
    cfg.start.name, cfg.dest and cfg.dest.name or '（無）', cfg.jamDist, cfg.jamCars, cfg.traffic, cfg.fuel or 100)
end
function M.getConfig() return cfg end

-- 按鍵進來的入口（actions json 的 onDown 呼叫；包 pcall，出錯只顯示提示，不讓遊戲跳錯誤）
function M.press(name)
  local f = ({ jam = M.jamToggle, start = M.backToStart, road = M.toRoad, traffic = M.trafficRespawn,
               auto = M.autoToggle })[name]
  if not f then return end
  local ok, err = pcall(f)
  if not ok then toast('快速鍵錯誤：' .. tostring(err), 6) end
end

function M.onUpdate(dtReal)
  drawToast(dtReal)
  local ok, err = pcall(runJob, dtReal or 0)
  if not ok then toast('回起點出錯：' .. tostring(err), 6) job = nil end
end

return M
