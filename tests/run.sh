#!/usr/bin/env bash
# Build the fixture (and the daemon jar if needed), then run every spec in a fresh headless Neovim.
# Exit non-zero if any spec fails. Works on Linux CI and local Git Bash on Windows.
set -uo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
export JADXNVIM_REPO="$REPO"
WORK="$REPO/tests/.work"
mkdir -p "$WORK"
JAR="${JADXNVIM_JAR:-$REPO/daemon/build/libs/jadxd.jar}"
export JADXNVIM_JAR="$JAR"
NVIM="${JADXNVIM_NVIM:-nvim}"
JAVAC="${JAVAC:-javac}"
JARBIN="${JARBIN:-jar}"

# 1. daemon jar
if [ ! -f "$JAR" ]; then
  echo ">> building daemon jar…"
  ( cd "$REPO/daemon" && chmod +x ./gradlew 2>/dev/null; ./gradlew --no-daemon --console=plain shadowJar ) || {
    echo "!! daemon build failed"; exit 1; }
fi

# 2. fixture jar (recompile if any source is newer)
FIX="$WORK/fixture.jar"
SRC_DIR="$REPO/tests/fixtures/src"
if [ ! -f "$FIX" ] || [ -n "$(find "$SRC_DIR" -name '*.java' -newer "$FIX" 2>/dev/null)" ]; then
  echo ">> compiling fixture jar…"
  rm -rf "$WORK/classes"; mkdir -p "$WORK/classes"
  # shellcheck disable=SC2046
  "$JAVAC" -d "$WORK/classes" $(find "$SRC_DIR" -name '*.java') || { echo "!! javac failed"; exit 1; }
  ( cd "$WORK/classes" && "$JARBIN" cf "$FIX" . ) || { echo "!! jar failed"; exit 1; }
fi
export JADX_TEST_JAR="$FIX"

# 2b. dex fixture (for the v2/fast engine, which reads .dex/.apk only). Built from the same sources
# via Android's d8. If d8 isn't available, the fast-engine specs skip themselves (JADX_TEST_DEX unset).
find_d8() {
  if [ -n "${D8:-}" ] && [ -x "${D8}" ]; then echo "$D8"; return; fi
  for c in d8 d8.bat; do
    if command -v "$c" >/dev/null 2>&1; then command -v "$c"; return; fi
  done
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
      "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/AppData/Local/Android/Sdk"; do
    [ -n "$sdk" ] || continue
    local hit
    hit="$(ls "$sdk"/build-tools/*/d8 "$sdk"/build-tools/*/d8.bat 2>/dev/null | sort -V | tail -1)"
    if [ -n "$hit" ]; then echo "$hit"; return; fi
  done
}
D8BIN="$(find_d8 || true)"
if [ -n "$D8BIN" ]; then
  DEX="$WORK/fixturedex.dex"
  if [ ! -f "$DEX" ] || [ -n "$(find "$SRC_DIR" -name '*.java' -newer "$DEX" 2>/dev/null)" ]; then
    echo ">> building dex fixture (d8)…"
    # d8 reads Java 8 bytecode reliably, so compile a dedicated --release 8 tree for it.
    rm -rf "$WORK/classes8" "$WORK/dexout"; mkdir -p "$WORK/classes8" "$WORK/dexout"
    "$JAVAC" --release 8 -d "$WORK/classes8" $(find "$SRC_DIR" -name '*.java') 2>/dev/null \
      || { echo "!! javac --release 8 failed"; exit 1; }
    "$D8BIN" --min-api 21 --output "$WORK/dexout" $(find "$WORK/classes8" -name '*.class') \
      || { echo "!! d8 failed"; exit 1; }
    mv -f "$WORK/dexout/classes.dex" "$DEX"
  fi
  export JADX_TEST_DEX="$DEX"
  echo ">> dex fixture: $DEX"
else
  echo ">> d8 not found (set \$D8 or \$ANDROID_HOME) — fast-engine specs will skip"
fi

# ripgrep (optional; the fixture is tiny so an in-memory scan works too)
if command -v rg >/dev/null 2>&1; then export JADXNVIM_RG="$(command -v rg)"; fi

# 3. run each spec in isolation
fail=0; total=0
for spec in "$REPO"/tests/spec/*_spec.lua; do
  [ -e "$spec" ] || continue
  name="$(basename "$spec")"
  rm -f "$WORK/fixture.jadx" "$WORK/fixturedex.jadx"
  rm -rf "$WORK/fixture.jadxnvim" "$WORK/fixturedex.jadxnvim"
  echo ""
  echo "── $name ─────────────────────────────"
  "$NVIM" --headless -u NONE -l "$spec"
  code=$?
  total=$((total + 1))
  if [ $code -ne 0 ]; then fail=$((fail + 1)); echo "   [FAILED] $name (exit $code)"; fi
done

echo ""
echo "════════════════════════════════════════"
echo "$((total - fail))/$total specs passed"
[ $fail -eq 0 ] || exit 1
