-- Search UI: class/method/field name search and streamed full-text search.
--
-- Results stream from the daemon as `searchHits` notifications and are appended to the quickfix
-- list incrementally, so large full-text searches stay responsive and can be cancelled.

local rpc = require("jadxnvim.rpc")

local M = {}

local active = { id = nil, count = 0, label = "" }

local function to_qf_items(items)
  local out = {}
  for _, it in ipairs(items) do
    local text = it.text or ""
    if it.kind then
      text = "[" .. it.kind .. "] " .. text
    end
    out[#out + 1] = {
      filename = "jadx://" .. it.id,
      lnum = it.line,
      col = (it.col or 0) + 1,
      text = text,
    }
  end
  return out
end

local function append(items)
  vim.fn.setqflist({}, "a", { items = to_qf_items(items) })
  active.count = active.count + #items
end

local function finish(params)
  local suffix = ""
  if params.cancelled then
    suffix = " (cancelled)"
  elseif params.truncated then
    suffix = " (truncated)"
  end
  vim.fn.setqflist({}, "a", { title = string.format("jadx %s: %d%s", active.label, active.count, suffix) })
  vim.notify(string.format("[jadxnvim] %s: %d result(s)%s", active.label, active.count, suffix), vim.log.levels.INFO)
  active.id = nil
end

--- Register the streaming notification handlers. Idempotent; call from setup().
function M.setup()
  rpc.on("searchHits", function(params)
    if not active.id or params.searchId ~= active.id then
      return
    end
    vim.schedule(function()
      if active.id == params.searchId then
        append(params.items or {})
      end
    end)
  end)
  rpc.on("searchDone", function(params)
    if not active.id or params.searchId ~= active.id then
      return
    end
    vim.schedule(function()
      finish(params)
    end)
  end)
end

local function start(method, query, label, opts)
  if not rpc.is_running() then
    vim.notify("[jadxnvim] no project open; use :Jadx <path>", vim.log.levels.WARN)
    return
  end
  if active.id then
    rpc.request("cancelSearch", { searchId = active.id })
  end
  active = { id = nil, count = 0, label = label .. " '" .. query .. "'" }
  vim.fn.setqflist({}, " ", { title = "jadx " .. active.label .. ": ...", items = {} })
  vim.cmd("botright copen")
  local params = vim.tbl_extend("force", { query = query }, opts or {})
  rpc.request(method, params, function(err, result)
    if err then
      vim.schedule(function()
        vim.notify("[jadxnvim] search failed: " .. (err.message or "?"), vim.log.levels.ERROR)
      end)
      return
    end
    active.id = result.searchId
  end)
end

local function prompt(arg, label, cb)
  if arg and arg ~= "" then
    cb(arg)
    return
  end
  vim.ui.input({ prompt = label .. ": " }, function(input)
    if input and input ~= "" then
      cb(input)
    end
  end)
end

--- Full-text search across decompiled code.
function M.text(query, opts)
  prompt(query, "Search text", function(q)
    start("searchText", q, "text", opts)
  end)
end

--- Name search across class/method/field names.
function M.name(query, opts)
  prompt(query, "Search name", function(q)
    start("searchName", q, "name", opts)
  end)
end

--- Whether a search is currently streaming results.
function M.is_active()
  return active.id ~= nil
end

function M.cancel()
  if active.id then
    rpc.request("cancelSearch", { searchId = active.id })
    vim.notify("[jadxnvim] search cancelled", vim.log.levels.INFO)
  end
end

return M
