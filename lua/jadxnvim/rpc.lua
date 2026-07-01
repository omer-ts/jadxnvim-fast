-- Newline-delimited JSON-RPC transport over the jadxd daemon's stdio.
--
-- A single daemon process is managed per Neovim session (one project at a time for v1).
-- Requests are correlated by integer id; unsolicited notifications (e.g. "ready", streamed
-- search hits) are dispatched to registered handlers.

local M = {}

local state = {
  job = nil,
  next_id = 0,
  pending = {}, -- id -> function(err, result)
  handlers = {}, -- method -> function(params)
  stdout_partial = "",
  ready = false,
  stopping = false,
}

local function log_err(msg)
  vim.schedule(function()
    vim.notify("[jadxnvim] " .. msg, vim.log.levels.ERROR)
  end)
end

local function dispatch(msg)
  if msg.id ~= nil and (msg.result ~= nil or msg.error ~= nil) then
    local cb = state.pending[msg.id]
    state.pending[msg.id] = nil
    if cb then
      cb(msg.error, msg.result)
    end
  elseif msg.method then
    local h = state.handlers[msg.method]
    if h then
      h(msg.params)
    end
  end
end

-- jobstart channel-lines semantics: data[1] continues the previous chunk's last line and
-- data[#data] is incomplete (continued next call). See :h channel-lines.
local function on_stdout(_, data, _)
  if not data then
    return
  end
  data[1] = state.stdout_partial .. data[1]
  state.stdout_partial = table.remove(data)
  for _, line in ipairs(data) do
    if #line > 0 then
      local ok, msg = pcall(vim.json.decode, line)
      if ok and type(msg) == "table" then
        dispatch(msg)
      else
        log_err("failed to parse daemon output: " .. line:sub(1, 200))
      end
    end
  end
end

local function on_stderr(_, data, _)
  if not data then
    return
  end
  for _, line in ipairs(data) do
    if #line > 0 then
      -- jadx logs (warnings, progress) — surface only at debug verbosity.
      if vim.g.jadxnvim_debug then
        vim.schedule(function()
          vim.notify("[jadxd] " .. line, vim.log.levels.DEBUG)
        end)
      end
    end
  end
end

local function on_exit(_, code, _)
  state.job = nil
  state.ready = false
  local pending = state.pending
  state.pending = {}
  for _, cb in pairs(pending) do
    cb({ message = "daemon exited (code " .. tostring(code) .. ")" }, nil)
  end
  -- Don't report intentional shutdowns (SIGTERM => 143) as errors.
  if code ~= 0 and not state.stopping then
    log_err("daemon exited with code " .. tostring(code))
  end
  state.stopping = false
end

--- Register a handler for an unsolicited notification method.
function M.on(method, fn)
  state.handlers[method] = fn
end

function M.is_running()
  return state.job ~= nil
end

--- Start the daemon. cmd is a list (e.g. {"java","-jar",jar,project}).
function M.start(cmd, on_ready)
  if state.job then
    M.stop()
  end
  state.stdout_partial = ""
  state.ready = false
  M.on("ready", function(params)
    state.ready = true
    if on_ready then
      vim.schedule(function()
        on_ready(params)
      end)
    end
  end)
  local job = vim.fn.jobstart(cmd, {
    on_stdout = on_stdout,
    on_stderr = on_stderr,
    on_exit = on_exit,
    stdout_buffered = false,
    stderr_buffered = false,
  })
  if job <= 0 then
    log_err("failed to start daemon: " .. table.concat(cmd, " "))
    return false
  end
  state.job = job
  return true
end

--- Send a request. cb receives (err, result); err is nil on success.
function M.request(method, params, cb)
  if not state.job then
    if cb then
      cb({ message = "daemon not running" }, nil)
    end
    return
  end
  state.next_id = state.next_id + 1
  local id = state.next_id
  state.pending[id] = cb or function() end
  local payload = vim.json.encode({
    id = id,
    method = method,
    params = params or vim.empty_dict(),
  })
  vim.fn.chansend(state.job, payload .. "\n")
  return id
end

function M.stop()
  if state.job then
    state.stopping = true
    -- Ask the daemon to exit cleanly, then force-stop the channel.
    pcall(function()
      vim.fn.chansend(state.job, vim.json.encode({ method = "shutdown" }) .. "\n")
    end)
    pcall(vim.fn.jobstop, state.job)
    state.job = nil
    state.ready = false
  end
end

return M
