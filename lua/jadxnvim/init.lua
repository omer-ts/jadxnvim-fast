-- jadxnvim: a Neovim front-end for the jadx decompiler, backed by the jadxd daemon.

local rpc = require("jadxnvim.rpc")
local tree = require("jadxnvim.tree")
local code = require("jadxnvim.code")
local search = require("jadxnvim.search")
local progress = require("jadxnvim.progress")
local clipboard = require("jadxnvim.clipboard")
local session = require("jadxnvim.session")

local M = {}

-- Plugin root: .../lua/jadxnvim/init.lua -> three levels up.
local function plugin_root()
  local src = debug.getinfo(1, "S").source:sub(2)
  return vim.fn.fnamemodify(src, ":h:h:h")
end

M.config = {
  java = "java",
  -- Path to the daemon fat jar; defaults to the in-repo build output.
  jar = nil,
  -- Extra JVM args (e.g. {"-Xmx4g"}) for large APKs.
  java_args = {},
  -- Export the decompiled sources to disk on load (in the background) so full-text search uses
  -- ripgrep instead of re-scanning in memory — much faster on big APKs. Shows a real 0-100% load
  -- bar and is cached between runs. Set false (or pass --no-export) to search purely in memory.
  -- For very large APKs give the JVM more heap via java_args (e.g. {"-Xmx6g"}).
  export = true,
  -- Path to the ripgrep binary used for full-text search. Defaults to auto-detection
  -- (`rg` on PATH). Only needed if rg isn't on PATH.
  rg = nil,
  -- By default opening an APK creates/saves a .jadx project next to it. Set temp = true (or pass
  -- --temp on the CLI / to :Jadx) to work purely in memory and never write a .jadx file.
  temp = false,
  -- Copy yanks to the system clipboard (via OSC 52, so it works over SSH). In code buffers
  -- `y`/`Y` target the system clipboard. Set false to leave the clipboard alone.
  clipboard = true,
  -- Remember the open classes + cursor positions per project and restore them on reopen.
  session = true,
  -- Build jadx's cross-reference (usage) graph at load. It powers precise find-usages (gr) and
  -- lets jadx inline single-use anonymous classes, but on a 400k-class APK it costs ~2 GB of heap
  -- and ~20 s of load time. Set false to save that memory on constrained servers; find-usages then
  -- falls back to a name-based text search and anonymous classes aren't inlined.
  usage = true,
  -- Lean mode: after the on-load export finishes, drop jadx's in-memory model and serve browsing,
  -- search and the class tree entirely from the on-disk export — RAM falls to a few hundred MB on a
  -- 400k-class APK. The model is rebuilt on demand (one-time) the first time you go-to-def, find
  -- usages, view smali, or edit. Implies usage = false. Requires export = true.
  lean = false,
  -- Global keymaps for the fuzzy finders. Set a value to false to skip mapping it.
  -- Bound to literal <Space> by default (works regardless of your mapleader).
  keys = {
    find_text = "<Space>ff",
    find_classes = "<Space>fc",
    find_methods = "<Space>fd",
    saved_searches = "<Space>fs",
  },
}

local function default_jar()
  return plugin_root() .. "/daemon/build/libs/jadxd.jar"
end

function M.setup(opts)
  M.config = vim.tbl_deep_extend("force", M.config, opts or {})
  if not M.config.jar then
    M.config.jar = default_jar()
  end
  code.setup()
  search.setup()
end

local function map_finders()
  local keys = M.config.keys or {}
  local map = function(lhs, fn, desc)
    if lhs then
      vim.keymap.set("n", lhs, fn, { desc = desc, silent = true })
    end
  end
  map(keys.find_text, function() require("jadxnvim.find").text() end, "jadx: fuzzy find text")
  map(keys.find_classes, function() require("jadxnvim.find").classes() end, "jadx: fuzzy find classes")
  map(keys.find_methods, function() require("jadxnvim.find").methods() end, "jadx: fuzzy find methods")
  map(keys.saved_searches, function() require("jadxnvim.searches").manager() end, "jadx: saved searches")
end

-- Register the daemon load-lifecycle handlers exactly once.
local function setup_load_handlers()
  if M._load_handlers then
    return
  end
  M._load_handlers = true
  rpc.on("loadProgress", function(p)
    if M._loading then
      progress.update(p.percent, "Indexing " .. (M._loading_name or ""))
    end
  end)
  rpc.on("loadDone", function()
    if M._loading then
      M._loading = false
      vim.schedule(progress.finish)
    end
  end)
  rpc.on("loadError", function(p)
    if M._loading then
      M._loading = false
      vim.schedule(function()
        progress.finish()
        vim.notify("[jadxnvim] load failed: " .. tostring(p and p.message), vim.log.levels.ERROR)
      end)
    end
  end)
  -- Lean mode lifecycle: the daemon dropped its in-memory model (low RAM), or is rebuilding it for a
  -- semantic op (go-to-def / usages / smali / edit) — the latter can take a while on a huge APK.
  rpc.on("modelUnloaded", function()
    vim.schedule(function()
      vim.notify("[jadxnvim] lean mode: model unloaded, serving from disk (low memory)", vim.log.levels.INFO)
    end)
  end)
  rpc.on("modelReloading", function()
    vim.schedule(function()
      vim.notify("[jadxnvim] re-materializing the jadx model (one-time) for a semantic operation…", vim.log.levels.WARN)
    end)
  end)
end

-- Snapshot the open Java class buffers and their cursor lines (active one flagged).
local function capture_session()
  local buffers = {}
  local active
  local cur = vim.api.nvim_get_current_buf()
  for _, b in ipairs(vim.api.nvim_list_bufs()) do
    if vim.api.nvim_buf_is_valid(b) then
      local id = vim.b[b].jadx_class_id
      if id and vim.b[b].jadx_view ~= "smali" then
        local win = vim.fn.bufwinid(b)
        local line
        if win ~= -1 then
          line = vim.api.nvim_win_get_cursor(win)[1]
        else
          local m = vim.api.nvim_buf_get_mark(b, '"')
          line = (m[1] and m[1] > 0) and m[1] or 1
        end
        buffers[#buffers + 1] = { id = id, line = line }
        if b == cur then
          active = id
        end
      end
    end
  end
  return { buffers = buffers, active = active }
end

local function save_session()
  if not (M.config.session and M._project) then
    return
  end
  local snap = capture_session()
  -- Don't clobber a good session with an empty snapshot (e.g. after a daemon crash wiped buffers).
  if #snap.buffers > 0 then
    session.save(M._project, snap)
  end
end

-- Reopen the classes from a previous session, focusing the last-active one at its cursor.
local function restore_session(project)
  if not M.config.session then
    return
  end
  local st = session.load(project)
  if not st or type(st.buffers) ~= "table" or #st.buffers == 0 then
    return
  end
  for _, e in ipairs(st.buffers) do
    if e.id and e.id ~= st.active then
      pcall(code.open, e.id, { line = e.line })
    end
  end
  if st.active then
    pcall(code.open, st.active, { line = (function()
      for _, e in ipairs(st.buffers) do
        if e.id == st.active then
          return e.line
        end
      end
    end)() })
  end
end

-- Ensure config defaults and autocmds are in place even if the user never called setup().
local function ensure_setup()
  if not M.config.jar then
    M.config.jar = default_jar()
  end
  code.setup()
  search.setup()
  clipboard.setup(M.config.clipboard)
  map_finders()
  setup_load_handlers()
  if not M._session_autocmd then
    M._session_autocmd = true
    vim.api.nvim_create_autocmd("VimLeavePre", {
      group = vim.api.nvim_create_augroup("jadxnvim_session", { clear = true }),
      callback = save_session,
    })
  end
end

--- Open a jadx project (APK/dex/jar or .jadx file): start the daemon and show the tree.
--- opts.temp = true works in memory and never writes a .jadx (overrides config.temp).
function M.open(project, opts)
  opts = opts or {}
  ensure_setup()
  if not project or project == "" then
    vim.notify("[jadxnvim] usage: :Jadx <path-to-apk-or-project>", vim.log.levels.ERROR)
    return
  end
  project = vim.fn.fnamemodify(project, ":p")
  if vim.fn.filereadable(project) == 0 then
    vim.notify("[jadxnvim] file not found: " .. project, vim.log.levels.ERROR)
    return
  end
  if not M.config.jar or vim.fn.filereadable(M.config.jar) == 0 then
    vim.notify(
      "[jadxnvim] daemon jar not found: " .. tostring(M.config.jar) .. "\nBuild it with: (cd daemon && ./gradlew shadowJar)",
      vim.log.levels.ERROR
    )
    return
  end

  -- Save the outgoing project's session before switching away (same-project reopen/recovery keeps
  -- the on-disk session as the source of truth).
  if M._project and M._project ~= project then
    save_session()
  end
  M._project = project

  code.reset()
  tree.reset()

  local name = vim.fn.fnamemodify(project, ":t")
  local temp = opts.temp
  if temp == nil then
    temp = M.config.temp
  end
  local will_export = M.config.export and not temp and not opts._force_no_export
  local cmd = { M.config.java }
  -- Size the heap to a fraction of *available* memory. Use MaxRAMPercentage rather than a computed
  -- -Xmx so the JVM respects a container/cgroup memory limit (computing from physical RAM can
  -- over-commit a cgroup and get the process OOM-killed with code 137). Users can override with
  -- their own -Xmx / -XX:MaxRAM* in java_args.
  local has_heap = false
  for _, a in ipairs(M.config.java_args) do
    if type(a) == "string" and (a:match("^%-Xmx") or a:match("MaxRAMPercentage") or a:match("MaxRAM=")) then
      has_heap = true
    end
  end
  if not has_heap then
    table.insert(cmd, "-XX:MaxRAMPercentage=70.0")
  end
  if M.config.lean == true then
    -- Lean mode's whole point is a small steady-state footprint, so tell G1 to actually hand the
    -- big export-time heap back to the OS once the model is dropped (default ratios keep it mapped).
    vim.list_extend(cmd, { "-XX:MinHeapFreeRatio=5", "-XX:MaxHeapFreeRatio=25", "-XX:G1PeriodicGCInterval=5000" })
  end
  if opts._index_threads then
    -- OOM-retry ladder: cap indexing concurrency to lower peak memory.
    table.insert(cmd, "-Djadxnvim.indexThreads=" .. tostring(opts._index_threads))
  end
  vim.list_extend(cmd, M.config.java_args)
  vim.list_extend(cmd, { "-jar", M.config.jar, project })
  if not will_export then
    table.insert(cmd, "--no-export")
  end
  if temp then
    table.insert(cmd, "--temp")
  end
  if M.config.usage == false then
    table.insert(cmd, "--no-usage")
  end
  if M.config.lean == true then
    table.insert(cmd, "--lean")
  end
  local rg = M.config.rg
  if not rg or rg == "" then
    rg = vim.fn.exepath("rg")
  end
  if rg and rg ~= "" then
    vim.list_extend(cmd, { "--rg", rg })
  end

  M._loading = true
  M._loading_name = name
  progress.start("Loading " .. name)
  rpc.start(cmd, function(info)
    -- Parse finished and the tree is ready. While exporting, keep the bar (it now shows the
    -- background decompile/export %); otherwise we're done loading.
    if not will_export then
      M._loading = false
      progress.finish()
    end
    vim.notify(
      string.format("[jadxnvim] loaded %s (%s classes)", name, tostring(info.classes)),
      vim.log.levels.INFO
    )
    tree.open()
    restore_session(project)
  end, function(code_)
    -- The daemon died unexpectedly (137 = OOM-killed, usually while indexing a huge APK). Rather
    -- than give up on the index, retry with progressively fewer indexing threads (each concurrent
    -- decompile holds memory, so fewer = lower peak). The daemon resumes from its last checkpoint,
    -- so little work is redone. Only after 1 thread still OOMs do we fall back to no index.
    M._loading = false
    progress.finish()
    if will_export and not opts._force_no_export then
      local ladder = { [8] = 4, [4] = 2, [2] = 1 }
      local cur = opts._index_threads or 8
      local nxt = ladder[cur]
      if nxt then
        vim.notify(
          string.format(
            "[jadxnvim] indexing ran out of memory (exit %s). Retrying with %d indexing thread%s "
              .. "(lower memory), resuming where it left off…",
            tostring(code_), nxt, nxt == 1 and "" or "s"
          ),
          vim.log.levels.WARN
        )
        M.open(project, { temp = temp, _index_threads = nxt })
      else
        vim.notify(
          "[jadxnvim] indexing ran out of memory even at 1 thread. Reopening without the search "
            .. "index — browsing and (slower) in-memory search still work. Give the JVM more memory "
            .. "via java_args (e.g. { '-Xmx24g' }), or set export = false.",
          vim.log.levels.WARN
        )
        M.open(project, { temp = temp, _force_no_export = true })
      end
    else
      vim.notify("[jadxnvim] daemon exited (" .. tostring(code_) .. ")", vim.log.levels.ERROR)
    end
  end)
end

function M.close()
  save_session()
  M._loading = false
  progress.finish()
  rpc.stop()
  tree.reset()
  code.reset()
  M._project = nil
end

--- Toggle/focus the tree window (project must already be open).
function M.tree()
  if not rpc.is_running() then
    vim.notify("[jadxnvim] no project open; use :Jadx <path>", vim.log.levels.WARN)
    return
  end
  tree.open()
end

return M
