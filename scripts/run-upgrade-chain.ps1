param(
    [switch]$RunMinecraft,
    [Parameter(Mandatory)][string]$CandidateDirectory,
    [Parameter(Mandatory)][string]$SourceDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
if (-not $RunMinecraft) { throw 'Pass -RunMinecraft to open sequential upgrade clients.' }
. (Join-Path $PSScriptRoot 'MinecraftVerificationTarget.ps1')
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskCandidateDirectory = (Resolve-Path -LiteralPath $CandidateDirectory).Path
$taskSources = (Resolve-Path -LiteralPath $SourceDirectory).Path
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskOutput) { throw "Upgrade chain output must be unused: $taskOutput" }
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskCandidate = Get-Content -LiteralPath (Join-Path $taskCandidateDirectory 'candidate.json') -Raw | ConvertFrom-Json
$taskGames = @($taskCatalog.targets.minecraftVersions | Sort-Object { [version]$_ })
if ($taskGames.Count -lt 2) { throw 'An upgrade chain requires at least two games.' }
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskChain = [ordered]@{ schema = 1; status = 'running'; candidate = $taskCandidate; games = $taskGames; steps = @() }
$taskChain | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $taskOutput 'chain.json') -Encoding UTF8
Push-Location $taskRepo
try {
    for ($taskIndex = 1; $taskIndex -lt $taskGames.Count; $taskIndex++) {
        $taskSourceGame = $taskGames[$taskIndex - 1]
        $taskGame = $taskGames[$taskIndex]
        $taskStep = Join-Path $taskOutput ("step-$taskIndex")
        & (Join-Path $PSScriptRoot 'run-upgrade-matrix.ps1') -RunMinecraft -CandidateDirectory $taskCandidateDirectory `
            -SourceDirectory $taskSources -OutputDirectory $taskStep -SourceGame $taskSourceGame -TargetGame $taskGame
        $taskChain.steps += Join-Path $taskStep 'matrix.json'
        $taskChain | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $taskOutput 'chain.json') -Encoding UTF8
        if ($taskIndex -eq $taskGames.Count - 1) { continue }
        # The next hop starts from this hop's closed, restored world, retaining every older generation.
        $taskPrevious = Join-Path $taskStep ($taskSourceGame + '-to-' + $taskGame)
        $taskSources = Join-Path $taskOutput ("sources-$taskIndex")
        $taskFixture = Join-Path $taskSources $taskGame
        New-Item -ItemType Directory -Path $taskFixture | Out-Null
        Copy-Item -LiteralPath (Join-Path $taskPrevious 'restored-target') -Destination (Join-Path $taskFixture 'source-a') -Recurse
        Copy-Item -LiteralPath (Join-Path $taskPrevious 'fixture.properties') -Destination $taskFixture
        $taskGroup = @($taskCandidate.groups | Where-Object { $taskGame -cin $_.target.minecraftVersions })
        if ($taskGroup.Count -ne 1) { throw "Wrong chain group: $taskGame" }
        $taskProfile = [LumiMinecraftVerificationTarget]::new($taskCatalog, $taskGroup[0].target.id, $taskGame,
            (Join-Path $taskCandidateDirectory $taskGroup[0].artifact.name))
        $taskArguments = $taskProfile.TaskArguments(@('runPackagedClientGameTest')) + @(
            '-Dlumi.gametest.suite=UPGRADE', '-Dlumi.gametest.upgrade.stage=edit',
            "-Dlumi.gametest.upgrade.fixture=$taskFixture", '--console=plain')
        $ErrorActionPreference = 'Continue'
        & (Join-Path $taskRepo 'gradlew.bat') @taskArguments *> (Join-Path $taskFixture 'edit.log')
        $taskExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($taskExit -ne 0) { throw "Cannot prepare the next chain hop; see $taskFixture/edit.log" }
    }
    $taskChain.status = 'passed'
} catch {
    $taskChain.status = 'failed'
    $taskChain.failure = $_.Exception.Message
    throw
} finally {
    $taskChain | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $taskOutput 'chain.json') -Encoding UTF8
    Pop-Location
}
