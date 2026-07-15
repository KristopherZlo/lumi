param(
    [string]$JavaHome
)

$ErrorActionPreference = 'Stop'
$testClientScript = Join-Path $PSScriptRoot 'run-test-client.ps1'
$arguments = @{
    GradleTasks = @('runClientGameTest', '-PlumiClientGameTestSuite=tools')
}
if ($JavaHome) {
    $arguments.JavaHome = $JavaHome
}

& $testClientScript @arguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host 'Builder-tools gate passed: real WorldEdit and Axiom save/restore journeys are exact.'
