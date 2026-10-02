param(
    [Parameter(Mandatory = $true)] [string] $Python,
    [Parameter(Mandatory = $true)] [string] $CosyVoiceDir,
    [Parameter(Mandatory = $true)] [string] $ModelDir,
    [string] $Speaker = "中文女"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$lines = Join-Path $PSScriptRoot "fixed_voice_lines.txt"
$generator = Join-Path $PSScriptRoot "generate_fixed_voice_audio.py"
$output = Join-Path $root "app\src\main\res\raw"

& $Python $generator --cosyvoice-dir $CosyVoiceDir --model-dir $ModelDir --lines $lines --output-dir $output --speaker $Speaker
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$generated = @(Get-ChildItem $output -Filter "*.ogg")
if ($generated.Count -ne 166) { throw "Expected 166 Ogg files, got $($generated.Count)" }
Write-Host "Generated and verified $($generated.Count) fixed voice files."
