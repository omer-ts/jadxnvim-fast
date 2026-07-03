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
end)
