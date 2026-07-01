-- Saved searches ("search tabs"): every text search and xref is recorded so you can reopen it
-- later, and close one or all of them. Class/method browsers are re-runnable and not recorded.

local fuzzy = require("jadxnvim.fuzzy")

local M = {}

-- Each saved search: { title, items, previewer, on_select, time }
local saved = {}
local MAX = 30

--- Record a search so it can be reopened later. Returns nothing.
function M.record(entry)
  -- de-dup by title: replace an existing tab with the same title
  for i, e in ipairs(saved) do
    if e.title == entry.title then
      table.remove(saved, i)
      break
    end
  end
  table.insert(saved, 1, entry)
  while #saved > MAX do
    table.remove(saved)
  end
end

--- Reopen a saved search's picker.
local function reopen(entry)
  fuzzy.pick({
    title = entry.title,
    items = entry.items,
    previewer = entry.previewer,
    on_select = entry.on_select,
  })
end

function M.clear()
  saved = {}
  vim.notify("[jadxnvim] cleared all saved searches", vim.log.levels.INFO)
end

--- Open the manager: a picker listing saved searches. <CR> reopens, <C-x>/dd removes one,
--- <C-l> clears all.
function M.manager()
  if #saved == 0 then
    vim.notify("[jadxnvim] no saved searches yet", vim.log.levels.INFO)
    return
  end
  local items = {}
  for _, e in ipairs(saved) do
    items[#items + 1] = { text = e.title, entry = e }
  end
  local handle = fuzzy.pick({
    title = " Saved searches ",
    items = items,
    on_select = function(it)
      if it and it.entry then
        vim.schedule(function()
          reopen(it.entry)
        end)
      end
    end,
  })

  -- extra keymaps in the manager's prompt buffer: remove one / clear all
  local function remove_selected()
    local sel = handle.get_filtered()[vim.api.nvim_win_get_cursor(handle.results_win)[1]]
    if not (sel and sel.entry) then
      return
    end
    for i, e in ipairs(saved) do
      if e == sel.entry then
        table.remove(saved, i)
        break
      end
    end
    handle.close()
    vim.schedule(M.manager)
  end
  local o = { buffer = handle.prompt_buf, silent = true, nowait = true }
  for _, mode in ipairs({ "i", "n" }) do
    vim.keymap.set(mode, "<C-x>", remove_selected, o)
    vim.keymap.set(mode, "<C-l>", function()
      handle.close()
      M.clear()
    end, o)
  end
  vim.keymap.set("n", "dd", remove_selected, o)
end

return M
