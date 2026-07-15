-- Search history must survive between runs WITH its results: the per-project session cache stores the
-- full entries (not just query strings), so the manager's overview shows real results after reopening.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec_fast(function(win)
  local searches = require("jadxnvim.searches")
  searches.clear()

  -- Record a realistic xref entry (as find-usages does) and a text entry.
  searches.record({
    kind = "xref", query = "getCount", title = " Usages of getCount (1) ",
    items = {
      { text = "com.example.app.Main:1  ...getCount()...", id = "com.example.app.Main",
        line = 1, col = 0, find = "getCount", member = "getCount", find_ordinal = 1 },
    },
    previewer = function() end, on_select = function() end, -- functions must NOT reach disk
  })
  searches.record({
    kind = "text", query = "Hello", title = " Text: Hello (2) ",
    items = {
      { text = "com.example.Hello:3  Hello", id = "com.example.Hello", line = 3, col = 0, snippet = "Hello" },
      { text = "com.example.app.Main:5  Hello", id = "com.example.app.Main", line = 5, col = 0, snippet = "Hello" },
    },
    previewer = function() end, on_select = function() end,
  })

  -- Round-trip through the ACTUAL per-project session cache on disk (what init.lua uses on quit/open).
  local session = require("jadxnvim.session")
  local exported = searches.export()
  H.check("both searches exported", #exported == 2, #exported .. " entries")
  H.check("export carries results (not just queries)",
    exported[1].items and #exported[1].items >= 1)
  local ok = pcall(vim.json.encode, { history = exported })
  H.check("history is JSON-serializable (no functions leaked)", ok)

  local project = H.dex .. "#history-spec"
  session.save(project, { buffers = {}, history = exported })
  local st = session.load(project)
  H.check("session cache round-trips history", type(st) == "table" and type(st.history) == "table"
    and #st.history == 2, st and st.history and #st.history)

  searches.clear()
  H.check("history cleared", #searches.export() == 0)

  searches.import(st.history)
  local restored = searches.export()
  H.check("history restored after reload", #restored == 2, #restored .. " entries")

  -- The overview is no longer empty: restored entries keep their items (results).
  local by_title = {}
  for _, e in ipairs(restored) do
    by_title[vim.trim(e.title)] = e
  end
  local xr = by_title["Usages of getCount (1)"]
  local tx = by_title["Text: Hello (2)"]
  H.check("xref entry kept its result", xr and #xr.items == 1, xr and #xr.items)
  H.check("text entry kept its 2 results", tx and #tx.items == 2, tx and #tx.items)
  H.check("restored xref item keeps navigation fields",
    xr and xr.items[1].id == "com.example.app.Main" and xr.items[1].member == "getCount")

  -- Idempotent: importing again does not duplicate entries.
  searches.import(st.history)
  H.check("re-import does not duplicate", #searches.export() == 2, #searches.export() .. " entries")
end)
