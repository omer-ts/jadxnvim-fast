-- Load, package tree, class list, and on-demand decompilation.
local repo = assert(os.getenv("JADXNVIM_REPO"))
vim.opt.runtimepath:prepend(repo)
package.path = repo .. "/tests/?.lua;" .. package.path
local H = require("helpers")

H.spec(function()
  local _, pkgs = H.req("getPackages")
  local names = {}
  for _, p in ipairs(pkgs.packages or {}) do
    names[p.name] = p.count
  end
  H.check("package com.example present", names["com.example"] ~= nil, vim.inspect(vim.tbl_keys(names)))
  H.check("package com.example.app present", names["com.example.app"] ~= nil)
  H.check("package com.example.util present", names["com.example.util"] ~= nil)

  local _, cls = H.req("getClasses", { package = "com.example" })
  local ids = {}
  for _, c in ipairs(cls.classes or {}) do
    ids[c.id] = true
  end
  H.check("com.example has Greeter", ids["com.example.Greeter"] == true)
  H.check("com.example has Hello", ids["com.example.Hello"] == true)
  H.check("com.example has Formal", ids["com.example.Formal"] == true)

  local _, coded = H.req("getCode", { id = "com.example.Hello" })
  H.check("getCode returns Hello source", coded.code:find("class Hello") ~= nil)
  H.check("getCode shows the greet method", coded.code:find("greet") ~= nil)
end)
