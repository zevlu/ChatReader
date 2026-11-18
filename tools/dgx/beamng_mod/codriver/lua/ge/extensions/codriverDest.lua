-- CoDriver：讓「到了」在遊戲裡很明顯（BeamNG.Earth 的建築是自動生成的，真實地標認不出來）
--   遊戲 GPS 有目的地、車子開到 APPROACH_M 公尺內時（那一帶地形已載入），在目的地最近的路邊、靠目的地那一側
--   在導航路線最後一段、行進方向右側路邊放 3 個橘色三角錐和一台車；抵達時在畫面上方用 imgui 顯示「【已抵達】…」大字（imgui 字型沒有 emoji）
--   （BeamNG.Earth 的 api.notify 走 CEF 介面，-noui 看不到）。
--   換了新的目的地才移走舊的標記（抵達時 Earth 會清掉 GPS，標記留著當抵達畫面）。
local M = {}

local APPROACH_M = 350
local placedKey, placedIds = nil, {}
local lastDest = nil        -- 最近一個 GPS 目的地 { key, lat, lon, name }
local acc = 0
local vecUp = vec3(0, 0, 1)
local vecY = vec3(0, 1, 0)

local function api() return rawget(_G, 'beamngEarth_api') end

local function anchor()
  local c = rawget(_G, 'beamngEarth_core')
  local ok, st = pcall(function() return c and c._state and c._state() end)
  return ok and st and st.anchor or nil
end

local function removeMarkers()
  for _, id in ipairs(placedIds) do
    local o = be:getObjectByID(id)
    if o then o:delete() end
  end
  placedIds, placedKey = {}, nil
end

local function rotFor(pos, dir)
  local normal = map.surfaceNormal(pos, 1) or vecUp
  return quatFromDir(vecY:rotated(quatFromDir(dir, normal)), normal)
end

local function spawnAt(model, config, pos, dir)
  local opts = { pos = pos, rot = rotFor(pos, dir), autoEnterVehicle = false, canSpawnAnotherVehicleCheck = false,
                 centeredPosition = true, licenseText = 'CODRIVER' }
  if config then opts.config = config end
  local veh = core_vehicles.spawnNewVehicle(model, opts)
  if not veh then return end
  local r = quat(0, 0, 1, 0) * rotFor(pos, dir)                          -- 跟 spawn.safeTeleport 一樣的轉換，但不找「安全位置」
  veh:setPosRot(pos.x, pos.y, pos.z + 0.3, r.x, r.y, r.z, r.w)
  veh:queueLuaCommand('ai.setMode("disabled") input.event("parkingbrake", 1, 1)')
  placedIds[#placedIds + 1] = veh:getID()
end

-- 車子「被判定抵達」的地方：導航路線終點往回 BACK_M 公尺（Earth 在離終點約 30～40 公尺就算抵達；
-- 路線最後一小段常是轉進巷子，終點本身從大路上看不到）。回傳那裡的位置與行進方向
local BACK_M = 35
local NEAR_M = 80            -- 路徑要經過目的地這麼近，才算「路徑已經延伸到目的地」（箭頭路徑只畫到局部道路網的邊緣）
local function routeEnd(dxy)
  local gm = rawget(_G, 'core_groundMarkers')
  local path = gm and gm.routePlanner and gm.routePlanner.path
  if not path or #path < 2 then return nil end
  local best, j = math.huge, nil
  for i, q in ipairs(path) do
    local dx, dy = q.pos.x - dxy.x, q.pos.y - dxy.y
    local d2 = dx * dx + dy * dy
    if d2 < best then best, j = d2, i end
  end
  if not j or math.sqrt(best) > NEAR_M then return nil end
  local acc = 0
  for i = j, 2, -1 do                                                    -- 從最近點往回走 BACK_M
    local b, a = path[i].pos, path[i - 1].pos
    local seg = a:distance(b)
    if seg > 0.01 and acc + seg >= BACK_M then
      local t = (BACK_M - acc) / seg
      return b + (a - b) * t, (b - a):normalized()
    end
    acc = acc + seg
  end
  if j >= 2 then return vec3(path[1].pos), (path[j].pos - path[1].pos):normalized() end
  return nil
end

local function placeMarkers(d)
  local a, pv = anchor(), be:getPlayerVehicle(0)
  if not a or not pv then return false end
  local okE, x, y = pcall(a.geodeticToEnu, a, d.lat, d.lon, 0)
  if not okE or not x then return false end
  local endPos, dir = routeEnd(vec3(x, y, 0))
  if not endPos then return false end                                   -- 路徑還沒延伸到目的地：下一秒再試
  local n1, n2, dist = map.findClosestRoad(endPos)
  local radius = 3
  if n1 and (dist or 99) < 30 then
    local nodes = map.getMap().nodes
    local xn = math.max(0, math.min(1, endPos:xnormOnLine(nodes[n1].pos, nodes[n2].pos)))
    endPos = nodes[n1].pos + (nodes[n2].pos - nodes[n1].pos) * xn       -- 道路中心線上
    radius = lerp(nodes[n1].radius, nodes[n2].radius, xn)
  end
  local right = dir:cross(vecUp):normalized()                            -- 台灣靠右：駕駛那一側的路邊
  if map.getRoadRules().rightHandDrive then right = -right end
  local edge = radius + 0.3                                             -- 三角錐放在路緣（不擋車道）
  for k = 0, 2 do                                                        -- 3 個三角錐：抵達點前 0／5／10 公尺
    spawnAt('cones', 'vehicles/cones/large_orange_double.pc', endPos - dir * (k * 5) + right * edge, dir)
  end
  -- 黃車：抵達點前方 6 公尺，一半停在人行道上（車身中心在路緣外 0.9 公尺），不佔車道
  -- （11/18 以前停在車道裡，擋住最後一段路，自動駕駛會卡在它後面）
  spawnAt('sunburst2', nil, endPos + dir * 6 + right * (radius + 1.2), dir)
  placedKey = d.key
  log('I', 'codriverDest', string.format('CODRIVER_DEST markers at %s (route end, road half-width %.1f m)', tostring(d.name), radius))
  return true
end

local bannerText, bannerLeft = nil, 0     -- 剩幾秒（用每幀的真實時間倒數；os.clock() 是 CPU 時間，多核心時會跑得比真實時間快）
local function drawBanner(dtReal)
  if not bannerText or bannerLeft <= 0 then return end
  bannerLeft = bannerLeft - (dtReal or 0)
  local im = rawget(_G, 'ui_imgui')
  if not im then return end
  local vp = im.GetMainViewport()
  if vp then
    im.SetNextWindowPos(im.ImVec2(vp.Pos.x + vp.Size.x * 0.5, vp.Pos.y + vp.Size.y * 0.18), im.Cond_Always, im.ImVec2(0.5, 0.5))
  end
  im.SetNextWindowBgAlpha(0.75)
  local flags = im.WindowFlags_NoTitleBar + im.WindowFlags_AlwaysAutoResize + im.WindowFlags_NoSavedSettings
    + im.WindowFlags_NoInputs + im.WindowFlags_NoFocusOnAppearing
  if im.Begin('##codriverArrived', nil, flags) then
    im.SetWindowFontScale(2.6)
    im.TextUnformatted(bannerText)
    im.SetWindowFontScale(1)
  end
  im.End()
end

function M.onUpdate(dtReal)
  drawBanner(dtReal)
  acc = acc + dtReal
  if acc < 1 then return end
  acc = 0
  local A = api()
  if not A then return end
  local okG, g = pcall(A.gps)
  if okG and g and g.lat and g.lon then
    local key = string.format('%.5f,%.5f', g.lat, g.lon)
    if not lastDest or lastDest.key ~= key then
      if placedKey and placedKey ~= key then removeMarkers() end
      lastDest = { key = key, lat = g.lat, lon = g.lon, name = g.name }
    end
    if placedKey ~= key and g.remaining and g.remaining < APPROACH_M then
      placeMarkers(lastDest)
    end
  end
end

-- BeamNG.Earth：GPS 目的地到了
function M.onBeamNGEarthRouteArrived(info)
  local A = api()
  local name = info and info.name or (lastDest and lastDest.name) or '目的地'
  bannerText, bannerLeft = '【已抵達】' .. tostring(name), 12      -- 畫面上方大字 12 秒
  if A and A.notify then pcall(A.notify, '🏁 已抵達：' .. tostring(name), 10) end         -- （有 CEF 時也顯示）
end

function M.clear() removeMarkers() lastDest = nil return '目的地標記已移走' end
-- 給 codriverPose 算前車數時排除（三角錐、路邊標記車不是車流）
function M.isMarker(id) for _, m in ipairs(placedIds) do if m == id then return true end end return false end
function M.testBanner(text) bannerText, bannerLeft = text or '【已抵達】測試', 12 return 'banner' end

function M.onExtensionUnloaded() removeMarkers() end

return M
