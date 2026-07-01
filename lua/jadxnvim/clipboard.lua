-- System-clipboard support that works over SSH via OSC 52.
--
-- Yanking on a remote nvim normally only fills the remote register. OSC 52 emits an escape
-- sequence that the *local* terminal turns into a clipboard write, so copying decompiled code
-- lands on your computer's clipboard even through SSH. We register an OSC 52 provider for the `+`
-- register (unless the user already configured a clipboard) and make yanks in the read-only code
-- buffers target that register.

local M = { enabled = false }

function M.setup(enabled)
  M.enabled = enabled and true or false
  if not M.enabled then
    return
  end
  -- Respect a clipboard the user already configured (e.g. win32yank / xclip / wl-clipboard).
  if vim.g.clipboard ~= nil then
    return
  end
  local ok, osc52 = pcall(require, "vim.ui.clipboard.osc52")
  if not ok then
    return
  end
  -- Cache what we copy so pasting from `+` in this session doesn't block on a terminal query
  -- (many terminals refuse OSC 52 read); the copy still goes out to the system clipboard.
  local last = { ["+"] = { { "" }, "v" }, ["*"] = { { "" }, "v" } }
  local function copy(reg)
    local osc = osc52.copy(reg)
    return function(lines, regtype)
      last[reg] = { lines, regtype }
      pcall(osc, lines, regtype)
    end
  end
  local function paste(reg)
    return function()
      return last[reg]
    end
  end
  vim.g.clipboard = {
    name = "jadxnvim-osc52",
    copy = { ["+"] = copy("+"), ["*"] = copy("*") },
    paste = { ["+"] = paste("+"), ["*"] = paste("*") },
  }
end

--- In a read-only code buffer, make yanks copy to the system clipboard (the `+` register).
function M.apply_buffer_maps(bufnr)
  if not M.enabled then
    return
  end
  local o = { buffer = bufnr, silent = true, nowait = true }
  vim.keymap.set({ "n", "x" }, "y", '"+y', o)
  vim.keymap.set("n", "Y", '"+Y', o)
  vim.keymap.set({ "n", "x" }, "<leader>y", '"+y', o)
end

return M
