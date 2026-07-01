-- Cross-reference navigation: go-to-definition and find-usages.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")

local M = {}

local function notify(msg, level)
  vim.notify("[jadxnvim] " .. msg, level or vim.log.levels.INFO)
end

function M.goto_def()
  local id, line, col = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  rpc.request("gotoDef", { id = id, line = line, col = col }, function(err, res)
    if err then
      vim.schedule(function()
        notify("gotoDef failed: " .. (err.message or "?"), vim.log.levels.ERROR)
      end)
      return
    end
    vim.schedule(function()
      if not res.found then
        notify("no definition under cursor", vim.log.levels.WARN)
        return
      end
      code.open(res.id, { line = res.line, col = res.col })
    end)
  end)
end

function M.find_usages()
  local id, line, col = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  rpc.request("findUsages", { id = id, line = line, col = col }, function(err, res)
    if err then
      vim.schedule(function()
        notify("findUsages failed: " .. (err.message or "?"), vim.log.levels.ERROR)
      end)
      return
    end
    vim.schedule(function()
      local usages = res.usages or {}
      local name = res.name or "symbol"
      if #usages == 0 then
        notify("no usages found for " .. name, vim.log.levels.WARN)
        return
      end
      local items = {}
      for _, u in ipairs(usages) do
        items[#items + 1] = {
          filename = "jadx://" .. u.id,
          lnum = u.line,
          col = (u.col or 0) + 1, -- quickfix col is 1-based
          text = u.text or "",
        }
      end
      local title = string.format("jadx usages: %s (%d%s)", name, #usages, res.truncated and "+" or "")
      vim.fn.setqflist({}, " ", { title = title, items = items })
      vim.cmd("botright copen")
      notify(string.format("%d usage(s) of %s%s", #usages, name, res.truncated and " (truncated)" or ""))
    end)
  end)
end

return M
