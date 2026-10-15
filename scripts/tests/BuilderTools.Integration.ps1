param(
    [switch]$RunMinecraft,
    [string]$Target = 'modern-263',
    [string]$GameVersion,
    [string]$Artifact,
    [ValidateSet('none', 'worldedit', 'axiom', 'both')]
    [string[]]$Tools = @('none', 'worldedit', 'axiom', 'both'),
    [ValidateSet('SMOKE', 'BUILDER_TOOLS')][string]$Suite = 'SMOKE',
    [switch]$Dedicated
)
$ErrorActionPreference = 'Stop'
if (-not $RunMinecraft) { throw 'Pass -RunMinecraft to check real builder-tool combinations.' }
if ($Suite -eq 'BUILDER_TOOLS' -and 'none' -in $Tools) { throw 'Builder-tool editing requires a tool; omit none.' }
. (Join-Path $PSScriptRoot '../MinecraftVerificationTarget.ps1')
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskProfile = [LumiMinecraftVerificationTarget]::new($taskCatalog, $Target, $GameVersion, $Artifact)
$taskOutput = Join-Path $taskRepo ('build/reports/lumi-builder-tools/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
Push-Location $taskRepo
try {
    foreach ($taskMode in $Tools) {
        $taskTasks = @()
        if ($Dedicated) { $taskTasks += 'runPackagedGameTest' }
        $taskTasks += 'runPackagedClientGameTest'
        $taskArguments = $taskProfile.TaskArguments($taskTasks) + @("-PlumiBuilderTools=$taskMode",
            "-Dlumi.gametest.suite=$Suite", '--console=plain', '--no-parallel')
        $ErrorActionPreference = 'Continue'
        & (Join-Path $taskRepo 'gradlew.bat') @taskArguments *> (Join-Path $taskOutput ($taskMode + '.log'))
        $taskExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($taskExit -ne 0) { throw "Builder tools failed: $taskMode; see $taskOutput" }
        Write-Output "Builder tools $taskMode passed: $taskOutput"
    }
} finally {
    Pop-Location
}
