-- Selecting a usage must jump to the RIGHT position — the actual call site — including when one class
-- calls the target more than once (each usage lands on its own distinct call line).
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

-- Emulate the find-usages picker's on_open for a usage entry, then return the (line, text) the cursor
-- landed on in the code window.
local function open_usage(u)
  H.code.open(u.id, {
    line = u.line, col = u.col,
    find = u.find or u.text, find_method = u.member, find_ordinal = u.ordinal,
  })
  vim.wait(300)
  local w = H.code.target_win()
  local buf = vim.api.nvim_win_get_buf(w)
  local ln = vim.api.nvim_win_get_cursor(w)[1]
  return ln, (vim.api.nvim_buf_get_lines(buf, ln - 1, ln, false))[1] or ""
end

H.spec_fast(function(win)
  -- find usages of Hello.getCount
  local _, hl = H.open_class(win, "com.example.Hello")
  local ml, mc = H.locate(hl, "int (getCount)%s*%(")
  H.check("found Hello.getCount declaration", ml ~= nil)
  vim.api.nvim_win_set_cursor(win, { ml, mc - 1 })
  local id, line, col = H.code.cursor_target()
  local _, gr = H.req("findUsages", { id = id, line = line, col = col })

  -- group usages by class
  local by_class = {}
  for _, u in ipairs(gr.usages or {}) do
    by_class[u.id] = by_class[u.id] or {}
    table.insert(by_class[u.id], u)
  end
  H.check("getCount used from Main", by_class["com.example.app.Main"] ~= nil)
  H.check("getCount used from Repeated", by_class["com.example.Repeated"] ~= nil)

  -- single call site in Main -> lands on the getCount call
  if by_class["com.example.app.Main"] then
    local lnum, text = open_usage(by_class["com.example.app.Main"][1])
    H.check("Main usage lands on a getCount call", text:find("getCount%s*%(") ~= nil, lnum .. ": " .. text)
  end

  -- two call sites in Repeated -> each usage lands on its OWN distinct getCount line
  local rep = by_class["com.example.Repeated"] or {}
  H.check("Repeated has two distinct call-site usages", #rep == 2, #rep .. " usages")
  if #rep == 2 then
    local l1, t1 = open_usage(rep[1])
    local l2, t2 = open_usage(rep[2])
    H.check("Repeated usage 1 lands on a getCount call", t1:find("getCount%s*%(") ~= nil, l1 .. ": " .. t1)
    H.check("Repeated usage 2 lands on a getCount call", t2:find("getCount%s*%(") ~= nil, l2 .. ": " .. t2)
    H.check("the two usages land on different lines", l1 ~= l2, l1 .. " vs " .. l2)
  end
end)
