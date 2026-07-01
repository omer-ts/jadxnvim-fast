-- Fuzzy finders for classes, methods, and full-text, backed by the built-in fuzzy picker.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")
local fuzzy = require("jadxnvim.fuzzy")

local M = {}

local function ensure()
  if not rpc.is_running() then
    vim.notify("[jadxnvim] no project open; use :Jadx <path>", vim.log.levels.WARN)
    return false
  end
  return true
end

local function err_notify(what, err)
  vim.schedule(function()
    vim.notify("[jadxnvim] " .. what .. " failed: " .. (err.message or "?"), vim.log.levels.ERROR)
  end)
end

--- Fuzzy-find a class by name; open it on select.
function M.classes()
  if not ensure() then
    return
  end
  rpc.request("listClasses", {}, function(err, res)
    if err then
      return err_notify("listClasses", err)
    end
    vim.schedule(function()
      local items = {}
      for _, c in ipairs(res.items or {}) do
        items[#items + 1] = { text = c.label, id = c.id }
      end
      local title = res.truncated and " Classes (truncated) " or " Classes "
      fuzzy.pick({ title = title, items = items, on_select = function(it)
        code.open(it.id)
      end })
    end)
  end)
end

--- Fuzzy-find a method by name; open its class and jump to the declaration on select.
function M.methods()
  if not ensure() then
    return
  end
  rpc.request("listMethods", {}, function(err, res)
    if err then
      return err_notify("listMethods", err)
    end
    vim.schedule(function()
      local items = {}
      for _, m in ipairs(res.items or {}) do
        items[#items + 1] = { text = m.label, id = m.id, index = m.index }
      end
      local title = res.truncated and " Methods (truncated) " or " Methods "
      fuzzy.pick({ title = title, items = items, on_select = function(it)
        rpc.request("memberPos", { id = it.id, index = it.index }, function(e, r)
          vim.schedule(function()
            if e or not r then
              code.open(it.id)
            else
              code.open(it.id, { line = r.line, col = r.col })
            end
          end)
        end)
      end })
    end)
  end)
end

--- Live full-text search: type in the picker and results stream in as you go. Open on select.
function M.text()
  if not ensure() then
    return
  end
  local cap = 1000
  local active = { id = nil, disposers = {}, collected = {} }

  local function cancel_current()
    if active.id then
      rpc.request("cancelSearch", { searchId = active.id })
    end
    for _, d in ipairs(active.disposers) do
      pcall(d)
    end
    active.disposers = {}
    active.id = nil
    active.collected = {}
  end

  local function on_query(query, emit)
    cancel_current()
    if not query or #query < 2 then
      emit({})
      return
    end
    local my = { id = nil }
    local function push()
      local items = {}
      for _, it in ipairs(active.collected) do
        items[#items + 1] = {
          text = it.fullName .. ":" .. it.line .. "  " .. (it.text or ""),
          id = it.id,
          line = it.line,
          col = it.col,
        }
      end
      emit(items)
    end

    active.disposers[#active.disposers + 1] = rpc.on("searchHits", function(p)
      if p.searchId ~= my.id then
        return
      end
      for _, it in ipairs(p.items or {}) do
        active.collected[#active.collected + 1] = it
      end
      push()
      if #active.collected >= cap then
        rpc.request("cancelSearch", { searchId = my.id })
      end
    end)
    active.disposers[#active.disposers + 1] = rpc.on("searchDone", function(p)
      if p.searchId == my.id then
        push()
      end
    end)

    rpc.request("searchText", { query = query, limit = cap }, function(err, res)
      if not err and res then
        my.id = res.searchId
        active.id = res.searchId
      end
    end)
  end

  fuzzy.pick({
    title = " Text search ",
    on_query = on_query,
    on_close = cancel_current,
    on_select = function(s)
      code.open(s.id, { line = s.line, col = s.col })
    end,
  })
end

return M
