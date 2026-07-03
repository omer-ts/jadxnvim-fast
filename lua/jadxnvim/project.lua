-- Sync jadx-gui project UI-state (open tabs, search history) between jadxnvim and the .jadx file,
-- so it round-trips with jadx-gui: jadxnvim writes what it has, and restores what it finds.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")
local searches = require("jadxnvim.searches")

local M = {}

-- Character offset of the cursor in a window showing bufnr (jadx-gui's tab "caret" is a char offset).
local function caret_of(bufnr)
  for _, w in ipairs(vim.api.nvim_list_wins()) do
    if vim.api.nvim_win_get_buf(w) == bufnr then
      local pos = vim.api.nvim_win_get_cursor(w) -- { row(1-based), col(0-based byte) }
      local before = vim.api.nvim_buf_get_lines(bufnr, 0, pos[1] - 1, false)
      local off = 0
      for _, l in ipairs(before) do
        off = off + #l + 1 -- +1 for the newline
      end
      return off + pos[2]
    end
  end
  return 0
end

-- Open Java class buffers -> jadx-gui TabViewState list (type "class", tabPath = raw class name).
function M.capture_open_tabs()
  local tabs = {}
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    if vim.api.nvim_buf_is_valid(b) then
      local id = vim.b[b].jadx_class_id
      if id and vim.b[b].jadx_view == "java" then
        tabs[#tabs + 1] =
          { type = "class", tabPath = id, subPath = "", caret = caret_of(b), view = { x = 0, y = 0 } }
      end
    end
  end
  return tabs
end

--- Push the current open tabs + search-query history into the .jadx (best-effort, async).
function M.push()
  if not rpc.is_running() then
    return
  end
  local state = {}
  local tabs = M.capture_open_tabs()
  if #tabs > 0 then
    state.openTabs = tabs -- a non-empty list encodes as a JSON array; omit when empty
  end
  local hist = searches.query_history()
  if #hist > 0 then
    state.searchHistory = hist
  end
  if state.openTabs or state.searchHistory then
    -- Wait briefly for the write so it commits before the daemon is stopped on exit.
    local done = false
    rpc.request("setProjectState", state, function()
      done = true
    end)
    vim.wait(1500, function()
      return done
    end, 20)
  end
end

--- Restore project UI-state reported by the daemon on load: seed the search history, and (only if
--- the caller says the local session didn't already restore tabs) reopen the project's tabs.
function M.restore(state, restore_tabs)
  if type(state) ~= "table" then
    return
  end
  if type(state.searchHistory) == "table" then
    searches.seed(state.searchHistory)
  end
  if restore_tabs and type(state.openTabs) == "table" then
    for _, t in ipairs(state.openTabs) do
      if t.type == "class" and type(t.tabPath) == "string" and t.tabPath ~= "" then
        pcall(code.open, t.tabPath)
      end
    end
  end
end

return M
