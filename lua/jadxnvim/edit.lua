-- Rename and comment, persisted to the .jadx project (so jadx-gui sees them).

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")

local M = {}

local function target_or_warn()
  local id, line, col = code.cursor_target()
  if not id then
    vim.notify("[jadxnvim] not in a jadx code buffer", vim.log.levels.WARN)
    return nil
  end
  return id, line, col
end

function M.rename()
  local id, line, col = target_or_warn()
  if not id then
    return
  end
  local current = vim.fn.expand("<cword>")
  vim.ui.input({ prompt = "Rename to: ", default = current }, function(new_name)
    if not new_name or new_name == "" or new_name == current then
      return
    end
    rpc.request("rename", { id = id, line = line, col = col, newName = new_name }, function(err, res)
      vim.schedule(function()
        if err then
          vim.notify("[jadxnvim] rename failed: " .. (err.message or "?"), vim.log.levels.ERROR)
          return
        end
        code.refresh_all()
        vim.notify("[jadxnvim] renamed to '" .. new_name .. "' (saved to project)", vim.log.levels.INFO)
      end)
    end)
  end)
end

function M.comment()
  local id, line, col = target_or_warn()
  if not id then
    return
  end
  vim.ui.input({ prompt = "Comment (empty to clear): " }, function(text)
    if text == nil then
      return
    end
    rpc.request("comment", { id = id, line = line, col = col, comment = text }, function(err, res)
      vim.schedule(function()
        if err then
          vim.notify("[jadxnvim] comment failed: " .. (err.message or "?"), vim.log.levels.ERROR)
          return
        end
        code.refresh_all()
        local what = (text == "") and "comment cleared" or "comment saved"
        vim.notify("[jadxnvim] " .. what .. " (saved to project)", vim.log.levels.INFO)
      end)
    end)
  end)
end

return M
