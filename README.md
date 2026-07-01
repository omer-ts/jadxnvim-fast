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

The daemon embeds `jadx-core` as a library and decompiles **lazily**: it loads the APK once and
decompiles a class only when its code is requested. This is what makes huge APKs usable — e.g.
Instagram (136 MB, ~158k classes) loads in ~50 s once, then any class decompiles in well under a
second.

Both the daemon and Neovim are meant to run on the same (powerful) machine you SSH into;
communication is local stdio, so there is no network protocol or auth to configure.

## Requirements

- Java 17+ (the daemon is built/run on the JVM)
- Neovim 0.10+
- No system Gradle needed — the repo ships a Gradle wrapper

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
      -- java_args = { "-Xmx6g" },  -- for very large APKs
      -- prefetch = true,           -- real 0-100% load bar (warms full decompilation)
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
| `:JadxUsages`          | Find usages of the symbol under the cursor (→ quickfix)         |
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

| Key         | Finds                                                       |
| ----------- | ----------------------------------------------------------- |
| `<Space>ff` | text — live full-text search; results stream in as you type |
| `<Space>fc` | classes                                                     |
| `<Space>fd` | methods (jumps to the declaration)                          |

In the picker: type to filter, `<C-n>`/`<C-p>` or `<Up>`/`<Down>` to move, `<CR>` to open,
`<Esc>` to cancel. Bound to literal `<Space>` so it works regardless of your `mapleader`. Rebind
or disable via `keys` in `setup()`:

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
| `gr`         | Find usages                    |
| `<Tab>`      | Toggle Java ⟷ Smali view        |
| `<leader>jr` | Rename                         |
| `<leader>jc` | Comment                        |

Go-to-definition and usages integrate with the jumplist and quickfix, so `<C-o>`/`<C-i>` and
`:cnext`/`:cprev` work as usual.

## Seamless jadx-gui interop

Renames and comments are stored in jadx's native code-data format and written to the `.jadx`
project file (same `projectVersion`/`files`/`codeData` shape and GSON serialization jadx-gui uses).
Open the same `.jadx` in jadx-gui and your renames/comments are there; conversely, opening a
project edited in jadx-gui shows its renames/comments in jadxnvim. The decompiled input APK is
referenced relatively, so a project directory stays portable.

## Status

All v1 milestones are implemented and tested against real APKs (incl. a 136 MB / 158k-class app):

- [x] Daemon: lazy load, package tree, on-demand class decompilation
- [x] Plugin: project tree, code view
- [x] Cross-references (go-to-definition, find-usages)
- [x] Search (class/method/field names, streamed full-text, cancellable)
- [x] Rename + comments persisted to the `.jadx` project (jadx-gui interop)
- [x] Built-in fuzzy finders for classes / methods / text (no external picker needed)
- [x] Java ⟷ Smali toggle (`<Tab>`) and a load progress bar (animated, or real % with `prefetch`)

Roadmap toward broader jadx-gui parity: resource/`AndroidManifest` viewer, certificate info, smali
view, bookmarks, deobfuscation toggle, mappings import/export, and instruction-level comments.
