# Launch the jadxnvim UI on a project from PowerShell:
#
#   .\scripts\jadxnvim.ps1 app.apk
#   .\scripts\jadxnvim.ps1 project.jadx
#
# Prepends this repo to Neovim's runtimepath for the session, so it works even without
# adding the plugin to your config. Your normal config is still loaded.
#
# Environment:
#   JADXNVIM_NVIM   nvim binary to use (default: nvim)
param(
  [Parameter(Mandatory = $true, Position = 0)][string]$Project,
  [Parameter(ValueFromRemainingArguments = $true)][string[]]$NvimArgs
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSScriptRoot          # repo root (scripts/ is one level down)
$jar = Join-Path $here "daemon\build\libs\jadxd.jar"
$nvim = if ($env:JADXNVIM_NVIM) { $env:JADXNVIM_NVIM } else { "nvim" }

if (-not (Test-Path $jar)) {
  Write-Host "jadxnvim: daemon not built yet, building (first run only)..."
  Push-Location (Join-Path $here "daemon")
  try { & .\gradlew.bat --console=plain shadowJar } finally { Pop-Location }
}
if (-not (Test-Path $Project)) { Write-Error "jadxnvim: file not found: $Project"; exit 1 }
$proj = (Resolve-Path $Project).Path

# Pull out --temp (work in memory, don't write a .jadx) from the passthrough args.
$temp = "false"
$rest = @()
foreach ($a in $NvimArgs) { if ($a -eq "--temp") { $temp = "true" } else { $rest += $a } }

& $nvim @rest `
  --cmd "lua vim.opt.runtimepath:prepend([[$here]])" `
  -c "lua require('jadxnvim').open([[$proj]], { temp = $temp })"
