-- Bookmarks: mark interesting positions and jump back to them. Persisted to the .jadx so they
-- round-trip with jadx-gui, which stores a bookmark as an openTabs entry with `bookmarked: true`
-- and a `caret` (char offset). jadxnvim keeps a richer list (multiple per class, with a text
-- snippet) in its own preserved field, and mirrors one-per-class to openTabs for jadx-gui.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")
local fuzzy = require("jadxnvim.fuzzy")
local preview = require("jadxnvim.preview")
local icons = require("jadxnvim.icons")

local M = {}

local list = {} -- { { id, line, col, caret, text } }

local function notify(msg, level)
  vim.notify("[jadxnvim] " .. msg, level or vim.log.levels.INFO)
end

-- char offset of (line,col) given the buffer's lines (jadx-gui's tab "caret")
local function caret_of(lines, line, col)
  local off = 0
  for i = 1, math.min(line - 1, #lines) do
    off = off + #lines[i] + 1
  end
  return off + (col or 0)
end

-- (line,col) of a char offset in `lines`
local function lc_of(lines, caret)
  local off = 0
  for i, l in ipairs(lines) do
    if caret <= off + #l then
      return i, caret - off
    end
    off = off + #l + 1
  end
  return 1, 0
end

--- Toggle a bookmark at the cursor of the current code buffer.
function M.toggle()
  local buf = vim.api.nvim_get_current_buf()
  local id = vim.b[buf].jadx_class_id
  if not id then
    notify("not in a jadx code buffer", vim.log.levels.WARN)
    return
  end
  local pos = vim.api.nvim_win_get_cursor(0) -- { line(1-based), col(0-based byte) }
  local line = pos[1]
  for i, bm in ipairs(list) do
    if bm.id == id and bm.line == line then
      table.remove(list, i)
      notify("bookmark removed (" .. id:match("[^.]+$") .. ":" .. line .. ")")
      return
    end
  end
  local lines = vim.api.nvim_buf_get_lines(buf, 0, -1, false)
  local text = (lines[line] or ""):gsub("^%s+", "")
  list[#list + 1] = { id = id, line = line, col = pos[2], caret = caret_of(lines, line, pos[2]), text = text }
  notify("bookmarked " .. id:match("[^.]+$") .. ":" .. line)
end

function M.clear()
  list = {}
  notify("all bookmarks cleared")
end

function M.list()
  return list
end

--- Open the bookmarks picker: <CR> jumps, <C-x>/dd removes, <C-l> clears all.
function M.picker()
  if #list == 0 then
    notify("no bookmarks yet — add one with :JadxBookmark")
    return
  end
  local bicon = icons.get("text")
  bicon = bicon ~= "" and (bicon .. " ") or ""
  local function build()
    local items = {}
    for _, bm in ipairs(list) do
      items[#items + 1] = {
        text = string.format("%s%s:%d  %s", bicon, bm.id:match("[^.]+$"), bm.line, bm.text or ""),
        id = bm.id,
        line = bm.line,
        col = bm.col,
        bm = bm,
      }
    end
    return items
  end
  local handle = fuzzy.pick({
    title = string.format(" Bookmarks (%d) ", #list),
    items = build(),
    previewer = preview.class(),
    on_select = function(it)
      code.open(it.id, { line = it.line, col = it.col })
    end,
  })
  local function remove_selected()
    local sel = handle.get_filtered()[vim.api.nvim_win_get_cursor(handle.results_win)[1]]
    if not (sel and sel.bm) then
      return
    end
    for i, bm in ipairs(list) do
      if bm == sel.bm then
        table.remove(list, i)
        break
      end
    end
    handle.close()
    if #list > 0 then
      vim.schedule(M.picker)
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

-- ---- persistence (.jadx) -----------------------------------------------------

--- jadxnvim's own bookmark records (full fidelity), for the preserved `jadxnvimBookmarks` field.
function M.records()
  local out = {}
  for _, bm in ipairs(list) do
    out[#out + 1] = { id = bm.id, line = bm.line, col = bm.col, caret = bm.caret, text = bm.text }
  end
  return out
end

--- Bookmarked openTabs entries (jadx-gui format), one per class (jadx-gui has one tab per class).
function M.tabs()
  local seen, out = {}, {}
  for _, bm in ipairs(list) do
    if not seen[bm.id] then
      seen[bm.id] = true
      out[#out + 1] =
        { type = "class", tabPath = bm.id, subPath = "", caret = bm.caret or 0, bookmarked = true, view = { x = 0, y = 0 } }
    end
  end
  return out
end

--- Restore bookmarks from the project. Prefer jadxnvim's own records; otherwise derive them from
--- jadx-gui's bookmarked openTabs (resolving each caret to a line via the class code).
function M.seed(records, open_tabs)
  list = {}
  if type(records) == "table" and #records > 0 then
    for _, r in ipairs(records) do
      if type(r) == "table" and type(r.id) == "string" then
        list[#list + 1] = { id = r.id, line = r.line or 1, col = r.col or 0, caret = r.caret, text = r.text }
      end
    end
    return
  end
  if type(open_tabs) ~= "table" then
    return
  end
  for _, t in ipairs(open_tabs) do
    if t.bookmarked == true and type(t.tabPath) == "string" then
      local line, col = 1, 0
      local err, res = false, nil
      rpc.request("getCode", { id = t.tabPath }, function(e, r)
        err, res = e, r
      end)
      vim.wait(3000, function()
        return err ~= false or res ~= nil
      end, 20)
      local text = ""
      if res and res.code then
        local lines = vim.split(res.code:gsub("\r", ""), "\n", { plain = true })
        line, col = lc_of(lines, t.caret or 0)
        text = (lines[line] or ""):gsub("^%s+", "")
      end
      list[#list + 1] = { id = t.tabPath, line = line, col = col, caret = t.caret or 0, text = text }
    end
  end
end

return M
