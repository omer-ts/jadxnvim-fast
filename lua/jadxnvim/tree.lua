-- Project explorer: a lazy package/class tree in a left split.
--
-- Packages are fetched up front (names + counts, cheap even for huge APKs). A package's classes
-- are fetched on first expand and cached, so the tree stays responsive on 150k-class projects.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")

local M = {}

local state = {
  bufnr = nil,
  winid = nil,
  packages = {}, -- { {name, count, expanded, classes=nil|{...}} }
  rows = {}, -- line index -> { kind, pkg, class }
}

local function pkg_label(p)
  local name = p.name ~= "" and p.name or "(default package)"
  local marker = p.expanded and "▾" or "▸"
  return string.format("%s %s  (%d)", marker, name, p.count)
end

local function render()
  if not (state.bufnr and vim.api.nvim_buf_is_valid(state.bufnr)) then
    return
  end
  local lines = {}
  local rows = {}
  for _, p in ipairs(state.packages) do
    lines[#lines + 1] = pkg_label(p)
    rows[#lines] = { kind = "package", pkg = p }
    if p.expanded and p.classes then
      for _, c in ipairs(p.classes) do
        lines[#lines + 1] = "    " .. c.name
        rows[#lines] = { kind = "class", pkg = p, class = c }
      end
    end
  end
  state.rows = rows
  vim.bo[state.bufnr].modifiable = true
  vim.api.nvim_buf_set_lines(state.bufnr, 0, -1, false, lines)
  vim.bo[state.bufnr].modifiable = false
end

local function current_row()
  local lnum = vim.api.nvim_win_get_cursor(0)[1]
  return state.rows[lnum]
end

local function on_enter()
  local row = current_row()
  if not row then
    return
  end
  if row.kind == "class" then
    code.open(row.class.id)
  elseif row.kind == "package" then
    local p = row.pkg
    if not p.expanded and not p.classes then
      rpc.request("getClasses", { package = p.name }, function(err, result)
        if err then
          vim.schedule(function()
            vim.notify("[jadxnvim] getClasses failed: " .. (err.message or "?"), vim.log.levels.ERROR)
          end)
          return
        end
        vim.schedule(function()
          p.classes = result.classes or {}
          p.expanded = true
          render()
        end)
      end)
    else
      p.expanded = not p.expanded
      render()
    end
  end
end

local function setup_buffer()
  local bufnr = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_buf_set_name(bufnr, "jadx://tree")
  vim.bo[bufnr].buftype = "nofile"
  vim.bo[bufnr].bufhidden = "hide"
  vim.bo[bufnr].swapfile = false
  vim.bo[bufnr].filetype = "jadxtree"
  vim.bo[bufnr].modifiable = false
  vim.b[bufnr].jadx_tree = true

  local opts = { buffer = bufnr, nowait = true, silent = true }
  vim.keymap.set("n", "<CR>", on_enter, opts)
  vim.keymap.set("n", "o", on_enter, opts)
  return bufnr
end

local function open_window()
  vim.cmd("topleft vsplit")
  vim.cmd("vertical resize 40")
  local win = vim.api.nvim_get_current_win()
  vim.api.nvim_win_set_buf(win, state.bufnr)
  vim.wo[win].number = false
  vim.wo[win].relativenumber = false
  vim.wo[win].wrap = false
  vim.wo[win].cursorline = true
  return win
end

--- Build/show the tree, fetching the package list from the daemon.
function M.open()
  if not (state.bufnr and vim.api.nvim_buf_is_valid(state.bufnr)) then
    state.bufnr = setup_buffer()
  end
  if not (state.winid and vim.api.nvim_win_is_valid(state.winid)) then
    state.winid = open_window()
  else
    vim.api.nvim_set_current_win(state.winid)
  end

  rpc.request("getPackages", nil, function(err, result)
    if err then
      vim.schedule(function()
        vim.notify("[jadxnvim] getPackages failed: " .. (err.message or "?"), vim.log.levels.ERROR)
      end)
      return
    end
    vim.schedule(function()
      state.packages = {}
      for _, p in ipairs(result.packages or {}) do
        state.packages[#state.packages + 1] =
          { name = p.name, count = p.count, expanded = false, classes = nil }
      end
      render()
    end)
  end)
end

function M.reset()
  state.packages = {}
  state.rows = {}
  if state.bufnr and vim.api.nvim_buf_is_valid(state.bufnr) then
    vim.bo[state.bufnr].modifiable = true
    vim.api.nvim_buf_set_lines(state.bufnr, 0, -1, false, {})
    vim.bo[state.bufnr].modifiable = false
  end
end

return M
