" Minimal Smali syntax for jadxnvim's Smali view.
if exists("b:current_syntax")
  finish
endif

syn match smaliComment "#.*$"
syn region smaliString start=+"+ skip=+\\"+ end=+"+ oneline
syn match smaliDirective "^\s*\.\w\+"
syn match smaliRegister "\<[vp]\d\+\>"
syn match smaliLabel ":\w\+"
syn match smaliType "\<L[A-Za-z0-9/_$]*;"
syn match smaliNumber "\<0x\x\+\>"
syn match smaliNumber "\<-\?\d\+\>"
syn match smaliAnnotation "@\w\+"
" first token of an instruction line (e.g. invoke-virtual, const-string, return-void)
syn match smaliOpcode "^\s*\zs[a-z][a-z0-9/-]*\ze\($\|\s\)"

hi def link smaliComment Comment
hi def link smaliString String
hi def link smaliDirective PreProc
hi def link smaliRegister Identifier
hi def link smaliLabel Label
hi def link smaliType Type
hi def link smaliNumber Number
hi def link smaliAnnotation Special
hi def link smaliOpcode Statement

let b:current_syntax = "smali"
