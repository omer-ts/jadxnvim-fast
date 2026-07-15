# jadxnvim

A Neovim front-end for [jadx](https://github.com/skylot/jadx) — browse and analyze decompiled
Android APKs from the terminal, over SSH, on huge apps, without leaving the editor.

The default **fast engine** opens and browses a **700 MB APK in seconds at a few hundred MB of RAM**:
it indexes the dex tables directly (no decompilation) into a memory-mapped database and decompiles a
class with jadx only when you actually open it. jadx's classic whole-APK parse (minutes, many GB) is
never on the hot path.

jadxnvim does **not** replace jadx-gui. It reads/writes the same project format, so a project opened
in jadxnvim can be opened in jadx-gui (and vice versa) with renames and comments intact.

## How it works

```
  Neovim plugin (Lua)  <--- newline JSON-RPC over stdio --->  jadxd daemon (JVM)
```

Both run on the same (powerful) machine you SSH into; communication is local stdio, so there is no
network protocol or auth to configure. The daemon has **two engines**:

### Fast engine (default, for `.apk` / `.dex`)

The insight: jadx has no lazy class loading — the moment you want *any* Java, it parses the whole APK.
So the fast engine stops using jadx for navigation and uses it only to render the one class you're
looking at:

- **Index — dexlib2 → SQLite.** On open, the dex tables (classes, methods, fields, string constants,
  a bytecode cross-reference graph, the class hierarchy) are read directly with dexlib2 — *memory-
  mapped and lazy, no decompilation* — and written to a memory-mapped SQLite database
  (`<name>.jadxnvim/index.db`). Everything you do to *navigate* — browse the tree, name/content
  search, go-to-definition, find-usages — is served from that, instantly, independent of jadx. Built
  once, cached, rebuilt only if the APK changes.
- **Render — jadx-core, on demand, mini-dex isolated.** When (and only when) you open a class, the
  engine extracts *that class plus a bounded 1-hop reference closure* into a tiny synthetic dex and
  hands it to jadx. Decompiling one class costs what that class costs — MB-scale RAM, a fraction of a
  second — never the whole-APK `load()`.

**Advanced ops that need jadx's whole-program model** — rename, comment, resource *content*, smali —
fall through to the classic engine, which builds the model lazily the first time you use one of them
(a one-time cost you pay only if you reach for those). Browse / view / search / gd / gr never do.

### Classic engine (`.jar` / `.class`, or `fast = false`)

Embeds `jadx-core`, parses the whole APK once, decompiles on demand, and exports the sources into a
ripgrep-friendly index for fast full-text search. This is what `.jar`/`.class` inputs use (dexlib2
reads dex only), and what you get with `fast = false`. See [Classic engine](#classic-engine-details).

## Benchmarks (fast engine)

Measured on **Instagram 136 MB / 165,819 classes**, `-Xmx512m` (it fits in half a gig):

| Operation | Cost |
| --- | --- |
| Build the index (dexlib2 → SQLite, one time, cached) | ~25–30 s |
| Open + browse + name/content search | instant, memory-mapped, ~hundreds of MB |
| View a class (`getCode`, on-demand render) | ~150–300 ms; first render pays a one-time ~1.5 s class-map build |
| Go-to-definition | instant (SQLite) + one render for the target's declaration |
| Find-usages (`gr`) | precise & bounded — typically sub-second; a 3,831-implementor interface method resolves in ~1.2 s |
| Go-to-implementations (`gd` on an interface method, 40 impls) | ~180 ms (pure SQLite, no render) |

The on-disk index is disposable/rebuildable and lives in the gitignored `<name>.jadxnvim/` cache
(~1 GB for Instagram, ~90 MB for a 10 MB app — mostly the cross-reference graph and FTS).

## Requirements

- **Java 17+** (the daemon runs on the JVM). Tested on JDK 23.
- **Neovim 0.10+**
- No system Gradle needed — the repo ships a Gradle wrapper.
- [ripgrep](https://github.com/BurntSushi/ripgrep) (`rg`) — **only** for the classic engine's
  full-text search; the fast engine doesn't need it.
- RAM: the fast engine is happy in a few hundred MB even on 700 MB APKs. jadxnvim sizes the heap to
  70 % of available memory (`-XX:MaxRAMPercentage`, so it respects container/cgroup limits); override
  with `java_args = { "-Xmx4g" }` if you want a hard cap.

## Build the daemon

```sh
cd daemon
./gradlew shadowJar         # produces daemon/build/libs/jadxd.jar   (Windows: ./gradlew.bat)
```

## Install the plugin (optional)

You only need this if you want the `:Jadx` commands in your normal Neovim sessions — the
`scripts/jadxnvim` CLI launcher below works without it. With lazy.nvim:

```lua
{
  dir = "/path/to/jadxnvim",
  config = function()
    require("jadxnvim").setup({
      -- fast = true,               -- fast engine for .apk/.dex (default). false = classic engine.
      -- jar defaults to <repo>/daemon/build/libs/jadxd.jar
      -- java = "java",
      -- java_args = { "-Xmx4g" },  -- override the auto heap size
    })
  end,
}
```

## Launch from the shell (CLI)

```sh
# Linux / macOS / SSH server:
scripts/jadxnvim app.apk
scripts/jadxnvim project.jadx
scripts/jadxnvim --temp app.apk    # work in memory, don't write a .jadx

# Windows (PowerShell):
scripts\jadxnvim.ps1 app.apk
```

Symlink it onto your `PATH` for a bare `jadxnvim app.apk`:

```sh
ln -s "$PWD/scripts/jadxnvim" ~/.local/bin/jadxnvim   # (chmod +x scripts/jadxnvim once)
```

The launcher prepends this repo to Neovim's `runtimepath` for the session (so your normal config still
loads), opens the project automatically, and builds the daemon jar on first run if needed. Set
`JADXD_JAVA_OPTS=-Xmx4g` for very large APKs, or `JADXNVIM_NVIM` to choose the nvim binary.

## Usage (inside Neovim)

| Command                | Action                                                          |
| ---------------------- | --------------------------------------------------------------- |
| `:Jadx <path>`         | Open an APK / dex / jar / `.jadx` project and show the tree     |
| `:JadxHelp`            | Command palette: list every command & shortcut, run the selected one |
| `:JadxTree`            | Focus the project tree                                          |
| `:JadxGotoPackage {p}` | Jump the tree to a package (Tab-completes package names)         |
| `:JadxDef`             | Go to definition of the symbol under the cursor                 |
| `:JadxUsages`          | Find usages of the symbol under the cursor (xref, browsable + preview) |
| `:JadxCallers`         | Incoming call hierarchy for the method under the cursor (expandable tree) |
| `:JadxTypeHierarchy`   | Super/subtype hierarchy for the class under the cursor          |
| `:JadxSearch [text]`   | Full-text search across code (streamed → quickfix)              |
| `:JadxSearchName [q]`  | Search class/method/field names                                 |
| `:JadxFindClass`       | Fuzzy-find a class                                              |
| `:JadxFindMethod`      | Fuzzy-find a method (jumps to its declaration)                  |
| `:JadxFindText`        | Full-text search, then fuzzy-narrow the results                |
| `:JadxRename`          | Rename the symbol under the cursor (persists to `.jadx`)        |
| `:JadxComment`         | Comment the symbol under the cursor (persists to `.jadx`)       |
| `:JadxBookmark` / `:JadxBookmarks` | Toggle / list bookmarks                             |
| `:JadxFridaHook` / `:JadxFridaHookClass` | Generate a Frida hook for the symbol / whole class |
| `:JadxClose`           | Close the project and stop the daemon                           |

### In a code buffer (`jadx://<class>`, read-only Java)

| Key          | Action                         |
| ------------ | ------------------------------ |
| `gd`         | Go to definition. On an abstract/interface method it lists **all implementations** to pick from |
| `gr`         | Find usages (xref) — browsable list with preview; includes **virtual-dispatch calls** made through a super/interface/subtype |
| `<leader>jk` | **Call hierarchy** — incoming callers of the method, as an expandable tree (see below) |
| `<leader>ji` | **Type hierarchy** — super/subtype tree of the class (see below) |
| `<leader>jt` | **Resolve a merged-lambda dispatcher call** to the branch it runs (see below) |
| `<Tab>`      | Toggle Java ⟷ Smali (syncs to the same method) |
| `<leader>jr` | Rename (classes, methods, fields, local variables) |
| `<leader>jc` | Comment                        |
| `<leader>jh` / `<leader>jH` | Frida hook the symbol / every method of the class |
| `<leader>jm` | Toggle a bookmark               |

`y` / `Y` copy to your **computer's** clipboard — over SSH too, via OSC 52. gd/gr integrate with the
jumplist and quickfix (`<C-o>`/`<C-i>`, `:cnext`/`:cprev`).

### Fuzzy finders

A self-contained fuzzy picker (Neovim's `matchfuzzy` — **no Telescope/fzf-lua needed**), bound to
global keys when a project is open:

| Key | Finds |
| --- | --- |
| `<Space>ff` | text — stream results, then fuzzy them |
| `<Space>fc` | classes |
| `<Space>fd` | methods (jumps to the declaration) |
| `<Space>fv` | everything — classes + methods + text (jadx-gui-style) |
| `<Space>fs` / `<Space>fb` / `<Space>fh` | search history / bookmarks / command palette |

In the tree, `/` filters (fast engine: the filter searches **all** classes server-side, so a match in
a collapsed package still shows). Tree rows carry Nerd-Font type icons (configurable via `icons`).

## Fast-engine navigation

Everything below is served from the SQLite index + on-demand render — no whole-APK model.

- **Class search** matches the **original (raw) name**, a **qualified/partial path** (`X.001`,
  `auth.LoginActivity`), **and the jadx-rendered name**. jadx renames classes whose raw name isn't a
  valid Java identifier — `X.000` renders as `AnonymousClass000`, `X.0Ac` as `C0Ac` (23 % of classes
  in an obfuscated APK) — so the tree and results show both, e.g. `000  → AnonymousClass000`, and
  either name finds the class.

- **Find-usages (`gr`)** returns the actual call sites — exact line and the code line as text, not
  just "used in class X". For a method it searches the whole **override group** (calls made through a
  super/interface/subtype), so virtual-dispatch usages aren't missed: an interface method called only
  through its implementations goes from *0* usages (naïve exact-key search) to all of them. On a hot
  method it renders the first ~30 referencing classes for exact lines and lists the rest class-
  granular (open one to relocate by name) — complete **and** bounded (~1 s even for thousands of
  usages). Multiple calls in one class each resolve to their own distinct call site.

- **Go-to-implementations.** `gd` on an abstract/interface method (or a call to one) offers every
  class that declares/overrides it — pick an implementation to jump to. Instant (pure SQLite).

- **Call hierarchy (`<leader>jk`).** "Who calls this method", as an expandable tree in a bottom
  split. Each caller is resolved to the enclosing **caller method** (with the exact call site), and
  every caller can be expanded to *its own* callers, recursively — walk the call graph inward without
  leaving the editor. Callers are found over the method's whole override group, so virtual-dispatch
  calls made through a super/interface/subtype are included. `<CR>`/`o` jumps to a call site; `<Tab>`
  expands a node. Complete but bounded (renders the first ~30 referencing classes for precise caller
  methods, lists the rest to open on demand).

- **Type hierarchy (`<leader>ji`).** The super/subtype tree of the class (or the type under the
  cursor): supertypes it extends/implements above, subtypes that extend/implement it below, both
  transitive. Interfaces and classes are marked; framework/library types show as `(external)`. Served
  entirely from the SQLite class hierarchy (no rendering). `<CR>` opens a type.

- **Content search** covers dex **string constants** across *all* classes with no decompilation
  (instant), plus the decompiled Java of classes you've viewed (a source FTS filled lazily).

### Merged-lambda dispatcher resolver (`<leader>jt`)

Optimizers (R8, Meta's **Redex** — which Instagram uses) merge hundreds of lambdas/callbacks into one
class dispatched by an integer id, rendered as `switch (this.$t)`. So `new X.Uez(.., 5)` (or a factory
`X.Uez.A00(.., 5)`) really means *"run case 5 of X.Uez"* — and on Instagram `X.000.A00()` has a
**3117-case** switch. Put the cursor on the dispatcher class in the construction and `<leader>jt`
jumps straight to `case N:` in its dispatch switch. The id may be a **literal**, or a **local variable
assigned a constant** (resolved by a light constant-dataflow trace).

The CLI can list these directly: `jadxd dispatchers <apk> [minCases]` scans the bytecode (dexlib2, no
decompilation) for classes whose method holds a large packed/sparse switch.

## CLI (`jadxd`)

The daemon jar doubles as a standalone CLI over the fast index (no Neovim needed):

```sh
java -jar daemon/build/libs/jadxd.jar index      app.apk                 # build the SQLite index
java -jar daemon/build/libs/jadxd.jar search     app.jadxnvim/index.db  LoginActivity
java -jar daemon/build/libs/jadxd.jar xref       app.jadxnvim/index.db  "Ljava/lang/String;"
java -jar daemon/build/libs/jadxd.jar decompile  app.apk  com.example.Foo
java -jar daemon/build/libs/jadxd.jar dispatchers app.apk 20
java -jar daemon/build/libs/jadxd.jar stats      app.jadxnvim/index.db
```

## Seamless jadx-gui interop

Opening an APK **creates a `.jadx` project next to it automatically** (use `--temp` / `temp = true` to
stay in memory). Renames and comments are stored in jadx's native code-data format and written to that
`.jadx` (same `projectVersion`/`files`/`codeData` shape and GSON serialization jadx-gui uses), so the
same project opens in jadx-gui with your edits intact, and vice versa. jadxnvim also reads/writes
jadx-gui's UI state (open tabs, search history, `cacheDir`, bookmarks) and preserves fields it doesn't
manage, so round-tripping never loses state. (In the fast engine, the first rename/comment builds the
jadx model on demand; browsing and search don't.)

## Classic engine details

With `fast = false` (or for `.jar`/`.class` inputs), the daemon embeds `jadx-core`, parses the whole
APK once, decompiles on demand, and exports the sources into a ripgrep index for full-text search
(cached). This is the heavier, higher-fidelity path; its knobs (`export`, `usage`, `lean`,
`keep_model`, `-Djadxnvim.indexThreads`) trade RAM for features on very large APKs. Rough idle memory
floor on a 540 MB / 396k-class APK: `usage = true` ≈ 6.9 GB (full jadx-gui-quality output + precise
`gr`), `usage = false` ≈ 4.6 GB (skips jadx's ~2.3 GB xref graph), `lean = true` ≈ 0.5–1.5 GB
(browse/search/nav served from the on-disk export), `lean = true` + `keep_model = false` ≈ 120 MB
(drops the model after export; slow first edit).

## Tests

```sh
bash tests/run.sh        # builds the daemon + a fixture jar, runs every spec in headless Neovim
```

Each `tests/spec/*_spec.lua` opens a fixture in a fresh headless Neovim and asserts behaviour. The
`.jar` fixture exercises the classic engine + the shared frontend; the **fast-engine specs**
(`v2_fast`, `call_hierarchy`, `type_hierarchy`) run against a `.dex` built from the same sources with
Android's `d8` — they self-skip when `d8` isn't found (set `$D8` or `$ANDROID_HOME`). Requires
`nvim`, a JDK (for `javac`/`jar`), and `rg` on `PATH`; `d8` (Android build-tools) for the fast-engine
specs.

## Status

The fast engine (default) is implemented and validated end-to-end against real APKs up to 700 MB
(Instagram 136 MB / 165k classes throughout):

- [x] dexlib2 → SQLite index; instant open + browse + name/content search at low RAM
- [x] On-demand mini-dex class rendering (no whole-APK model)
- [x] Class search over raw names, qualified paths, and jadx-rendered names
- [x] Go-to-definition, go-to-implementations, and precise find-usages with override groups
- [x] Call hierarchy (expandable incoming callers) and type hierarchy (super/subtype tree)
- [x] Merged-lambda dispatcher resolver (`<leader>jt`) + `jadxd dispatchers` scanner
- [x] Rename / comment / resources / smali via the classic model, built lazily on demand
- [x] Full jadx-gui project interop (renames, comments, tabs, search history, bookmarks)
- [x] Classic engine retained for `.jar`/`.class` and `fast = false`
