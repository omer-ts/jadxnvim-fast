-- Call hierarchy (fast engine): incoming callers of a method, resolved to the caller method and
-- expandable to ITS callers. Fixture: Caller.go(Base b, ..) calls b.describe() and b.process(..);
-- describe() is overridden by Upper, so a call-hierarchy on it must find the call through the Base
-- type (override group).
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

-- Put the cursor on the declaration of `member` in the open buffer and return (id, line, col).
local function cursor_on_decl(win, lines, member)
  local rl, rc = H.locate(lines, "%f[%w_]" .. member .. "%s*%(")
  if not rl then
    return nil
  end
  vim.api.nvim_win_set_cursor(win, { rl, rc - 1 })
  return H.code.cursor_target()
end

local function caller_ids(res)
  local ids = {}
  for _, c in ipairs(res.callers or {}) do
    ids[c.id] = c
  end
  return ids
end

H.spec_fast(function(win)
  local _, blines = H.open_class(win, "com.example.Base")

  -- Callers of the concrete method Base.process -> Caller.go, resolved to the caller method.
  local id, line, col = cursor_on_decl(win, blines, "process")
  H.check("found Base.process declaration", id ~= nil)
  local proc = H.rpc_ok("callHierarchy", { id = id, line = line, col = col })
  H.check("callHierarchy on process is found", proc.found == true, proc.reason)
  local pcallers = caller_ids(proc)
  H.check("process is called from Caller", pcallers["com.example.Caller"] ~= nil)
  if pcallers["com.example.Caller"] then
    local c = pcallers["com.example.Caller"]
    H.check("caller is resolved to method go", c.name == "go", c.name)
    H.check("caller node is expandable (has a key)", c.expandable == true and c.key ~= nil)
  end

  -- Callers of the overridden method Base.describe must include the Base-typed call in Caller.go
  -- (override group: the call is virtual-dispatched through the Base declaration).
  id, line, col = cursor_on_decl(win, blines, "describe")
  H.check("found Base.describe declaration", id ~= nil)
  local desc = H.rpc_ok("callHierarchy", { id = id, line = line, col = col })
  H.check("describe (override group) is called from Caller", caller_ids(desc)["com.example.Caller"] ~= nil,
    #(desc.callers or {}) .. " callers")

  -- Expansion by key: callers of Caller.go (nobody calls it) resolves cleanly to an empty set.
  if pcallers["com.example.Caller"] and pcallers["com.example.Caller"].key then
    local expand = H.rpc_ok("callHierarchy", { key = pcallers["com.example.Caller"].key })
    H.check("expanding a caller resolves (found)", expand.found == true)
    H.check("Caller.go has no callers in the fixture", #(expand.callers or {}) == 0,
      #(expand.callers or {}) .. " callers")
  end

  -- UI: invoking the view builds the tree window listing the caller.
  id, line, col = cursor_on_decl(win, blines, "process")
  require("jadxnvim.nav").call_hierarchy()
  local built = vim.wait(15000, function()
    for _, b in ipairs(vim.api.nvim_list_bufs()) do
      if vim.api.nvim_buf_get_name(b):match("jadx://hierarchy$") then
        return #vim.api.nvim_buf_get_lines(b, 0, -1, false) > 1
      end
    end
    return false
  end, 50)
  H.check("call hierarchy view opens", built)
  local labels = require("jadxnvim.hierarchy").rendered_labels()
  local has_go = false
  local go_row
  for i, l in ipairs(labels) do
    if l and (l:find("Caller") or l:find("%.go")) then
      has_go = true
      go_row = go_row or i
    end
  end
  H.check("call hierarchy view lists the caller", has_go, table.concat(labels, " | "))

  -- Pressing <CR> on a caller opens ITS class in the code window (not inside the hierarchy panel).
  if go_row then
    local hwin
    for _, w in ipairs(vim.api.nvim_tabpage_list_wins(0)) do
      if vim.api.nvim_buf_get_name(vim.api.nvim_win_get_buf(w)):match("jadx://hierarchy$") then
        hwin = w
      end
    end
    H.check("hierarchy window is present", hwin ~= nil)
    if hwin then
      vim.api.nvim_set_current_win(hwin)
      vim.api.nvim_win_set_cursor(hwin, { go_row, 0 })
      vim.api.nvim_feedkeys(vim.api.nvim_replace_termcodes("<CR>", true, false, true), "x", false)
      vim.wait(3000, function()
        return vim.fn.bufnr("jadx://com.example.Caller") ~= -1
      end, 50)
      local cbuf = vim.fn.bufnr("jadx://com.example.Caller")
      H.check("caller class opened in code buffer", cbuf ~= -1)
      -- and it was shown in a non-panel window, not by hijacking the hierarchy split
      local shown_win = vim.fn.bufwinid(cbuf)
      H.check("caller opened outside the hierarchy panel",
        shown_win ~= -1 and not vim.b[vim.api.nvim_win_get_buf(shown_win)].jadx_hierarchy)
    end
  end
end)
