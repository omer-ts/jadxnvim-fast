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

-- Re-locate a matched line's text in the decompiled lines (nearest to the approximate line), so the
-- preview centers on what was actually searched even if the export decompiled slightly differently.
local function locate(lines, snippet, approx)
  if not snippet or snippet == "" then
    return approx or 1
  end
  local target = vim.trim(snippet)
  local best, best_dist
  for i, l in ipairs(lines) do
    if vim.trim(l) == target then
      local d = math.abs(i - (approx or i))
      if not best_dist or d < best_dist then
        best, best_dist = i, d
      end
    end
  end
  return best or approx or 1
end

--- Previewer showing item.id's code centered on item.line (or the located snippet).
function M.class()
  local cache = {}
  return function(item, render)
    if not item or not item.id then
      return
    end
    M.class_lines(cache, item.id, function(lines)
      if lines then
        local line = item.snippet and locate(lines, item.snippet, item.line) or (item.line or 1)
        render({ lines = lines, filetype = "java", line = line })
      end
    end)
  end
end

-- Find the declaration line of method `name` nearest `approx`: a line with `name(` at a word
-- boundary that isn't a call (`obj.name(`). memberPos gives a good approx; this snaps onto the
-- actual declaration in the *shown* code, so the preview is right even if that copy was decompiled
-- slightly differently from the one memberPos measured.
local function locate_method(lines, name, approx)
  if not name or name == "" then
    return approx or 1
  end
  local esc = vim.pesc(name)
  local best, best_dist
  for i, l in ipairs(lines) do
    local at = l:find("[^%w_.]" .. esc .. "%s*%(") or l:find("^%s*" .. esc .. "%s*%(")
    if at then
      local d = math.abs(i - (approx or i))
      if not best_dist or d < best_dist then
        best, best_dist = i, d
      end
    end
  end
  return best or approx or 1
end

--- Previewer for methods: shows the class code centered on the method's declaration. Uses memberPos
--- as an approximate line, then relocates onto the declaration within the shown code.
function M.method()
  local cache = {}
  return function(item, render)
    if not item or not item.id then
      return
    end
    -- method name is the part before the "  ·  " class separator in the label
    local name = item.name or (item.text and item.text:match("^%s*(.-)%s*·"))
    M.class_lines(cache, item.id, function(lines)
      if not lines then
        return
      end
      local function show(approx)
        render({ lines = lines, filetype = "java", line = locate_method(lines, name, approx) })
      end
      if item._line then
        show(item._line)
        return
      end
      rpc.request("memberPos", { id = item.id, index = item.index }, function(e, r)
        item._line = (not e and r) and r.line or 1
        show(item._line)
      end)
    end)
  end
end

return M
