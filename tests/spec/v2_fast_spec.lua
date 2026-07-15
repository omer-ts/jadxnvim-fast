-- The v2 "fast" engine: dexlib2 → SQLite index + on-demand mini-dex render, driven through the same
-- Neovim frontend. Exercises browse (tree from the index), on-demand class render, and xref-backed
-- go-to-def / go-to-implementations / find-usages — none of which build a whole-APK jadx model.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec_fast(function(win)
  -- 1. Tree served from the SQLite index (no decompilation).
  local _, pkgs = H.req("getPackages")
  local pkgset = {}
  for _, p in ipairs(pkgs.packages or {}) do
    pkgset[p.name] = true
  end
  H.check("index lists com.example", pkgset["com.example"] == true)
  H.check("index lists com.example.app", pkgset["com.example.app"] == true)

  local _, cls = H.req("getClasses", { package = "com.example" })
  local clsset = {}
  for _, c in ipairs(cls.classes or {}) do
    clsset[c.id] = true
  end
  H.check("com.example has Hello", clsset["com.example.Hello"] == true)
  H.check("com.example has Greeter", clsset["com.example.Greeter"] == true)

  -- 2. On-demand render (mini-dex): open a class and read its Java.
  local _, hlines = H.open_class(win, "com.example.Hello")
  local code_str = table.concat(hlines, "\n")
  H.check("Hello renders on demand", code_str:find("class Hello") ~= nil)
  H.check("Hello body contains getCount", code_str:find("getCount") ~= nil)

  -- 2b. Java⟷Smali toggle served from the mini-dex (no whole-APK model): the smali view renders and
  -- the buffer fills without erroring (regression guard for a nil getSmali result in fast mode).
  local sm = H.rpc_ok("getSmali", { id = "com.example.Hello" })
  H.check("fast-mode smali renders", type(sm.smali) == "string" and sm.smali:find("%.class") ~= nil)
  H.open_class(win, "com.example.Hello")
  H.code.toggle_view()
  vim.wait(3000, function()
    return vim.fn.bufnr("jadxsmali://com.example.Hello") ~= -1
  end, 50)
  local sbuf = vim.fn.bufnr("jadxsmali://com.example.Hello")
  H.check("smali buffer opened via <Tab>", sbuf ~= -1)
  if sbuf ~= -1 then
    local stext = table.concat(vim.api.nvim_buf_get_lines(sbuf, 0, -1, false), "\n")
    H.check("smali buffer has bytecode, not an error", stext:find("Lcom/example/Hello;") ~= nil,
      stext:sub(1, 60))
  end

  -- 3. go-to-def on a type reference: cursor on `new Hello()` in Main -> jumps to Hello.
  local _, mlines = H.open_class(win, "com.example.app.Main")
  local rl, rc = H.locate(mlines, "new (Hello)%s*%(")
  H.check("found a Hello reference in Main", rl ~= nil)
  if rl then
    vim.api.nvim_win_set_cursor(win, { rl, rc - 1 })
    local id, line, col = H.code.cursor_target()
    local _, gd = H.req("gotoDef", { id = id, line = line, col = col })
    H.check("gotoDef reaches com.example.Hello", gd.found and gd.id == "com.example.Hello", gd and gd.id)
  end

  -- 4. go-to-implementations: cursor on the interface-typed call g.greet(...) -> offers Hello.
  local gl, gc = H.locate(mlines, "%.(greet)%s*%(")
  H.check("found the greet() call in Main", gl ~= nil)
  if gl then
    vim.api.nvim_win_set_cursor(win, { gl, gc - 1 })
    local id, line, col = H.code.cursor_target()
    local _, gd = H.req("gotoDef", { id = id, line = line, col = col })
    local impl = {}
    for _, t in ipairs(gd.targets or {}) do
      impl[t.id] = true
    end
    H.check("gd on Greeter.greet offers the Hello implementation", impl["com.example.Hello"] == true,
      gd.targets and (#gd.targets .. " targets") or "no targets")
  end

  -- 5. find-usages on Hello.getCount reaches the call in Main (xref index, override group).
  local _, hl2 = H.open_class(win, "com.example.Hello")
  local ml, mc = H.locate(hl2, "int (getCount)%s*%(")
  H.check("found Hello.getCount declaration", ml ~= nil)
  if ml then
    vim.api.nvim_win_set_cursor(win, { ml, mc - 1 })
    local id, line, col = H.code.cursor_target()
    local _, gr = H.req("findUsages", { id = id, line = line, col = col })
    local hits_main = false
    for _, u in ipairs(gr.usages or {}) do
      if u.id == "com.example.app.Main" then
        hits_main = true
      end
    end
    H.check("gr on getCount finds the call in Main", hits_main, #(gr.usages or {}) .. " usages")
  end
end)
