# jadxnvim

A Neovim front-end for [jadx](https://github.com/skylot/jadx) — browse and analyze decompiled
Android APKs from the terminal, over SSH, on large projects, without leaving the editor.

jadxnvim does **not** replace jadx-gui. It is a second interface that reads/writes the same
project format, so a project opened in jadxnvim can be opened in jadx-gui (and vice versa) with
renames and comments intact.

## How it works

```
  Neovim plugin (Lua)  <--- newline JSON-RPC over stdio --->  jadxd daemon (JVM, embeds jadx-core)
```

The daemon embeds `jadx-core` as a library. It parses the APK once, then decompiles a class on
demand for browsing. On first load it also **exports** the decompiled sources in the background into
a compact, ripgrep-friendly index, so **full-text search runs at ripgrep speed** even on very large
APKs. The export is cached, so subsequent opens skip it.

Benchmarks (this is not a small tool): a large APK (540 MB, **396k classes**) parses in ~80 s, exports
in ~3 min (one-time, cached), after which full-text search returns in **~0.1–0.3 s**. Instagram
(136 MB, 158k classes) is proportionally faster.

Both the daemon and Neovim are meant to run on the same (powerful) machine you SSH into;
communication is local stdio, so there is no network protocol or auth to configure.

## Requirements

- Java 17+ (the daemon is built/run on the JVM)
- Neovim 0.10+
- [ripgrep](https://github.com/BurntSushi/ripgrep) (`rg`) on `PATH` for fast full-text search
  (jadxnvim falls back to an in-memory scan if it's missing)
- No system Gradle needed — the repo ships a Gradle wrapper
- Enough RAM for very large APKs. jadxnvim sizes the heap to 70% of available memory automatically
  (`-XX:MaxRAMPercentage`, so it respects container/cgroup limits). If indexing still gets OOM-killed
  (daemon exits with code 137), jadxnvim reopens the project **without** the search index so browsing
  and (slower) in-memory search keep working — give the JVM more memory via `java_args = { "-Xmx24g" }`,
  lower the export concurrency (`java_args = { "-Djadxnvim.indexThreads=4" }`), or set `export = false`.

  Rough memory floor (measured, a large APK — 540 MB / 396k classes, idle after load):

  | mode | live heap | load time |
  | --- | --- | --- |
  | default (`usage = true`) | ~6.9 GB | ~72 s |
  | `usage = false` | ~4.6 GB | ~52 s |

  jadx builds a global cross-reference (usage) graph at load — ~2.3 GB of it for a large APK — that powers
  precise find-usages (`gr`) and single-use anonymous-class inlining. On a memory-constrained server
  set `usage = false` to skip it: `gr` then falls back to a fast name-based text search, and anonymous
  classes aren't inlined. The rest of the floor is jadx's parsed model + dex buffers, which must stay
  in RAM for on-demand decompilation.

## Build the daemon

```sh
cd daemon
./gradlew shadowJar         # produces daemon/build/libs/jadxd.jar
```

On Windows: `./gradlew.bat shadowJar`.

## Install the plugin (optional)

You only need this if you want the `:Jadx` commands available in your normal Neovim sessions — the
`scripts/jadxnvim` CLI launcher below works without it. Point your plugin manager at this repo, or
add it to the runtimepath. With lazy.nvim:

```lua
{
  dir = "/path/to/jadxnvim",
  config = function()
    require("jadxnvim").setup({
      -- jar defaults to <repo>/daemon/build/libs/jadxd.jar
      -- jar = "/custom/path/jadxd.jar",
      -- java = "java",
      -- java_args = { "-Xmx24g" }, -- override the auto heap size
      -- export = true,             -- decompile to a ripgrep index on load (fast search); cached
      -- rg = "/path/to/rg",        -- ripgrep binary (default: auto-detect on PATH)
    })
  end,
}
```

## Launch from the shell (CLI)

The quickest way in — open a project straight from your terminal, no need to start Neovim and run
`:Jadx` yourself:

```sh
# Linux / macOS / SSH server:
scripts/jadxnvim app.apk
scripts/jadxnvim project.jadx
scripts/jadxnvim --temp app.apk   # work in memory, don't write a .jadx

# Windows (PowerShell):
scripts\jadxnvim.ps1 app.apk
```

Put it on your `PATH` for a bare `jadxnvim app.apk` command, e.g.:

```sh
ln -s "$PWD/scripts/jadxnvim" ~/.local/bin/jadxnvim   # (chmod +x scripts/jadxnvim once)
```

The launcher prepends this repo to Neovim's `runtimepath` for the session, so it works even if you
haven't added the plugin to your config — your normal config (colors, keymaps, other plugins) still
loads, and the project opens automatically. It builds the daemon jar on first run if needed.
Set `JADXD_JAVA_OPTS=-Xmx6g` for very large APKs, or `JADXNVIM_NVIM` to choose the nvim binary.
Extra args after the project are passed through to `nvim`.

## Usage (inside Neovim)

| Command                | Action                                                          |
| ---------------------- | --------------------------------------------------------------- |
| `:Jadx <path>`         | Open an APK / dex / jar / `.jadx` project and show the tree     |
| `:JadxTree`            | Focus the project tree                                          |
| `:JadxDef`             | Go to definition of the symbol under the cursor                 |
| `:JadxUsages`          | Find usages of the symbol under the cursor (xref, browsable + preview) |
| `:JadxSearch [text]`   | Full-text search across decompiled code (streamed → quickfix)   |
| `:JadxSearchName [q]`  | Search class/method/field names                                 |
| `:JadxSearchCancel`    | Cancel the running search                                       |
| `:JadxFindClass`       | Fuzzy-find a class                                              |
| `:JadxFindMethod`      | Fuzzy-find a method (jumps to its declaration)                  |
| `:JadxFindText`        | Full-text search, then fuzzy-narrow the results                |
| `:JadxRename`          | Rename the symbol under the cursor (persists to `.jadx`)        |
| `:JadxComment`         | Comment the symbol under the cursor (persists to `.jadx`)       |
| `:JadxClose`           | Close the project and stop the daemon                           |

### Fuzzy finders

A self-contained fuzzy picker (built on Neovim's `matchfuzzy` — **no Telescope/fzf-lua required**)
is bound to these global keys when a project is open:

| Key         | Finds                                                         |
| ----------- | ------------------------------------------------------------- |
| `<Space>ff` | text — enter a term, watch results stream in, then fuzzy them |
| `<Space>fc` | classes                                                       |
| `<Space>fd` | methods (jumps to the declaration)                            |

In the picker: type to filter, `<C-n>`/`<C-p>` or `<Up>`/`<Down>` (`<C-d>`/`<C-u>` to page) to move,
`<CR>` to open, `<Esc>` to cancel. A **syntax-highlighted preview** of the highlighted result is
shown beside the list (bat-style — line-numbered and centered on the match). A status footer shows
`shown / total` (and `searching… N found` while a text search is still streaming). Bound to literal `<Space>` so it works regardless of your `mapleader`.
Rebind or disable via `keys` in `setup()`:

```lua
require("jadxnvim").setup({
  keys = {
    find_text = "<Space>ff",      -- set to false to skip mapping
    find_classes = "<Space>fc",
    find_methods = "<Space>fd",
  },
})
```

In the **tree** window: `<CR>` / `o` expand/collapse a package or open a class.

In a **code** buffer (`jadx://<class>`, read-only `java`):

| Key          | Action                         |
| ------------ | ------------------------------ |
| `gd`         | Go to definition               |
| `gr`         | Find usages (xref) — browsable list with preview |
| `<Tab>`      | Toggle Java ⟷ Smali view        |
| `<leader>jr` | Rename                         |
| `<leader>jc` | Comment                        |

In code buffers `y` / `Y` copy to your **computer's** clipboard — over SSH too, via OSC 52 (no
`xclip`/`win32yank` needed). Disable with `clipboard = false` in `setup()`, or it steps aside if
you've already configured a clipboard provider.

Go-to-definition and usages integrate with the jumplist and quickfix, so `<C-o>`/`<C-i>` and
`:cnext`/`:cprev` work as usual.

## Seamless jadx-gui interop

Opening an APK **creates a `.jadx` project next to it automatically** (use `--temp` / `temp = true`
to work purely in memory and never write a file). Renames and comments are stored in jadx's native
code-data format and written to that `.jadx` (same `projectVersion`/`files`/`codeData` shape and
GSON serialization jadx-gui uses). Open the same `.jadx` in jadx-gui and your renames/comments are
there; conversely, opening a project edited in jadx-gui shows its renames/comments in jadxnvim.
Saving preserves fields jadxnvim doesn't manage (open tabs, tree state, ...), so round-tripping a
jadx-gui project doesn't lose its UI state. The input APK is referenced relatively, so a project
directory stays portable.

## Status

All v1 milestones are implemented and tested against real APKs (incl. a 136 MB / 158k-class app):

- [x] Daemon: lazy load, package tree, on-demand class decompilation
- [x] Plugin: project tree, code view
- [x] Cross-references (go-to-definition, find-usages)
- [x] Search (class/method/field names, and ripgrep full-text over an on-load export — fast on
      400k-class APKs, cached, with results re-located onto the live decompiled line)
- [x] Saved searches ("search tabs"): reopen or close past text searches / xrefs (`<Space>fs`)
- [x] Class / method finders stream matches server-side (query-driven), scanning the raw parsed
      model (`getClassNode().getMethods()`, like jadx-gui) so no class is decompiled during a name
      search — sub-second on a 400k-class APK with no OOM, even without the export. Once the on-load
      export is ready they ripgrep a class/method **name index** for an extra speedup.
- [x] Rename + comments persisted to the `.jadx` project (jadx-gui interop)
- [x] Built-in fuzzy finders for classes / methods / text (no external picker needed)
- [x] Java ⟷ Smali toggle (`<Tab>`) and a load progress bar (animated, or real % with `prefetch`)
- [x] Syntax highlighting for Java and Smali (applies a readable palette on a bare Neovim; a
      colorscheme you set is respected)

Roadmap toward broader jadx-gui parity: resource/`AndroidManifest` viewer, certificate info, smali
view, bookmarks, deobfuscation toggle, mappings import/export, and instruction-level comments.
