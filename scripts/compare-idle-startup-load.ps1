param(
    [ValidateRange(1, 20)]
    [int]$Runs = 1,

    [int]$KeepUpRegressionMs = 250,

    [switch]$FailOnRegression,

    [switch]$StartupProfile,

    [string]$OutputRoot
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$compareScript = Join-Path $PSScriptRoot "compare-runtime-load.ps1"

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot "build\runtime-load-idle"
}

$lumiCommand = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-idle-client.ps1"
if ($StartupProfile) {
    $lumiCommand += " -StartupProfile"
}

& $compareScript `
    -BaselineCommand "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-baseline-idle-client.ps1" `
    -LumiCommand $lumiCommand `
    -Runs $Runs `
    -KeepUpRegressionMs $KeepUpRegressionMs `
    -OutputRoot $OutputRoot `
    -BaselineExtraLogs @("build\run\baselineIdleClientGameTest\logs\latest.log") `
    -LumiExtraLogs @("build\run\idleClientGameTest\logs\latest.log") `
    -RequireBaselineActionRun `
    -RequireLumiActionRun `
    -FailOnRegression:$FailOnRegression

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
