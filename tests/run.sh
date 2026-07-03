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

# ripgrep (optional; the fixture is tiny so an in-memory scan works too)
if command -v rg >/dev/null 2>&1; then export JADXNVIM_RG="$(command -v rg)"; fi

# 3. run each spec in isolation
fail=0; total=0
for spec in "$REPO"/tests/spec/*_spec.lua; do
  [ -e "$spec" ] || continue
  name="$(basename "$spec")"
  rm -f "$WORK/fixture.jadx"; rm -rf "$WORK/fixture.jadxnvim"
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
