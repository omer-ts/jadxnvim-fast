-- Lean mode edits: with the model kept resident (keep_model default), renaming/commenting works
-- without dropping to a slow on-demand reparse, and getCode reflects the change (served from the
-- model once the on-disk export is stale from the edit).
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec(function(win)
  -- Sanity: browsing/serving from disk works in lean mode.
  local _, c0 = H.req("getCode", { id = "com.example.Hello" })
  H.check("lean: getCode before edit", c0.code and c0.code:find("class Hello") ~= nil)

  -- Resolve the class declaration position and rename it (needs the jadx model — which lean keeps
  -- resident, so this doesn't trigger a full reparse).
  local lines = vim.split(c0.code, "\n", { plain = true })
  local cl, cc = H.locate(lines, "class (Hello)")
  H.check("lean: found class decl", cl ~= nil)
  local err = H.req("rename", { id = "com.example.Hello", line = cl, col = cc - 1, newName = "LeanEdited" })
  H.check("lean: rename ok", err == nil, err and err.message)

  -- After the edit the export is stale, so getCode re-decompiles from the resident model and shows
  -- the new name.
  local _, c1 = H.req("getCode", { id = "com.example.Hello" })
  H.check("lean: getCode reflects the rename", c1.code and c1.code:find("class LeanEdited") ~= nil)

  -- A second edit still works (the model stayed resident — no repeated reload).
  local l1 = vim.split(c1.code, "\n", { plain = true })
  local gl, gc = H.locate(l1, "class (LeanEdited)")
  local e2 = H.req("comment", { id = "com.example.Hello", line = gl, col = gc - 1, comment = "LEAN_MARK" })
  H.check("lean: comment ok", e2 == nil, e2 and e2.message)
  local _, c2 = H.req("getCode", { id = "com.example.Hello" })
  H.check("lean: comment appears", c2.code and c2.code:find("LEAN_MARK") ~= nil)

  -- The edit persisted to the .jadx so a reopen would reuse the index and refresh names in the
  -- background.
  local jadxfile = H.fixture:gsub("%.jar$", ".jadx")
  local f = io.open(jadxfile, "r")
  H.check("lean: .jadx written", f ~= nil)
  if f then
    local body = f:read("*a")
    f:close()
    H.check("lean: .jadx records the rename", body:find("LeanEdited", 1, true) ~= nil)
  end
end, { lean = true })
