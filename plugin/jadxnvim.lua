-- Command definitions for jadxnvim. Loading is lazy: requiring the module only happens
-- when a command runs.

if vim.g.loaded_jadxnvim then
  return
end
vim.g.loaded_jadxnvim = true

vim.api.nvim_create_user_command("Jadx", function(opts)
  require("jadxnvim").open(opts.args)
end, {
  nargs = 1,
  complete = "file",
  desc = "Open an APK/dex/jar/.jadx project in jadxnvim",
})

vim.api.nvim_create_user_command("JadxTree", function()
  require("jadxnvim").tree()
end, { desc = "Focus the jadxnvim project tree" })

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

vim.api.nvim_create_user_command("JadxFindClass", function()
  require("jadxnvim.find").classes()
end, { desc = "jadxnvim: fuzzy-find a class" })

vim.api.nvim_create_user_command("JadxFindMethod", function()
  require("jadxnvim.find").methods()
end, { desc = "jadxnvim: fuzzy-find a method" })

vim.api.nvim_create_user_command("JadxFindText", function(opts)
  require("jadxnvim.find").text(opts.args)
end, { nargs = "?", desc = "jadxnvim: fuzzy-find across full-text search results" })

vim.api.nvim_create_user_command("JadxRename", function()
  require("jadxnvim.edit").rename()
end, { desc = "jadxnvim: rename symbol under cursor (persists to .jadx)" })

vim.api.nvim_create_user_command("JadxComment", function()
  require("jadxnvim.edit").comment()
end, { desc = "jadxnvim: comment symbol under cursor (persists to .jadx)" })
