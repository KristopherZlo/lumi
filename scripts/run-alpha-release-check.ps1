param(
    [string]$JavaHome,
    [switch]$SkipRuntimeLoad,
    [switch]$SkipCrashHarness,
    [switch]$FullStack,
    [ValidateRange(1, 20)]
    [int]$RuntimeLoadRuns = 1
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$testClientScript = Join-Path $PSScriptRoot "run-test-client.ps1"
$runtimeCompareScript = Join-Path $PSScriptRoot "compare-runtime-load.ps1"
$crashHarnessScript = Join-Path $PSScriptRoot "run-crash-safety-check.ps1"

function Invoke-TestClientGradle {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Tasks,
        [string]$Mode
    )

    $previousMode = $env:LUMI_SINGLEPLAYER_TEST_MODE
    try {
        if ([string]::IsNullOrWhiteSpace($Mode)) {
            Remove-Item Env:\LUMI_SINGLEPLAYER_TEST_MODE -ErrorAction SilentlyContinue
        } else {
            $env:LUMI_SINGLEPLAYER_TEST_MODE = $Mode
        }

        $arguments = @("-GradleTasks")
        $arguments += $Tasks
        if ($JavaHome) {
            $arguments += @("-JavaHome", $JavaHome)
        }
        if ($FullStack) {
            $arguments += "-FullStack"
        }
        & $testClientScript @arguments
    } finally {
        if ($null -eq $previousMode) {
            Remove-Item Env:\LUMI_SINGLEPLAYER_TEST_MODE -ErrorAction SilentlyContinue
        } else {
            $env:LUMI_SINGLEPLAYER_TEST_MODE = $previousMode
        }
    }
}

Push-Location $repoRoot
try {
    Invoke-TestClientGradle -Tasks @("test", "verifyCoverageRatchet")
    Invoke-TestClientGradle -Tasks @("runGameTest")
    Invoke-TestClientGradle -Tasks @("runClientGameTest")
    Invoke-TestClientGradle -Tasks @("runClientGameTest") -Mode "structure-fixtures"
    Invoke-TestClientGradle -Tasks @("runClientGameTest") -Mode "external-tools"
    Invoke-TestClientGradle -Tasks @("runClientGameTest") -Mode "crash-safety"

    if (-not $SkipRuntimeLoad) {
        $baseline = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-baseline-client.ps1"
        $lumi = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-test-client.ps1 -GradleTasks runClientGameTest"
        & $runtimeCompareScript `
            -BaselineCommand $baseline `
            -LumiCommand $lumi `
            -Runs $RuntimeLoadRuns `
            -RequireBaselineActionRun `
            -RequireLumiActionRun `
            -FailOnRegression
    }

    if (-not $SkipCrashHarness) {
        $arguments = @()
        if ($JavaHome) {
            $arguments += @("-JavaHome", $JavaHome)
        }
        & $crashHarnessScript @arguments
    }
} finally {
    Pop-Location
}
