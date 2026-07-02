-- Project explorer: a lazy tree in a left split, split into two sections —
--   Sources   : packages (fetched up front, cheap) → classes (fetched on first expand, cached)
--   Resources : the APK's resource files, arranged as a directory tree (fetched on first expand)
-- Each row carries a type icon (see jadxnvim.icons). Press `/` to filter the visible tree.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")
local icons = require("jadxnvim.icons")

local M = {}

local state = {
  bufnr = nil,
  winid = nil,
  packages = {}, -- { {name, count, expanded, classes=nil|{...}} }
  res_root = nil, -- resource directory tree (nil until Resources first expanded)
  res_loading = false,
  sources_open = true,
  resources_open = false,
  filter = "", -- active filter ("" = none)
  rows = {}, -- line index -> row descriptor
}

local function ic(name)
  local g = icons.get(name)
  return g ~= "" and (g .. " ") or ""
end

local function matches(text, f)
  return f == "" or (text or ""):lower():find(f:lower(), 1, true) ~= nil
end

-- ---- resource directory tree -------------------------------------------------

local function new_dir(name)
  return { name = name, expanded = false, dirs = {}, files = {} }
end

local function res_insert(root, path, rtype)
  local node = root
  local parts = vim.split(path, "/", { plain = true })
  for i = 1, #parts do
    local part = parts[i]
    if part ~= "" then
      if i == #parts then
        node.files[#node.files + 1] = { name = part, fullname = path, type = rtype }
      else
        if not node.dirs[part] then
          node.dirs[part] = new_dir(part)
        end
        node = node.dirs[part]
      end
    end
  end
end

local function build_res_tree(list)
  local root = new_dir("")
  for _, r in ipairs(list) do
    res_insert(root, r.name, r.type)
  end
  return root
end

local function sorted_dir_names(node)
  local names = {}
  for k in pairs(node.dirs) do
    names[#names + 1] = k
  end
  table.sort(names)
  return names
end

-- Does this dir (or any descendant) contain a name matching the filter?
local function dir_has_match(node, f)
  if f == "" then
    return true
  end
  for _, file in ipairs(node.files) do
    if matches(file.name, f) then
      return true
    end
  end
  for _, dn in ipairs(sorted_dir_names(node)) do
    if matches(dn, f) or dir_has_match(node.dirs[dn], f) then
      return true
    end
  end
  return false
end

local function render_res(node, depth, lines, rows, f)
  local pad = string.rep("  ", depth)
  for _, dn in ipairs(sorted_dir_names(node)) do
    local d = node.dirs[dn]
    local self_match = matches(dn, f)
    if f == "" or self_match or dir_has_match(d, f) then
      local expanded = d.expanded or (f ~= "" and not self_match)
      local marker = expanded and "▾" or "▸"
      local folder = expanded and ic("folder_open") or ic("folder")
      lines[#lines + 1] = string.format("%s%s %s%s", pad, marker, folder, dn)
      rows[#lines] = { kind = "resdir", node = d }
      if expanded then
        render_res(d, depth + 1, lines, rows, self_match and "" or f)
      end
    end
  end
  local files = node.files
  local names = {}
  for _, file in ipairs(files) do
    names[#names + 1] = file
  end
  table.sort(names, function(a, b)
    return a.name < b.name
  end)
  for _, file in ipairs(names) do
    if matches(file.name, f) then
      lines[#lines + 1] = string.format("%s  %s%s", pad, ic("file"), file.name)
      rows[#lines] = { kind = "resfile", file = file }
    end
  end
end

-- ---- render ------------------------------------------------------------------

local function pkg_label(p, expanded)
  local name = p.name ~= "" and p.name or "(default package)"
  local marker = expanded and "▾" or "▸"
  return string.format("%s %s%s  (%d)", marker, ic("package"), name, p.count)
end

local function render()
  if not (state.bufnr and vim.api.nvim_buf_is_valid(state.bufnr)) then
    return
  end
  local f = state.filter
  local lines = {}
  local rows = {}

  if f ~= "" then
    lines[#lines + 1] = "/" .. f
    rows[#lines] = { kind = "filter" }
  end

  -- Sources section
  do
    local marker = state.sources_open and "▾" or "▸"
    lines[#lines + 1] = string.format("%s %s%s", marker, ic("sources"), "Sources")
    rows[#lines] = { kind = "sources" }
  end
  if state.sources_open then
    for _, p in ipairs(state.packages) do
      local pkg_name = p.name ~= "" and p.name or "(default package)"
      local classes = p.classes or {}
      local pkg_match = matches(pkg_name, f)
      local shown_classes = {}
      if p.expanded or f ~= "" then
        for _, c in ipairs(classes) do
          if pkg_match or matches(c.name, f) then
            shown_classes[#shown_classes + 1] = c
          end
        end
      end
      if f == "" or pkg_match or #shown_classes > 0 then
        local expand = p.expanded or (f ~= "" and #shown_classes > 0)
        lines[#lines + 1] = "  " .. pkg_label(p, expand)
        rows[#lines] = { kind = "package", pkg = p }
        if expand then
          for _, c in ipairs(shown_classes) do
            lines[#lines + 1] = "      " .. ic("class") .. c.name
            rows[#lines] = { kind = "class", pkg = p, class = c }
          end
        end
      end
    end
  end

  -- Resources section
  do
    local marker = state.resources_open and "▾" or "▸"
    lines[#lines + 1] = string.format("%s %s%s", marker, ic("resources"), "Resources")
    rows[#lines] = { kind = "resources" }
  end
  if state.resources_open then
    if state.res_loading then
      lines[#lines + 1] = "    loading…"
      rows[#lines] = { kind = "info" }
    elseif state.res_root then
      render_res(state.res_root, 1, lines, rows, f)
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

-- ---- data loading ------------------------------------------------------------

local function load_resources(cb)
  if state.res_root or state.res_loading then
    if cb then
      cb()
    end
    return
  end
  state.res_loading = true
  rpc.request("getResources", nil, function(err, result)
    vim.schedule(function()
      state.res_loading = false
      if err then
        vim.notify("[jadxnvim] getResources failed: " .. (err.message or "?"), vim.log.levels.ERROR)
      else
        state.res_root = build_res_tree(result.resources or {})
      end
      render()
      if cb then
        cb()
      end
    end)
  end)
end

local function expand_package(p, cb)
  if p.classes then
    if cb then
      cb()
    end
    return
  end
  rpc.request("getClasses", { package = p.name }, function(err, result)
    vim.schedule(function()
      if err then
        vim.notify("[jadxnvim] getClasses failed: " .. (err.message or "?"), vim.log.levels.ERROR)
      else
        p.classes = result.classes or {}
      end
      if cb then
        cb()
      end
    end)
  end)
end

local function on_enter()
  local row = current_row()
  if not row then
    return
  end
  if row.kind == "sources" then
    state.sources_open = not state.sources_open
    render()
  elseif row.kind == "resources" then
    state.resources_open = not state.resources_open
    if state.resources_open and not state.res_root then
      load_resources()
    else
      render()
    end
  elseif row.kind == "class" then
    code.open(row.class.id)
  elseif row.kind == "resfile" then
    code.open_resource(row.file.fullname)
  elseif row.kind == "resdir" then
    row.node.expanded = not row.node.expanded
    render()
  elseif row.kind == "package" then
    local p = row.pkg
    if not p.expanded and not p.classes then
      expand_package(p, function()
        p.expanded = true
        render()
      end)
    else
      p.expanded = not p.expanded
      render()
    end
  end
end

-- ---- filter ------------------------------------------------------------------

-- Cap how many not-yet-loaded packages a filter will pull in, so a broad filter on a huge APK
-- (thousands of packages) doesn't try to load them all at once.
local FILTER_LOAD_CAP = 60

local function set_filter(f)
  state.filter = f or ""
  if state.filter ~= "" then
    -- Classes are lazy, so a match inside a collapsed package is invisible until its classes load.
    -- Load classes for packages whose NAME matches the filter (bounded) so those folds can open and
    -- reveal their classes; render() then auto-expands them.
    local loaded = 0
    for _, p in ipairs(state.packages) do
      if loaded >= FILTER_LOAD_CAP then
        break
      end
      local pkg_name = p.name ~= "" and p.name or "(default package)"
      if p.classes == nil and not p.loading and matches(pkg_name, state.filter) then
        p.loading = true
        loaded = loaded + 1
        rpc.request("getClasses", { package = p.name }, function(err, res)
          vim.schedule(function()
            p.loading = false
            if not err then
              p.classes = res.classes or {}
            end
            if state.filter ~= "" then
              render()
            end
          end)
        end)
      end
    end
    -- the resource tree is fully in memory once loaded; make sure it is, so matches there show too
    if state.resources_open and not state.res_root then
      load_resources()
      return
    end
  end
  render()
end

local function prompt_filter()
  vim.ui.input({ prompt = "Filter tree: ", default = state.filter }, function(input)
    if input == nil then
      return
    end
    set_filter(vim.trim(input))
  end)
end

local function clear_filter()
  if state.filter ~= "" then
    set_filter("")
  end
end

-- ---- window / buffer ---------------------------------------------------------

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
  vim.keymap.set("n", "/", prompt_filter, opts)
  vim.keymap.set("n", "<Esc>", clear_filter, opts)
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

-- Package names for command-line completion (empty until the tree has loaded).
function M.package_names()
  local names = {}
  for _, p in ipairs(state.packages) do
    names[#names + 1] = p.name ~= "" and p.name or "(default package)"
  end
  return names
end

local function focus_package(p)
  if not (state.winid and vim.api.nvim_win_is_valid(state.winid)) then
    return
  end
  for i, row in pairs(state.rows) do
    if row.kind == "package" and row.pkg == p then
      vim.api.nvim_set_current_win(state.winid)
      pcall(vim.api.nvim_win_set_cursor, state.winid, { i, 0 })
      vim.cmd("normal! zz")
      return
    end
  end
end

--- Expand the tree at `name` and put the cursor on it. `name` matches getPackages names
--- ("(default package)" for the root). Waits briefly if the package list is still loading.
function M.goto_package(name)
  if not (state.winid and vim.api.nvim_win_is_valid(state.winid)) then
    M.open() -- opens the window and loads packages asynchronously
  else
    vim.api.nvim_set_current_win(state.winid)
  end
  state.filter = "" -- a stale filter would hide the target
  state.sources_open = true
  local target = (name == "(default package)") and "" or name
  local function go()
    for _, p in ipairs(state.packages) do
      if p.name == target then
        expand_package(p, function()
          p.expanded = true
          render()
          focus_package(p)
        end)
        return true
      end
    end
    return false
  end
  if not go() then
    -- packages may still be loading from M.open(); retry once shortly
    vim.defer_fn(function()
      if not go() then
        vim.notify("[jadxnvim] package not found: " .. name, vim.log.levels.WARN)
      end
    end, 400)
  end
end

function M.reset()
  state.packages = {}
  state.rows = {}
  state.res_root = nil
  state.res_loading = false
  state.resources_open = false
  state.sources_open = true
  state.filter = ""
  if state.bufnr and vim.api.nvim_buf_is_valid(state.bufnr) then
    vim.bo[state.bufnr].modifiable = true
    vim.api.nvim_buf_set_lines(state.bufnr, 0, -1, false, {})
    vim.bo[state.bufnr].modifiable = false
  end
end

return M
