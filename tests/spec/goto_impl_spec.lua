-- gd on a call made through a framework/JDK interface type (whose declaration isn't in the APK) must
-- resolve to the APK implementation(s), not fail. Scheduler.go calls task.run() through
-- java.lang.Runnable; Job implements Runnable. (This is the reproducible analogue of gd on a call
-- through android.content.BroadcastReceiver.onReceive.)
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec_fast(function(win)
  local _, slines = H.open_class(win, "com.example.Scheduler")
  local rl, rc = H.locate(slines, "%.(run)%s*%(")
  H.check("found the run() call through Runnable in Scheduler", rl ~= nil)
  if not rl then
    return
  end
  vim.api.nvim_win_set_cursor(win, { rl, rc - 1 })
  local id, line, col = H.code.cursor_target()

  local _, gd = H.req("gotoDef", { id = id, line = line, col = col })
  H.check("gd through a framework type does not fail", gd.found == true, gd and gd.found)
  H.check("gd result is a method", gd.kind == "method", gd.kind)
  local reaches_job = gd.id == "com.example.Job"
  for _, t in ipairs(gd.targets or {}) do
    if t.id == "com.example.Job" then
      reaches_job = true
    end
  end
  H.check("gd offers the Job implementation of run()", reaches_job, gd.id)

  -- End-to-end: driving nav.goto_def opens Job and lands on the run() declaration.
  vim.api.nvim_win_set_cursor(win, { rl, rc - 1 })
  require("jadxnvim.nav").goto_def()
  vim.wait(3000, function()
    return vim.fn.bufnr("jadx://com.example.Job") ~= -1
  end, 50)
  local jbuf = vim.fn.bufnr("jadx://com.example.Job")
  H.check("gd opened the Job implementation", jbuf ~= -1)
  if jbuf ~= -1 then
    local w = H.code.target_win()
    if vim.api.nvim_win_get_buf(w) == jbuf then
      local cl = vim.api.nvim_win_get_cursor(w)[1]
      local text = (vim.api.nvim_buf_get_lines(jbuf, cl - 1, cl, false))[1] or ""
      H.check("cursor landed on the run() declaration", text:find("void run%s*%(") ~= nil, cl .. ": " .. text)
    end
  end
end)
