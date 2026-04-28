param(
    [string]$Username = "LumiIdleClient",
    [string]$JavaHome,
    [switch]$StartupProfile
)

$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "run-test-client.ps1"
$gradleTasks = @("runIdleClientGameTest")
if ($StartupProfile) {
    $gradleTasks += "-Plumi.startupProfile=true"
}

& $script `
    -Username $Username `
    -JavaHome $JavaHome `
    -GradleTasks $gradleTasks

if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
