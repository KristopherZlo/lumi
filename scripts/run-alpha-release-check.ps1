param(
    [string]$JavaHome,
    [switch]$SkipRuntimeLoad,
    [ValidateRange(1, 20)]
    [int]$RuntimeLoadRuns = 1
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$testClientScript = Join-Path $PSScriptRoot "run-test-client.ps1"
$idleCompareScript = Join-Path $PSScriptRoot "compare-idle-startup-load.ps1"

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

Push-Location $repoRoot
try {
    Invoke-TestClientGradle -Tasks @("test", "verifyCoverageRatchet")
    Invoke-TestClientGradle -Tasks @("runGameTest")
    Invoke-TestClientGradle -Tasks @("runClientGameTest")

    if (-not $SkipRuntimeLoad) {
        & $idleCompareScript -Runs $RuntimeLoadRuns -FailOnRegression
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
} finally {
    Pop-Location
}
