param(
    [string]$Target = 'legacy-12111',
    [string]$GameVersion,
    [Parameter(Mandatory)] [string]$Artifact
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../MinecraftVerificationTarget.ps1')
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$taskTarget = [LumiMinecraftVerificationTarget]::new($taskCatalog, $Target, $GameVersion, $Artifact)
$taskBuild = Join-Path $taskRepo $(if ($taskTarget.Modern) { "versions/$Target/build" } else { 'build' })
Push-Location $taskRepo
try {
    $taskArguments = $taskTarget.PrepareArguments()
    $ErrorActionPreference = 'Continue'
    & (Join-Path $taskRepo 'gradlew.bat') @taskArguments --console=plain
    $taskExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($taskExit -ne 0) { throw 'Prepared client build failed.' }
    $taskInputs = Get-Content -LiteralPath (Join-Path $taskBuild 'multiplayer-launch.inputs.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $taskJvm = Get-Content -LiteralPath (Join-Path $taskBuild 'multiplayer-launch.args') -Encoding UTF8
    if ($taskInputs.launchKind -cne 'production-client' -or
        $taskInputs.target.id -cne $Target -or
        $taskInputs.target.minecraftVersion -cne $taskTarget.Definition.minecraftVersion -or
        $taskInputs.artifact.sha256 -cne (Get-FileHash -LiteralPath $taskTarget.Artifact -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'Prepared client does not identify the selected player JAR and game.'
    }
    if (@($taskJvm | Where-Object { $_ -like '*-XX:ErrorFile=*' }).Count -ne 1 -or
        $taskJvm -cnotcontains '"-XX:ErrorFile=hs_err_pid%p.log"' -or
        @($taskJvm | Where-Object { $_ -like '*-Dlumi.gametest.completion=*' }).Count -ne 0) {
        throw 'Prepared client retained a foreign run directory.'
    }
    if (@($taskInputs.classpath | Where-Object { -not [IO.File]::Exists($_) }).Count -ne 0 -or
        $taskInputs.mods -cnotcontains $taskTarget.Artifact) {
        throw 'Prepared production classpath or player mod is missing.'
    }
    Write-Output "Prepared production launch passed: $Target/$($taskTarget.Definition.minecraftVersion)"
} finally {
    Pop-Location
}
