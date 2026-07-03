-- Rename + comment, reflected in the code and persisted to the .jadx project.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec(function(win)
  local b, lines = H.open_class(win, "com.example.Hello")
  local cl, cc = H.locate(lines, "class (Hello)")
  H.check("found class Hello decl", cl ~= nil)
  vim.api.nvim_win_set_cursor(win, { cl, cc - 1 })
  local id, line, col = H.code.cursor_target()

  local err = H.req("rename", { id = id, line = line, col = col, newName = "Greeting" })
  H.check("rename ok", err == nil, err and err.message)

  local _, coded = H.req("getCode", { id = "com.example.Hello" })
  H.check("code reflects the rename", coded.code:find("class Greeting") ~= nil)

  -- persisted to the sidecar .jadx (rename lives in codeData)
  local jadxfile = H.fixture:gsub("%.jar$", ".jadx")
  local f = io.open(jadxfile, "r")
  H.check(".jadx project written", f ~= nil)
  if f then
    local body = f:read("*a")
    f:close()
    H.check(".jadx records the rename", body:find("Greeting", 1, true) ~= nil)
  end

  -- comment on the (renamed) class, then a second edit works (cursor stays on the symbol)
  local _, c2 = H.req("getCode", { id = "com.example.Hello" })
  local l2 = vim.split(c2.code, "\n", { plain = true })
  local gl, gc = H.locate(l2, "class (Greeting)")
  local e2 = H.req("comment", { id = "com.example.Hello", line = gl, col = gc - 1, comment = "MARK_COMMENT" })
  H.check("comment ok", e2 == nil, e2 and e2.message)
  local _, c3 = H.req("getCode", { id = "com.example.Hello" })
  H.check("comment appears in code", c3.code:find("MARK_COMMENT") ~= nil)

  -- Renaming via the CONSTRUCTOR renames the class (a constructor's name is the class name).
  local _, cc0 = H.req("getCode", { id = "com.example.Ctor" })
  local clines = vim.split(cc0.code, "\n", { plain = true })
  local kl, kc = H.locate(clines, "public (Ctor)%s*%(") -- the constructor declaration
  H.check("found Ctor constructor decl", kl ~= nil)
  vim.api.nvim_win_set_cursor(win, { kl, kc - 1 })
  -- open the Ctor buffer so cursor_target resolves against it
  H.open_class(win, "com.example.Ctor")
  vim.api.nvim_win_set_cursor(win, { kl, kc - 1 })
  local kid, kln, kcol = H.code.cursor_target()
  local ke = H.req("rename", { id = kid, line = kln, col = kcol, newName = "Widget" })
  H.check("constructor rename ok", ke == nil, ke and ke.message)
  local _, cc1 = H.req("getCode", { id = "com.example.Ctor" })
  H.check("renaming the constructor renamed the class", cc1.code:find("class Widget") ~= nil, cc1.code:match("[^\n]*class[^\n]*"))
  H.check("and the constructor now uses the new class name", cc1.code:find("Widget%s*%(") ~= nil)

  -- Rename a LOCAL VARIABLE (identified by a code ref, not a global node) — propagates to every use.
  local _, vlines = H.open_class(win, "com.example.Vars")
  local vl, vcol, vname
  for i, l in ipairs(vlines) do
    local s, _, nm = l:find("for %(int (%w+)")
    if s then
      vl, vname = i, nm
      vcol = l:find(nm, s, true)
      break
    end
  end
  H.check("found a loop variable to rename", vl ~= nil, vname)
  vim.api.nvim_win_set_cursor(win, { vl, vcol - 1 })
  local vid, vln, vco = H.code.cursor_target()
  local ve = H.req("rename", { id = vid, line = vln, col = vco, newName = "counter" })
  H.check("variable rename ok", ve == nil, ve and ve.message)
  local _, vc2 = H.req("getCode", { id = "com.example.Vars" })
  H.check("the variable was renamed everywhere it's used", vc2.code:find("int counter") ~= nil and vc2.code:find("counter%+%+") ~= nil,
    vc2.code:match("[^\n]*for %([^\n]*"))
  H.check("the old synthetic name is gone from the loop header",
    not (vc2.code:match("for %(int (%w+)") == vname), vname)
end)
