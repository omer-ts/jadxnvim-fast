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

--- Full-text search, fully inside the popup: enter a term, watch results stream in (with a
--- loading/count status in the popup), then fuzzy-filter the loaded results. Open on select.
function M.text()
  if not ensure() then
    return
  end
  fuzzy.pick({
    title = " Text search ",
    items = {},
    query_phase = {
      prompt = "search term",
      on_submit = function(term, handle)
        term = vim.trim(term or "")
        if term == "" then
          handle.close()
          return
        end
        handle.set_title(" Text: " .. term .. " ")
        handle.set_loading(true)

        local cap = 5000
        local my = { id = nil }
        local d1 = rpc.on("searchHits", function(p)
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
        local d2 = rpc.on("searchDone", function(p)
          if p.searchId == my.id then
            vim.schedule(handle.done)
          end
        end)
        handle.on_cleanup(function()
          if my.id then
            rpc.request("cancelSearch", { searchId = my.id })
          end
          pcall(d1)
          pcall(d2)
        end)

        rpc.request("searchText", { query = term, limit = cap }, function(err, res)
          if err then
            vim.schedule(handle.done)
            return
          end
          my.id = res.searchId
        end)
      end,
    },
    on_select = function(s)
      code.open(s.id, { line = s.line, col = s.col })
    end,
  })
end

return M
