-- Decompiled-code buffers.
--
-- Each class has a read-only Java buffer named `jadx://<id>` and (on demand) a Smali buffer named
-- `jadxsmali://<id>`. A BufReadCmd loads each on demand, so the buffers behave like real files for
-- `:edit`, quickfix, the jumplist, and `gF`. <Tab> toggles between the Java and Smali views.

local rpc = require("jadxnvim.rpc")

local M = {}

local JAVA_PREFIX = "jadx://"
local SMALI_PREFIX = "jadxsmali://"
local RES_PREFIX = "jadxres://"

-- Map a resource path's extension to a Neovim filetype for syntax highlighting.
local RES_FT = {
  xml = "xml", json = "json", html = "html", htm = "html", txt = "text",
  properties = "jproperties", smali = "smali", java = "java", kt = "kotlin",
  js = "javascript", css = "css", md = "markdown", yml = "yaml", yaml = "yaml",
  gradle = "groovy", pro = "text", cfg = "dosini", ini = "dosini", sql = "sql",
}

local function res_filetype(name)
  local ext = name:match("%.([%w]+)$")
  if ext then
    return RES_FT[ext:lower()]
  end
  return nil
end

local function fetch(method, value, key)
  local done, result, errm = false, nil, nil
  rpc.request(method, { [key or "id"] = value }, function(err, res)
    errm, result, done = err, res, true
  end)
  vim.wait(20000, function()
    return done
  end, 20)
  return errm, result
end

-- jadx emits platform line endings (\r\n on Windows); strip CR so buffers don't show ^M. This is
-- safe for navigation: the daemon's line/column positions are line-based and in-line, before the
-- trailing CR, so they map unchanged onto the stripped lines.
local function to_lines(text)
  return vim.split((text or ""):gsub("\r", ""), "\n", { plain = true })
end

-- Configure window-local folding for a decompiled buffer so `zc`/`za`/`zM` work on { } blocks.
-- Scratch buffers default to foldmethod=manual (no folds exist -> "E490: No fold found"), so we set
-- a real fold method: treesitter's fold expression when the parser is attached (precise block folds),
-- otherwise indent folding (works on jadx's consistently-indented output).
local function setup_folds(bufnr, win)
  local mode = (require("jadxnvim").config or {}).folding
  if mode == nil then
    mode = "auto"
  end
  if mode == false or mode == "manual" or mode == "none" then
    return
  end
  local method, expr
  local ts_folds = vim.b[bufnr].jadx_ts and pcall(vim.treesitter.query.get, "java", "folds")
  if mode == "expr" or (mode == "auto" and ts_folds) then
    method, expr = "expr", "v:lua.vim.treesitter.foldexpr()"
  elseif mode == "syntax" then
    method = "syntax"
  else -- "auto" without treesitter, or "indent"
    method = "indent"
    -- jadx indents 4 spaces per level; match it so each nested { } block folds separately (with the
    -- default shiftwidth of 8 two levels would collapse into one, so an inner if-body wouldn't fold).
    vim.bo[bufnr].shiftwidth = 4
  end
  pcall(function()
    vim.wo[win].foldmethod = method
    if expr then
      vim.wo[win].foldexpr = expr
    end
    vim.wo[win].foldenable = true
    vim.wo[win].foldlevel = 99 -- open fully; folds exist so zc/za/zM operate on them
    vim.wo[win].foldnestmax = 20
  end)
end

local function common_keymaps(bufnr)
  vim.keymap.set("n", "<Tab>", function()
    M.toggle_view()
  end, { buffer = bufnr, silent = true, nowait = true, desc = "jadx: toggle Java/Smali" })
  vim.keymap.set("n", "<leader>jm", function()
    require("jadxnvim.bookmarks").toggle()
  end, { buffer = bufnr, silent = true, nowait = true, desc = "jadx: toggle bookmark" })
  require("jadxnvim.clipboard").apply_buffer_maps(bufnr)
end

local function java_keymaps(bufnr)
  local nav = function(fn)
    return function()
      require("jadxnvim.nav")[fn]()
    end
  end
  local opts = { buffer = bufnr, silent = true, nowait = true }
  vim.keymap.set("n", "gd", nav("goto_def"), opts)
  vim.keymap.set("n", "gr", nav("find_usages"), opts)
  -- resolve a merged-lambda dispatcher call (`new X.Y(.., N)`) to its `case N:` branch
  vim.keymap.set("n", "<leader>jt", nav("resolve_task"), opts)
  vim.keymap.set("n", "<leader>jk", nav("call_hierarchy"), opts) -- incoming callers of the method
  vim.keymap.set("n", "<leader>ji", nav("type_hierarchy"), opts) -- super/subtype hierarchy
  vim.keymap.set("n", "<leader>jf", function()
    M.toggle_follow()
  end, opts) -- live Java→Smali follow split
  vim.keymap.set("n", "<leader>jr", function()
    require("jadxnvim.edit").rename()
  end, opts)
  vim.keymap.set("n", "<leader>jc", function()
    require("jadxnvim.edit").comment()
  end, opts)
  vim.keymap.set("n", "<leader>jh", nav("frida_hook"), opts) -- Frida hook: symbol under cursor
  vim.keymap.set("n", "<leader>jH", nav("frida_hook_class"), opts) -- Frida hook: whole class
  common_keymaps(bufnr)
end

-- Use treesitter only if BOTH a parser and highlight queries exist; a partial attach (parser
-- without queries) would suppress the regex syntax and leave the buffer barely highlighted.
local function ts_ready(lang)
  if not pcall(vim.treesitter.language.add, lang) then
    return false
  end
  local ok, q = pcall(vim.treesitter.query.get, lang, "highlights")
  return ok and q ~= nil
end

local function highlight(bufnr, ft)
  vim.bo[bufnr].filetype = ft
  local used_ts = false
  if ft == "java" and ts_ready("java") then
    used_ts = pcall(vim.treesitter.start, bufnr, "java")
  end
  vim.b[bufnr].jadx_ts = used_ts -- treesitter attached -> its fold expression is available
  if not used_ts then
    -- Force the regex syntax to load (works even if the session never ran `:syntax on`) and sync
    -- from the top so the whole decompiled file is highlighted, not just the visible window.
    pcall(function()
      vim.bo[bufnr].syntax = ft
    end)
    vim.api.nvim_buf_call(bufnr, function()
      pcall(vim.cmd, "syntax sync fromstart")
    end)
  end
end

local function fill_java(bufnr, id)
  local err, result = fetch("getCode", id)
  vim.bo[bufnr].modifiable = true
  if err or not result then
    vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, {
      "// jadxnvim: failed to load " .. id,
      "// " .. ((err and err.message) or "the daemon did not respond in time"),
    })
    vim.bo[bufnr].modifiable = false
    return
  end
  vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, to_lines(result.code))
  vim.bo[bufnr].modifiable = false
  vim.bo[bufnr].modified = false
  vim.b[bufnr].jadx_class_id = id
  vim.b[bufnr].jadx_full_name = result.fullName
  vim.b[bufnr].jadx_view = "java"
  highlight(bufnr, "java")
  java_keymaps(bufnr)
end

local function fill_smali(bufnr, id)
  local err, result = fetch("getSmali", id)
  vim.bo[bufnr].modifiable = true
  if err or not result then
    vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, {
      "; jadxnvim: failed to load smali for " .. id,
      "; " .. ((err and err.message) or "the daemon did not respond in time"),
    })
    vim.bo[bufnr].modifiable = false
    return
  end
  vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, to_lines(result.smali))
  vim.bo[bufnr].modifiable = false
  vim.bo[bufnr].modified = false
  vim.b[bufnr].jadx_class_id = id
  vim.b[bufnr].jadx_full_name = result.fullName
  vim.b[bufnr].jadx_view = "smali"
  highlight(bufnr, "smali")
  common_keymaps(bufnr)
end

local function fill_resource(bufnr, name)
  local err, result = fetch("getResource", name, "name")
  vim.bo[bufnr].modifiable = true
  if err or not result then
    vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, {
      "// jadxnvim: failed to load resource " .. name,
      "// " .. ((err and err.message) or "the daemon did not respond in time"),
    })
    vim.bo[bufnr].modifiable = false
    return
  end
  vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, to_lines(result.text))
  vim.bo[bufnr].modifiable = false
  vim.bo[bufnr].modified = false
  vim.b[bufnr].jadx_resource = name
  local ft = res_filetype(name)
  if ft then
    vim.bo[bufnr].filetype = ft
    highlight(bufnr, ft)
  end
  common_keymaps(bufnr)
end

-- On a bare Neovim (no colorscheme) the default theme leaves keywords/types/functions at the
-- normal foreground, so code looks flat. Give the common syntax groups a readable VS Code-like
-- palette (with 256-color fallbacks). Skipped entirely if the user has a colorscheme.
local function apply_default_colors()
  if vim.g.colors_name ~= nil then
    return
  end
  -- Enable truecolor only when the terminal advertises it; otherwise the 256-color ctermfg
  -- fallbacks are used (forcing termguicolors on a non-truecolor terminal garbles colors).
  if not vim.o.termguicolors then
    local ct = vim.env.COLORTERM
    if ct == "truecolor" or ct == "24bit" then
      vim.o.termguicolors = true
    end
  end
  local set = function(group, gui, cterm)
    pcall(vim.api.nvim_set_hl, 0, group, { fg = gui, ctermfg = cterm, default = false })
  end
  set("Comment", "#6a9955", 65)
  set("Constant", "#4fc1ff", 75)
  set("String", "#ce9178", 173)
  set("Character", "#ce9178", 173)
  set("Number", "#b5cea8", 151)
  set("Float", "#b5cea8", 151)
  set("Boolean", "#569cd6", 75)
  set("Identifier", "#9cdcfe", 117)
  set("Function", "#dcdcaa", 187)
  set("Statement", "#c586c0", 176)
  set("Conditional", "#c586c0", 176)
  set("Repeat", "#c586c0", 176)
  set("Label", "#c586c0", 176)
  set("Keyword", "#569cd6", 75)
  set("Exception", "#c586c0", 176)
  set("Operator", "#d4d4d4", 252)
  set("PreProc", "#c586c0", 176)
  set("Type", "#4ec9b0", 79)
  set("StorageClass", "#569cd6", 75)
  set("Structure", "#4ec9b0", 79)
  set("Typedef", "#4ec9b0", 79)
  set("Special", "#d7ba7d", 180)
end

--- Register the BufReadCmds that back jadx:// (Java) and jadxsmali:// (Smali) buffers. Idempotent.
function M.setup()
  -- A decompiler viewer needs highlighting on; enable the machinery if the session hasn't.
  if not vim.g.syntax_on then
    pcall(vim.cmd, "syntax enable")
  end
  pcall(vim.cmd, "filetype on")
  apply_default_colors()
  -- Highlight of the smali line the follow view is synced to (user-overridable via `default`).
  pcall(vim.api.nvim_set_hl, 0, "JadxFollowLine", { link = "Visual", default = true })

  local group = vim.api.nvim_create_augroup("jadxnvim_code", { clear = true })
  vim.api.nvim_create_autocmd("BufReadCmd", {
    group = group,
    pattern = JAVA_PREFIX .. "*",
    callback = function(ev)
      local id = ev.match:sub(#JAVA_PREFIX + 1)
      if id == "" or id == "tree" then
        return
      end
      vim.bo[ev.buf].buftype = "nofile"
      vim.bo[ev.buf].bufhidden = "hide"
      vim.bo[ev.buf].swapfile = false
      fill_java(ev.buf, id)
    end,
  })
  vim.api.nvim_create_autocmd("BufReadCmd", {
    group = group,
    pattern = SMALI_PREFIX .. "*",
    callback = function(ev)
      local id = ev.match:sub(#SMALI_PREFIX + 1)
      if id == "" then
        return
      end
      vim.bo[ev.buf].buftype = "nofile"
      vim.bo[ev.buf].bufhidden = "hide"
      vim.bo[ev.buf].swapfile = false
      fill_smali(ev.buf, id)
    end,
  })
  vim.api.nvim_create_autocmd("BufReadCmd", {
    group = group,
    pattern = RES_PREFIX .. "*",
    callback = function(ev)
      local name = ev.match:sub(#RES_PREFIX + 1)
      if name == "" then
        return
      end
      vim.bo[ev.buf].buftype = "nofile"
      vim.bo[ev.buf].bufhidden = "hide"
      vim.bo[ev.buf].swapfile = false
      fill_resource(ev.buf, name)
    end,
  })
  -- Folding is window-local, so (re)apply it whenever a jadx buffer is shown in a window.
  vim.api.nvim_create_autocmd("BufWinEnter", {
    group = group,
    pattern = { JAVA_PREFIX .. "*", SMALI_PREFIX .. "*", RES_PREFIX .. "*" },
    callback = function(ev)
      setup_folds(ev.buf, vim.api.nvim_get_current_win())
    end,
  })
end

-- The exported search index can decompile slightly differently from the on-demand view (jadx
-- inlining is non-deterministic under parallel export), so a search hit's line number is only
-- approximate. When we have the matched line's text, re-locate it in the actual buffer (nearest to
-- the approximate line) so the cursor always lands on what you searched for.
-- Nearest line whose trimmed text equals the snippet, or nil if none matches (so the caller can fall
-- back to a name-based relocation instead of a stale approximate line).
local function locate_line(bufnr, snippet, approx)
  if not snippet or snippet == "" then
    return nil
  end
  local target = vim.trim(snippet)
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local best, best_dist
  for i, l in ipairs(lines) do
    if vim.trim(l) == target then
      local d = math.abs(i - (approx or i))
      if not best_dist or d < best_dist then
        best, best_dist = i, d
      end
    end
  end
  return best
end

-- The line of the `n`-th call to `name` in the buffer (a `name(` that isn't a declaration — i.e.
-- preceded by `.`/`(`/space, not a return type). Calls are counted in source order, so this maps a
-- usage's ordinal (k-th call to the target in its class) onto the k-th call in the opened buffer even
-- when the on-demand render's line text didn't match — keeping distinct usages on distinct call sites.
local function locate_nth_call(bufnr, name, n)
  if not (name and name ~= "" and n and n >= 1) then
    return nil
  end
  local esc = vim.pesc(name)
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local count = 0
  for i, l in ipairs(lines) do
    -- a call: `name(` preceded by `.`, `(`, `,`, space, etc. — not another identifier char and not a
    -- declaration (a declaration has a return type before the name, e.g. `void name(`).
    local s = l:find("[%.%(%[,%s=+%-*/!&|?:{}]" .. esc .. "%s*%(") or l:find("^%s*" .. esc .. "%s*%(")
    if s and not l:find("[%w_%.>%]]%s+" .. esc .. "%s*%(") then
      count = count + 1
      if count == n then
        return i
      end
    end
  end
  return nil -- fewer calls than n (renders disagree on count) — caller falls back
end

-- Locate a method declaration line by name (nearest `approx`): `name(` at a word boundary that
-- isn't a call. Keeps navigation on the real declaration even if this buffer's copy decompiled
-- slightly differently from the position the daemon computed.
local function locate_method_line(bufnr, name, approx)
  if not name or name == "" then
    return approx
  end
  local esc = vim.pesc(name)
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local best, best_dist
  for i, l in ipairs(lines) do
    if l:find("[^%w_.]" .. esc .. "%s*%(") or l:find("^%s*" .. esc .. "%s*%(") then
      local d = math.abs(i - (approx or i))
      if not best_dist or d < best_dist then
        best, best_dist = i, d
      end
    end
  end
  return best or approx
end

local function open_named(name, opts)
  opts = opts or {}
  local win = M.target_win()
  vim.api.nvim_set_current_win(win)
  -- Record the current spot in the jumplist BEFORE moving, so <C-o> comes back here (go-to-def,
  -- a search/usage result, a stack frame). This is what LSP go-to-def does; without it there's no
  -- entry to return to and <C-o> lands on a stale one (E19: Mark has invalid line number).
  local same_buf = vim.api.nvim_buf_get_name(0) == name
  local navigating = opts.line or opts.find or opts.find_method or opts.source_line
  if navigating or not same_buf then
    pcall(vim.cmd, "normal! m'")
  end
  -- Only (re)load when we're not already in the target buffer. Re-`:edit`ing the same jadx buffer
  -- refetches and replaces every line, which invalidates marks/jumplist entries pointing into it.
  if not same_buf then
    vim.cmd("edit " .. vim.fn.fnameescape(name))
  end
  local bufnr = vim.api.nvim_get_current_buf()
  -- Relocate to the exact line by snippet text; if that snippet isn't in this buffer (an on-demand
  -- render can differ slightly from the one the position was computed against), fall back to the
  -- k-th call of the referenced member (find_ordinal), then to the nearest occurrence by name — so the
  -- cursor still lands on the intended call rather than a stale line.
  if opts.find then
    local exact = locate_line(bufnr, opts.find, opts.line)
    if exact then
      opts.line = exact
    else
      opts.line = (opts.find_ordinal and opts.find_method
            and locate_nth_call(bufnr, opts.find_method, opts.find_ordinal))
        or (opts.find_method and locate_method_line(bufnr, opts.find_method, opts.line))
        or opts.line
    end
  elseif opts.find_method then
    opts.line = locate_method_line(bufnr, opts.find_method, opts.line)
  end
  if opts.source_line then
    -- smali carries dex debug lines as `.line <n>`; land on that directive
    local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
    local want = "^%s*%.line%s+" .. opts.source_line .. "%s*$"
    for i, l in ipairs(lines) do
      if l:match(want) then
        opts.line = i
        break
      end
    end
  end
  if opts.line then
    local lnum = math.max(1, opts.line)
    local text = (vim.api.nvim_buf_get_lines(bufnr, lnum - 1, lnum, false))[1] or ""
    local bytecol = 0
    if opts.col and opts.col > 0 then
      local ok, bc = pcall(vim.str_byteindex, text, opts.col)
      bytecol = ok and bc or 0
    end
    pcall(vim.api.nvim_win_set_cursor, win, { lnum, bytecol })
    vim.cmd("normal! zz")
  end
  if opts.on_open then
    opts.on_open(bufnr)
  end
  return bufnr
end

--- Open (or focus) the Java code buffer for class `id`. opts: { line, col, on_open }.
function M.open(id, opts)
  return open_named(JAVA_PREFIX .. id, opts)
end

--- Open (or focus) the Smali buffer for class `id`.
function M.open_smali(id, opts)
  return open_named(SMALI_PREFIX .. id, opts)
end

--- Open (or focus) the decoded-text buffer for a resource by its path/name.
function M.open_resource(name, opts)
  return open_named(RES_PREFIX .. name, opts)
end

-- Per-class Java/Smali view state: last cursor line in each pane + which pane we last synced INTO
-- and where we landed (to tell "the user moved" from "just toggled back").
local view_mem = {}

-- Nearest method name at/above `upto` in a buffer (the enclosing method).
local function nearest_method_name(bufnr, upto, is_smali)
  local last = math.min(upto, vim.api.nvim_buf_line_count(bufnr))
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, last, false)
  local kw = { ["if"] = 1, ["for"] = 1, ["while"] = 1, ["switch"] = 1, ["catch"] = 1,
    ["return"] = 1, ["synchronized"] = 1, ["new"] = 1, ["else"] = 1, ["do"] = 1 }
  for i = #lines, 1, -1 do
    local l = lines[i]
    if is_smali then
      local n = l:match("^%s*%.method%s.-([%w_$<>]+)%(")
      if n then
        return n
      end
    else
      -- a declaration-like `name(`: preceded by a type/modifier, not a call (`obj.name(`)
      local n = l:match("[%s%*&%]>]([%w_$]+)%s*%(")
      if n and not kw[n] and not l:find("%." .. vim.pesc(n) .. "%s*%(") then
        return n
      end
    end
  end
end

-- Declaration line of `name` in a smali buffer (`.method ... name(`), or nil.
local function smali_method_line(bufnr, name)
  if not name then
    return nil
  end
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  for i, l in ipairs(lines) do
    if l:match("^%s*%.method%s.-([%w_$<>]+)%(") == name then
      return i
    end
  end
end

--- Toggle the current class buffer between Java and Smali. Remembers each pane's cursor; on switch
--- it maps to the corresponding method in the other pane, but if you toggle back without moving it
--- restores the exact previous cursor.
function M.toggle_view()
  local buf = vim.api.nvim_get_current_buf()
  local id = vim.b[buf].jadx_class_id
  if not id then
    return
  end
  local cur_is_smali = vim.b[buf].jadx_view == "smali"
  local cur_view = cur_is_smali and "smali" or "java"
  local other = cur_is_smali and "java" or "smali"
  local cur_line = vim.api.nvim_win_get_cursor(0)[1]

  local m = view_mem[id]
  if not m then
    m = {}
    view_mem[id] = m
  end
  m[cur_view] = cur_line -- remember where we are in this pane
  -- "moved" unless we're still exactly where we last landed when syncing INTO this pane
  local moved = not (m.from_view == cur_view and m.from_line == cur_line)
  local method = moved and nearest_method_name(buf, cur_line, cur_is_smali) or nil

  -- open the other view (synchronous load), then position
  local tbuf = other == "smali" and M.open_smali(id) or M.open(id)
  local win = vim.api.nvim_get_current_win()

  local target
  if not moved and m[other] then
    target = m[other] -- toggled back without moving -> exact restore
  elseif method then
    target = other == "smali" and smali_method_line(tbuf, method)
      or locate_method_line(tbuf, method, m[other])
  end
  target = target or m[other] or 1
  pcall(vim.api.nvim_win_set_cursor, win, { math.max(1, target), 0 })
  vim.cmd("normal! zz")

  m[other] = target
  m.from_view = other
  m.from_line = target
end

--- Clear remembered Java/Smali positions (on project close/reopen).
function M.reset_views()
  view_mem = {}
end

-- ============================ Java ⟷ Smali follow view ============================
-- A live split that keeps the Java and smali panes pinned to each other. Toggled with <leader>jf.
-- Unlike <Tab> (which replaces the current buffer), follow opens a dedicated smali split and syncs
-- BOTH ways: move in the Java pane and the smali scrolls to the matching bytecode; move in the smali
-- pane and the Java jumps to the matching source. The mapping is principled: jadx reports each
-- decompiled Java line's original source line (getLineMap); smali carries the same source lines as
-- `.line N` directives — so Java line ⟷ source line ⟷ the matching `.line` inside the enclosing
-- method. With no debug info (stripped dex) it degrades to method-level sync.

local follow = { active = false, jwin = nil, swin = nil, srcmaps = {}, ns = nil, syncing = false }

-- Java-line → source-line map for a class (cached per id; one daemon render). Empty on error or when
-- the dex has no line info, in which case the sync falls back to method granularity.
local function fetch_srcmap(id)
  local cached = follow.srcmaps[id]
  if cached then
    return cached
  end
  local err, result = fetch("getLineMap", id)
  local map = {}
  if not err and result and result.map then
    for k, v in pairs(result.map) do
      map[tonumber(k)] = tonumber(v)
    end
  end
  follow.srcmaps[id] = map
  return map
end

-- [start, end] buffer line range of the smali method named `name` (its `.method` decl line through
-- the matching `.end method`), or nil if not found.
local function smali_method_range(bufnr, name)
  local start = smali_method_line(bufnr, name)
  if not start then
    return nil, nil
  end
  local rest = vim.api.nvim_buf_get_lines(bufnr, start, -1, false)
  local endl = start + #rest
  for i, l in ipairs(rest) do
    if l:match("^%s*%.end%s+method") then
      endl = start + i
      break
    end
  end
  return start, endl
end

-- Buffer line of the `.line srcline` directive within [mstart, mend] (or nearest `.line` <= srcline
-- in that range). nil when no directive is at/below srcline — smali only emits `.line` where debug
-- info exists, so a Java line with no exact match snaps to the closest preceding bytecode line.
local function smali_line_for_src(bufnr, srcline, mstart, mend)
  if not srcline then
    return nil
  end
  local base = mstart or 1
  local lines = vim.api.nvim_buf_get_lines(bufnr, base - 1, mend or -1, false)
  local best, best_n
  for i, l in ipairs(lines) do
    local n = l:match("^%s*%.line%s+(%d+)")
    if n then
      n = tonumber(n)
      if n == srcline then
        return base + i - 1
      end
      if n <= srcline and (not best_n or n > best_n) then
        best, best_n = base + i - 1, n
      end
    end
  end
  return best
end

-- Mirror the Java cursor into the follow smali split: load the matching class if needed, then move
-- (and highlight) the smali line corresponding to the Java line under the cursor.
local function follow_sync()
  if not follow.active or follow.syncing then
    return
  end
  local jwin, swin = follow.jwin, follow.swin
  if not (jwin and vim.api.nvim_win_is_valid(jwin) and swin and vim.api.nvim_win_is_valid(swin)) then
    M.stop_follow()
    return
  end
  -- Only drive the sync from the Java window; ignore cursor moves in the smali split itself.
  if vim.api.nvim_get_current_win() ~= jwin then
    return
  end
  local jbuf = vim.api.nvim_win_get_buf(jwin)
  if vim.b[jbuf].jadx_view ~= "java" then
    return
  end
  local id = vim.b[jbuf].jadx_class_id
  if not id then
    return
  end
  follow.syncing = true
  local ok, err = pcall(function()
    local sbuf = vim.api.nvim_win_get_buf(swin)
    if vim.b[sbuf].jadx_class_id ~= id then
      -- Java window navigated to another class: bring the smali split along.
      vim.api.nvim_win_call(swin, function()
        vim.cmd("edit " .. vim.fn.fnameescape(SMALI_PREFIX .. id))
      end)
      sbuf = vim.api.nvim_win_get_buf(swin)
    end
    local jline = vim.api.nvim_win_get_cursor(jwin)[1]
    local srcmap = fetch_srcmap(id)
    local srcline = srcmap[jline]
    if not srcline then
      for l = jline, 1, -1 do -- nearest mapped Java line at/above the cursor
        if srcmap[l] then
          srcline = srcmap[l]
          break
        end
      end
    end
    local method = nearest_method_name(jbuf, jline, false)
    local mstart, mend
    if method then
      mstart, mend = smali_method_range(sbuf, method)
    end
    local target = smali_line_for_src(sbuf, srcline, mstart, mend) or mstart or 1
    target = math.max(1, math.min(target, vim.api.nvim_buf_line_count(sbuf)))
    pcall(vim.api.nvim_win_set_cursor, swin, { target, 0 })
    vim.api.nvim_win_call(swin, function()
      vim.cmd("normal! zz")
    end)
    vim.api.nvim_buf_clear_namespace(sbuf, follow.ns, 0, -1)
    pcall(vim.api.nvim_buf_set_extmark, sbuf, follow.ns, target - 1, 0, {
      line_hl_group = "JadxFollowLine",
    })
  end)
  follow.syncing = false
  if not ok then
    vim.notify("[jadxnvim] follow sync: " .. tostring(err), vim.log.levels.DEBUG)
  end
end

-- Name of the smali method enclosing `line` (nearest `.method ... name(` at/above it), or nil.
local function smali_method_at(bufnr, line)
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, line, false)
  for i = #lines, 1, -1 do
    local n = lines[i]:match("^%s*%.method%s.-([%w_$<>]+)%(")
    if n then
      return n
    end
  end
end

-- The source line for smali `line`: the nearest `.line N` directive at/above it, not crossing the
-- enclosing `.method` boundary. nil when the method carries no debug line info.
local function smali_src_at(bufnr, line)
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, line, false)
  for i = #lines, 1, -1 do
    local l = lines[i]
    if l:match("^%s*%.method%s") then
      return nil -- reached the method header without a `.line` above the cursor
    end
    local n = l:match("^%s*%.line%s+(%d+)")
    if n then
      return tonumber(n)
    end
  end
end

-- The Java line mapping to `srcline` (inverse of the java→source map), preferring the occurrence
-- nearest `near` (the enclosing method's decl line) so a source line reused across methods resolves
-- within the right one.
local function java_line_for_src(srcmap, srcline, near)
  if not srcline then
    return nil
  end
  local best, best_d
  for jl, src in pairs(srcmap) do
    if src == srcline then
      local d = near and math.abs(jl - near) or 0
      if not best_d or d < best_d then
        best, best_d = jl, d
      end
    end
  end
  return best
end

-- Reverse sync: mirror the smali cursor back into the Java pane (load the matching class if needed),
-- then move (and highlight) the Java line corresponding to the bytecode under the cursor.
local function follow_sync_smali()
  if not follow.active or follow.syncing then
    return
  end
  local jwin, swin = follow.jwin, follow.swin
  if not (jwin and vim.api.nvim_win_is_valid(jwin) and swin and vim.api.nvim_win_is_valid(swin)) then
    M.stop_follow()
    return
  end
  if vim.api.nvim_get_current_win() ~= swin then
    return
  end
  local sbuf = vim.api.nvim_win_get_buf(swin)
  local id = vim.b[sbuf].jadx_class_id
  if not id then
    return
  end
  follow.syncing = true
  local ok, err = pcall(function()
    local jbuf = vim.api.nvim_win_get_buf(jwin)
    if vim.b[jbuf].jadx_view ~= "java" or vim.b[jbuf].jadx_class_id ~= id then
      -- Smali showing a different class than the Java pane: bring the Java pane along.
      vim.api.nvim_win_call(jwin, function()
        vim.cmd("edit " .. vim.fn.fnameescape(JAVA_PREFIX .. id))
      end)
      jbuf = vim.api.nvim_win_get_buf(jwin)
    end
    local sline = vim.api.nvim_win_get_cursor(swin)[1]
    local srcline = smali_src_at(sbuf, sline)
    local method = smali_method_at(sbuf, sline)
    local srcmap = fetch_srcmap(id)
    local near = method and locate_method_line(jbuf, method, nil) or nil
    local target = java_line_for_src(srcmap, srcline, near) or near or 1
    target = math.max(1, math.min(target, vim.api.nvim_buf_line_count(jbuf)))
    pcall(vim.api.nvim_win_set_cursor, jwin, { target, 0 })
    vim.api.nvim_win_call(jwin, function()
      vim.cmd("normal! zz")
    end)
    vim.api.nvim_buf_clear_namespace(jbuf, follow.ns, 0, -1)
    pcall(vim.api.nvim_buf_set_extmark, jbuf, follow.ns, target - 1, 0, {
      line_hl_group = "JadxFollowLine",
    })
  end)
  follow.syncing = false
  if not ok then
    vim.notify("[jadxnvim] follow sync: " .. tostring(err), vim.log.levels.DEBUG)
  end
end

-- Route a cursor move to the sync for whichever follow pane is active (Java drives smali, or vice
-- versa). Moves the sync makes in the OTHER pane don't re-enter (guarded by follow.syncing).
local function follow_on_move()
  local cur = vim.api.nvim_get_current_win()
  if cur == follow.jwin then
    follow_sync()
  elseif cur == follow.swin then
    follow_sync_smali()
  end
end

--- Turn the Java ⟷ Smali follow split off: close the split we opened and detach the sync.
function M.stop_follow()
  if not follow.active then
    return
  end
  follow.active = false
  if follow.aug then
    pcall(vim.api.nvim_del_augroup_by_id, follow.aug)
    follow.aug = nil
  end
  if follow.ns and follow.jwin and vim.api.nvim_win_is_valid(follow.jwin) then
    pcall(vim.api.nvim_buf_clear_namespace, vim.api.nvim_win_get_buf(follow.jwin), follow.ns, 0, -1)
  end
  if follow.swin and vim.api.nvim_win_is_valid(follow.swin) then
    if follow.ns then
      pcall(vim.api.nvim_buf_clear_namespace, vim.api.nvim_win_get_buf(follow.swin), follow.ns, 0, -1)
    end
    pcall(vim.api.nvim_win_close, follow.swin, false)
  end
  follow.swin, follow.jwin = nil, nil
  vim.notify("jadxnvim: Java ⟷ Smali follow OFF", vim.log.levels.INFO)
end

--- Toggle the Java ⟷ Smali follow split. Off → open a smali split for the current class and keep the
--- two panes synced to each other on every cursor move (either direction); on → close it. Must be
--- started from a Java code buffer.
function M.toggle_follow()
  if follow.active then
    M.stop_follow()
    return
  end
  local jbuf = vim.api.nvim_get_current_buf()
  local id = vim.b[jbuf].jadx_class_id
  if not id or vim.b[jbuf].jadx_view ~= "java" then
    vim.notify("jadxnvim: open a Java class buffer first (follow links Java ⟷ Smali)", vim.log.levels.WARN)
    return
  end
  local jwin = vim.api.nvim_get_current_win()
  vim.cmd("rightbelow vsplit " .. vim.fn.fnameescape(SMALI_PREFIX .. id))
  local swin = vim.api.nvim_get_current_win()
  vim.w[swin].jadx_follow = true
  vim.wo[swin].cursorline = true
  vim.api.nvim_set_current_win(jwin) -- keep editing focus on the Java pane

  follow.active = true
  follow.jwin, follow.swin, follow.jbuf = jwin, swin, jbuf
  follow.ns = follow.ns or vim.api.nvim_create_namespace("jadxnvim_follow")
  follow.aug = vim.api.nvim_create_augroup("jadxnvim_follow", { clear = true })
  vim.api.nvim_create_autocmd({ "CursorMoved", "CursorMovedI" }, {
    group = follow.aug,
    callback = follow_on_move,
  })
  vim.api.nvim_create_autocmd("WinClosed", {
    group = follow.aug,
    callback = function(ev)
      local w = tonumber(ev.match)
      if w == follow.swin or w == follow.jwin then
        M.stop_follow()
      end
    end,
  })
  follow_sync()
  vim.notify("jadxnvim: Java ⟷ Smali follow ON (<leader>jf to turn off)", vim.log.levels.INFO)
end

--- True while the follow split is active (used by tests / callers).
function M.follow_active()
  return follow.active
end

-- A side-panel window (the project tree or the hierarchy view) is never a place to show code.
local function is_panel(win)
  local b = vim.api.nvim_win_get_buf(win)
  return vim.b[b].jadx_tree or vim.b[b].jadx_hierarchy
end

--- The window to show code in: prefer a non-panel window, else open a vertical split.
function M.target_win()
  local cur = vim.api.nvim_get_current_win()
  if not is_panel(cur) then
    return cur
  end
  for _, win in ipairs(vim.api.nvim_tabpage_list_wins(0)) do
    if not is_panel(win) then
      return win
    end
  end
  vim.cmd("rightbelow vsplit")
  return vim.api.nvim_get_current_win()
end

--- (class_id, line, char_col) for the symbol under the cursor, or nil if not a jadx code buffer.
-- Byte column (0-based) of the start of the identifier under/just-after the cursor. jadx anchors a
-- symbol's annotation at the token's *start*, so gd/gr/rename must query there — otherwise they only
-- resolve when the cursor sits on the first character of the word.
local function ident_start_bytecol(line, col0)
  local function is_ident(c)
    return c ~= "" and c:match("[%w_$]") ~= nil
  end
  local idx = col0 + 1 -- 1-based index of the char under the cursor
  if not is_ident(line:sub(idx, idx)) then
    -- cursor might be just past the end of an identifier (e.g. on the '(' after a call)
    if idx > 1 and is_ident(line:sub(idx - 1, idx - 1)) then
      idx = idx - 1
    else
      return col0 -- not on an identifier; leave the column as-is
    end
  end
  while idx > 1 and is_ident(line:sub(idx - 1, idx - 1)) do
    idx = idx - 1
  end
  return idx - 1 -- back to 0-based byte column
end

function M.cursor_target()
  local buf = vim.api.nvim_get_current_buf()
  local id = vim.b[buf].jadx_class_id
  if not id then
    return nil
  end
  local pos = vim.api.nvim_win_get_cursor(0) -- { row(1-based), col(0-based byte) }
  local line = vim.api.nvim_get_current_line()
  local bytecol = ident_start_bytecol(line, pos[2])
  local ok, charcol = pcall(vim.str_utfindex, line, bytecol)
  return id, pos[1], ok and charcol or bytecol
end

--- After an edit shifts lines (a rename/comment inserts a `/* renamed from */` etc. comment above
--- the symbol), move the cursor back onto `word` — the occurrence closest to `near_line` — so a
--- follow-up action lands on the same symbol instead of the now-shifted comment line.
function M.recenter(win, bufnr, word, near_line)
  if not (word and word ~= "" and bufnr and vim.api.nvim_buf_is_valid(bufnr)) then
    return
  end
  if not (win and vim.api.nvim_win_is_valid(win) and vim.api.nvim_win_get_buf(win) == bufnr) then
    return
  end
  local lines = vim.api.nvim_buf_get_lines(bufnr, 0, -1, false)
  local best_line, best_col, best_d
  for i, l in ipairs(lines) do
    local from = 1
    while true do
      local s, e = l:find(word, from, true)
      if not s then
        break
      end
      local before = s > 1 and l:sub(s - 1, s - 1) or ""
      local after = l:sub(e + 1, e + 1)
      if not before:match("[%w_$]") and not after:match("[%w_$]") then
        local d = math.abs(i - (near_line or i))
        if not best_d or d < best_d then
          best_line, best_col, best_d = i, s, d
        end
        break -- one (word-bounded) hit per line is enough
      end
      from = e + 1
    end
  end
  if best_line then
    pcall(vim.api.nvim_win_set_cursor, win, { best_line, best_col - 1 })
  end
end

--- Reload every open Java code buffer (after a rename/comment changes code data).
function M.refresh_all()
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    if vim.api.nvim_buf_is_valid(b) and vim.b[b].jadx_view == "java" then
      local id = vim.b[b].jadx_class_id
      if id then
        fill_java(b, id)
      end
    end
  end
end

--- Reload an already-open Java class buffer. No-op if not open.
function M.refresh(id)
  local bufnr = vim.fn.bufnr(JAVA_PREFIX .. id)
  if bufnr ~= -1 and vim.api.nvim_buf_is_valid(bufnr) then
    fill_java(bufnr, id)
  end
end

--- Wipe all decompiled-code buffers (e.g. when switching projects).
function M.reset()
  pcall(M.stop_follow)
  follow.srcmaps = {}
  view_mem = {}
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    if vim.api.nvim_buf_is_valid(b) then
      local name = vim.api.nvim_buf_get_name(b)
      if (name:find(JAVA_PREFIX, 1, true) or name:find(SMALI_PREFIX, 1, true)
            or name:find(RES_PREFIX, 1, true))
          and not name:match("jadx://tree$") then
        pcall(vim.api.nvim_buf_delete, b, { force = true })
      end
    end
  end
end

return M
