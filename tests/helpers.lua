-- Shared helpers for the headless-nvim test specs. Each spec bootstraps the runtimepath + package
-- path (from $JADXNVIM_REPO) and then requires this module. Paths come from the environment so the
-- suite runs unchanged on CI:
--   JADXNVIM_REPO   repo root (added to runtimepath)
--   JADX_TEST_JAR   the compiled fixture jar to open
--   JADXNVIM_JAR    the daemon jar (defaults to <repo>/daemon/build/libs/jadxd.jar)
--   JADXNVIM_RG     ripgrep binary (optional; auto-detected otherwise)

local M = { fails = 0, checks = 0 }

M.repo = assert(os.getenv("JADXNVIM_REPO"), "JADXNVIM_REPO not set")
M.fixture = assert(os.getenv("JADX_TEST_JAR"), "JADX_TEST_JAR not set")
M.jar = os.getenv("JADXNVIM_JAR") or (M.repo .. "/daemon/build/libs/jadxd.jar")
M.rg = os.getenv("JADXNVIM_RG")

local jadx = require("jadxnvim")
local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")
M.jadx, M.rpc, M.code = jadx, rpc, code

function M.check(name, cond, extra)
  M.checks = M.checks + 1
  io.stderr:write((cond and "  ok   " or "  FAIL ") .. name .. (extra and ("  — " .. tostring(extra)) or "") .. "\n")
  if not cond then
    M.fails = M.fails + 1
  end
  return cond
end

--- Synchronous RPC request. Returns (err, result).
function M.req(method, params)
  local done, result, err = false, nil, nil
  rpc.request(method, params, function(e, r)
    err, result, done = e, r, true
  end)
  vim.wait(20000, function()
    return done
  end, 20)
  return err, result
end

--- Run a streamed search (searchName/searchText) and collect all hits synchronously.
function M.search(method, params)
  local items, done = {}, false
  local d1 = rpc.on("searchHits", function(p)
    for _, it in ipairs(p.items or {}) do
      items[#items + 1] = it
    end
  end)
  local d2 = rpc.on("searchDone", function()
    done = true
  end)
  M.req(method, params)
  vim.wait(20000, function()
    return done
  end, 20)
  pcall(d1)
  pcall(d2)
  return items
end

--- Open the fixture project and wait until the tree is populated AND the background export finishes
--- (the export decompiles every class, which is what builds jadx's override/usage graph — so
--- go-to-implementations and polymorphic find-usages are complete). Returns the code window.
function M.open(opts)
  opts = opts or {}
  jadx.setup({ jar = M.jar, rg = M.rg, session = false, lean = opts.lean == true })
  local export_done = false
  local d = rpc.on("loadDone", function()
    export_done = true
  end)
  jadx.open(M.fixture)
  local ok = vim.wait(120000, function()
    for _, b in ipairs(vim.api.nvim_list_bufs()) do
      if vim.api.nvim_buf_get_name(b):match("jadx://tree$") and #vim.api.nvim_buf_get_lines(b, 0, -1, false) > 1 then
        return true
      end
    end
    return false
  end, 100)
  M.check("project loaded", ok)
  vim.wait(60000, function()
    return export_done
  end, 50)
  pcall(d)
  vim.wait(300)
  local win = code.target_win()
  vim.api.nvim_set_current_win(win)
  return win
end

--- Open class `id` into the given window and return its buffer + lines.
function M.open_class(win, id)
  code.open(id)
  vim.wait(400)
  local b = vim.fn.bufnr("jadx://" .. id)
  if b ~= -1 then
    vim.api.nvim_win_set_buf(win, b)
  end
  return b, vim.api.nvim_buf_get_lines(b, 0, -1, false)
end

--- Find (line, col) of the first buffer line matching `pat` (a Lua pattern); col is at capture 1.
function M.locate(lines, pat)
  for i, l in ipairs(lines) do
    local s, _, cap = l:find(pat)
    if s then
      return i, (cap and l:find(cap, s, true) or s)
    end
  end
end

--- Finish the spec: print a summary and exit non-zero if anything failed (for CI).
function M.done()
  pcall(function()
    jadx.close()
  end)
  if M.fails == 0 then
    io.stderr:write(("\nALL PASS (%d checks)\n"):format(M.checks))
  else
    io.stderr:write(("\n%d FAILURE(S) / %d checks\n"):format(M.fails, M.checks))
  end
  vim.cmd("cquit " .. (M.fails == 0 and 0 or 1))
end

--- Run a spec body with a fresh project, then finish. Errors are reported as a failure.
--- opts.lean = true opens in lean mode (serve from the on-disk export).
function M.spec(fn, opts)
  local win = M.open(opts)
  local ok, err = pcall(fn, win)
  if not ok then
    M.check("spec raised no error", false, err)
  end
  M.done()
end

return M
