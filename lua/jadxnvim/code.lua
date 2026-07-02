-- Decompiled-code buffers.
--
-- Each class has a read-only Java buffer named `jadx://<id>` and (on demand) a Smali buffer named
-- `jadxsmali://<id>`. A BufReadCmd loads each on demand, so the buffers behave like real files for
-- `:edit`, quickfix, the jumplist, and `gF`. <Tab> toggles between the Java and Smali views.

local rpc = require("jadxnvim.rpc")

local M = {}

local JAVA_PREFIX = "jadx://"
local SMALI_PREFIX = "jadxsmali://"

local function fetch(method, id)
  local done, result, errm = false, nil, nil
  rpc.request(method, { id = id }, function(err, res)
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

local function common_keymaps(bufnr)
  vim.keymap.set("n", "<Tab>", function()
    M.toggle_view()
  end, { buffer = bufnr, silent = true, nowait = true, desc = "jadx: toggle Java/Smali" })
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
  vim.keymap.set("n", "<leader>jr", function()
    require("jadxnvim.edit").rename()
  end, opts)
  vim.keymap.set("n", "<leader>jc", function()
    require("jadxnvim.edit").comment()
  end, opts)
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
  if err then
    vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, {
      "// jadxnvim: failed to load " .. id,
      "// " .. (err.message or "unknown error"),
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
  if err then
    vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, {
      "; jadxnvim: failed to load smali for " .. id,
      "; " .. (err.message or "unknown error"),
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
end

-- The exported search index can decompile slightly differently from the on-demand view (jadx
-- inlining is non-deterministic under parallel export), so a search hit's line number is only
-- approximate. When we have the matched line's text, re-locate it in the actual buffer (nearest to
-- the approximate line) so the cursor always lands on what you searched for.
local function locate_line(bufnr, snippet, approx)
  if not snippet or snippet == "" then
    return approx
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
  return best or approx
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
  vim.cmd("edit " .. vim.fn.fnameescape(name))
  local bufnr = vim.api.nvim_get_current_buf()
  if opts.find then
    opts.line = locate_line(bufnr, opts.find, opts.line)
  end
  if opts.find_method then
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

--- The window to show code in: prefer a non-tree window, else open a vertical split.
function M.target_win()
  local cur = vim.api.nvim_get_current_win()
  if not vim.b[vim.api.nvim_win_get_buf(cur)].jadx_tree then
    return cur
  end
  for _, win in ipairs(vim.api.nvim_tabpage_list_wins(0)) do
    if not vim.b[vim.api.nvim_win_get_buf(win)].jadx_tree then
      return win
    end
  end
  vim.cmd("rightbelow vsplit")
  return vim.api.nvim_get_current_win()
end

--- (class_id, line, char_col) for the symbol under the cursor, or nil if not a jadx code buffer.
function M.cursor_target()
  local buf = vim.api.nvim_get_current_buf()
  local id = vim.b[buf].jadx_class_id
  if not id then
    return nil
  end
  local pos = vim.api.nvim_win_get_cursor(0) -- { row(1-based), col(0-based byte) }
  local line = vim.api.nvim_get_current_line()
  local ok, charcol = pcall(vim.str_utfindex, line, pos[2])
  return id, pos[1], ok and charcol or pos[2]
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
  view_mem = {}
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    if vim.api.nvim_buf_is_valid(b) then
      local name = vim.api.nvim_buf_get_name(b)
      if (name:find(JAVA_PREFIX, 1, true) or name:find(SMALI_PREFIX, 1, true))
          and not name:match("jadx://tree$") then
        pcall(vim.api.nvim_buf_delete, b, { force = true })
      end
    end
  end
end

return M
