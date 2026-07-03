-- Frida hook generation: script shape, target derivation, and cursor-based hooks. The
-- hook-every-implementation case lives in override_spec.lua.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")
local frida = require("jadxnvim.frida")
local nav = require("jadxnvim.nav")

H.spec(function(win)
  local js = frida.script({ { class = "com.example.Hello", method = "greet" }, { class = "com.example.Formal" } })
  H.check("script hooks the method", js:find("hook('com.example.Hello', 'greet')", 1, true) ~= nil)
  H.check("script hooks the whole class", js:find("hookClass('com.example.Formal')", 1, true) ~= nil)
  H.check("script is a Java.perform block", js:find("Java.perform", 1, true) ~= nil)

  local targets = frida.from_items({
    { kind = "class", id = "com.example.Hello" },
    { kind = "method", id = "com.example.Formal", name = "greet" },
  })
  H.check("from_items: class -> whole class", targets[1].class == "com.example.Hello" and targets[1].method == nil)
  H.check("from_items: method -> class+method", targets[2].method == "greet")

  -- cursor hook on a concrete, non-overriding method -> just that class + method
  local captured
  local orig = frida.open
  frida.open = function(t)
    captured = t
  end
  local _, hlines = H.open_class(win, "com.example.Hello")
  local ml, mc = H.locate(hlines, "int (getCount)%s*%(")
  vim.api.nvim_win_set_cursor(win, { ml, mc - 1 })
  nav.frida_hook()
  local ok = vim.wait(6000, function()
    return captured ~= nil
  end, 50)
  H.check("cursor hook produced a target", ok and #captured >= 1, captured and #captured)
  H.check("hooks Hello.getCount", captured and captured[1].class == "com.example.Hello"
    and captured[1].method == "getCount", captured and vim.inspect(captured[1]))

  -- whole-class hook
  captured = nil
  nav.frida_hook_class()
  H.check("class hook targets the whole class", vim.wait(3000, function()
    return captured ~= nil
  end, 50) and captured[1].class == "com.example.Hello" and captured[1].method == nil)
  frida.open = orig
end)
