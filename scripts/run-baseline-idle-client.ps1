param(
    [string]$Username = "LumiBaselineIdleClient",
    [string]$JavaHome
)

$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "run-test-client.ps1"

& $script `
    -Username $Username `
    -JavaHome $JavaHome `
    -GradleTasks runBaselineIdleClientGameTest

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
