-- Type hierarchy (fast engine): the super/subtype tree served from the SQLite class hierarchy.
-- Fixture relationships: Greeter (interface) <- Hello, Formal;  Base (abstract) <- Upper;
-- Menu.Callback (interface) <- MenuImpl.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

-- Depth-first search of a hierarchy node list (following `key` = "supers"|"subs") for a node id.
local function contains(nodes, key, id)
  for _, n in ipairs(nodes or {}) do
    if n.id == id then
      return true
    end
    if contains(n[key], key, id) then
      return true
    end
  end
  return false
end

H.spec_fast(function(win)
  -- Interface -> implementors (down).
  local greeter = H.rpc_ok("typeHierarchy", { id = "com.example.Greeter" })
  H.check("Greeter is found", greeter.found == true)
  H.check("Greeter kind is interface", greeter.kind == "interface", greeter.kind)
  H.check("Greeter subtypes include Hello", contains(greeter.subs, "subs", "com.example.Hello"))

  -- Subclass -> superclass (up).
  local upper = H.rpc_ok("typeHierarchy", { id = "com.example.Upper" })
  H.check("Upper supertypes include Base", contains(upper.supers, "supers", "com.example.Base"))

  -- Abstract class -> subclasses (down).
  local base = H.rpc_ok("typeHierarchy", { id = "com.example.Base" })
  H.check("Base subtypes include Upper", contains(base.subs, "subs", "com.example.Upper"))

  -- Unknown class -> not found (rather than an error).
  local missing = H.rpc_ok("typeHierarchy", { id = "com.example.DoesNotExist" })
  H.check("unknown class reports not found", missing.found == false)

  -- UI: invoking the view from a code buffer builds the tree window with the subtype visible.
  H.open_class(win, "com.example.Base")
  vim.api.nvim_win_set_cursor(win, { 1, 0 })
  require("jadxnvim.nav").type_hierarchy()
  local built = vim.wait(10000, function()
    for _, b in ipairs(vim.api.nvim_list_bufs()) do
      if vim.api.nvim_buf_get_name(b):match("jadx://hierarchy$") then
        return #vim.api.nvim_buf_get_lines(b, 0, -1, false) > 1
      end
    end
    return false
  end, 50)
  H.check("type hierarchy view opens", built)
  local labels = require("jadxnvim.hierarchy").rendered_labels()
  local has_upper = false
  for _, l in ipairs(labels) do
    if l and l:find("Upper") then
      has_upper = true
    end
  end
  H.check("type hierarchy view shows the Upper subtype", has_upper, table.concat(labels, " | "))
end)
