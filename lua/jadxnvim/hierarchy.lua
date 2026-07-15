-- A reusable lazy tree view, shown in a bottom split. Backs the call hierarchy (incoming callers,
-- expanded on demand) and the type hierarchy (super/subtype tree). Rows are plain node tables:
--   label       display text (indent + expand marker + icon are added by the renderer)
--   icon        optional icon glyph
--   openable    <CR>/o jumps to the node's source via opts.on_open(node)
--   expandable  <Tab>/za toggles children
--   children    preset child nodes, or nil to fetch lazily
--   expand(node, cb)  async child fetch; cb(children) is called once, then children is cached
--   _open       internal: whether the node is currently expanded

local M = {}

local state = { bufnr = nil, winid = nil, rows = {}, roots = {}, on_open = nil }

local function ic(node)
  return node.icon and node.icon ~= "" and (node.icon .. " ") or ""
end

local function marker(node)
  if node.expandable then
    return node._open and "▾" or "▸"
  end
  return " "
end

local function walk(nodes, depth, lines, rows)
  for _, n in ipairs(nodes) do
    local pad = string.rep("  ", depth)
    local suffix = n._loading and "  …" or ""
    lines[#lines + 1] = string.format("%s%s %s%s%s", pad, marker(n), ic(n), n.label, suffix)
    rows[#lines] = n
    if n.expandable and n._open and n.children then
      walk(n.children, depth + 1, lines, rows)
    end
  end
end

local function render()
  if not (state.bufnr and vim.api.nvim_buf_is_valid(state.bufnr)) then
    return
  end
  local lines, rows = {}, {}
  walk(state.roots, 0, lines, rows)
  if #lines == 0 then
    lines[1] = "  (nothing to show)"
  end
  state.rows = rows
  vim.bo[state.bufnr].modifiable = true
  vim.api.nvim_buf_set_lines(state.bufnr, 0, -1, false, lines)
  vim.bo[state.bufnr].modifiable = false
end

local function current_node()
  return state.rows[vim.api.nvim_win_get_cursor(0)[1]]
end

local function toggle(node)
  if not node or not node.expandable then
    return
  end
  if node._open then
    node._open = false
    render()
    return
  end
  if node.children then
    node._open = true
    render()
    return
  end
  if node.expand then
    node._loading = true
    render()
    node.expand(node, function(children)
      node._loading = false
      node.children = children or {}
      node.expandable = #node.children > 0
      node._open = true
      render()
    end)
  end
end

local function on_enter()
  local node = current_node()
  if not node then
    return
  end
  if node.openable and state.on_open then
    state.on_open(node)
  elseif node.expandable then
    toggle(node)
  end
end

local function setup_buffer()
  local bufnr = vim.api.nvim_create_buf(false, true)
  vim.api.nvim_buf_set_name(bufnr, "jadx://hierarchy")
  vim.bo[bufnr].buftype = "nofile"
  vim.bo[bufnr].bufhidden = "hide"
  vim.bo[bufnr].swapfile = false
  vim.bo[bufnr].filetype = "jadxhierarchy"
  vim.bo[bufnr].modifiable = false
  vim.b[bufnr].jadx_hierarchy = true

  local opts = { buffer = bufnr, nowait = true, silent = true }
  vim.keymap.set("n", "<CR>", on_enter, opts)
  vim.keymap.set("n", "o", on_enter, opts)
  vim.keymap.set("n", "<Tab>", function()
    toggle(current_node())
  end, opts)
  vim.keymap.set("n", "za", function()
    toggle(current_node())
  end, opts)
  vim.keymap.set("n", "q", function()
    M.close()
  end, opts)
  return bufnr
end

local function open_window()
  vim.cmd("botright split")
  vim.cmd("resize 14")
  local win = vim.api.nvim_get_current_win()
  vim.api.nvim_win_set_buf(win, state.bufnr)
  vim.wo[win].number = false
  vim.wo[win].relativenumber = false
  vim.wo[win].wrap = false
  vim.wo[win].cursorline = true
  vim.wo[win].winfixheight = true
  return win
end

--- Show a hierarchy tree. opts: { title, roots (node list), on_open(node) }.
function M.show(opts)
  opts = opts or {}
  state.roots = opts.roots or {}
  state.on_open = opts.on_open
  if not (state.bufnr and vim.api.nvim_buf_is_valid(state.bufnr)) then
    state.bufnr = setup_buffer()
  end
  if not (state.winid and vim.api.nvim_win_is_valid(state.winid)) then
    state.winid = open_window()
  else
    vim.api.nvim_set_current_win(state.winid)
    vim.api.nvim_win_set_buf(state.winid, state.bufnr)
  end
  if opts.title then
    pcall(vim.api.nvim_buf_set_var, state.bufnr, "jadx_hierarchy_title", opts.title)
    pcall(function()
      vim.wo[state.winid].winbar = opts.title
    end)
  end
  render()
  pcall(vim.api.nvim_win_set_cursor, state.winid, { 1, 0 })
end

function M.close()
  if state.winid and vim.api.nvim_win_is_valid(state.winid) then
    pcall(vim.api.nvim_win_close, state.winid, true)
  end
  state.winid = nil
end

--- Test/introspection helper: the currently rendered node labels (indented tree flattened).
function M.rendered_labels()
  local out = {}
  for i = 1, #state.rows do
    out[i] = state.rows[i] and state.rows[i].label
  end
  return out
end

return M
