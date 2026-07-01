-- Decompiled-code buffers.
--
-- Each class is a buffer named `jadx://<id>` (id = jadx raw/original full name). A BufReadCmd
-- loads the code on demand, which makes these buffers behave like real files for `:edit`, the
-- quickfix list, the jumplist, and `gF` — so cross-reference navigation comes for free.

local rpc = require("jadxnvim.rpc")

local M = {}

local PREFIX = "jadx://"

local function fetch_code(id)
  local done, result, errm = false, nil, nil
  rpc.request("getCode", { id = id }, function(err, res)
    errm, result, done = err, res, true
  end)
  vim.wait(20000, function()
    return done
  end, 20)
  return errm, result
end

local function set_keymaps(bufnr)
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

--- Reload every open decompiled buffer (after a rename/comment changes code data).
function M.refresh_all()
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    if vim.api.nvim_buf_is_valid(b) then
      local id = vim.b[b].jadx_class_id
      if id then
        M.refresh(id)
      end
    end
  end
end

--- Fill an (empty) buffer with the decompiled code for class `id`. Synchronous.
local function fill_buffer(bufnr, id)
  local err, result = fetch_code(id)
  vim.bo[bufnr].modifiable = true
  if err then
    vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, {
      "// jadxnvim: failed to load " .. id,
      "// " .. (err.message or "unknown error"),
    })
    vim.bo[bufnr].modifiable = false
    return
  end
  local lines = vim.split(result.code or "", "\n", { plain = true })
  vim.api.nvim_buf_set_lines(bufnr, 0, -1, false, lines)
  vim.bo[bufnr].modifiable = false
  vim.bo[bufnr].modified = false
  vim.bo[bufnr].filetype = "java"
  vim.b[bufnr].jadx_class_id = id
  vim.b[bufnr].jadx_full_name = result.fullName
  set_keymaps(bufnr)
end

--- Register the BufReadCmd that backs jadx:// code buffers. Idempotent.
function M.setup()
  local group = vim.api.nvim_create_augroup("jadxnvim_code", { clear = true })
  vim.api.nvim_create_autocmd("BufReadCmd", {
    group = group,
    pattern = PREFIX .. "*",
    callback = function(ev)
      local id = ev.match:sub(#PREFIX + 1)
      if id == "" or id == "tree" then
        return
      end
      vim.bo[ev.buf].buftype = "nofile"
      vim.bo[ev.buf].bufhidden = "hide"
      vim.bo[ev.buf].swapfile = false
      fill_buffer(ev.buf, id)
    end,
  })
end

--- Open (or focus) the code buffer for class `id`. opts: { line, col, on_open }.
--- line is 1-based; col is a 0-based character index (as returned by the daemon).
function M.open(id, opts)
  opts = opts or {}
  local win = M.target_win()
  vim.api.nvim_set_current_win(win)
  vim.cmd("edit " .. vim.fn.fnameescape(PREFIX .. id))
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

--- Reload an already-open class buffer (after rename/comment). No-op if not open.
function M.refresh(id)
  local bufnr = vim.fn.bufnr(PREFIX .. id)
  if bufnr == -1 or not vim.api.nvim_buf_is_valid(bufnr) then
    return
  end
  fill_buffer(bufnr, id)
end

--- Wipe all decompiled-code buffers (e.g. when switching projects).
function M.reset()
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    if vim.api.nvim_buf_is_valid(b) then
      local name = vim.api.nvim_buf_get_name(b)
      if name:find(PREFIX, 1, true) and not name:match("jadx://tree$") then
        pcall(vim.api.nvim_buf_delete, b, { force = true })
      end
    end
  end
end

return M
