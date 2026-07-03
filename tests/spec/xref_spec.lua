-- Go-to-definition and find-usages (direct references). The override-hierarchy cases
-- (go-to-implementations, polymorphic usages) live in override_spec.lua.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec(function(win)
  -- go-to-def on a type reference in Main -> jumps to the class definition
  local _, mlines = H.open_class(win, "com.example.app.Main")
  local rl, rc = H.locate(mlines, "new (Hello)%s*%(")
  H.check("found a Hello reference in Main", rl ~= nil)
  vim.api.nvim_win_set_cursor(win, { rl, rc - 1 })
  local id, line, col = H.code.cursor_target()
  local _, gd = H.req("gotoDef", { id = id, line = line, col = col })
  H.check("gotoDef resolves the reference", gd.found == true)
  local target_ids = {}
  for _, t in ipairs(gd.targets or {}) do
    target_ids[t.id] = true
  end
  H.check("gd reaches com.example.Hello", target_ids["com.example.Hello"] == true or gd.id == "com.example.Hello",
    gd.id)

  -- find-usages on a directly-called method reaches the caller
  local hb, hlines = H.open_class(win, "com.example.Hello")
  local ml, mc = H.locate(hlines, "int (getCount)%s*%(")
  H.check("found Hello.getCount declaration", ml ~= nil)
  vim.api.nvim_win_set_cursor(win, { ml, mc - 1 })
  local id2, line2, col2 = H.code.cursor_target()
  local _, gr = H.req("findUsages", { id = id2, line = line2, col = col2 })
  local hits_main = false
  for _, u in ipairs(gr.usages or {}) do
    if u.id == "com.example.app.Main" then
      hits_main = true
    end
  end
  H.check("gr on Hello.getCount finds the call in Main", hits_main, #(gr.usages or {}) .. " usages")
end)
