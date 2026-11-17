-- CoDriver：每 5 秒在 beamng.log 印出平均 FPS、最慢一幀（ms）、模擬速度（遊戲時間/真實時間，1.0 = 即時）
-- 有 BeamNG.Earth 時再加：世界是否就緒、目前經緯度
local M = {}
local n, t, sim, worst = 0, 0, 0, 0
local function earthInfo()
  local api = rawget(_G, 'beamngEarth_api')
  if not api then return '' end
  local okR, ready = pcall(api.ready)
  local okP, p = pcall(api.pose)
  local pos = (okP and p) and string.format(' lat=%.5f lon=%.5f', p.lat, p.lon) or ' pos=nil'
  return ' earthReady=' .. tostring(okR and ready) .. pos
end
function M.onUpdate(dtReal, dtSim)
  n = n + 1; t = t + dtReal; sim = sim + (dtSim or 0)
  if dtReal > worst then worst = dtReal end
  if t >= 5 then
    log('I', 'codriverFps', string.format('CODRIVER_FPS avg=%.1f worst_ms=%.1f simspeed=%.2f vehicles=%d', n / t, worst * 1000, sim / t, be:getObjectCount()) .. earthInfo())
    n, t, sim, worst = 0, 0, 0, 0
  end
end
return M
