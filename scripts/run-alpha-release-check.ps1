param(
    [string]$JavaHome,
    [switch]$SkipRuntimeLoad,
    [ValidateRange(3, 20)]
    [int]$RuntimeLoadRuns = 3
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$testClientScript = Join-Path $PSScriptRoot "run-test-client.ps1"
$idleCompareScript = Join-Path $PSScriptRoot "compare-idle-startup-load.ps1"
$builderToolsGateScript = Join-Path $PSScriptRoot "run-builder-tools-gate.ps1"
$historyLoadGateScript = Join-Path $PSScriptRoot "run-history-load-gate.ps1"

function Invoke-TestClientGradle {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Tasks
    )

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
}

function Invoke-ReleaseGateScript {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptPath
    )

    $arguments = @{}
    if ($JavaHome) {
        $arguments.JavaHome = $JavaHome
    }
    & $ScriptPath @arguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

Push-Location $repoRoot
try {
    Invoke-TestClientGradle -Tasks @("test", "verifyCoverageRatchet")
    Invoke-TestClientGradle -Tasks @("runGameTest")
    Invoke-TestClientGradle -Tasks @("runClientGameTest")
    Invoke-ReleaseGateScript -ScriptPath $builderToolsGateScript
    Invoke-ReleaseGateScript -ScriptPath $historyLoadGateScript

    if (-not $SkipRuntimeLoad) {
        & $idleCompareScript -Runs $RuntimeLoadRuns -FailOnRegression
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
        Write-Host "Alpha release gate passed. Crash-interruption testing still requires manual sign-off."
    } else {
        Write-Warning "Runtime load comparison was skipped; the release gate is incomplete."
    }
} finally {
    Pop-Location
}
