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
      -- For a method, re-locate the declaration by name in the opened buffer (robust to on-demand
      -- render differences); classes/fields jump to the reported line directly.
      local find_method = res.kind == "method" and res.name or nil
      code.open(res.id, { line = res.line, col = res.col, find_method = find_method })
    end)
  end)
end

-- Candidate task ids for a dispatcher construction at `line`: the integer LITERALS on the call
-- lines, plus a light dataflow trace — for each variable passed as an argument, the constant it was
-- most recently assigned above the call (so `int i2 = 5; ... new X.Y(a, i2)` resolves too). The
-- daemon keeps only the candidate that is a real case in the dispatch switch, so over-collecting is
-- harmless.
local function gather_task_ids(buf, line)
  local ints, seen = {}, {}
  local function add(n)
    n = tonumber(n)
    if n and not seen[n] then
      seen[n] = true
      ints[#ints + 1] = n
    end
  end
  -- the call may wrap over a few lines
  local ctx = vim.api.nvim_buf_get_lines(buf, line - 1, line + 2, false)
  local vars = {}
  for _, l in ipairs(ctx) do
    for n in (" " .. l):gmatch("[^%w_%.](%d+)") do
      add(n) -- literal args
    end
    for v in l:gmatch("([%a_][%w_]*)%s*[,%)]") do
      vars[v] = true -- identifiers in argument position -> trace them
    end
  end
  -- trace each argument variable to its nearest constant assignment above the call (bounded window)
  local top = math.max(0, line - 400)
  local up = vim.api.nvim_buf_get_lines(buf, top, line, false)
  for v in pairs(vars) do
    local esc = vim.pesc(v)
    for i = #up, 1, -1 do
      local n = up[i]:match("[^%w_%.]" .. esc .. "%s*=%s*(%-?%d+)%s*;")
        or up[i]:match("^%s*" .. esc .. "%s*=%s*(%-?%d+)%s*;")
      if n then
        add(n)
        break
      end
    end
  end
  return ints
end

-- Resolve a merged-lambda/-callback dispatcher call to the branch it actually runs. Optimizers
-- (R8 / Meta's Redex) merge many lambdas into one class dispatched by an integer id
-- (`switch (this.$t)`), so a call like `new X.Uez(.., 5)` means "run case 5 of X.Uez". Put the cursor
-- on the dispatcher class name in the construction and this jumps to `case 5:` in its dispatch switch.
-- The id may be a literal or a variable assigned a constant nearby (traced by gather_task_ids).
function M.resolve_task()
  local id, line, col = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  local ints = gather_task_ids(vim.api.nvim_get_current_buf(), line)
  rpc.request("resolveTask", { id = id, line = line, col = col, taskIds = ints }, function(err, res)
    vim.schedule(function()
      if err then
        notify("resolve task failed: " .. (err.message or "?"), vim.log.levels.ERROR)
        return
      end
      if not res.found then
        notify("resolve task: " .. (res.reason or "not a merged dispatcher call"), vim.log.levels.WARN)
        return
      end
      notify(string.format("task %d → %s:%d", res.task, res.id, res.line), vim.log.levels.INFO)
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
      -- Fully-qualified path of the resolved symbol (e.g. com.example.Utils.debugLog); shown on
      -- failure so it's clear exactly what was searched. Falls back to the short name.
      local path = (type(res.path) == "string") and res.path or name
      -- In low-memory mode (usage graph disabled) there is no semantic xref; fall back to a fast
      -- name-based text search over the exported sources.
      if res.usageFallback and #usages == 0 and name ~= "symbol" then
        notify("find-usages: usage graph off — searching for '" .. path .. "' as text", vim.log.levels.INFO)
        require("jadxnvim.search").text(name)
        return
      end
      if #usages == 0 then
        notify("no usages found for " .. path, vim.log.levels.WARN)
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
          -- `find` (the code line) re-locates a precise call site by text; if it doesn't match, the
          -- k-th call (`find_ordinal` + `member`) then the nearest by name keep the cursor on the call.
          -- Fall back to u.text for the legacy engine, whose usages carry the source line as text.
          find = u.find or u.text,
          member = u.member,
          find_ordinal = u.ordinal,
        }
      end
      local title = string.format(" Usages of %s (%d%s) ", name, #usages, res.truncated and "+" or "")
      local on_open = function(it)
        -- Re-locate in the opened buffer: by the code line for precise sites, else the k-th call.
        code.open(it.id, {
          line = it.line, col = it.col,
          find = it.find, find_method = it.member, find_ordinal = it.find_ordinal,
        })
      end
      -- For <C-f> (Frida) in the usages picker, hook the searched symbol itself: the target method
      -- (all overloads) or the whole class — not each call site.
      local hook
      if type(res.targetId) == "string" then
        hook = (res.targetKind == "method")
            and { { class = res.targetId, method = res.targetRawName or name } }
          or { { class = res.targetId } }
      end
      searches.record({ kind = "xref", query = name, title = title, items = items, previewer = preview.class(), on_select = on_open })
      fuzzy.pick({ title = title, items = items, previewer = preview.class(), on_select = on_open, hook = hook })
    end)
  end)
end

-- Open the source for a hierarchy node in the code window (call site for callers, class for types).
local function open_hier_node(n)
  if not n or not n.id then
    return
  end
  code.open(n.id, {
    line = n.line, col = n.col,
    find = n.find, find_method = n.member, find_ordinal = n.ordinal,
  })
end

local function type_icon(kind)
  local icons = require("jadxnvim.icons")
  return icons.get(kind == "interface" and "interface" or "class")
end

-- Turn a server type-hierarchy node into a hierarchy.lua node (recursively, following `dir`).
local function type_node(t, dir)
  local kids = {}
  for _, k in ipairs(t[dir] or {}) do
    kids[#kids + 1] = type_node(k, dir)
  end
  local label = t.name or t.id
  if t.inApk == false then
    label = label .. "  (external)"
  end
  return {
    label = label,
    icon = type_icon(t.kind),
    openable = t.inApk ~= false,
    id = t.id,
    expandable = #kids > 0,
    children = #kids > 0 and kids or nil,
    _open = true, -- type hierarchies are small; show them fully expanded
  }
end

--- Show the super/subtype hierarchy of the class in the current buffer (or the type under the cursor)
--- in a tree: supertypes it extends/implements above, subtypes that extend/implement it below.
function M.type_hierarchy()
  local id, line, col = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  rpc.request("typeHierarchy", { id = id, line = line, col = col }, function(err, res)
    vim.schedule(function()
      if err then
        notify("type hierarchy failed: " .. (err.message or "?"), vim.log.levels.ERROR)
        return
      end
      if not res.found then
        notify("no type hierarchy (class not in this APK)", vim.log.levels.WARN)
        return
      end
      local supers, subs = {}, {}
      for _, t in ipairs(res.supers or {}) do
        supers[#supers + 1] = type_node(t, "supers")
      end
      for _, t in ipairs(res.subs or {}) do
        subs[#subs + 1] = type_node(t, "subs")
      end
      local self_node = {
        label = res.name or id,
        icon = type_icon(res.kind),
        openable = true,
        id = res.id,
      }
      local roots = {
        { label = string.format("Supertypes (%d)", #supers), expandable = #supers > 0,
          children = #supers > 0 and supers or nil, _open = true },
        self_node,
        { label = string.format("Subtypes (%d)", #subs), expandable = #subs > 0,
          children = #subs > 0 and subs or nil, _open = true },
      }
      require("jadxnvim.hierarchy").show({
        title = " Type hierarchy: " .. (res.fullName or res.name or id) .. " ",
        roots = roots,
        on_open = open_hier_node,
      })
    end)
  end)
end

-- Lazily fetch a caller node's own callers when it is expanded in the tree.
local function expand_callers(node, cb)
  rpc.request("callHierarchy", { key = node.key, name = node.name }, function(err, res)
    vim.schedule(function()
      if err or not res or not res.found then
        cb({})
        return
      end
      cb(M._caller_nodes(res.callers))
    end)
  end)
end

-- Collapse whitespace and clip a call-site line for compact display.
local function trunc(s, n)
  s = (s or ""):gsub("%s+", " "):gsub("^%s+", "")
  if #s > n then
    return s:sub(1, n - 1) .. "…"
  end
  return s
end

-- Build hierarchy nodes for a list of caller entries from the daemon. Method-resolved callers show
-- the call-site code (relocated on open by that text, so the line number — which comes from a lighter
-- render than the opened buffer — is deliberately not shown); class-granular callers show the class.
function M._caller_nodes(callers)
  local icons = require("jadxnvim.icons")
  local nodes = {}
  for _, c in ipairs(callers or {}) do
    local label = c.fullName or c.name or c.id
    if c.key and c.text and c.text ~= "" then
      label = label .. "   " .. trunc(c.text, 60)
    end
    nodes[#nodes + 1] = {
      label = label,
      icon = icons.get(c.key and "method" or "class"),
      openable = true,
      id = c.id, line = c.line, col = c.col,
      find = c.find, member = c.member, ordinal = c.ordinal,
      key = c.key,
      expandable = c.expandable == true,
      expand = c.expandable == true and expand_callers or nil,
    }
  end
  return nodes
end

--- Show the incoming call hierarchy ("who calls this") for the method under the cursor: a tree of
--- caller methods, each expandable to its own callers.
function M.call_hierarchy()
  local id, line, col = code.cursor_target()
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  -- Resolving the caller methods renders the top referencing classes, which can take a few seconds on
  -- a very hot method — let the user know the (async) work is underway.
  notify("resolving callers…", vim.log.levels.INFO)
  rpc.request("callHierarchy", { id = id, line = line, col = col }, function(err, res)
    vim.schedule(function()
      if err then
        notify("call hierarchy failed: " .. (err.message or "?"), vim.log.levels.ERROR)
        return
      end
      if not res.found then
        notify("call hierarchy: " .. (res.reason or "put the cursor on a method"), vim.log.levels.WARN)
        return
      end
      local icons = require("jadxnvim.icons")
      local children = M._caller_nodes(res.callers)
      local root = {
        label = (res.root and res.root.fullName or res.root and res.root.name or "method")
          .. string.format("  — %d caller%s%s", #children, #children == 1 and "" or "s",
            res.truncated and "+" or ""),
        icon = icons.get("method"),
        openable = res.root ~= nil,
        id = res.root and res.root.id,
        member = res.root and res.root.name, -- open the method's declaration, not just its class
        expandable = #children > 0,
        children = #children > 0 and children or nil,
        _open = true,
      }
      require("jadxnvim.hierarchy").show({
        title = " Call hierarchy: " .. (res.root and res.root.fullName or "callers") .. " ",
        roots = { root },
        on_open = open_hier_node,
      })
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
          targets[#targets + 1] = { class = t.id, method = t.rawName or t.name }
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
