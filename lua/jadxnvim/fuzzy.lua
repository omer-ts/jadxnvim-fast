-- A self-contained fuzzy picker (no external plugin dependency). Everything lives inside the
-- floating popup: the input prompt, the streaming results, and a status line — nothing leaks to
-- Neovim's command line or statusline.
--
-- Modes:
--   * static      - pass `items`; typing filters them locally via `matchfuzzy`.
--   * query+collect - pass `query_phase = { prompt, on_submit(term, handle) }`. The popup opens
--       asking for a search term; <CR> runs on_submit, which streams results in via
--       handle.append(list)/handle.done(); then typing filters the collected results.

local uv = vim.uv or vim.loop

local M = {}

local SPINNER = { "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏" }

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

function M.pick(opts)
  local items = opts.items or {}
  local on_select = opts.on_select or function() end
  local title = opts.title or " jadx "
  local max_display = opts.limit or 200
  local query_phase = opts.query_phase
  local loading = opts.loading or false

  local cleanups = {}
  if opts.on_close then
    cleanups[#cleanups + 1] = opts.on_close
  end

  local mode = query_phase and "query" or "filter"

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
    footer = "", footer_pos = "right",
  })
  local prompt_win = vim.api.nvim_open_win(prompt_buf, true, {
    relative = "editor", width = width, height = 1, row = row + height + 1, col = col,
    style = "minimal", border = "rounded", title = " search term ", title_pos = "left",
  })
  vim.wo[results_win].cursorline = true
  vim.wo[results_win].wrap = false

  local handle
  local closed = false
  local current = {}
  local spin_frame, spin_timer = 0, nil

  local function prompt_text()
    return (vim.api.nvim_buf_get_lines(prompt_buf, 0, 1, false))[1] or ""
  end

  local function set_prompt_title()
    local t = (mode == "query") and (" " .. (query_phase.prompt or "search term") .. " ") or " filter "
    pcall(vim.api.nvim_win_set_config, prompt_win, { title = t, title_pos = "left" })
  end

  local function status_text()
    if mode == "query" then
      return " ⏎ search "
    end
    if loading then
      return string.format(" %s %d found · %d shown ", SPINNER[(spin_frame % #SPINNER) + 1], #items, #current)
    end
    return string.format(" %d / %d ", #current, #items)
  end

  local function update_status()
    if vim.api.nvim_win_is_valid(results_win) then
      pcall(vim.api.nvim_win_set_config, results_win, { footer = status_text(), footer_pos = "right" })
    end
  end

  local function set_lines(lines)
    vim.bo[results_buf].modifiable = true
    vim.api.nvim_buf_set_lines(results_buf, 0, -1, false, #lines > 0 and lines or { "" })
    vim.bo[results_buf].modifiable = false
  end

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
    set_lines(lines)
    if vim.api.nvim_win_is_valid(results_win) then
      local target = math.max(1, math.min(#current > 0 and #current or 1, prev))
      pcall(vim.api.nvim_win_set_cursor, results_win, { target, 0 })
    end
    update_status()
  end

  local function recompute()
    if mode == "query" then
      current = {}
      set_lines({ "", "   Type a search term above, then press <Enter>.", "" })
      update_status()
      return
    end
    show(M.filter(items, prompt_text(), max_display))
  end

  local function stop_spinner()
    if spin_timer then
      pcall(function()
        spin_timer:stop()
        spin_timer:close()
      end)
      spin_timer = nil
    end
  end

  local function start_spinner()
    if spin_timer then
      return
    end
    spin_timer = uv.new_timer()
    spin_timer:start(120, 120, vim.schedule_wrap(function()
      spin_frame = spin_frame + 1
      update_status()
    end))
  end

  local function move(delta)
    if not vim.api.nvim_win_is_valid(results_win) or #current == 0 then
      return
    end
    local cur = vim.api.nvim_win_get_cursor(results_win)[1]
    vim.api.nvim_win_set_cursor(results_win, { math.max(1, math.min(#current, cur + delta)), 0 })
  end

  local function close()
    if closed then
      return
    end
    closed = true
    stop_spinner()
    pcall(vim.api.nvim_win_close, prompt_win, true)
    pcall(vim.api.nvim_win_close, results_win, true)
    pcall(vim.cmd, "stopinsert")
    for _, fn in ipairs(cleanups) do
      pcall(fn)
    end
  end

  local function accept()
    if mode == "query" then
      local term = prompt_text()
      mode = "filter"
      set_prompt_title()
      vim.api.nvim_buf_set_lines(prompt_buf, 0, -1, false, { "" })
      recompute()
      if query_phase and query_phase.on_submit then
        query_phase.on_submit(term, handle)
      end
      return
    end
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

  local function on_change()
    if mode == "filter" then
      recompute()
    end
  end

  local group = vim.api.nvim_create_augroup("jadxnvim_fuzzy", { clear = true })
  vim.api.nvim_create_autocmd({ "TextChangedI", "TextChanged" }, {
    group = group, buffer = prompt_buf, callback = on_change,
  })
  vim.api.nvim_create_autocmd("BufLeave", { group = group, buffer = prompt_buf, callback = close })

  local kopts = { buffer = prompt_buf, nowait = true, silent = true }
  for _, m in ipairs({ "i", "n" }) do
    vim.keymap.set(m, "<Down>", function() move(1) end, kopts)
    vim.keymap.set(m, "<Up>", function() move(-1) end, kopts)
    vim.keymap.set(m, "<C-n>", function() move(1) end, kopts)
    vim.keymap.set(m, "<C-p>", function() move(-1) end, kopts)
    vim.keymap.set(m, "<CR>", accept, kopts)
    vim.keymap.set(m, "<Esc>", close, kopts)
    vim.keymap.set(m, "<C-c>", close, kopts)
  end

  handle = {
    close = close,
    render = recompute,
    accept = accept,
    prompt_buf = prompt_buf,
    results_win = results_win,
    get_filtered = function() return current end,
    status = status_text,
    on_cleanup = function(fn) cleanups[#cleanups + 1] = fn end,
    set_title = function(t)
      title = t
      pcall(vim.api.nvim_win_set_config, results_win, { title = t, title_pos = "center" })
    end,
    set_loading = function(v)
      loading = v and true or false
      if loading then
        start_spinner()
      else
        stop_spinner()
      end
      update_status()
    end,
    append = function(list)
      for _, it in ipairs(list or {}) do
        items[#items + 1] = it
      end
      if mode == "filter" then
        recompute()
      end
    end,
    done = function()
      loading = false
      stop_spinner()
      update_status()
    end,
  }

  set_prompt_title()
  if loading then
    start_spinner()
  end
  recompute()
  vim.cmd("startinsert")
  return handle
end

return M
