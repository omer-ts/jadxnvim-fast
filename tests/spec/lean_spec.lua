-- Lean mode: after the export the jadx model is dropped and browsing/search are served from the
-- on-disk index. (Disk-served xref needs dex-level metadata that this jar fixture doesn't carry —
-- lean gd/gr is covered against real dex APKs elsewhere — so here we assert the serving path.)
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec(function(win)
  -- the model was dropped, yet the tree, code and search all serve from the on-disk export
  local _, pkgs = H.req("getPackages")
  local names = {}
  for _, p in ipairs(pkgs.packages or {}) do
    names[p.name] = true
  end
  H.check("lean: package tree served from disk", names["com.example"] and names["com.example.util"])

  local _, cls = H.req("getClasses", { package = "com.example" })
  local ids = {}
  for _, c in ipairs(cls.classes or {}) do
    ids[c.id] = true
  end
  H.check("lean: class list served from disk", ids["com.example.Hello"] and ids["com.example.Greeter"])

  local _, coded = H.req("getCode", { id = "com.example.Hello" })
  H.check("lean: getCode served from disk", coded.code and coded.code:find("class Hello") ~= nil)

  local text = H.search("searchText", { query = "s3cr3t_marker_9f2a", limit = 100 })
  local hit = false
  for _, it in ipairs(text) do
    if it.id == "com.example.util.Strings" then
      hit = true
    end
  end
  H.check("lean: full-text search served from disk (ripgrep)", hit, #text .. " hits")
end, { lean = true })
