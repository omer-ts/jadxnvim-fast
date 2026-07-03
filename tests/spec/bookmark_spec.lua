-- Bookmarks: toggle, list, navigate, and round-trip to the .jadx (jadxnvim records + jadx-gui's
-- bookmarked openTabs).
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")
local bookmarks = require("jadxnvim.bookmarks")
local project = require("jadxnvim.project")

H.spec(function(win)
  local b, lines = H.open_class(win, "com.example.Hello")
  local gl = H.locate(lines, "greet")
  H.check("found a line to bookmark", gl ~= nil)
  vim.api.nvim_win_set_cursor(win, { gl, 0 })

  bookmarks.toggle()
  H.check("bookmark added", #bookmarks.list() == 1 and bookmarks.list()[1].id == "com.example.Hello", #bookmarks.list())
  H.check("bookmark records the line", bookmarks.list()[1].line == gl)

  -- toggle again on the same line removes it, then re-add
  vim.api.nvim_win_set_cursor(win, { gl, 0 })
  bookmarks.toggle()
  H.check("toggle removes the bookmark", #bookmarks.list() == 0)
  bookmarks.toggle()
  H.check("re-added", #bookmarks.list() == 1)

  -- persist to the .jadx
  project.push()
  local jadxfile = H.fixture:gsub("%.jar$", ".jadx")
  local f = io.open(jadxfile, "r")
  H.check(".jadx exists", f ~= nil)
  local body = f and f:read("*a") or ""
  if f then
    f:close()
  end
  H.check(".jadx has jadxnvimBookmarks", body:find("jadxnvimBookmarks", 1, true) ~= nil)
  H.check(".jadx marks a bookmarked tab (jadx-gui compatible)", body:find('"bookmarked": true', 1, true) ~= nil)

  -- restore into a fresh bookmark list
  bookmarks.seed(nil, nil) -- clear
  H.check("cleared", #bookmarks.list() == 0)
  local recs = {}
  for _, r in ipairs({ { id = "com.example.Hello", line = gl, col = 0, caret = 10, text = "greet" } }) do
    recs[#recs + 1] = r
  end
  bookmarks.seed(recs, nil)
  H.check("restored from records", #bookmarks.list() == 1 and bookmarks.list()[1].line == gl)

  -- navigate via the picker
  local captured
  local fuzzy = require("jadxnvim.fuzzy")
  fuzzy.pick = function(o)
    captured = o
    return { get_filtered = function() return o.items end, results_win = 0, prompt_buf = vim.api.nvim_create_buf(false, true), close = function() end }
  end
  bookmarks.picker()
  H.check("picker lists the bookmark", captured and #captured.items == 1 and captured.items[1].id == "com.example.Hello")
  local opened
  H.code.open = (function(orig)
    return function(id, opts)
      opened = { id = id, line = opts and opts.line }
      return orig(id, opts)
    end
  end)(H.code.open)
  captured.on_select(captured.items[1])
  H.check("selecting a bookmark opens its class at the line", opened and opened.id == "com.example.Hello" and opened.line == gl,
    opened and vim.inspect(opened))
end)
