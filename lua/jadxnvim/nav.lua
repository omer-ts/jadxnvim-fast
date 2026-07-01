-- Cross-reference navigation: go-to-definition and find-usages.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")
local fuzzy = require("jadxnvim.fuzzy")
local preview = require("jadxnvim.preview")
local searches = require("jadxnvim.searches")

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
      -- daemon may return name = null (JSON) which decodes to vim.NIL (a truthy userdata)
      local name = (type(res.name) == "string") and res.name or "symbol"
      -- In low-memory mode (usage graph disabled) there is no semantic xref; fall back to a fast
      -- name-based text search over the exported sources.
      if res.usageFallback and #usages == 0 and name ~= "symbol" then
        notify("find-usages: usage graph off — searching for '" .. name .. "' as text", vim.log.levels.INFO)
        require("jadxnvim.search").text(name)
        return
      end
      if #usages == 0 then
        notify("no usages found for " .. name, vim.log.levels.WARN)
        return
      end
      -- jadx-gui-style xref: a browsable list of usages with a live code preview.
      local items = {}
      for _, u in ipairs(usages) do
        items[#items + 1] = {
          text = u.fullName .. ":" .. u.line .. "  " .. (u.text or ""),
          id = u.id,
          line = u.line,
          col = u.col,
        }
      end
      local title = string.format(" Usages of %s (%d%s) ", name, #usages, res.truncated and "+" or "")
      local on_open = function(it)
        code.open(it.id, { line = it.line, col = it.col })
      end
      searches.record({ title = title, items = items, previewer = preview.class(), on_select = on_open })
      fuzzy.pick({ title = title, items = items, previewer = preview.class(), on_select = on_open })
    end)
  end)
end

return M
