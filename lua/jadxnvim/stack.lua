-- Jump from a stack-trace / logcat frame to the source location in the opened APK.
--
-- A frame like `at com.foo.Bar.doThing(Bar.java:123)` carries the *original* source line (123),
-- which does not match jadx's decompiled Java line numbers. jadx's smali, however, keeps the dex
-- debug info as `.line 123` directives, so we open the class's smali and land on that directive.

local rpc = require("jadxnvim.rpc")
local code = require("jadxnvim.code")

local M = {}

--- Parse a stack-trace frame -> (classFqn, sourceLine, methodName) or nil.
function M.parse(line)
  if not line then
    return nil
  end
  -- qualified `pkg.Class.method` (incl. $ inner, <init>) followed by `(...:<line>)`
  local qn, ln = line:match("([%w_$.<>]+)%s*%([^)]*:(%d+)%)")
  if not qn or not ln then
    return nil
  end
  local cls, method = qn:match("^(.*)%.([%w_$<>]+)$") -- split trailing `.method`
  if not cls then
    cls, method = qn, nil
  end
  return cls, tonumber(ln), method
end

--- Jump to the source frame on the given text (or the current line): open the class's smali and
--- position on its `.line <n>` directive.
function M.goto_source(input)
  if not rpc.is_running() then
    vim.notify("[jadxnvim] no project open", vim.log.levels.WARN)
    return
  end
  local text = (input and input ~= "") and input or vim.api.nvim_get_current_line()
  local cls, ln = M.parse(text)
  if not cls then
    vim.notify("[jadxnvim] no 'Class(File.java:line)' frame here", vim.log.levels.WARN)
    return
  end
  local top = cls:match("^(.-)%$") -- top-level class (before the first inner-class '$')
  local function open(id, fallback)
    rpc.request("getSmali", { id = id }, function(err, res)
      vim.schedule(function()
        if err or not res or not res.smali then
          if fallback then
            open(fallback, nil)
          else
            vim.notify("[jadxnvim] class not found for frame: " .. cls, vim.log.levels.WARN)
          end
          return
        end
        code.open_smali(id, { source_line = ln })
      end)
    end)
  end
  open(cls, (top and top ~= cls) and top or nil)
end

--- Like goto_source but opens the decompiled Java view at the nearest position — the method from
--- the frame (jadx's Java line numbers don't match the original source, so this lands on the method
--- that contains it rather than an exact line).
function M.goto_source_java(input)
  if not rpc.is_running() then
    vim.notify("[jadxnvim] no project open", vim.log.levels.WARN)
    return
  end
  local text = (input and input ~= "") and input or vim.api.nvim_get_current_line()
  local cls, _, method = M.parse(text)
  if not cls then
    vim.notify("[jadxnvim] no 'Class(File.java:line)' frame here", vim.log.levels.WARN)
    return
  end
  local top = cls:match("^(.-)%$")
  local function open(id, fallback)
    rpc.request("getCode", { id = id }, function(err, res)
      vim.schedule(function()
        if err or not res or not res.code then
          if fallback then
            open(fallback, nil)
          else
            vim.notify("[jadxnvim] class not found for frame: " .. cls, vim.log.levels.WARN)
          end
          return
        end
        code.open(id, { find_method = method })
      end)
    end)
  end
  open(cls, (top and top ~= cls) and top or nil)
end

return M
