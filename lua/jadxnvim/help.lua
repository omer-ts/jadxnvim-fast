-- A command palette / cheat-sheet: lists every jadxnvim action with its shortcut, and runs the one
-- you pick. Context actions (gd, rename, …) run against the window you were in before opening it.

local M = {}

-- Build the action list. `key` is looked up from the configured finder keys where relevant, or is
-- the fixed buffer-local mapping. `ctx` = "code" actions need the cursor in a jadx:// code buffer.
local function entries()
  local cfg = (require("jadxnvim").config or {})
  local k = cfg.keys or {}
  local function run(mod, fn)
    return function()
      require("jadxnvim." .. mod)[fn]()
    end
  end
  local function excmd(c)
    return function()
      vim.cmd(c)
    end
  end
  return {
    -- Find & search (work anywhere a project is open)
    { group = "Find", key = k.find_combined, name = "Search everywhere", desc = "classes + methods + text in one list", run = run("find", "combined") },
    { group = "Find", key = k.find_text, name = "Find text", desc = "fuzzy full-text search", run = run("find", "text") },
    { group = "Find", key = k.find_classes, name = "Find class", desc = "fuzzy class name", run = run("find", "classes") },
    { group = "Find", key = k.find_methods, name = "Find method", desc = "fuzzy method name (jumps to decl)", run = run("find", "methods") },
    { group = "Find", key = k.saved_searches, name = "Search history", desc = "reopen / delete past searches & xrefs", run = run("searches", "manager") },
    { group = "Find", key = k.bookmarks, name = "Bookmarks", desc = "jump to / delete bookmarks", run = run("bookmarks", "picker") },
    { group = "Find", key = ":JadxSearch", name = "Full-text search → quickfix", desc = "streamed grep into the quickfix list", run = excmd("JadxSearch") },
    { group = "Find", key = ":JadxSearchName", name = "Name search → quickfix", desc = "class/method/field names", run = excmd("JadxSearchName") },

    -- Navigate
    { group = "Navigate", key = "gd", name = "Go to definition", desc = "resolve symbol (implementations picker for interfaces)", ctx = "code", run = run("nav", "goto_def") },
    { group = "Navigate", key = "gr", name = "Find usages", desc = "cross-references (incl. polymorphic)", ctx = "code", run = run("nav", "find_usages") },
    { group = "Navigate", key = "<Tab>", name = "Toggle Java ⟷ Smali", desc = "in a code buffer", ctx = "code", run = run("code", "toggle_view") },
    { group = "Navigate", key = ":JadxTree", name = "Focus the tree", desc = "project explorer (/ to filter)", run = run("tree", "open") },
    { group = "Navigate", key = ":JadxGotoPackage", name = "Go to package", desc = "jump the tree to a package (Tab-completes)", run = excmd("JadxGotoPackage") },
    { group = "Navigate", key = ":JadxGotoSource", name = "Stack frame → smali line", desc = "Class(File.java:line) → smali .line", run = excmd("JadxGotoSource") },
    { group = "Navigate", key = ":JadxGotoSourceJava", name = "Stack frame → Java", desc = "nearest decompiled position", run = excmd("JadxGotoSourceJava") },

    -- Edit
    { group = "Edit", key = "<leader>jr", name = "Rename", desc = "persists to the .jadx", ctx = "code", run = run("edit", "rename") },
    { group = "Edit", key = "<leader>jc", name = "Comment", desc = "persists to the .jadx", ctx = "code", run = run("edit", "comment") },
    { group = "Edit", key = "<leader>jm", name = "Toggle bookmark", desc = "at the cursor", ctx = "code", run = run("bookmarks", "toggle") },

    -- Tools
    { group = "Tools", key = "<leader>jh", name = "Frida hook (symbol)", desc = "method — every impl for an interface call", ctx = "code", run = run("nav", "frida_hook") },
    { group = "Tools", key = "<leader>jH", name = "Frida hook (class)", desc = "every method of this class", ctx = "code", run = run("nav", "frida_hook_class") },

    -- Project
    { group = "Project", key = ":JadxClose", name = "Close project", desc = "stop the daemon", run = excmd("JadxClose") },
  }
end

--- Open the help palette. Pick an entry to run it.
function M.menu()
  local fuzzy = require("jadxnvim.fuzzy")
  local list = entries()
  -- widest key for column alignment
  local kw = 4
  for _, e in ipairs(list) do
    local key = (e.key and e.key ~= "" and tostring(e.key)) or ""
    kw = math.max(kw, #key)
  end
  local items = {}
  for _, e in ipairs(list) do
    local key = (e.key and e.key ~= "" and tostring(e.key)) or "—"
    items[#items + 1] = {
      text = string.format("%-9s %-" .. kw .. "s  %-26s %s", e.group, key, e.name, e.desc),
      run = e.run,
    }
  end
  fuzzy.pick({
    title = " jadxnvim — commands & shortcuts (Enter runs) ",
    items = items,
    on_select = function(it)
      if it and it.run then
        vim.schedule(function()
          local ok, err = pcall(it.run)
          if not ok then
            vim.notify("[jadxnvim] " .. tostring(err), vim.log.levels.ERROR)
          end
        end)
      end
    end,
  })
end

return M
