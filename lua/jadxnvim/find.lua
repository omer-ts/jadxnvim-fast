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

--- Full-text search: enter a term, watch results stream in (with a loading/count status),
--- then fuzzy-filter the loaded results. Open the selected location.
function M.text(term)
  if not ensure() then
    return
  end
  local function run(query)
    if not query or query == "" then
      return
    end
    local cap = 5000
    local search = { id = nil, disposers = {} }
    local function cleanup()
      if search.id then
        rpc.request("cancelSearch", { searchId = search.id })
      end
      for _, d in ipairs(search.disposers) do
        pcall(d)
      end
      search.disposers = {}
    end

    local handle = fuzzy.pick({
      title = " Text: " .. query .. " ",
      items = {},
      loading = true,
      on_close = cleanup,
      on_select = function(s)
        code.open(s.id, { line = s.line, col = s.col })
      end,
    })

    local my = { id = nil }
    search.disposers[#search.disposers + 1] = rpc.on("searchHits", function(p)
      if p.searchId ~= my.id then
        return
      end
      local batch = {}
      for _, it in ipairs(p.items or {}) do
        batch[#batch + 1] = {
          text = it.fullName .. ":" .. it.line .. "  " .. (it.text or ""),
          id = it.id,
          line = it.line,
          col = it.col,
        }
      end
      vim.schedule(function()
        handle.append(batch)
      end)
    end)
    search.disposers[#search.disposers + 1] = rpc.on("searchDone", function(p)
      if p.searchId == my.id then
        vim.schedule(handle.done)
      end
    end)

    rpc.request("searchText", { query = query, limit = cap }, function(err, res)
      if err then
        vim.schedule(handle.done)
        return
      end
      my.id = res.searchId
      search.id = res.searchId
    end)
  end

  if term and term ~= "" then
    run(term)
  else
    vim.ui.input({ prompt = "Search text: " }, run)
  end
end

return M
