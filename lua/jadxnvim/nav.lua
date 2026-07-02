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

-- Collect all name-search hits for a term (bounded), then call cb(items).
local function collect_names(kind, term, cb)
  local items = {}
  local sid
  local d1, d2
  local function finish()
    pcall(d1)
    pcall(d2)
    vim.schedule(function()
      cb(items)
    end)
  end
  d1 = rpc.on("searchHits", function(p)
    if p.searchId == sid then
      for _, it in ipairs(p.items or {}) do
        items[#items + 1] = it
      end
    end
  end)
  d2 = rpc.on("searchDone", function(p)
    if p.searchId == sid then
      finish()
    end
  end)
  rpc.request("searchName", { query = term, kind = kind, limit = 200 }, function(err, res)
    if err or not res then
      finish()
    else
      sid = res.searchId
    end
  end)
end

-- gd fallback: some symbols don't resolve via getJavaNodeAtPosition, but the identifier under the
-- cursor can still be found by name. Try an exact class match, then an exact method match, and jump.
local function goto_def_fallback(word)
  if not word or word == "" then
    notify("no definition under cursor", vim.log.levels.WARN)
    return
  end
  collect_names("class", word, function(classes)
    for _, it in ipairs(classes) do
      if ((it.fullName or ""):match("[^.]+$")) == word then -- exact short class name
        notify("gd: resolved '" .. word .. "' by class-name search", vim.log.levels.INFO)
        code.open(it.id)
        return
      end
    end
    collect_names("method", word, function(methods)
      for _, it in ipairs(methods) do
        local mname = (it.fullName or ""):match("^%s*(.-)%s*·")
        if mname == word then -- exact method name
          notify("gd: resolved '" .. word .. "' by method-name search", vim.log.levels.INFO)
          rpc.request("memberPos", { id = it.id, index = it.index }, function(e, r)
            vim.schedule(function()
              code.open(it.id, { line = r and r.line or 1, col = r and r.col or 0, find_method = word })
            end)
          end)
          return
        end
      end
      notify("no definition for '" .. word .. "' (try <Space>fv)", vim.log.levels.WARN)
    end)
  end)
end

function M.goto_def()
  local id, line, col = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  local word = vim.fn.expand("<cword>")
  rpc.request("gotoDef", { id = id, line = line, col = col }, function(err, res)
    if err then
      vim.schedule(function()
        notify("gotoDef failed: " .. (err.message or "?"), vim.log.levels.ERROR)
      end)
      return
    end
    vim.schedule(function()
      if not res.found then
        goto_def_fallback(word) -- couldn't resolve semantically; fall back to name search
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
