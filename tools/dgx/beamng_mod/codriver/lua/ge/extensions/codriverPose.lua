-- CoDriver：把遊戲裡車子的經緯度、方向、BeamNG.Earth GPS 狀態送給橋接程式（UDP JSON），
-- 並接收橋接傳來的「設定／取消 GPS」指令（App 規劃導航時，遊戲 GPS 自動跟著設定）。
--
-- 送到：OutGauge 的位址（設定 protocols_outgauge_address），port = OutGauge port + 1（預設 4445）
--   {"t":"pose","lat":..,"lon":..,"heading":度,"speed":m/s,"ready":true,
--    "gps":{"name":..,"lat":..,"lon":..,"remaining":公尺,"eta":秒,"planning":false} 或 沒有,
--    "arrivedSeq":抵達次數,"arrivedName":最後抵達的地點,
--    "carsAhead":車頭前方 40 公尺內同方向的車數,"jamActive":bng jam on 中}   每秒一次；抵達時立刻多送一次
--   {"t":"route","version":..,"dist":公尺,"dur":秒,"points":[[lat,lon],...]}   路線改變時、之後每 10 秒重送
-- 收：port = OutGauge port + 2（預設 4446），只接受這兩種，而且只接受 OutGauge 位址或本機送來的：
--   {"cmd":"gps","lat":..,"lon":..,"name":".."}   {"cmd":"gpsClear"}
local M = {}

local sendSock, cmdSock, cmdPort = nil, nil, nil
local acc, routeAcc = 0, 0
local routeVer = 'none'
local arrivedSeq, arrivedName = 0, ''
local MAX_POINTS = 400            -- 路線點數上限（UDP 封包大小）

local function api() return rawget(_G, 'beamngEarth_api') end
local function host() return settings.getValue('protocols_outgauge_address') or '127.0.0.1' end
local function basePort() return tonumber(settings.getValue('protocols_outgauge_port')) or 4444 end

local function send(tbl)
  if not sendSock then return end
  local ok, s = pcall(jsonEncode, tbl)
  if ok and s then sendSock:sendto(s, host(), basePort() + 1) end
end

local function openSockets()
  sendSock = socket.udp()
  local want = basePort() + 2
  if cmdSock and cmdPort == want then return end
  if cmdSock then cmdSock:close() end
  cmdSock = socket.udp()
  if cmdSock:setsockname('*', want) == nil then
    log('W', 'codriverPose', 'cannot listen for commands on UDP ' .. want)
    cmdSock = nil
  else
    cmdSock:settimeout(0)
    cmdPort = want
    log('I', 'codriverPose', 'CODRIVER_POSE sending to ' .. host() .. ':' .. (basePort() + 1) .. ', commands on UDP ' .. want)
  end
end

local function sendRoute(force)
  local ok, gps = pcall(require, 'beamngEarth/gps')
  local r = ok and gps and gps.route and gps.route() or nil
  local ver = r and tostring(r.version) or 'none'
  if not force and ver == routeVer then return end
  routeVer = ver
  if not r then send({ t = 'route', version = 'none', points = {} }) return end
  local pts, n = {}, #(r.points or {})
  local step = math.max(1, math.ceil(n / MAX_POINTS))
  for i = 1, n, step do pts[#pts + 1] = { r.points[i][1], r.points[i][2] } end
  if n > 0 and (n - 1) % step ~= 0 then pts[#pts + 1] = { r.points[n][1], r.points[n][2] } end
  send({ t = 'route', version = ver, dist = r.dist, dur = r.dur, points = pts })
end

-- 車頭前方 AHEAD_M 公尺內、橫向 LATERAL_M 公尺內、跟玩家同方向（夾角 < 60°）的車有幾台
-- （橋接用來分辨「真的塞車」和「停紅燈、路邊停車」）
local AHEAD_M, LATERAL_M = 40, 4
local function carsAhead()
  local pv = be:getPlayerVehicle(0)
  if not pv then return nil end
  local pid, ppos, pdir = pv:getID(), pv:getPosition(), pv:getDirectionVector()
  local n = 0
  local dest = rawget(_G, 'codriverDest')
  for i = 0, be:getObjectCount() - 1 do
    local o = be:getObject(i)
    if o and o:getID() ~= pid and o.getDirectionVector and o:getJBeamFilename() ~= 'cones'
       and not (dest and dest.isMarker and dest.isMarker(o:getID())) then
      local rel = o:getPosition() - ppos
      local along = rel:dot(pdir)
      if along > 2 and along < AHEAD_M and (rel - pdir * along):length() < LATERAL_M
         and o:getDirectionVector():dot(pdir) > 0.5 then
        n = n + 1
      end
    end
  end
  return n
end

local function sendPose()
  local a = api()
  if not a then return end
  local okP, p = pcall(a.pose)
  if not okP or not p then return end
  local okR, ready = pcall(a.ready)
  local okG, g = pcall(a.gps)
  local okC, ahead = pcall(carsAhead)
  local jam = rawget(_G, 'codriverJam')
  local pkt = { t = 'pose', lat = p.lat, lon = p.lon, heading = p.heading, speed = p.speed,
                ready = okR and ready == true, arrivedSeq = arrivedSeq, arrivedName = arrivedName,
                carsAhead = okC and ahead or nil, jamActive = jam and jam.isActive() or false }
  if okG and g then
    pkt.gps = { name = g.name, lat = g.lat, lon = g.lon, remaining = g.remaining, eta = g.eta, planning = g.planning }
  end
  send(pkt)
end

local function handleCommands()
  if not cmdSock then return end
  for _ = 1, 5 do
    local data, ip = cmdSock:receivefrom(4096)
    if not data then return end
    if ip ~= host() and ip ~= '127.0.0.1' then
      log('W', 'codriverPose', 'ignored command from ' .. tostring(ip))
    else
      local ok, c = pcall(jsonDecode, data, 'codriverPose')
      local a = api()
      if ok and type(c) == 'table' and a then
        if c.cmd == 'gps' and tonumber(c.lat) and tonumber(c.lon) then
          local r = a.gpsSet(tonumber(c.lat), tonumber(c.lon), type(c.name) == 'string' and c.name or 'CoDriver')
          log('I', 'codriverPose', string.format('CODRIVER_GPS set %s (%.5f, %.5f): %s', tostring(c.name), c.lat, c.lon, tostring(r)))
          routeAcc = 9                                   -- 新路線盡快送出
        elseif c.cmd == 'gpsClear' then
          a.gpsClear()
          log('I', 'codriverPose', 'CODRIVER_GPS cleared')
        end
      end
    end
  end
end

-- BeamNG.Earth：GPS 目的地到了（gps.lua 的 arrive()）
function M.onBeamNGEarthRouteArrived(info)
  arrivedSeq = arrivedSeq + 1
  arrivedName = info and tostring(info.name or '') or ''
  log('I', 'codriverPose', 'CODRIVER_GPS arrived ' .. arrivedName)
  sendPose()
end

function M.onExtensionLoaded() openSockets() end

function M.onUpdate(dtReal)
  handleCommands()
  acc = acc + dtReal
  routeAcc = routeAcc + dtReal
  if acc < 1 then return end
  acc = 0
  if cmdPort ~= basePort() + 2 then openSockets() end      -- OutGauge port 改過
  sendPose()
  if routeAcc >= 10 then routeAcc = 0; sendRoute(true) else sendRoute(false) end
end

function M.onExtensionUnloaded()
  if cmdSock then cmdSock:close() end
  if sendSock then sendSock:close() end
end

return M
