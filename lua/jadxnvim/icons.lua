-- Shared icon/symbol set for the tree and combined search. Defaults are Nerd Font glyphs (so it
-- looks like jadx-gui); override any of them via `icons = {...}` in setup() — set them to plain
-- ASCII (e.g. class = "C") if you don't run a Nerd Font.

local M = {}

M.icons = {
  sources = "", -- Source code section
  resources = "", -- Resources section
  package = "", -- a Java package
  class = "", -- a class
  method = "", -- a method (combined search)
  field = "", -- a field
  folder = "", -- a resource directory (collapsed)
  folder_open = "", -- a resource directory (expanded)
  file = "", -- a resource file
  text = "", -- a full-text search hit
}

function M.setup(over)
  if type(over) == "table" then
    M.icons = vim.tbl_extend("force", M.icons, over)
  end
end

function M.get(name)
  local v = M.icons[name]
  return v ~= nil and v or ""
end

return M
