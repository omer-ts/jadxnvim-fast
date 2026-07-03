-- jadx-gui project UI-state round-trip: open tabs + search history persisted to the .jadx and
-- reported back on load.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec(function()
  local state = {
    openTabs = { { type = "class", tabPath = "com.example.Hello", subPath = "", caret = 5, view = { x = 0, y = 0 } } },
    searchHistory = { "greet", "s3cr3t_marker_9f2a" },
  }
  local err = H.req("setProjectState", state)
  H.check("setProjectState ok", err == nil, err and err.message)

  local jadxfile = H.fixture:gsub("%.jar$", ".jadx")
  local f = io.open(jadxfile, "r")
  H.check(".jadx exists", f ~= nil)
  if f then
    local body = f:read("*a")
    f:close()
    H.check(".jadx has openTabs", body:find("openTabs", 1, true) ~= nil)
    H.check(".jadx has the tab class", body:find("com.example.Hello", 1, true) ~= nil)
    H.check(".jadx has searchHistory", body:find("searchHistory", 1, true) ~= nil)
    H.check(".jadx records the query", body:find("s3cr3t_marker_9f2a", 1, true) ~= nil)
  end
end)
