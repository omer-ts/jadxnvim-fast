" Smali syntax for jadxnvim's Smali view.
if exists("b:current_syntax")
  finish
endif

" Comments
syn match   smaliComment    "#.*$" contains=@Spell

" Strings and chars
syn region  smaliString     start=+"+ skip=+\\"+ end=+"+ oneline
syn match   smaliChar       "'\\\?.'"

" Directives (.class, .method, .end method, .line, .annotation, ...)
syn match   smaliDirective  "^\s*\.\%(end\s\+\)\?\%(class\|super\|implements\|source\|field\|method\|annotation\|subannotation\|param\|parameter\|local\|locals\|registers\|prologue\|epilogue\|line\|catch\|catchall\|packed-switch\|sparse-switch\|array-data\|enum\|restart\|packed-switch-payload\|sparse-switch-payload\|array-data-payload\)\>"

" Access / modifier keywords
syn keyword smaliAccess     public private protected static final synchronized
syn keyword smaliAccess     abstract native transient volatile synthetic bridge
syn keyword smaliAccess     varargs interface enum annotation constructor declared-synchronized strict

" Registers
syn match   smaliRegister   "\<[vp]\d\+\>"

" Labels (:cond_0, :goto_1, :try_start_0)
syn match   smaliLabel      ":[[:alnum:]_$-]\+"

" JVM/Dalvik type descriptors: Lcom/foo/Bar; , [I , primitives V Z B S C I J F D
syn match   smaliType       "\[*L[[:alnum:]/_$]\+;"
syn match   smaliType       "\[\+[VZBSCIJFD]\>"

" Numbers (hex, decimal, float suffixes)
syn match   smaliNumber     "\<0x[[:xdigit:]]\+[Lst]\?\>"
syn match   smaliNumber     "\<-\?\d\+\%(\.\d\+\)\?\%([eE][-+]\?\d\+\)\?[LfdFDst]\?\>"

" Annotations references
syn match   smaliAnnotation "@[[:alnum:]/_$;]\+"

" Opcodes: first token of an instruction line (invoke-virtual, const-string, return-void, ...)
syn match   smaliOpcode     "^\s*\zs[a-z][a-z0-9/-]*\ze\%($\|\s\)"

hi def link smaliComment    Comment
hi def link smaliString     String
hi def link smaliChar       Character
hi def link smaliDirective  PreProc
hi def link smaliAccess     StorageClass
hi def link smaliRegister   Identifier
hi def link smaliLabel      Label
hi def link smaliType       Type
hi def link smaliNumber     Number
hi def link smaliAnnotation Special
hi def link smaliOpcode     Statement

let b:current_syntax = "smali"
