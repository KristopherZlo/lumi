param(
    [string]$JavaHome,
    [switch]$SkipRuntimeLoad,
    [switch]$SkipCrashHarness,
    [ValidateRange(1, 20)]
    [int]$RuntimeLoadRuns = 1,
    [ValidateRange(60, 7200)]
    [int]$RuntimeLoadSampleTimeoutSeconds = 900,
    [ValidateRange(30, 7200)]
    [int]$CrashHarnessMarkerTimeoutSeconds = 240,
    [string[]]$CrashHarnessFailpoints = @()
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

        $arguments = @{
            GradleTasks = $Tasks
        }
        if ($JavaHome) {
            $arguments.JavaHome = $JavaHome
        }
        & $testClientScript @arguments
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
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
    Invoke-TestClientGradle -Tasks @("runHistoryJourneyClientGameTest")
    Invoke-TestClientGradle -Tasks @("runClientGameTest") -Mode "structure-fixtures"
    Invoke-TestClientGradle -Tasks @("runClientGameTest") -Mode "external-tools"
    Invoke-TestClientGradle -Tasks @("runClientGameTest") -Mode "crash-safety"

    if (-not $SkipRuntimeLoad) {
        $baseline = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-baseline-client.ps1"
        $lumi = "powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-test-client.ps1 -GradleTasks runClientGameTest"
        if ($JavaHome) {
            $quotedJavaHome = '"' + ($JavaHome -replace '"', '\"') + '"'
            $baseline += " -JavaHome $quotedJavaHome"
            $lumi += " -JavaHome $quotedJavaHome"
        }
        & $runtimeCompareScript `
            -BaselineCommand $baseline `
            -LumiCommand $lumi `
            -Runs $RuntimeLoadRuns `
            -SampleTimeoutSeconds $RuntimeLoadSampleTimeoutSeconds `
            -RequireBaselineActionRun `
            -RequireLumiActionRun `
            -FailOnRegression
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }

    if (-not $SkipCrashHarness) {
        $arguments = @{
            MarkerTimeoutSeconds = $CrashHarnessMarkerTimeoutSeconds
        }
        if ($JavaHome) {
            $arguments.JavaHome = $JavaHome
        }
        if ($CrashHarnessFailpoints.Count -gt 0) {
            $arguments.Failpoints = $CrashHarnessFailpoints
        }
        & $crashHarnessScript @arguments
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
} finally {
    Pop-Location
}
