-- The help palette lists commands + shortcuts and runs the selected one.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")
local help = require("jadxnvim.help")
local fuzzy = require("jadxnvim.fuzzy")

H.spec(function()
  local captured
  fuzzy.pick = function(o)
    captured = o
  end
  help.menu()
  H.check("help menu opened with many entries", captured and #captured.items > 12, captured and #captured.items)

  -- entries show a name and its shortcut
  local function find_item(name)
    for _, it in ipairs(captured.items) do
      if it.text:find(name, 1, true) then
        return it
      end
    end
  end
  H.check("lists 'Find class' with its <Space>fc shortcut", (function()
    local it = find_item("Find class")
    return it ~= nil and it.text:find("<Space>fc", 1, true) ~= nil
  end)())
  H.check("lists 'Go to definition' with gd", (function()
    local it = find_item("Go to definition")
    return it ~= nil and it.text:find("gd", 1, true) ~= nil
  end)())
  H.check("lists 'Bookmarks'", find_item("Bookmarks") ~= nil)
  H.check("lists 'Frida hook'", find_item("Frida hook") ~= nil)

  -- selecting an entry runs its action
  local ran = false
  require("jadxnvim.find").classes = function()
    ran = true
  end
  local it = find_item("Find class")
  captured.on_select(it)
  vim.wait(500, function()
    return ran
  end, 20)
  H.check("selecting an entry runs its action", ran)
end)
