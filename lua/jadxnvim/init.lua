-- jadxnvim: a Neovim front-end for the jadx decompiler, backed by the jadxd daemon.

local rpc = require("jadxnvim.rpc")
local tree = require("jadxnvim.tree")
local code = require("jadxnvim.code")
local search = require("jadxnvim.search")
local progress = require("jadxnvim.progress")

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
  -- Prefetch (decompile all classes in the background) to show a real 0-100% load bar.
  -- Off by default: with it off the load bar is an animated activity indicator. Enabling it on
  -- very large APKs warms the whole decompilation, so give the JVM more heap via java_args.
  prefetch = false,
  -- By default opening an APK creates/saves a .jadx project next to it. Set temp = true (or pass
  -- --temp on the CLI / to :Jadx) to work purely in memory and never write a .jadx file.
  temp = false,
  -- Global keymaps for the fuzzy finders. Set a value to false to skip mapping it.
  -- Bound to literal <Space> by default (works regardless of your mapleader).
  keys = {
    find_text = "<Space>ff",
    find_classes = "<Space>fc",
    find_methods = "<Space>fd",
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
end

-- Register the daemon load-lifecycle handlers exactly once.
local function setup_load_handlers()
  if M._load_handlers then
    return
  end
  M._load_handlers = true
  rpc.on("loadProgress", function(p)
    if M._loading then
      progress.update(p.percent, "Decompiling " .. (M._loading_name or ""))
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
end

-- Ensure config defaults and autocmds are in place even if the user never called setup().
local function ensure_setup()
  if not M.config.jar then
    M.config.jar = default_jar()
  end
  code.setup()
  search.setup()
  map_finders()
  setup_load_handlers()
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

  code.reset()
  tree.reset()

  local name = vim.fn.fnamemodify(project, ":t")
  local temp = opts.temp
  if temp == nil then
    temp = M.config.temp
  end
  local cmd = { M.config.java }
  vim.list_extend(cmd, M.config.java_args)
  vim.list_extend(cmd, { "-jar", M.config.jar, project })
  if M.config.prefetch then
    table.insert(cmd, "--prefetch")
  end
  if temp then
    table.insert(cmd, "--temp")
  end

  M._loading = true
  M._loading_name = name
  progress.start("Loading " .. name)
  rpc.start(cmd, function(info)
    -- Parse finished and the tree is ready. With prefetch, keep the bar (it now shows the
    -- background decompilation %); otherwise we're done loading.
    if not M.config.prefetch then
      M._loading = false
      progress.finish()
    end
    vim.notify(
      string.format("[jadxnvim] loaded %s (%s classes)", name, tostring(info.classes)),
      vim.log.levels.INFO
    )
    tree.open()
  end)
end

function M.close()
  M._loading = false
  progress.finish()
  rpc.stop()
  tree.reset()
  code.reset()
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
