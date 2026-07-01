-- A small floating progress indicator shown while the daemon loads a project.
--
-- jadx's load() (dex parse + class-model build) exposes no progress callback, so during that phase
-- this is an animated activity bar with elapsed time. If the daemon ever sends a determinate
-- percentage (loadProgress notifications), `update()` switches the bar to a real 0-100% fill.

local uv = vim.uv or vim.loop

local M = {}

local SPINNER = { "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏" }
local BAR_W = 28

local state = { win = nil, buf = nil, timer = nil, t0 = 0, frame = 0, title = "", percent = nil }

local function bar_indeterminate(frame)
  local span = BAR_W * 2
  local pos = frame % span
  if pos >= BAR_W then
    pos = span - pos
  end
  local out = {}
  for i = 0, BAR_W - 1 do
    out[#out + 1] = (i >= pos and i < pos + 5) and "█" or "░"
  end
  return table.concat(out)
end

local function bar_determinate(percent)
  local filled = math.floor(BAR_W * math.max(0, math.min(100, percent)) / 100)
  return string.rep("█", filled) .. string.rep("░", BAR_W - filled)
end

local function render()
  if not (state.buf and vim.api.nvim_buf_is_valid(state.buf)) then
    return
  end
  state.frame = state.frame + 1
  local elapsed = (uv.now() - state.t0) / 1000
  local spin = SPINNER[(state.frame % #SPINNER) + 1]
  local line
  if state.percent then
    line = string.format(" %s  %s  [%s] %3d%%  %4.1fs", spin, state.title,
      bar_determinate(state.percent), state.percent, elapsed)
  else
    line = string.format(" %s  %s  [%s]  %4.1fs", spin, state.title, bar_indeterminate(state.frame), elapsed)
  end
  vim.bo[state.buf].modifiable = true
  vim.api.nvim_buf_set_lines(state.buf, 0, -1, false, { line })
  vim.bo[state.buf].modifiable = false
end

--- Show the progress bar. Indeterminate until update() is called with a percentage.
function M.start(title)
  M.finish()
  state.title = title or "Loading"
  state.frame = 0
  state.percent = nil
  state.t0 = uv.now()

  state.buf = vim.api.nvim_create_buf(false, true)
  vim.bo[state.buf].bufhidden = "wipe"
  local width = math.min(70, vim.o.columns - 2)
  state.win = vim.api.nvim_open_win(state.buf, false, {
    relative = "editor", anchor = "SE",
    row = vim.o.lines - 2, col = vim.o.columns - 1,
    width = width, height = 1, style = "minimal", border = "rounded",
    focusable = false, noautocmd = true, zindex = 200,
  })
  render()
  state.timer = uv.new_timer()
  state.timer:start(90, 90, vim.schedule_wrap(render))
end

--- Switch to a determinate percentage (0-100).
function M.update(percent, title)
  if title then
    state.title = title
  end
  state.percent = percent
  vim.schedule(render)
end

function M.active()
  return state.win ~= nil and vim.api.nvim_win_is_valid(state.win)
end

function M.finish()
  if state.timer then
    pcall(function()
      state.timer:stop()
      state.timer:close()
    end)
    state.timer = nil
  end
  if state.win and vim.api.nvim_win_is_valid(state.win) then
    pcall(vim.api.nvim_win_close, state.win, true)
  end
  if state.buf and vim.api.nvim_buf_is_valid(state.buf) then
    pcall(vim.api.nvim_buf_delete, state.buf, { force = true })
  end
  state.win, state.buf = nil, nil
end

return M
