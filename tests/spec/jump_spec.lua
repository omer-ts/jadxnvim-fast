-- Navigation (go-to-def / results / stack frames) records the origin in the jumplist so <C-o>
-- returns there instead of failing with "E19: Mark has invalid line number".
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")
local code = require("jadxnvim.code")

H.spec(function(win)
  H.open_class(win, "com.example.Hello")
  vim.api.nvim_set_current_win(win)
  vim.api.nvim_win_set_cursor(win, { 3, 0 })
  local origin = vim.api.nvim_get_current_buf()

  -- simulate a go-to-def jump into another class
  code.open("com.example.Formal", { line = 2, col = 0 })
  vim.wait(300)
  H.check("navigated to the target class", vim.api.nvim_buf_get_name(0):match("Formal$") ~= nil)

  local jl = vim.fn.getjumplist(win)[1]
  local recorded = false
  for _, e in ipairs(jl) do
    if e.bufnr == origin and e.lnum == 3 then
      recorded = true
    end
  end
  H.check("the origin was pushed to the jumplist", recorded, vim.inspect(jl))

  -- <C-o> returns to the origin without error
  vim.api.nvim_set_current_win(win)
  local co = vim.api.nvim_replace_termcodes("<C-o>", true, false, true)
  local ok = pcall(vim.api.nvim_feedkeys, co, "nx", false)
  vim.wait(100)
  H.check("<C-o> jumped back without error (no E19)", ok)
  H.check("<C-o> landed back on the origin (Hello:3)",
    vim.api.nvim_get_current_buf() == origin and vim.api.nvim_win_get_cursor(win)[1] == 3,
    vim.api.nvim_buf_get_name(0) .. ":" .. vim.api.nvim_win_get_cursor(win)[1])
end)
