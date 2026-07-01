-- A small self-contained fuzzy picker (no external plugin dependency).
--
-- Two modes:
--   * static  - pass `items`; typing filters them locally via Neovim's built-in `matchfuzzy`.
--   * dynamic - pass `on_query(query, emit)`; typing (debounced) calls the provider, which may
--               call `emit(items)` repeatedly to stream results in live.
--
-- A floating results window shows matches; a one-line prompt below drives it. <CR> selects,
-- <C-n>/<C-p> or <Up>/<Down> move, <Esc>/<C-c> cancels.

local uv = vim.uv or vim.loop

local M = {}

--- Pure filter (exposed for testing). Returns matching items, best first, capped at `limit`.
function M.filter(items, query, limit)
  limit = limit or 200
  if not query or query == "" then
    local out = {}
    for i = 1, math.min(#items, limit) do
      out[i] = items[i]
    end
    return out
  end
  local ok, res = pcall(vim.fn.matchfuzzy, items, query, { key = "text", limit = limit })
  if ok then
    return res
  end
  local out, q = {}, query:lower()
  for _, it in ipairs(items) do
    if it.text:lower():find(q, 1, true) then
      out[#out + 1] = it
      if #out >= limit then
        break
      end
    end
  end
  return out
end

--- Open the picker. opts:
---   { title, on_select(item), limit, debounce,
---     items                       -- static mode
---     on_query(query, emit),      -- dynamic mode
---     on_close() }
function M.pick(opts)
  local items = opts.items or {}
  local on_select = opts.on_select or function() end
  local on_query = opts.on_query
  local on_close = opts.on_close
  local title = opts.title or " jadx "
  local max_display = opts.limit or 200
  local debounce_ms = opts.debounce or 150

  local total_w, total_h = vim.o.columns, vim.o.lines
  local width = math.max(20, math.min(110, math.floor(total_w * 0.8)))
  local height = math.max(3, math.min(25, math.floor(total_h * 0.6)))
  local row = math.max(0, math.floor((total_h - height) / 2) - 1)
  local col = math.floor((total_w - width) / 2)

  local results_buf = vim.api.nvim_create_buf(false, true)
  local prompt_buf = vim.api.nvim_create_buf(false, true)
  vim.bo[results_buf].bufhidden = "wipe"
  vim.bo[prompt_buf].bufhidden = "wipe"

  local results_win = vim.api.nvim_open_win(results_buf, false, {
    relative = "editor", width = width, height = height, row = row, col = col,
    style = "minimal", border = "rounded", title = title, title_pos = "center",
  })
  local prompt_win = vim.api.nvim_open_win(prompt_buf, true, {
    relative = "editor", width = width, height = 1, row = row + height + 1, col = col,
    style = "minimal", border = "rounded", title = " filter ", title_pos = "left",
  })
  vim.wo[results_win].cursorline = true
  vim.wo[results_win].wrap = false

  local closed = false
  local current = {}
  local query_seq = 0
  local timer

  local function show(list)
    current = list or {}
    if not vim.api.nvim_buf_is_valid(results_buf) then
      return
    end
    local prev = vim.api.nvim_win_is_valid(results_win)
        and vim.api.nvim_win_get_cursor(results_win)[1] or 1
    local lines = {}
    for i, it in ipairs(current) do
      lines[i] = it.text
    end
    vim.bo[results_buf].modifiable = true
    vim.api.nvim_buf_set_lines(results_buf, 0, -1, false, #lines > 0 and lines or { "" })
    vim.bo[results_buf].modifiable = false
    if vim.api.nvim_win_is_valid(results_win) then
      local target = math.max(1, math.min(#current > 0 and #current or 1, prev))
      pcall(vim.api.nvim_win_set_cursor, results_win, { target, 0 })
      pcall(vim.api.nvim_win_set_config, results_win, { title = title .. ("(%d)"):format(#current) })
    end
  end

  local function prompt_text()
    return (vim.api.nvim_buf_get_lines(prompt_buf, 0, 1, false))[1] or ""
  end

  local function recompute()
    local q = prompt_text()
    if on_query then
      query_seq = query_seq + 1
      local seq = query_seq
      on_query(q, function(list)
        vim.schedule(function()
          if not closed and seq == query_seq then
            show(list)
          end
        end)
      end)
    else
      show(M.filter(items, q, max_display))
    end
  end

  local function on_change()
    if on_query and debounce_ms > 0 then
      if timer then
        timer:stop()
        timer:close()
        timer = nil
      end
      timer = uv.new_timer()
      timer:start(debounce_ms, 0, function()
        timer:stop()
        timer:close()
        timer = nil
        vim.schedule(recompute)
      end)
    else
      recompute()
    end
  end

  local function move(delta)
    if not vim.api.nvim_win_is_valid(results_win) or #current == 0 then
      return
    end
    local cur = vim.api.nvim_win_get_cursor(results_win)[1]
    local nl = math.max(1, math.min(#current, cur + delta))
    vim.api.nvim_win_set_cursor(results_win, { nl, 0 })
  end

  local function close()
    if closed then
      return
    end
    closed = true
    if timer then
      pcall(function()
        timer:stop()
        timer:close()
      end)
      timer = nil
    end
    pcall(vim.api.nvim_win_close, prompt_win, true)
    pcall(vim.api.nvim_win_close, results_win, true)
    pcall(vim.cmd, "stopinsert")
    if on_close then
      pcall(on_close)
    end
  end

  local function accept()
    local item
    if vim.api.nvim_win_is_valid(results_win) and #current > 0 then
      item = current[vim.api.nvim_win_get_cursor(results_win)[1]]
    end
    close()
    if item then
      vim.schedule(function()
        on_select(item)
      end)
    end
  end

  local group = vim.api.nvim_create_augroup("jadxnvim_fuzzy", { clear = true })
  vim.api.nvim_create_autocmd({ "TextChangedI", "TextChanged" }, {
    group = group, buffer = prompt_buf, callback = on_change,
  })
  vim.api.nvim_create_autocmd("BufLeave", { group = group, buffer = prompt_buf, callback = close })

  local kopts = { buffer = prompt_buf, nowait = true, silent = true }
  for _, mode in ipairs({ "i", "n" }) do
    vim.keymap.set(mode, "<Down>", function() move(1) end, kopts)
    vim.keymap.set(mode, "<Up>", function() move(-1) end, kopts)
    vim.keymap.set(mode, "<C-n>", function() move(1) end, kopts)
    vim.keymap.set(mode, "<C-p>", function() move(-1) end, kopts)
    vim.keymap.set(mode, "<CR>", accept, kopts)
    vim.keymap.set(mode, "<Esc>", close, kopts)
    vim.keymap.set(mode, "<C-c>", close, kopts)
  end

  recompute()
  vim.cmd("startinsert")

  return {
    close = close,
    render = recompute,
    accept = accept,
    prompt_buf = prompt_buf,
    results_win = results_win,
    get_filtered = function() return current end,
  }
end

return M
