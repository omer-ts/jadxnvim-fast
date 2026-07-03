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

  | mode | live heap (idle) | notes |
  | --- | --- | --- |
  | default (`usage = true`) | ~6.9 GB | full jadx-gui-quality output + precise `gr` |
  | `usage = false` | ~4.6 GB | skips jadx's ~2.3 GB xref graph (see below) |
  | `lean = true` | **~120 MB** | model dropped after export; served from disk |

  jadx builds a global cross-reference (usage) graph at load — ~2.3 GB of it for a large APK — that powers
  precise find-usages (`gr`) and single-use anonymous-class inlining. Set `usage = false` to skip it:
  `gr` then falls back to a fast name-based text search, and anonymous classes aren't inlined.

  **Lean mode (`lean = true`)** goes further: once the on-load export is written, jadxnvim drops jadx's
  entire in-memory model and serves the class tree, code view, search, the fuzzy finders, **go-to-def
  and find-usages** straight from the on-disk export (the export also writes an rg-searchable
  cross-reference index) — steady-state RAM falls to a few hundred MB (~420 MB RSS on a large APK). Only
  smali view and editing (rename/comment) rebuild the model on demand (one-time, with a notice). And
  once the export is **cached**, opening the project skips building the model entirely — it serves from
  disk immediately, so there's no multi-GB parse peak at all (a large APK opens in ~0 s at a few hundred
  MB). Only the *first* indexing of an APK needs the full model in memory. Implies `usage = false`;
  requires `export = true`. Cost: the cross-reference index adds disk (≈2 GB for a large APK, on top of
  the ~1 GB of decompiled shards) — it's queried with ripgrep and never loaded into RAM, and lives in
  the gitignored `<name>.jadxnvim/` cache.

## Build the daemon

```sh
cd daemon
./gradlew shadowJar         # produces daemon/build/libs/jadxd.jar
```

On Windows: `./gradlew.bat shadowJar`.

## Tests

A headless test suite runs the daemon + plugin against a small fixture compiled from
`tests/fixtures/src`, and runs on CI (see `.github/workflows/ci.yml`):

```sh
bash tests/run.sh        # builds the daemon jar + fixture jar if needed, runs every spec
```

Each `tests/spec/*_spec.lua` opens the fixture in a fresh headless Neovim and asserts behaviour
(load/tree, search, xref, edit + `.jadx` round-trip, Frida hooks, project state). Requires `nvim`,
a JDK (for `javac`/`jar`), and optionally `rg` on `PATH`.

After pulling changes that add daemon features, rebuild the jar and restart Neovim (the daemon runs
for the life of a Neovim session). If the plugin ever warns that *"the jadxd daemon is out of date"*,
it means a running daemon predates a method the plugin needs — rebuild as above and restart.

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
| `:JadxHelp`            | Command palette: list every command & shortcut, run the selected one |
| `:JadxTree`            | Focus the project tree                                          |
| `:JadxGotoPackage {p}` | Jump the tree to a package (Tab-completes package names)         |
| `:JadxGotoSource [f]`  | Jump a stack-trace frame `Class(File.java:line)` to its smali source line |
| `:JadxGotoSourceJava [f]` | Same, but open the decompiled Java at the nearest position (the method) |
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
| `:JadxBookmark`        | Toggle a bookmark at the cursor                                 |
| `:JadxBookmarks`       | List / jump to / delete bookmarks                              |
| `:JadxFridaHook`       | Generate a Frida hook for the symbol under the cursor           |
| `:JadxFridaHookClass`  | Generate a Frida hook for every method of the current class     |
| `:JadxClose`           | Close the project and stop the daemon                           |

### Fuzzy finders

A self-contained fuzzy picker (built on Neovim's `matchfuzzy` — **no Telescope/fzf-lua required**)
is bound to these global keys when a project is open:

| Key         | Finds                                                         |
| ----------- | ------------------------------------------------------------- |
| `<Space>ff` | text — enter a term, watch results stream in, then fuzzy them |
| `<Space>fc` | classes                                                       |
| `<Space>fd` | methods (jumps to the declaration)                            |
| `<Space>fv` | everything — classes + methods + text in one list (jadx-gui-style) |
| `<Space>fs` | **search history** — reopen or delete past searches / xrefs (also `:JadxHistory`) |
| `<Space>fb` | **bookmarks** — jump to or delete bookmarked positions (also `:JadxBookmarks`) |
| `<Space>fh` | **help / command palette** — every command & shortcut; Enter runs it (also `:JadxHelp`) |

**Search history:** every text search, xref (find-usages), class/method search is recorded. `<Space>fs`
opens a history list — each entry shows its type icon, query + count, and age, with a preview of its
results. `<CR>` reopens the full result set (after you closed it), `<C-x>` / `dd` deletes an entry,
`<C-l>` clears all.

In the picker: type to filter, `<C-n>`/`<C-p>` or `<Up>`/`<Down>` (`<C-d>`/`<C-u>` to page) to move,
`<CR>` to open, `<Esc>` to cancel. `<C-t>` sends the current results to a **persistent scratch pane**
(a normal, searchable buffer where `<CR>` opens a result and `q` closes). `<C-f>` generates a
**Frida hook script** for the results (classes → hook the class, methods → hook the method, a usages
list → hook the searched method) into a scratch `.js` buffer. A **syntax-highlighted preview** of the highlighted result is
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

In the **tree** window the project is split into two sections, each row tagged with a type icon:

- **Sources** — packages → classes (`<CR>` / `o` expands a package or opens a class).
- **Resources** — the APK's resource files as a directory tree; opening one (`AndroidManifest.xml`,
  `res/values/strings.xml`, …) shows its **decoded** text, syntax-highlighted by extension. In lean
  mode the resource *list* is served from disk; opening a resource's *content* rebuilds the model
  once (like smali).

Press `/` to **filter** the tree (case-insensitive; `<Esc>` clears). The icons default to Nerd Font
glyphs — override them (or switch to plain ASCII) via `icons` in `setup()`:

```lua
require("jadxnvim").setup({
  icons = { class = "C", package = "pkg", folder = "/", file = "-" }, -- any subset; see lua/jadxnvim/icons.lua
})
```

In a **code** buffer (`jadx://<class>`, read-only `java`):

| Key          | Action                         |
| ------------ | ------------------------------ |
| `gd`         | Go to definition (falls back to class/method name search if unresolved). On a call through an interface/base type it lists **all implementations** to pick from |
| `gr`         | Find usages (xref) — browsable list with preview; on an overriding method it also finds virtual-dispatch calls made through the interface/base type |
| `<Tab>`      | Toggle Java ⟷ Smali (syncs to the same method; remembers each pane's cursor) |
| `<leader>jr` | Rename                         |
| `<leader>jc` | Comment                        |
| `<leader>jh` | Frida hook the symbol under the cursor (a method — and every implementation for an interface call) |
| `<leader>jH` | Frida hook every method of this class |
| `<leader>jm` | Toggle a bookmark at the cursor |

In code buffers `y` / `Y` copy to your **computer's** clipboard — over SSH too, via OSC 52 (no
`xclip`/`win32yank` needed). Disable with `clipboard = false` in `setup()`, or it steps aside if
you've already configured a clipboard provider.

Decompiled buffers are set up for **folding** (`zc`/`za` to fold a block, `zM`/`zR` to close/open
all). By default (`folding = "auto"`) they use treesitter's fold expression when the Java parser is
active — folding `if`/`for`/method `{ }` blocks precisely — and fall back to indent folding
otherwise. Set `folding = "indent"`, `"expr"`, or `false` in `setup()` to override.

Go-to-definition and usages integrate with the jumplist and quickfix, so `<C-o>`/`<C-i>` and
`:cnext`/`:cprev` work as usual.

## Seamless jadx-gui interop

Opening an APK **creates a `.jadx` project next to it automatically** (use `--temp` / `temp = true`
to work purely in memory and never write a file). Renames and comments are stored in jadx's native
code-data format and written to that `.jadx` (same `projectVersion`/`files`/`codeData` shape and
GSON serialization jadx-gui uses). Open the same `.jadx` in jadx-gui and your renames/comments are
there; conversely, opening a project edited in jadx-gui shows its renames/comments in jadxnvim.

jadxnvim also **reads and writes jadx-gui's UI-state**, in jadx-gui's own format:

- **Open tabs** — your open class buffers are saved as the project's `openTabs` (as jadx-gui
  `TabViewState`s). Reopen the project — in jadxnvim or jadx-gui — and the same tabs come back.
- **Search history** — text searches, xrefs and name searches are recorded to the project's
  `searchHistory`; on open they're available again (re-runnable) in `<Space>fs`, and populate
  jadx-gui's search dropdown.
- **cacheDir** — the project points at jadxnvim's on-disk cache (`<name>.jadxnvim/`, a dedicated
  `gui-cache` subdir for jadx-gui so it never touches jadxnvim's index).
- **Bookmarks** — `<leader>jm` bookmarks the position under the cursor; `<Space>fb` lists/jumps.
  They're written as jadx-gui bookmarked tabs (`openTabs` with `bookmarked: true` + `caret`), so they
  show up in jadx-gui's *Bookmarked tabs* panel; jadxnvim also keeps a richer list (multiple per
  class, with a text snippet) in its own preserved field.

Any fields jadxnvim doesn't manage (tree state, plugin options, ...) are preserved, so round-tripping
a jadx-gui project never loses its state. The input APK is referenced relatively, so a project
directory stays portable. (Verified against jadx-gui's own project loader: a project jadxnvim writes
loads in jadx-gui with the rename applied and the tabs/search-history restored.)

## Status

All v1 milestones are implemented and tested against real APKs (incl. a 136 MB / 158k-class app):

- [x] Daemon: lazy load, package tree, on-demand class decompilation
- [x] Plugin: project tree, code view
- [x] Cross-references (go-to-definition, find-usages)
- [x] Search (class/method/field names, and ripgrep full-text over an on-load export — fast on
      400k-class APKs, cached, with results re-located onto the live decompiled line)
- [x] Search history: reopen (with results) or delete past text searches / xrefs / name searches,
      with per-entry result previews (`<Space>fs` / `:JadxHistory`)
- [x] Class / method finders stream matches server-side (query-driven), scanning the raw parsed
      model (`getClassNode().getMethods()`, like jadx-gui) so no class is decompiled during a name
      search — sub-second on a 400k-class APK with no OOM, even without the export. Once the on-load
      export is ready they ripgrep a class/method **name index** for an extra speedup.
- [x] Rename + comments persisted to the `.jadx` project (jadx-gui interop)
- [x] Built-in fuzzy finders for classes / methods / text (no external picker needed)
- [x] Java ⟷ Smali toggle (`<Tab>`) and a load progress bar (animated, or real % with `prefetch`)
- [x] Syntax highlighting for Java and Smali (applies a readable palette on a bare Neovim; a
      colorscheme you set is respected)
- [x] Resource browser: a Resources section in the tree (directory tree of the APK's resources),
      with decoded, syntax-highlighted viewing of `AndroidManifest.xml`, `res/**`, etc.
- [x] Tree type icons (Nerd Font, configurable) and a `/` tree filter

Roadmap toward broader jadx-gui parity: certificate info, bookmarks, deobfuscation toggle,
mappings import/export, and instruction-level comments.
