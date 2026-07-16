-- The Java→Smali follow split (<leader>jf): a live split whose smali cursor tracks the Java cursor.
-- Repeated.report() has two println statements on distinct source lines, so moving the Java cursor
-- from the first to the second must move the smali cursor to a LATER line within the same method —
-- proving line-level sync (via jadx getLineMap + smali `.line` directives), not just method-level.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

-- The smali follow window (the non-Java window showing a jadxsmali:// buffer), or nil.
local function smali_win()
  for _, w in ipairs(vim.api.nvim_tabpage_list_wins(0)) do
    local b = vim.api.nvim_win_get_buf(w)
    if vim.api.nvim_buf_get_name(b):match("^jadxsmali://") then
      return w, b
    end
  end
end

H.spec_fast(function(win)
  local id = "com.example.Repeated"
  local jbuf, jlines = H.open_class(win, id)
  vim.api.nvim_set_current_win(win)
  vim.api.nvim_win_set_buf(win, jbuf)

  -- The daemon's Java→source line map (drives line-level sync; empty if the dex has no debug info).
  local _, lm = H.req("getLineMap", { id = id })
  local has_lineinfo = lm and lm.map and next(lm.map) ~= nil
  H.check("getLineMap returns a source-line map for the fixture", has_lineinfo)

  require("jadxnvim.code").toggle_follow()
  vim.wait(300)
  H.check("follow is active", require("jadxnvim.code").follow_active())
  local sw, sbuf = smali_win()
  H.check("a smali follow split opened", sw ~= nil)
  if not sw then
    return
  end
  H.check("the split shows this class's smali", vim.api.nvim_buf_get_name(sbuf):match(id .. "$") ~= nil)

  local function sync_to_line(l)
    if not l then
      return nil
    end
    vim.api.nvim_win_set_cursor(win, { l, 0 })
    vim.cmd("doautocmd CursorMoved")
    vim.wait(200)
    return vim.api.nvim_win_get_cursor(sw)[1]
  end

  -- The two println statements, by source order (jadx picks the local names, so match structurally).
  local plines = {}
  for i, l in ipairs(jlines) do
    if l:find("println%(") then
      plines[#plines + 1] = i
    end
  end
  H.check("report() has two distinct println call lines", #plines >= 2, #plines)

  -- Smali line range of the report() method, to assert both syncs land inside it.
  local mstart, mend
  for i, l in ipairs(vim.api.nvim_buf_get_lines(sbuf, 0, -1, false)) do
    if l:match("^%s*%.method.-report%(") then
      mstart = i
    elseif mstart and not mend and l:match("^%s*%.end%s+method") then
      mend = i
    end
  end
  H.check("found report() in the smali", mstart ~= nil)

  local first = sync_to_line(plines[1])
  local second = sync_to_line(plines[2])
  H.check("first println synced the smali cursor", first ~= nil, first)
  H.check("second println synced the smali cursor", second ~= nil, second)

  if mstart and mend and first and second then
    H.check("first sync landed inside report()", first >= mstart and first <= mend, first)
    H.check("second sync landed inside report()", second >= mstart and second <= mend, second)
  end
  if has_lineinfo and first and second then
    -- Distinct source lines → distinct smali targets, with the second below the first.
    H.check("the two Java lines map to distinct smali lines", first ~= second, first .. " vs " .. second)
    H.check("the later Java line maps to a later smali line", second > first, first .. " -> " .. second)
  end

  -- Reverse direction: moving in the smali pane drives the Java cursor back. Land on the smali line
  -- of the second println (line 31 above) and assert the Java cursor lands on that println's line.
  local function rsync_to(sline)
    vim.api.nvim_set_current_win(sw)
    vim.api.nvim_win_set_cursor(sw, { sline, 0 })
    vim.cmd("doautocmd CursorMoved")
    vim.wait(200)
    local jl = vim.api.nvim_win_get_cursor(win)[1]
    vim.api.nvim_set_current_win(win) -- restore driving focus to the Java pane
    return jl
  end
  if has_lineinfo and second then
    local back = rsync_to(second)
    H.check("smali → Java sync landed on the second println", back == plines[2], back .. " vs " .. plines[2])
    if first then
      local back1 = rsync_to(first)
      H.check("smali → Java sync landed on the first println", back1 == plines[1], back1 .. " vs " .. plines[1])
    end
  end

  -- Toggling off closes the split and detaches the sync.
  require("jadxnvim.code").toggle_follow()
  vim.wait(200)
  H.check("follow is inactive after toggle", not require("jadxnvim.code").follow_active())
  H.check("the smali follow split was closed", smali_win() == nil)
end)
