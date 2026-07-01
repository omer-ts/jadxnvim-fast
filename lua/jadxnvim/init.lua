-- jadxnvim: a Neovim front-end for the jadx decompiler, backed by the jadxd daemon.

local rpc = require("jadxnvim.rpc")
local tree = require("jadxnvim.tree")
local code = require("jadxnvim.code")
local search = require("jadxnvim.search")

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
  -- Global keymaps for the fuzzy finders. Set a value to false to skip mapping it.
  keys = {
    find_text = "<leader>ff",
    find_classes = "<leader>fc",
    find_methods = "<leader>fd",
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

-- Ensure config defaults and autocmds are in place even if the user never called setup().
local function ensure_setup()
  if not M.config.jar then
    M.config.jar = default_jar()
  end
  code.setup()
  search.setup()
  map_finders()
end

--- Open a jadx project (APK/dex/jar or .jadx file): start the daemon and show the tree.
function M.open(project)
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

  local cmd = { M.config.java }
  vim.list_extend(cmd, M.config.java_args)
  vim.list_extend(cmd, { "-jar", M.config.jar, project })

  vim.notify("[jadxnvim] loading " .. vim.fn.fnamemodify(project, ":t") .. " ...", vim.log.levels.INFO)
  rpc.start(cmd, function(info)
    vim.notify(
      string.format("[jadxnvim] loaded %s (%s classes)", vim.fn.fnamemodify(project, ":t"), tostring(info.classes)),
      vim.log.levels.INFO
    )
    tree.open()
  end)
end

function M.close()
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
