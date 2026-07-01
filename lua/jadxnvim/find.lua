-- Fuzzy finders for classes, methods, and full-text, backed by the built-in fuzzy picker.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")
local fuzzy = require("jadxnvim.fuzzy")
local preview = require("jadxnvim.preview")
local searches = require("jadxnvim.searches")

local M = {}

local function ensure()
  if not rpc.is_running() then
    vim.notify("[jadxnvim] no project open; use :Jadx <path>", vim.log.levels.WARN)
    return false
  end
  return true
end

local function err_notify(what, err)
  vim.schedule(function()
    vim.notify("[jadxnvim] " .. what .. " failed: " .. (err.message or "?"), vim.log.levels.ERROR)
  end)
end

local class_previewer = preview.class
local method_previewer = preview.method

local function open_method(it)
  rpc.request("memberPos", { id = it.id, index = it.index }, function(e, r)
    vim.schedule(function()
      if e or not r then
        code.open(it.id)
      else
        code.open(it.id, { line = r.line, col = r.col })
      end
    end)
  end)
end

-- Query-driven name finder: enter a term, the daemon streams matching names (server-side, so it
-- never loads millions of names into one response and can't OOM on huge APKs), then you fuzzy-
-- filter the streamed results. Used for both classes and methods.
local function name_finder(kind, title, prompt, on_select, previewer)
  if not ensure() then
    return
  end
  fuzzy.pick({
    title = title,
    items = {},
    previewer = previewer,
    query_phase = {
      prompt = prompt,
      on_submit = function(term, handle)
        term = vim.trim(term or "")
        if term == "" then
          handle.close()
          return
        end
        handle.set_loading(true)
        local my = { id = nil }
        local collected = {}
        local d1 = rpc.on("searchHits", function(p)
          if p.searchId ~= my.id then
            return
          end
          local batch = {}
          for _, it in ipairs(p.items or {}) do
            local item = { text = it.fullName, id = it.id, index = it.index, kind = it.kind }
            batch[#batch + 1] = item
            collected[#collected + 1] = item
          end
          vim.schedule(function()
            handle.append(batch)
          end)
        end)
        local d2 = rpc.on("searchDone", function(p)
          if p.searchId == my.id then
            vim.schedule(function()
              handle.done()
              if #collected > 0 then
                searches.record({
                  title = string.format(" %s '%s' (%d) ", vim.trim(title), term, #collected),
                  items = collected,
                  previewer = previewer,
                  on_select = on_select,
                })
              end
            end)
          end
        end)
        handle.on_cleanup(function()
          if my.id then
            rpc.request("cancelSearch", { searchId = my.id })
          end
          pcall(d1)
          pcall(d2)
        end)
        rpc.request("searchName", { query = term, kind = kind, limit = 5000 }, function(err, res)
          if err then
            vim.schedule(handle.done)
            return
          end
          my.id = res.searchId
        end)
      end,
    },
    on_select = on_select,
  })
end

--- Find a class by name; open it on select.
function M.classes()
  name_finder("class", " Classes ", "class name", function(it)
    code.open(it.id)
  end, class_previewer())
end

--- Find a method by name; open its class and jump to the declaration on select.
function M.methods()
  name_finder("method", " Methods ", "method name", open_method, method_previewer())
end

--- Full-text search, fully inside the popup: enter a term, watch results stream in (with a
--- loading/count status in the popup), then fuzzy-filter the loaded results. Open on select.
function M.text()
  if not ensure() then
    return
  end
  fuzzy.pick({
    title = " Text search ",
    items = {},
    previewer = class_previewer(),
    query_phase = {
      prompt = "search term",
      on_submit = function(term, handle)
        term = vim.trim(term or "")
        if term == "" then
          handle.close()
          return
        end
        handle.set_title(" Text: " .. term .. " ")
        handle.set_loading(true)

        local on_open = function(s)
          code.open(s.id, { line = s.line, col = s.col, find = s.snippet })
        end
        local collected = {}
        local cap = 5000
        local my = { id = nil }
        local d1 = rpc.on("searchHits", function(p)
          if p.searchId ~= my.id then
            return
          end
          local batch = {}
          for _, it in ipairs(p.items or {}) do
            batch[#batch + 1] = {
              text = it.fullName .. ":" .. it.line .. "  " .. (it.text or ""),
              id = it.id,
              line = it.line,
              col = it.col,
              snippet = it.text,
            }
            collected[#collected + 1] = batch[#batch]
          end
          vim.schedule(function()
            handle.append(batch)
          end)
        end)
        local d2 = rpc.on("searchDone", function(p)
          if p.searchId == my.id then
            vim.schedule(function()
              handle.done()
              if #collected > 0 then
                searches.record({
                  title = string.format(" Text: %s (%d) ", term, #collected),
                  items = collected,
                  previewer = preview.class(),
                  on_select = on_open,
                })
              end
            end)
          end
        end)
        handle.on_cleanup(function()
          if my.id then
            rpc.request("cancelSearch", { searchId = my.id })
          end
          pcall(d1)
          pcall(d2)
        end)

        rpc.request("searchText", { query = term, limit = cap }, function(err, res)
          if err then
            vim.schedule(handle.done)
            return
          end
          my.id = res.searchId
        end)
      end,
    },
    on_select = function(s)
      code.open(s.id, { line = s.line, col = s.col, find = s.snippet })
    end,
  })
end

return M
