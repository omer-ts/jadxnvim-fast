-- The override hierarchy: go-to-implementations, polymorphic find-usages, and hook-every-impl.
-- These rely on the override graph captured during export (which survives the post-export unload).
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")
local frida = require("jadxnvim.frida")
local nav = require("jadxnvim.nav")

H.spec(function(win)
  -- go-to-def on the interface method -> both implementations offered
  local _, glines = H.open_class(win, "com.example.Greeter")
  local dl, dc = H.locate(glines, "(greet)%s*%(")
  H.check("found Greeter.greet declaration", dl ~= nil)
  vim.api.nvim_win_set_cursor(win, { dl, dc - 1 })
  local id, line, col = H.code.cursor_target()
  local _, gd = H.req("gotoDef", { id = id, line = line, col = col })
  local impls = {}
  for _, t in ipairs(gd.targets or {}) do
    impls[t.id] = t["abstract"]
  end
  H.check("gd offers Hello implementation", impls["com.example.Hello"] == false, vim.inspect(vim.tbl_keys(impls)))
  H.check("gd offers Formal implementation", impls["com.example.Formal"] == false)
  H.check("gd marks the interface as abstract", impls["com.example.Greeter"] == true)

  -- polymorphic find-usages: gr on Hello.greet reaches the interface-typed call in Main
  local _, hlines = H.open_class(win, "com.example.Hello")
  local ml, mc = H.locate(hlines, "String (greet)%s*%(")
  H.check("found Hello.greet declaration", ml ~= nil)
  vim.api.nvim_win_set_cursor(win, { ml, mc - 1 })
  local id2, line2, col2 = H.code.cursor_target()
  local _, gr = H.req("findUsages", { id = id2, line = line2, col = col2 })
  local hits_main = false
  for _, u in ipairs(gr.usages or {}) do
    if u.id == "com.example.app.Main" then
      hits_main = true
    end
  end
  H.check("gr on Hello.greet finds the interface-typed call in Main", hits_main, #(gr.usages or {}) .. " usages")

  -- gr must expand to base declarations whether abstract OR concrete, but never to sibling overrides.
  local function usages_of(cls, decl_pat)
    local _, lines = H.open_class(win, cls)
    local l, c = H.locate(lines, decl_pat)
    H.check("found " .. cls .. " " .. decl_pat, l ~= nil)
    vim.api.nvim_win_set_cursor(win, { l, c - 1 })
    local i, ln, co = H.code.cursor_target()
    local _, r = H.req("findUsages", { id = i, line = ln, col = co })
    local by = {}
    for _, u in ipairs(r.usages or {}) do
      by[u.id] = true
    end
    return by
  end
  -- abstract base (Base.handle is abstract, called by Base.process)
  H.check("gr on Upper.handle reaches the abstract base's call site", usages_of("com.example.Upper", "String (handle)%s*%(")["com.example.Base"] == true)
  -- concrete base (Base.describe has a body; Caller calls it through the Base type) — the case the
  -- old abstract-only filter missed
  local du = usages_of("com.example.Upper", "String (describe)%s*%(")
  H.check("gr on Upper.describe reaches the CONCRETE base call via the base type", du["com.example.Caller"] == true, vim.inspect(vim.tbl_keys(du)))

  -- Frida: hooking the interface method targets every implementation
  local captured
  local orig = frida.open
  frida.open = function(t)
    captured = t
  end
  vim.api.nvim_win_set_cursor(win, { dl, dc - 1 })
  H.open_class(win, "com.example.Greeter") -- ensure the interface buffer is current
  vim.api.nvim_win_set_cursor(win, { dl, dc - 1 })
  nav.frida_hook()
  vim.wait(6000, function()
    return captured ~= nil
  end, 50)
  local classes = {}
  for _, t in ipairs(captured or {}) do
    classes[t.class] = true
  end
  H.check("frida hooks the Hello implementation", classes["com.example.Hello"] == true, captured and vim.inspect(captured))
  H.check("frida hooks the Formal implementation", classes["com.example.Formal"] == true)
  frida.open = orig
end)
