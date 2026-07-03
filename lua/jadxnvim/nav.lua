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

-- The identifier under the cursor plus the dotted qualifier before it on the same chain:
-- `Foo.bar` -> ("bar","Foo"); `com.pkg.Foo` -> ("Foo","com.pkg"); bare `x` -> ("x","").
local function qualified_at_cursor()
  local word = vim.fn.expand("<cword>")
  local line = vim.api.nvim_get_current_line()
  local n = #line
  local ci = vim.api.nvim_win_get_cursor(0)[2] + 1 -- 1-based byte col at cursor
  if n == 0 or word == "" or ci > n or not line:sub(ci, ci):match("[%w_$.]") then
    return word, ""
  end
  local a = ci
  while a > 1 and line:sub(a - 1, a - 1):match("[%w_$.]") do
    a = a - 1
  end
  local chain = line:sub(a):match("^[%w_$.]+") or ""
  local rel = ci - a + 1 -- cursor position within the chain
  local acc, idx = {}, 1
  while idx <= #chain + 1 do
    local dot = chain:find("%.", idx) or (#chain + 1)
    if rel >= idx and rel <= dot then
      return chain:sub(idx, dot - 1), table.concat(acc, ".")
    end
    acc[#acc + 1] = chain:sub(idx, dot - 1)
    idx = dot + 1
  end
  return word, ""
end

local function short_name(fn)
  return (fn or ""):match("[^.]+$")
end
local function method_name(it)
  return (it.fullName or ""):match("^%s*(.-)%s*·")
end
local function method_class(it)
  return (it.fullName or ""):match("·%s*(.+)$")
end
local function open_class(it, why)
  notify("gd: resolved " .. why .. " by name search", vim.log.levels.INFO)
  code.open(it.id)
end
local function open_method(it, why, name)
  notify("gd: resolved " .. why .. " by name search", vim.log.levels.INFO)
  rpc.request("memberPos", { id = it.id, index = it.index }, function(e, r)
    vim.schedule(function()
      code.open(it.id, { line = r and r.line or 1, col = r and r.col or 0, find_method = name })
    end)
  end)
end

-- Bare resolution: exact short class name, then exact method name (any class).
local function bare_resolve(word, classes)
  for _, it in ipairs(classes) do
    if short_name(it.fullName) == word then
      return open_class(it, "class '" .. word .. "'")
    end
  end
  collect_names("method", word, function(methods)
    for _, it in ipairs(methods) do
      if method_name(it) == word then
        return open_method(it, "method '" .. word .. "'", word)
      end
    end
    notify("no definition for '" .. word .. "' (try <Space>fv)", vim.log.levels.WARN)
  end)
end

-- gd fallback: the symbol didn't resolve via getJavaNodeAtPosition, so find it by name — using the
-- qualified context (Class.member / pkg.Class) to pick the right class/member rather than any match.
local function goto_def_fallback(word, qualifier)
  if not word or word == "" then
    notify("no definition under cursor", vim.log.levels.WARN)
    return
  end
  local prefix = (qualifier ~= "") and (qualifier .. "." .. word) or word
  collect_names("class", word, function(classes)
    -- 1. fully-qualified class name (com.pkg.Foo)
    for _, it in ipairs(classes) do
      if it.fullName == prefix then
        return open_class(it, "class '" .. prefix .. "'")
      end
    end
    if qualifier == "" then
      return bare_resolve(word, classes)
    end
    -- 2. qualified member: resolve the qualifier as a class, then find `word` inside it
    local qs = short_name(qualifier)
    collect_names("class", qs, function(qclasses)
      local qcls
      for _, it in ipairs(qclasses) do
        if it.fullName == qualifier or short_name(it.fullName) == qs then
          qcls = it
          break
        end
      end
      if not qcls then
        return bare_resolve(word, classes) -- qualifier isn't a class (e.g. a local variable)
      end
      collect_names("method", word, function(methods)
        for _, it in ipairs(methods) do
          if method_name(it) == word and it.id == qcls.id then -- `word` method of the qualifier class
            return open_method(it, "method '" .. qualifier .. "." .. word .. "'", word)
          end
        end
        -- `word` is a field/other member -> open the qualifier class positioned at it
        notify("gd: opened class '" .. qualifier .. "' for member '" .. word .. "'", vim.log.levels.INFO)
        code.open(qcls.id, { find_method = word })
      end)
    end)
  end)
end

function M.goto_def()
  local id, line, col = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  local word, qualifier = qualified_at_cursor()
  rpc.request("gotoDef", { id = id, line = line, col = col }, function(err, res)
    if err then
      vim.schedule(function()
        notify("gotoDef failed: " .. (err.message or "?"), vim.log.levels.ERROR)
      end)
      return
    end
    vim.schedule(function()
      if not res.found then
        goto_def_fallback(word, qualifier) -- couldn't resolve semantically; fall back to name search
        return
      end
      local targets = res.targets
      if type(targets) == "table" and #targets > 1 then
        -- virtual dispatch: the call resolves to a base/interface method with several
        -- implementations — let the user pick which one to jump to.
        local items = {}
        for _, t in ipairs(targets) do
          local tag = t["abstract"] and "interface" or "impl"
          items[#items + 1] = {
            text = string.format("%-9s %s", tag, t.fullName or t.id),
            id = t.id,
            name = t.name,
          }
        end
        fuzzy.pick({
          title = string.format(" Implementations of %s (%d) ", res.name or "method", #items),
          items = items,
          previewer = preview.class(),
          on_select = function(it)
            code.open(it.id, { find_method = it.name })
          end,
        })
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
      -- For <C-f> (Frida) in the usages picker, hook the searched symbol itself: the target method
      -- (all overloads) or the whole class — not each call site.
      local hook
      if type(res.targetId) == "string" then
        hook = (res.targetKind == "method")
            and { { class = res.targetId, method = name } }
          or { { class = res.targetId } }
      end
      searches.record({ kind = "xref", query = name, title = title, items = items, previewer = preview.class(), on_select = on_open })
      fuzzy.pick({ title = title, items = items, previewer = preview.class(), on_select = on_open, hook = hook })
    end)
  end)
end

--- Generate a Frida hook for the symbol under the cursor: the method (all overloads — and every
--- implementation, for an interface/virtual call) or the whole class.
function M.frida_hook()
  local id, line, col = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  local word = vim.fn.expand("<cword>")
  rpc.request("gotoDef", { id = id, line = line, col = col }, function(err, res)
    vim.schedule(function()
      local frida = require("jadxnvim.frida")
      if err or not res or not res.found then
        -- unresolved: best-effort hook of the word under the cursor as a method of this class
        frida.open({ { class = id, method = word } }, word)
        return
      end
      local targets = {}
      if res.kind == "class" then
        targets[1] = { class = res.id } -- whole class
      elseif res.kind == "method" then
        for _, t in ipairs(res.targets or {}) do
          targets[#targets + 1] = { class = t.id, method = t.name }
        end
      else
        targets[1] = { class = res.id } -- field/other -> hook the declaring class
      end
      frida.open(targets, res.name or "hook")
    end)
  end)
end

--- Generate a Frida hook for every method of the class in the current buffer.
function M.frida_hook_class()
  local id = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  require("jadxnvim.frida").open({ { class = id } }, id)
end

return M
