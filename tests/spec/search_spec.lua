-- Class / method name search and full-text search.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec(function()
  local classes = H.search("searchName", { query = "Hello", kind = "class", limit = 100 })
  local found_cls = false
  for _, it in ipairs(classes) do
    if it.id == "com.example.Hello" then
      found_cls = true
    end
  end
  H.check("class search finds com.example.Hello", found_cls, #classes .. " hits")

  local methods = H.search("searchName", { query = "greet", kind = "method", limit = 100 })
  local greet_hit
  for _, it in ipairs(methods) do
    if (it.fullName or ""):find("greet") then
      greet_hit = it
    end
  end
  H.check("method search finds greet", greet_hit ~= nil, #methods .. " hits")
  H.check("method hit carries an index for navigation", greet_hit and greet_hit.index ~= nil)

  local text = H.search("searchText", { query = "s3cr3t_marker_9f2a", limit = 100 })
  local text_hit
  for _, it in ipairs(text) do
    if it.id == "com.example.util.Strings" then
      text_hit = it
    end
  end
  H.check("text search finds the token in Strings", text_hit ~= nil, #text .. " hits")
  H.check("text hit carries id + line", text_hit and text_hit.line ~= nil)
end)
