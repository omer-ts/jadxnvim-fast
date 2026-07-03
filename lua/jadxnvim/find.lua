-- Fuzzy finders for classes, methods, and full-text, backed by the built-in fuzzy picker.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")
local fuzzy = require("jadxnvim.fuzzy")
local preview = require("jadxnvim.preview")
local searches = require("jadxnvim.searches")
local icons = require("jadxnvim.icons")

local function icon(name)
  local g = icons.get(name)
  return g ~= "" and (g .. " ") or ""
end

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
  local name = it.name or (it.text and it.text:match("^%s*(.-)%s*·"))
  rpc.request("memberPos", { id = it.id, index = it.index }, function(e, r)
    vim.schedule(function()
      if e or not r then
        code.open(it.id, { find_method = name })
      else
        code.open(it.id, { line = r.line, col = r.col, find_method = name })
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
          local ikind = (kind == "method") and "method" or "class"
          for _, it in ipairs(p.items or {}) do
            local full = it.fullName or ""
            local item = { text = icon(ikind) .. full, id = it.id, index = it.index, kind = it.kind }
            if ikind == "method" then
              -- daemon sends "name  ·  owner"; stash the bare name so open_method needn't parse
              -- (and can't trip over) the icon-prefixed display text.
              item.name = vim.trim(full:match("^(.-)%s*·") or full)
            end
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
                  kind = kind,
                  query = term,
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
                  kind = "text",
                  query = term,
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

-- Combined search (jadx-gui style): one term searches classes, methods and full text at once;
-- results are tagged by kind and share one bat-preview pane. Reuses the class/method previewers and
-- the class/method/text open actions — no duplicated search logic.
local function combined_open(it)
  if it.kind == "method" then
    open_method(it)
  elseif it.kind == "text" then
    code.open(it.id, { line = it.line, col = it.col, find = it.snippet })
  else
    code.open(it.id)
  end
end

local function combined_previewer()
  local cp = preview.class()
  local mp = preview.method()
  return function(item, render)
    if item and item.kind == "method" then
      mp(item, render)
    else
      cp(item, render) -- class (line 1) or text (relocate on snippet) — same class previewer
    end
  end
end

-- jadx-gui's "search everywhere" lists a type icon, the primary name, then a dimmer location.
-- We render the same shape in one line: "<icon> <name>   <location>".
local function combined_item(kind, it)
  if kind == "class" then
    local full = it.fullName or it.id or ""
    local short = full:match("[^.]+$") or full
    local pkg = full:sub(1, math.max(0, #full - #short - 1))
    return {
      kind = "class",
      id = it.id,
      fullName = full,
      text = string.format("%s%s   %s", icon("class"), short, pkg),
    }
  elseif kind == "method" then
    local full = it.fullName or "" -- daemon sends "methodName  ·  owner.Class"
    local mname, owner = full:match("^(.-)%s*·%s*(.+)$")
    mname = mname and vim.trim(mname) or full
    owner = owner and vim.trim(owner) or ""
    return {
      kind = "method",
      id = it.id,
      index = it.index,
      name = mname,
      fullName = full,
      text = string.format("%s%s   %s", icon("method"), mname, owner),
    }
  else
    local loc = string.format("%s:%d", it.fullName or it.id or "?", it.line or 0)
    return {
      kind = "text",
      id = it.id,
      line = it.line,
      col = it.col,
      snippet = it.text,
      text = string.format("%s%s   %s", icon("text"), (it.text or ""):gsub("%s+", " "), loc),
    }
  end
end

--- Unified search popup (classes + methods + full text) with a shared preview.
function M.combined()
  if not ensure() then
    return
  end
  fuzzy.pick({
    title = string.format(" Search everywhere  %sclass  %smethod  %stext ",
      icon("class"), icon("method"), icon("text")),
    items = {},
    previewer = combined_previewer(),
    query_phase = {
      prompt = "search everything",
      on_submit = function(term, handle)
        term = vim.trim(term or "")
        if term == "" then
          handle.close()
          return
        end
        handle.set_title(" Search: " .. term .. " ")
        handle.set_loading(true)
        local collected = {}
        local ids = { cls = nil, mth = nil, txt = nil }
        local finished = 0
        local function tick()
          finished = finished + 1
          if finished >= 3 then
            handle.done()
            if #collected > 0 then
              searches.record({
                kind = "text",
                query = term,
                title = string.format(" Search '%s' (%d) ", term, #collected),
                items = collected,
                previewer = combined_previewer(),
                on_select = combined_open,
              })
            end
          end
        end
        local function kind_of(searchId)
          if searchId == ids.cls then
            return "class"
          elseif searchId == ids.mth then
            return "method"
          elseif searchId == ids.txt then
            return "text"
          end
        end
        local d1 = rpc.on("searchHits", function(p)
          local kind = kind_of(p.searchId)
          if not kind then
            return
          end
          local batch = {}
          for _, it in ipairs(p.items or {}) do
            local item = combined_item(kind, it)
            batch[#batch + 1] = item
            collected[#collected + 1] = item
          end
          vim.schedule(function()
            handle.append(batch)
          end)
        end)
        local d2 = rpc.on("searchDone", function(p)
          if kind_of(p.searchId) then
            vim.schedule(tick)
          end
        end)
        handle.on_cleanup(function()
          for _, sid in pairs(ids) do
            if sid then
              rpc.request("cancelSearch", { searchId = sid })
            end
          end
          pcall(d1)
          pcall(d2)
        end)
        local function launch(method, params, slot)
          rpc.request(method, params, function(err, res)
            if err or not res then
              vim.schedule(tick) -- count the failed search so we can still finish
            else
              ids[slot] = res.searchId
            end
          end)
        end
        launch("searchName", { query = term, kind = "class", limit = 3000 }, "cls")
        launch("searchName", { query = term, kind = "method", limit = 3000 }, "mth")
        launch("searchText", { query = term, limit = 3000 }, "txt")
      end,
    },
    on_select = combined_open,
  })
end

return M
