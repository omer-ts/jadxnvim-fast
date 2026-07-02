-- Search history ("search tabs"): every text search, xref (find-usages) and name search is
-- recorded so you can reopen its results after closing it, preview what's in each, and delete
-- entries you don't want. Class/method browsers are re-runnable and also recorded.

local fuzzy = require("jadxnvim.fuzzy")
local icons = require("jadxnvim.icons")

local M = {}

-- Each entry: { title, items, previewer, on_select, kind, time }
local saved = {}
local MAX = 50

local function icon_for(kind)
  local key = ({ class = "class", method = "method" })[kind] or "text"
  local g = icons.get(key)
  return g ~= "" and (g .. " ") or ""
end

local function rel_age(t)
  if not t then
    return ""
  end
  local d = os.time() - t
  if d < 60 then
    return d .. "s ago"
  elseif d < 3600 then
    return math.floor(d / 60) .. "m ago"
  elseif d < 86400 then
    return math.floor(d / 3600) .. "h ago"
  end
  return math.floor(d / 86400) .. "d ago"
end

--- Record a search so it can be reopened later.
function M.record(entry)
  entry.time = entry.time or os.time()
  -- de-dup by title: replace an existing entry with the same title (keep it newest-first)
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

--- Reopen a recorded search's picker (its full results, with the original preview + open action).
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
  vim.notify("[jadxnvim] cleared search history", vim.log.levels.INFO)
end

-- Preview an entry's results (its item texts) so you can see what's inside before reopening.
local function history_previewer(item, render)
  local e = item and item.entry
  local lines = {}
  if e then
    lines[#lines + 1] = vim.trim(e.title)
    lines[#lines + 1] = ("%d result%s · %s"):format(#(e.items or {}), (#(e.items or {}) == 1) and "" or "s", rel_age(e.time))
    lines[#lines + 1] = string.rep("─", 48)
    for i, it in ipairs(e.items or {}) do
      if i > 300 then
        lines[#lines + 1] = ("… %d more"):format(#e.items - 300)
        break
      end
      lines[#lines + 1] = it.text or ""
    end
  end
  render({ lines = lines })
end

--- Open the history manager: a picker listing recorded searches with a preview of each one's
--- results. <CR> reopens the full results; <C-x> / dd removes the highlighted entry; <C-l> clears all.
function M.manager()
  if #saved == 0 then
    vim.notify("[jadxnvim] no search history yet", vim.log.levels.INFO)
    return
  end
  local function build_items()
    local items = {}
    for _, e in ipairs(saved) do
      items[#items + 1] = {
        text = string.format("%s%s   %s", icon_for(e.kind), vim.trim(e.title), rel_age(e.time)),
        entry = e,
      }
    end
    return items
  end

  local handle = fuzzy.pick({
    title = " Search history  (CR reopen · C-x delete · C-l clear) ",
    items = build_items(),
    previewer = history_previewer,
    on_select = function(it)
      if it and it.entry then
        vim.schedule(function()
          reopen(it.entry)
        end)
      end
    end,
  })

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
    if #saved > 0 then
      vim.schedule(M.manager)
    else
      vim.schedule(function()
        vim.notify("[jadxnvim] search history is now empty", vim.log.levels.INFO)
      end)
    end
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
