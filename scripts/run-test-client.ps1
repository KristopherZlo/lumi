param(
    [string]$Username = "LumaTestClient"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

Push-Location $repoRoot
try {
    & .\gradlew.bat installTestClientMods runTestClient "-Pluma.testUsername=$Username"
} finally {
    Pop-Location
}
