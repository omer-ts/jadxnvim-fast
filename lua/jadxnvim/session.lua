-- Per-project session persistence: remembers which classes you had open and where the cursor was,
-- so reopening a project restores your last position. State lives in Neovim's state dir keyed by
-- the project path (nothing is written into the project directory).

local M = {}

local function state_dir()
  local d = vim.fn.stdpath("state") .. "/jadxnvim"
  vim.fn.mkdir(d, "p")
  return d
end

local function path_for(project)
  local key = vim.fn.sha256(vim.fn.fnamemodify(project, ":p"))
  return state_dir() .. "/" .. key .. ".json"
end

function M.save(project, data)
  if not project or project == "" then
    return
  end
  local ok, enc = pcall(vim.json.encode, data)
  if ok then
    pcall(vim.fn.writefile, { enc }, path_for(project))
  end
end

function M.load(project)
  if not project or project == "" then
    return nil
  end
  local p = path_for(project)
  if vim.fn.filereadable(p) == 0 then
    return nil
  end
  local ok, lines = pcall(vim.fn.readfile, p)
  if not ok or not lines or #lines == 0 then
    return nil
  end
  local ok2, data = pcall(vim.json.decode, table.concat(lines, "\n"))
  if ok2 and type(data) == "table" then
    return data
  end
  return nil
end

return M
