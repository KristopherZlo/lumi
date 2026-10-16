param(
    [switch]$RunMinecraft,
    [Parameter(Mandatory)][string]$CandidateDirectory,
    [Parameter(Mandatory)][string]$SourceDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string[]]$TargetGame = @(),
    [string[]]$SourceGame = @()
)
$ErrorActionPreference = 'Stop'
if (-not $RunMinecraft) { throw 'Pass -RunMinecraft to open the stopped-world upgrade and vanilla-reopen clients.' }
. (Join-Path $PSScriptRoot 'MinecraftVerificationTarget.ps1')
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskCandidateDirectory = (Resolve-Path -LiteralPath $CandidateDirectory).Path
$taskSources = (Resolve-Path -LiteralPath $SourceDirectory).Path
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskOutput) { throw "Upgrade output must be unused: $taskOutput" }
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskCandidate = Get-Content -LiteralPath (Join-Path $taskCandidateDirectory 'candidate.json') -Raw | ConvertFrom-Json
if ($taskCandidate.schema -ne 1 -or $taskCandidate.groups.Count -ne $taskCatalog.targets.Count) { throw 'Wrong candidate manifest.' }
$taskGames = @($taskCatalog.targets | Where-Object { $_.javaVersion -gt 21 } |
    ForEach-Object { $_.minecraftVersions } | Sort-Object { [version]$_ })
$taskSourceGames = @(Get-ChildItem -LiteralPath $taskSources -Directory | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'fixture.properties')
} | ForEach-Object { $_.Name })
foreach ($taskGame in $taskSourceGames) {
    $taskVersion = $null
    if (-not [version]::TryParse($taskGame, [ref]$taskVersion)) { throw "Invalid source fixture game: $taskGame" }
}
$taskSourceGames = @($taskSourceGames | Sort-Object { [version]$_ })
foreach ($taskGame in $TargetGame) {
    if ($taskGame -cnotin $taskGames) { throw "Unknown upgrade target: $taskGame" }
}
foreach ($taskGame in $SourceGame) {
    if ($taskGame -cnotin $taskSourceGames) { throw "Unknown upgrade source: $taskGame" }
}
$taskSelectedSources = if ($SourceGame.Count) { $SourceGame } else { $taskSourceGames }
$taskSelectedTargets = if ($TargetGame.Count) { $TargetGame } else { $taskGames }
if (-not @($taskSelectedSources | Where-Object {
    $taskSourceVersion = [version]$_
    @($taskSelectedTargets | Where-Object { [version]$_ -gt $taskSourceVersion }).Count -gt 0
}).Count) { throw 'No forward upgrade route selected.' }
$taskProfiles = @{}
foreach ($taskTarget in $taskCatalog.targets) {
    $taskGroup = @($taskCandidate.groups | Where-Object { $_.target.id -ceq $taskTarget.id })
    if ($taskGroup.Count -ne 1 -or (@($taskGroup[0].target.minecraftVersions) -join ',') -cne
        (@($taskTarget.minecraftVersions) -join ',')) { throw "Wrong candidate group: $($taskTarget.id)" }
    $taskName = [string]$taskGroup[0].artifact.name
    if ([IO.Path]::GetFileName($taskName) -cne $taskName) { throw 'Candidate artifact must be a basename.' }
    $taskArtifact = Join-Path $taskCandidateDirectory $taskName
    if ((Get-FileHash -LiteralPath $taskArtifact -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        $taskGroup[0].artifact.sha256) { throw "Candidate changed: $taskArtifact" }
    if ($taskTarget.javaVersion -le 21) { continue }
    foreach ($taskGame in $taskTarget.minecraftVersions) {
        $taskProfiles[$taskGame] = [LumiMinecraftVerificationTarget]::new($taskCatalog, $taskTarget.id, $taskGame, $taskArtifact)
    }
}
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskMatrix = [ordered]@{ schema = 1; status = 'running'; candidate = $taskCandidate; routes = @() }
$taskMatrix | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $taskOutput 'matrix.json') -Encoding UTF8
Push-Location $taskRepo
try {
    foreach ($taskGame in $taskGames) {
        if ($TargetGame.Count -gt 0 -and $taskGame -cnotin $TargetGame) { continue }
        $taskProfile = $taskProfiles[$taskGame]
        $taskHash = (Get-FileHash -LiteralPath $taskProfile.Artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        foreach ($taskSourceGame in $taskSourceGames) {
            if ([version]$taskSourceGame -ge [version]$taskGame) { continue }
            if ($SourceGame.Count -gt 0 -and $taskSourceGame -cnotin $SourceGame) { continue }
            $taskFixture = Join-Path $taskOutput ($taskSourceGame + '-to-' + $taskGame)
            New-Item -ItemType Directory -Path $taskFixture | Out-Null
            foreach ($taskItem in @('source-a', 'source-b', 'fixture.properties')) {
                Copy-Item -LiteralPath (Join-Path (Join-Path $taskSources $taskSourceGame) $taskItem) -Destination $taskFixture -Recurse
            }
            $taskArguments = $taskProfile.TaskArguments(@('runPackagedClientGameTest')) + @(
                '-Dlumi.gametest.suite=UPGRADE', '-Dlumi.gametest.upgrade.stage=startup',
                "-Dlumi.gametest.upgrade.fixture=$taskFixture", '--console=plain')
            $taskLog = Join-Path $taskFixture 'startup.log'
            $ErrorActionPreference = 'Continue'
            & (Join-Path $taskRepo 'gradlew.bat') @taskArguments *> $taskLog
            $taskExit = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
            if ($taskExit -ne 0) { throw "Startup failed: $taskSourceGame -> $taskGame; see $taskLog" }
            $taskCompletion = Get-Content -LiteralPath $taskLog | Where-Object { $_ -like 'Packaged client *evidence: *' } | Select-Object -Last 1
            if (-not $taskCompletion) { throw "No packaged completion in $taskLog" }
            $taskEvidence = ($taskCompletion -split 'evidence: ', 2)[1]
            $taskProof = Get-Content -LiteralPath $taskEvidence -Raw | ConvertFrom-Json
            if ($taskProof.status -cne 'passed' -or $taskProof.game -cne $taskGame -or
                $taskProof.suite -cne 'UPGRADE' -or $taskProof.artifact.sha256 -cne $taskHash) { throw 'Wrong upgrade evidence.' }
            $taskArguments = $taskProfile.TaskArguments(@('runVanillaReopenGameTest')) + @(
                "-Dlumi.gametest.upgrade.fixture=$taskFixture", '--console=plain')
            $taskVanillaLog = Join-Path $taskFixture 'without-lumi.log'
            $ErrorActionPreference = 'Continue'
            & (Join-Path $taskRepo 'gradlew.bat') @taskArguments *> $taskVanillaLog
            $taskExit = $LASTEXITCODE
            $ErrorActionPreference = 'Stop'
            if ($taskExit -ne 0) { throw "Vanilla reopen failed: $taskSourceGame -> $taskGame; see $taskVanillaLog" }
            if ((Get-FileHash -LiteralPath $taskProfile.Artifact -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHash) { throw 'Candidate changed during upgrade.' }
            $taskRoute = [ordered]@{ source = $taskSourceGame; target = $taskGame; status = 'passed';
                sha256 = $taskHash; evidence = $taskEvidence; vanillaReopen = $taskVanillaLog }
            $taskMatrix.routes += $taskRoute
            $taskRoute | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskFixture 'result.json') -Encoding UTF8
            $taskMatrix | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $taskOutput 'matrix.json') -Encoding UTF8
            Write-Output "Passed $taskSourceGame -> $taskGame"
        }
    }
    $taskMatrix.status = 'passed'
} catch {
    $taskMatrix.status = 'failed'
    $taskMatrix.failure = $_.Exception.Message
    throw
} finally {
    $taskMatrix | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $taskOutput 'matrix.json') -Encoding UTF8
    Pop-Location
}
