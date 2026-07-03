-- Decompiled code buffers get a real foldmethod so { } blocks (e.g. an if statement) can be folded.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec(function(win)
  local _, lines = H.open_class(win, "com.example.Folds")
  local fm = vim.wo[win].foldmethod
  H.check("code buffer uses a real foldmethod (not manual)", fm == "indent" or fm == "expr", fm)

  -- locate the multi-line `if (...) {` and a line inside its body
  local ifline
  for i, l in ipairs(lines) do
    if l:match("if %(") and l:match("{%s*$") then
      ifline = i
      break
    end
  end
  H.check("found the multi-line if statement", ifline ~= nil)
  local body = ifline + 1

  -- a fold exists over the if body (this is what used to fail with "E490: No fold found")
  H.check("the if body is inside a fold (foldlevel deeper than the if line)",
    vim.fn.foldlevel(body) > vim.fn.foldlevel(ifline),
    ("if=%d body=%d"):format(vim.fn.foldlevel(ifline), vim.fn.foldlevel(body)))

  -- closing the fold at a body line actually folds it
  vim.api.nvim_win_set_cursor(win, { body, 0 })
  local ok = pcall(vim.cmd, "normal! zc")
  H.check("zc folds the if block", ok and vim.fn.foldclosed(body) ~= -1, "foldclosed=" .. vim.fn.foldclosed(body))
end)
