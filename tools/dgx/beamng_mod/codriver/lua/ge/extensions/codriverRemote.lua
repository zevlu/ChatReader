-- CoDriver：從外面送 Lua 指令進遊戲（不用 CEF 介面也能控制）
-- 把 Lua 程式寫進使用者資料夾根目錄的 codriver_cmd.lua，這裡每 0.5 秒檢查一次，執行完就刪掉
local M = {}
local CMD = '/codriver_cmd.lua'
local acc = 0
function M.onUpdate(dtReal)
  acc = acc + dtReal
  if acc < 0.5 then return end
  acc = 0
  if not FS:fileExists(CMD) then return end
  local src = readFile(CMD)
  FS:removeFile(CMD)
  if not src or src == '' then return end
  local fn, err = loadstring(src, 'codriver_cmd')
  if not fn then log('E', 'codriverRemote', 'CODRIVER_CMD compile error: ' .. tostring(err)); return end
  local ok, res = pcall(fn)
  local msg = (ok and 'ok: ' or 'error: ') .. tostring(res)
  log('I', 'codriverRemote', 'CODRIVER_CMD ' .. msg)
  writeFile('/codriver_result.txt', msg)     -- 給 bng 立刻讀（log 要很久才寫進硬碟）
end
return M
