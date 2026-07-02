-- Command definitions for jadxnvim. Loading is lazy: requiring the module only happens
-- when a command runs.

if vim.g.loaded_jadxnvim then
  return
end
vim.g.loaded_jadxnvim = true

vim.api.nvim_create_user_command("Jadx", function(opts)
  local temp = false
  local path
  for _, a in ipairs(opts.fargs) do
    if a == "--temp" then
      temp = true
    else
      path = a
    end
  end
  require("jadxnvim").open(path, { temp = temp })
end, {
  nargs = "+",
  complete = "file",
  desc = "Open an APK/dex/jar/.jadx project (append --temp to not write a .jadx)",
})

vim.api.nvim_create_user_command("JadxTree", function()
  require("jadxnvim").tree()
end, { desc = "Focus the jadxnvim project tree" })

vim.api.nvim_create_user_command("JadxGotoPackage", function(opts)
  require("jadxnvim.tree").goto_package(opts.args)
end, {
  nargs = 1,
  complete = function(arglead)
    local names = require("jadxnvim.tree").package_names()
    if arglead == "" then
      return names
    end
    local out = {}
    for _, n in ipairs(names) do
      if n:lower():find(arglead:lower(), 1, true) then
        out[#out + 1] = n
      end
    end
    return out
  end,
  desc = "jadxnvim: jump the tree to a package (with completion)",
})

vim.api.nvim_create_user_command("JadxClose", function()
  require("jadxnvim").close()
end, { desc = "Close the jadxnvim project and stop the daemon" })

vim.api.nvim_create_user_command("JadxDef", function()
  require("jadxnvim.nav").goto_def()
end, { desc = "jadxnvim: go to definition under cursor" })

vim.api.nvim_create_user_command("JadxUsages", function()
  require("jadxnvim.nav").find_usages()
end, { desc = "jadxnvim: find usages of symbol under cursor" })

vim.api.nvim_create_user_command("JadxSearch", function(opts)
  require("jadxnvim.search").text(opts.args)
end, { nargs = "?", desc = "jadxnvim: full-text search across decompiled code" })

vim.api.nvim_create_user_command("JadxSearchName", function(opts)
  require("jadxnvim.search").name(opts.args)
end, { nargs = "?", desc = "jadxnvim: search class/method/field names" })

vim.api.nvim_create_user_command("JadxSearchCancel", function()
  require("jadxnvim.search").cancel()
end, { desc = "jadxnvim: cancel the running search" })

vim.api.nvim_create_user_command("JadxSearches", function()
  require("jadxnvim.searches").manager()
end, { desc = "jadxnvim: reopen/close saved searches (search tabs)" })

-- Discoverable alias for the same search-history manager.
vim.api.nvim_create_user_command("JadxHistory", function()
  require("jadxnvim.searches").manager()
end, { desc = "jadxnvim: search history — reopen or delete past searches/xrefs" })

vim.api.nvim_create_user_command("JadxSearchesClear", function()
  require("jadxnvim.searches").clear()
end, { desc = "jadxnvim: close all saved searches" })

vim.api.nvim_create_user_command("JadxFindClass", function()
  require("jadxnvim.find").classes()
end, { desc = "jadxnvim: fuzzy-find a class" })

vim.api.nvim_create_user_command("JadxFindMethod", function()
  require("jadxnvim.find").methods()
end, { desc = "jadxnvim: fuzzy-find a method" })

vim.api.nvim_create_user_command("JadxFindText", function(opts)
  require("jadxnvim.find").text(opts.args)
end, { nargs = "?", desc = "jadxnvim: fuzzy-find across full-text search results" })

vim.api.nvim_create_user_command("JadxFindAll", function()
  require("jadxnvim.find").combined()
end, { desc = "jadxnvim: combined search (classes + methods + text)" })

vim.api.nvim_create_user_command("JadxGotoSource", function(opts)
  require("jadxnvim.stack").goto_source(opts.args)
end, {
  nargs = "?",
  desc = "jadxnvim: jump a stack-trace frame to its smali source line (Class(File.java:line))",
})

vim.api.nvim_create_user_command("JadxGotoSourceJava", function(opts)
  require("jadxnvim.stack").goto_source_java(opts.args)
end, {
  nargs = "?",
  desc = "jadxnvim: jump a stack-trace frame to the decompiled Java (nearest position/method)",
})

vim.api.nvim_create_user_command("JadxRename", function()
  require("jadxnvim.edit").rename()
end, { desc = "jadxnvim: rename symbol under cursor (persists to .jadx)" })

vim.api.nvim_create_user_command("JadxComment", function()
  require("jadxnvim.edit").comment()
end, { desc = "jadxnvim: comment symbol under cursor (persists to .jadx)" })
