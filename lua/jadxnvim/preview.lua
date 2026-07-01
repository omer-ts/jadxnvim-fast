-- Previewers for the fuzzy pickers: fetch a class's decompiled code (cached per picker) and show
-- it centered on the relevant line.

local rpc = require("jadxnvim.rpc")

local M = {}

--- Fetch a class's decompiled lines (cached) and hand them to cb (or nil on error).
function M.class_lines(cache, id, cb)
  if cache[id] then
    cb(cache[id])
    return
  end
  rpc.request("getCode", { id = id }, function(err, res)
    if err or not res then
      cb(nil)
      return
    end
    local lines = vim.split((res.code or ""):gsub("\r", ""), "\n", { plain = true })
    cache[id] = lines
    cb(lines)
  end)
end

--- Previewer showing item.id's code centered on item.line (defaults to the top).
function M.class()
  local cache = {}
  return function(item, render)
    if not item or not item.id then
      return
    end
    M.class_lines(cache, item.id, function(lines)
      if lines then
        render({ lines = lines, filetype = "java", line = item.line or 1 })
      end
    end)
  end
end

--- Previewer for methods: resolves the declaration line via memberPos (cached on the item).
function M.method()
  local cache = {}
  return function(item, render)
    if not item or not item.id then
      return
    end
    M.class_lines(cache, item.id, function(lines)
      if not lines then
        return
      end
      if item._line then
        render({ lines = lines, filetype = "java", line = item._line })
        return
      end
      rpc.request("memberPos", { id = item.id, index = item.index }, function(e, r)
        item._line = (not e and r) and r.line or 1
        render({ lines = lines, filetype = "java", line = item._line })
      end)
    end)
  end
end

return M
