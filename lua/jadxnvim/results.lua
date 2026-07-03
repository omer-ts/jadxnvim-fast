-- Send a picker's results (searches / usages / history) to a persistent scratch pane: a normal,
-- searchable, yankable buffer where <CR> on a line opens that result in the code view.

local M = {}

local panes = {} -- bufnr -> { items, open }
local counter = 0

--- Open a split listing `items` (each rendered as item.text). <CR> on a line runs `open_fn(item)`.
function M.to_pane(items, open_fn, title)
  if not items or #items == 0 then
    vim.notify("[jadxnvim] no results to send to a pane", vim.log.levels.WARN)
    return
  end
  counter = counter + 1
  vim.cmd("botright split")
  vim.cmd("resize 15")
  local buf = vim.api.nvim_create_buf(true, true)
  vim.api.nvim_win_set_buf(0, buf)
  pcall(vim.api.nvim_buf_set_name, buf, string.format("jadx-results-%d-%s", counter, title or "results"))
  vim.bo[buf].buftype = "nofile"
  vim.bo[buf].bufhidden = "hide"
  vim.bo[buf].swapfile = false
  vim.bo[buf].filetype = "jadxresults"

  local lines = {}
  for _, it in ipairs(items) do
    lines[#lines + 1] = (it.text or ""):gsub("[\r\n]", " ")
  end
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, lines)
  vim.bo[buf].modifiable = false

  panes[buf] = { items = items, open = open_fn }
  local win = vim.api.nvim_get_current_win()
  vim.wo[win].cursorline = true
  vim.wo[win].wrap = false
  vim.wo[win].number = false

  local o = { buffer = buf, nowait = true, silent = true }
  local function open_here()
    local rec = panes[buf]
    if not rec then
      return
    end
    local ln = vim.api.nvim_win_get_cursor(0)[1]
    local it = rec.items[ln]
    if it and rec.open then
      rec.open(it)
    end
  end
  vim.keymap.set("n", "<CR>", open_here, o)
  vim.keymap.set("n", "o", open_here, o)
  vim.keymap.set("n", "q", function()
    pcall(vim.api.nvim_win_close, 0, true)
  end, o)
  vim.api.nvim_create_autocmd("BufWipeout", {
    buffer = buf,
    callback = function()
      panes[buf] = nil
    end,
  })
  vim.notify(string.format("[jadxnvim] %d results in a pane — <CR> opens, q closes", #items), vim.log.levels.INFO)
  return buf
end

return M
