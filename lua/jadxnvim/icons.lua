-- Shared icon/symbol set for the tree and combined search. Defaults are Nerd Font glyphs (so it
-- looks like jadx-gui); override any of them via `icons = {...}` in setup() — set them to plain
-- ASCII (e.g. class = "C") if you don't run a Nerd Font.

local M = {}

-- Values are written as explicit UTF-8 byte escapes (Nerd Font glyphs) so they survive editing
-- regardless of the editor's encoding. Comments note the codepoint.
M.icons = {
  sources = "\239\132\161", -- U+F121  Source code section (code)
  resources = "\239\129\187", -- U+F07B  Resources section (folder)
  package = "\239\146\135", -- U+F487  a Java package
  class = "\238\173\155", -- U+EB5B  a class
  method = "\238\170\140", -- U+EA8C  a method
  field = "\238\173\159", -- U+EB5F  a field
  folder = "\239\129\187", -- U+F07B  a resource directory (collapsed)
  folder_open = "\239\129\188", -- U+F07C  a resource directory (expanded)
  file = "\239\133\155", -- U+F15B  a resource file
  text = "\239\128\130", -- U+F002  a full-text search hit
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
