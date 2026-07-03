-- A method renamed in jadx must still hook by its original runtime name in Frida.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")
local nav = require("jadxnvim.nav")
local frida = require("jadxnvim.frida")

H.spec(function(win)
  -- rename getCount -> tally
  local b, lines = H.open_class(win, "com.example.Hello")
  local ml, mc = H.locate(lines, "int (getCount)%s*%(")
  vim.api.nvim_win_set_cursor(win, { ml, mc - 1 })
  local id, line, col = H.code.cursor_target()
  local err = H.req("rename", { id = id, line = line, col = col, newName = "tally" })
  H.check("rename getCount -> tally ok", err == nil, err and err.message)

  local _, coded = H.req("getCode", { id = "com.example.Hello" })
  H.check("code shows the renamed method", coded.code:find("tally") ~= nil)
  H.code.refresh_all() -- the raw rename RPC doesn't refresh the buffer; the plugin normally does
  vim.wait(300)

  -- Frida hook on the renamed method should use the raw name (getCount), not the alias (tally)
  local captured
  local orig = frida.open
  frida.open = function(t)
    captured = t
  end
  local l2 = vim.api.nvim_buf_get_lines(b, 0, -1, false)
  local rl, rc = H.locate(l2, "int (tally)%s*%(")
  H.check("found the renamed decl", rl ~= nil)
  vim.api.nvim_win_set_cursor(win, { rl, rc - 1 })
  nav.frida_hook()
  H.check("frida hook produced a target", vim.wait(6000, function()
    return captured ~= nil
  end, 50))
  H.check("frida hooks the RAW method name (getCount), not the alias", captured and captured[1]
    and captured[1].method == "getCount", captured and vim.inspect(captured[1]))
  frida.open = orig
end)
