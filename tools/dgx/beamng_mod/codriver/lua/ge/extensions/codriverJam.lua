-- CoDriver：在導航路線前方製造「真的」塞車（bng jam on / off）
--   on(dist, n)：沿遊戲 GPS 路線（core_groundMarkers 的路徑）找前方 dist 公尺的點，
--                同方向每個車道各放一台故障車擋住（警示燈、手煞車、沒有 AI；多線道也過不去），
--                再把 n 台現有交通車傳送到它後面排隊；交通車限速 3 m/s、暫停重生（不然離你太遠會被移走）。
--                沒有 GPS 路線時改用車頭正前方。
--   off()：移走故障車、恢復交通車速度與重生，車流散開。
-- 車道與方向用遊戲交通系統自己的 gameplay_traffic_trafficUtils.finalizeSpawnPoint（跟交通車重生同一套）。
local M = {}

local brokenId = nil
local brokenIds = {}
local active = false
local vecUp = vec3(0, 0, 1)
local vecY = vec3(0, 1, 0)

function M.isActive() return active end

-- 從離 startPos 最近的路徑點開始，沿路徑走 d 公尺；回傳位置與方向。走到盡頭就回傳最後一段的終點
local function pointAlong(path, startPos, d)
  local best, bi = math.huge, 1
  for i, q in ipairs(path) do
    local dd = q.pos:squaredDistance(startPos)
    if dd < best then best, bi = dd, i end
  end
  local acc = 0
  for i = bi, #path - 1 do
    local a, b = path[i].pos, path[i + 1].pos
    local seg = a:distance(b)
    if seg > 0.01 then
      if acc + seg >= d then
        local t = (d - acc) / seg
        return a + (b - a) * t, (b - a):normalized()
      end
      acc = acc + seg
    end
  end
  if #path >= 2 then
    local a, b = path[#path - 1].pos, path[#path].pos
    return vec3(b), (b - a):normalized()
  end
  return nil
end

-- 放到行進方向（dir）那一側的車道上
local function placeOnLane(pos, dir)
  local n1, n2, dist = map.findClosestRoad(pos)
  if not n1 or (dist or 99) > 30 then return pos, dir end
  local nodes = map.getMap().nodes
  if (nodes[n2].pos - nodes[n1].pos):dot(dir) < 0 then n1, n2 = n2, n1 end
  for _, rd in ipairs({ -1, 1 }) do
    local ok, p, d = pcall(gameplay_traffic_trafficUtils.finalizeSpawnPoint, vec3(pos), vec3(dir), n1, n2, { roadDir = rd, legalDirection = true })
    if ok and p and d and d:dot(dir) > 0.5 then return p, d end
  end
  return pos, dir
end

-- 行進方向（dir）那一側「每一個車道」的中心位置（跟遊戲交通系統 finalizeSpawnPoint 同一套車道公式；
-- 它的 minLane／maxLane 會被限制成 ≤0／≥0，不能指定第幾個車道，所以自己算）。回傳 { {pos, dir}, ... }，靠路邊的車道在前
local function lanesAt(pos, dir)
  local n1, n2, dist = map.findClosestRoad(pos)
  if not n1 or (dist or 99) > 30 then return { { pos, dir } } end
  local nodes = map.getMap().nodes
  if (nodes[n2].pos - nodes[n1].pos):dot(dir) < 0 then n1, n2 = n2, n1 end
  local p1, p2 = nodes[n1].pos, nodes[n2].pos
  local rd = (p2 - p1):normalized()
  local xnorm = math.max(0, math.min(1, pos:xnormOnLine(p1, p2)))
  local center = p1 + (p2 - p1) * xnorm                                  -- 投影到道路中心線
  local radius = lerp(nodes[n1].radius, nodes[n2].radius, xnorm)
  local normal = nodes[n1].normal:slerp(nodes[n2].normal, xnorm):normalized()
  local link = nodes[n1].links[n2] or nodes[n2].links[n1]
  local oneWay = link and link.oneWay
  local roadWidth = radius * 2
  local laneWidth = roadWidth >= 6.1 and 3.05 or 2.2
  local laneCount = math.max(1, math.floor(roadWidth / laneWidth))
  if not oneWay and laneCount % 2 ~= 0 then laneCount = math.max(1, laneCount - 1) end
  local legalSide = map.getRoadRules().rightHandDrive and -1 or 1
  local first, last = 1, laneCount
  if not oneWay and laneCount > 1 then first = math.floor(laneCount * 0.5) + 1 end   -- 順著 rd 方向的那一半
  local out = {}
  for lane = last, first, -1 do                                          -- 從最外側（路邊）開始
    local offset = (lane - (laneCount * 0.5 + 0.5)) * (roadWidth / laneCount) * legalSide
    out[#out + 1] = { center + rd:cross(normal) * offset, vec3(rd) }
  end
  return out
end

local function rotFor(pos, dir)
  local normal = map.surfaceNormal(pos, 1) or vecUp
  return quatFromDir(vecY:rotated(quatFromDir(dir, normal)), normal)
end

local function trafficIds()
  local ids, player = {}, be:getPlayerVehicleID(0)
  local data = gameplay_traffic and gameplay_traffic.getTrafficData and gameplay_traffic.getTrafficData() or {}
  for id, _ in pairs(data) do
    local broken = false
    for _, b in ipairs(brokenIds) do if b == id then broken = true end end
    if id ~= player and not broken and be:getObjectByID(id) then ids[#ids + 1] = id end
  end
  return ids
end

-- 2026-11-19：塞車時 Earth 自動駕駛看到「30 秒沒前進」會把車往前放 150 公尺（可能直接放進車陣裡）。
-- 塞車期間把這個檢查關掉（秒數調很大），解除時還原
local NOPROGRESS_OFF_S = 3600
local savedNoProgress = nil
local function holdAutodrive(on)
  local ok, gps = pcall(require, 'beamngEarth/gps')
  if not ok or type(gps) ~= 'table' or not gps.AUTO_NOPROGRESS_S then return end
  if on then
    if not savedNoProgress then savedNoProgress = gps.AUTO_NOPROGRESS_S end
    gps.AUTO_NOPROGRESS_S = NOPROGRESS_OFF_S
  elseif savedNoProgress then
    gps.AUTO_NOPROGRESS_S, savedNoProgress = savedNoProgress, nil
  end
end

function M.off()
  holdAutodrive(false)
  for _, id in ipairs(brokenIds) do
    local o = be:getObjectByID(id)
    if o then o:delete() end
  end
  brokenIds, brokenId = {}, nil
  if gameplay_traffic then
    gameplay_traffic.setTrafficVars({ speedLimit = -1, enableRespawn = true, spawnValue = 1, spawnDirBias = 0.2 })
  end
  active = false
  log('I', 'codriverJam', 'CODRIVER_JAM off')
  return 'jam off：故障車移走、車流恢復'
end

function M.on(dist, n)
  dist, n = tonumber(dist) or 400, tonumber(n) or 5
  if active then M.off() end
  local pv = be:getPlayerVehicle(0)
  if not pv then return 'jam on 失敗：沒有玩家的車' end
  local ppos, pdir = pv:getPosition(), pv:getDirectionVector()

  local gm = rawget(_G, 'core_groundMarkers')
  local path = gm and gm.routePlanner and gm.routePlanner.path
  local usingGps = path and #path >= 2
  local function at(d)
    if usingGps then return pointAlong(path, ppos, d) end
    return ppos + pdir * d, vec3(pdir)
  end

  local bpos, bdir = at(dist)
  if not bpos then return 'jam on 失敗：找不到前方的路' end
  local lanes = lanesAt(bpos, bdir)

  -- 故障車：同方向每個車道各一台（第一台白色廂型車，其他小貨車），警示燈、手煞車、沒有 AI → 像事故現場，整個方向都過不去
  brokenIds = {}
  for i, l in ipairs(lanes) do
    local model = i == 1 and 'van' or 'pickup'
    local veh = core_vehicles.spawnNewVehicle(model, { pos = l[1], rot = rotFor(l[1], l[2]), autoEnterVehicle = false,
      canSpawnAnotherVehicleCheck = false, centeredPosition = true, licenseText = 'CODRIVER' })
    if veh then
      brokenIds[#brokenIds + 1] = veh:getID()
      spawn.safeTeleport(veh, l[1], rotFor(l[1], l[2]), true, nil, false)
      veh:queueLuaCommand('ai.setMode("disabled") electrics.set_warn_signal(true) input.event("parkingbrake", 1, 1)')
    end
  end
  if #brokenIds == 0 then return 'jam on 失敗：生成故障車失敗' end
  brokenId = brokenIds[1]

  -- 交通車：限速、暫停重生，再把 n 台排到故障車後面（平均分到各車道，每排間隔 9 公尺）
  gameplay_traffic.setTrafficVars({ speedLimit = 3, enableRespawn = false })
  local ids, moved = trafficIds(), 0
  for k = 1, math.min(n, #ids) do
    local row = math.floor((k - 1) / #lanes) + 1
    local laneIdx = (k - 1) % #lanes + 1
    local qpos, qdir = at(dist - 9 * row)
    if qpos then
      local ql = lanesAt(qpos, qdir)
      local l = ql[math.min(laneIdx, #ql)]
      local o = be:getObjectByID(ids[k])
      if o and l then
        spawn.safeTeleport(o, l[1], rotFor(l[1], l[2]), true, nil, false)
        moved = moved + 1
      end
    end
  end
  active = true
  holdAutodrive(true)
  log('I', 'codriverJam', string.format('CODRIVER_JAM on at %.0f m (%s), %d lanes blocked, queued %d', dist, usingGps and 'GPS route' or 'straight ahead', #lanes, moved))
  return string.format('jam on：前方 %d 公尺（%s），擋住同方向 %d 個車道，%d 台交通車排在後面，車流限速約 11 km/h',
    dist, usingGps and '沿導航路線' or '沒有導航，車頭正前方', #lanes, moved)
end

-- 把玩家的車轉成朝向導航路線（傳送後車頭方向是隨機的，避免一開始就要迴轉）
function M.faceRoute()
  local pv = be:getPlayerVehicle(0)
  local gm = rawget(_G, 'core_groundMarkers')
  local path = gm and gm.routePlanner and gm.routePlanner.path
  if not pv then return 'face 失敗：沒有玩家的車' end
  if not path or #path < 2 then return 'face 失敗：還沒有導航路線（先 bng gps …，等路線規劃好）' end
  local ppos = pv:getPosition()
  local pos, dir = pointAlong(path, ppos, 15)
  if not pos then return 'face 失敗：路線太短' end
  local _, d0 = pointAlong(path, ppos, 0)
  dir = d0 or dir
  local p, d = placeOnLane(ppos, dir)
  spawn.safeTeleport(pv, p, rotFor(p, d), true, nil, false)
  return string.format('車頭轉向導航路線（%.0f°）', (math.deg(math.atan2(d.x, d.y)) + 360) % 360)
end

function M.onExtensionUnloaded() if #brokenIds > 0 then M.off() end end

return M
