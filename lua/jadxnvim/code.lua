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

local function highlight(bufnr, ft)
  vim.bo[bufnr].filetype = ft
  -- Prefer treesitter when its parser is present; otherwise force the regex syntax to load.
  -- Setting 'syntax' explicitly makes highlighting work even when the user's session never ran
  -- `:syntax on` (e.g. a bare nvim launched just to browse an APK).
  local ok_ts = false
  if ft == "java" then
    ok_ts = pcall(vim.treesitter.start, bufnr, "java")
  end
  if not ok_ts then
    pcall(function()
      vim.bo[bufnr].syntax = ft
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

--- Register the BufReadCmds that back jadx:// (Java) and jadxsmali:// (Smali) buffers. Idempotent.
function M.setup()
  -- A decompiler viewer needs highlighting on; enable the machinery if the session hasn't.
  if not vim.g.syntax_on then
    pcall(vim.cmd, "syntax enable")
  end
  pcall(vim.cmd, "filetype on")

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

local function open_named(name, opts)
  opts = opts or {}
  local win = M.target_win()
  vim.api.nvim_set_current_win(win)
  vim.cmd("edit " .. vim.fn.fnameescape(name))
  local bufnr = vim.api.nvim_get_current_buf()
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

--- Toggle the current class buffer between the Java and Smali views.
function M.toggle_view()
  local buf = vim.api.nvim_get_current_buf()
  local id = vim.b[buf].jadx_class_id
  if not id then
    return
  end
  if vim.b[buf].jadx_view == "smali" then
    M.open(id)
  else
    M.open_smali(id)
  end
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
