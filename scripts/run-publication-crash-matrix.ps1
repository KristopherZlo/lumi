param(
    [switch]$RunMinecraft,
    [ValidateSet('save', 'import', 'merge', 'restore', 'upgrade')] [string]$Kind = 'save',
    [string]$Target = 'legacy-12111',
    [string]$GameVersion,
    [string]$Artifact,
    [string]$UpgradeFixture,
    [string[]]$Phase = @(),
    [switch]$RestoreCold,
    [string[]]$ResumeFrom = @(),
    [ValidateRange(120, 7200)] [int]$TimeoutSeconds = 900,
    [string]$Java
)

$ErrorActionPreference = 'Stop'
if (-not $RunMinecraft) {
    throw 'No tests started. This matrix repeatedly opens Minecraft windows and uses CPU/GPU. Pass -RunMinecraft only when the computer is available for testing.'
}
. (Join-Path $PSScriptRoot 'CrashMatrixDefinition.ps1')
. (Join-Path $PSScriptRoot 'MinecraftVerificationTarget.ps1')
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskVerification = [LumiMinecraftVerificationTarget]::new($taskCatalog, $Target, $GameVersion, $Artifact)
$taskProfile = @($taskVerification.Definition)
$taskModern = $taskVerification.Modern
$taskBuild = Join-Path $taskRepo $(if ($taskModern) { "versions/$Target/build" } else { 'build' })
if ($Kind -eq 'upgrade') {
    if (-not $taskModern) { throw 'Upgrade matrix requires a modern target.' }
    if ([string]::IsNullOrWhiteSpace($UpgradeFixture)) { throw '-UpgradeFixture must name a stopped-world source/edit fixture.' }
    $UpgradeFixture = (Resolve-Path -LiteralPath $UpgradeFixture).Path
    foreach ($name in @('fixture.properties', 'source-a/level.dat', 'source-b/level.dat')) {
        if (-not (Test-Path -LiteralPath (Join-Path $UpgradeFixture $name))) { throw "Incomplete upgrade fixture: $name" }
    }
}
$taskDefinition = [LumiCrashMatrixDefinition]::new($Kind)
$taskPhases = $taskDefinition.Phases
if ($RestoreCold -and $Kind -ne 'restore') { throw '-RestoreCold requires -Kind restore.' }
if ($Phase.Count -gt 0) {
    foreach ($taskPhase in $Phase) {
        if ($taskPhase -notin $taskPhases) { throw "Invalid $Kind cutpoint: $taskPhase" }
    }
    $taskPhases = $Phase
}
Push-Location $taskRepo
try {
    $ErrorActionPreference = 'Continue'
    $taskPrepare = $taskVerification.PrepareArguments()
    & (Join-Path $taskRepo 'gradlew.bat') @taskPrepare --no-daemon
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw 'Client launch preparation failed.' }
    $taskLaunch = Get-Content -LiteralPath (Join-Path $taskBuild 'multiplayer-launch.inputs.json') -Raw | ConvertFrom-Json
    if (-not $Java) { $Java = $taskLaunch.javaExecutable }
    if (-not $Java -or -not (Test-Path -LiteralPath $Java)) { throw "JDK $($taskProfile[0].javaVersion) java.exe is required." }
    $taskProduction = $taskLaunch.launchKind -ceq 'production-client'
    if ($taskModern -and -not $taskProduction) { throw 'Native crash gates require the packaged production launch.' }
    if ($taskLaunch.target.id -cne $Target -or $taskLaunch.target.javaVersion -ne $taskProfile[0].javaVersion) {
        throw 'Prepared launch does not match the selected target.'
    }
    foreach ($name in @('minecraftVersion', 'minecraftVersions', 'loaderVersion', 'fabricApiVersion',
            'worldeditVersionId', 'axiomVersionId', 'worldeditCuiCoordinate', 'axiomRuntimeJars')) {
        if ((@($taskLaunch.target.$name) -join "`n") -cne (@($taskProfile[0].$name) -join "`n")) {
            throw "Prepared launch has a different target field: $name"
        }
    }
    $taskSha = (& git rev-parse HEAD).Trim()
    $taskDirty = [bool](& git status --porcelain)
    $taskFingerprint = [LumiCrashMatrixEvidence]::Inputs($taskRepo, $Java, $taskBuild)
    $taskEvidence = [LumiCrashMatrixEvidence]::new($taskFingerprint, $taskSha, $taskDirty, $taskDefinition)
    $taskResumed = $taskEvidence.Resume($ResumeFrom, $taskPhases, [bool]$RestoreCold)
    $taskRun = Join-Path $taskRepo ('build/reports/lumi-publication-crash/' +
        [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskRun | Out-Null
    $taskManifest = [ordered]@{
        schema = 2; gitSha = $taskSha; dirty = $taskDirty; inputFingerprint = $taskFingerprint
        kind = $Kind; phases = $taskPhases; restoreCold = [bool]$RestoreCold
        startedUtc = [DateTime]::UtcNow.ToString('o')
        status = 'unverified'; completed = @(); results = [ordered]@{}
        resumedFrom = $ResumeFrom
        buildProperties = @((Get-Content -LiteralPath (Join-Path $taskRepo 'gradle.properties')) | ForEach-Object { [string]$_ })
        minecraftTarget = (Get-Content -LiteralPath (Join-Path $taskBuild 'multiplayer-launch.inputs.json') -Raw | ConvertFrom-Json).target
        artifact = $taskLaunch.artifact
    }
    $taskManifestPath = Join-Path $taskRun 'manifest.json'
    $taskManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
    try {
        foreach ($taskPhase in $taskPhases) {
            if ((& git rev-parse HEAD).Trim() -cne $taskSha -or [bool](& git status --porcelain) -ne $taskDirty) {
                throw 'The candidate changed during the crash matrix.'
            }
            if ($taskResumed.ContainsKey($taskPhase)) {
                $taskManifest.results[$taskPhase] = $taskResumed[$taskPhase]
                $taskManifest.completed += $taskPhase
                $taskManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
                Write-Output "Reused verified $Kind/$taskPhase`: $($taskResumed[$taskPhase].directory)"
                continue
            }
            $taskDirectory = Join-Path $taskRun $taskPhase
            $taskGameDirectory = Join-Path $taskDirectory 'run/clientGameTest'
            $taskTemp = Join-Path $taskDirectory 'tmp'
            New-Item -ItemType Directory -Path $taskGameDirectory, $taskTemp -Force | Out-Null
            if ($Kind -eq 'upgrade') {
                foreach ($name in @('fixture.properties', 'source-a', 'source-b')) {
                    Copy-Item -LiteralPath (Join-Path $UpgradeFixture $name) -Destination $taskDirectory -Recurse
                }
            }
            $taskMarker = Join-Path $taskDirectory 'crash.marker'
            $taskReceipts = [ordered]@{}
            foreach ($taskMode in @('produce', 'verify')) {
                $taskArguments = Get-Content -LiteralPath (Join-Path $taskBuild 'multiplayer-launch.args')
                $taskExtra = @('-Djava.io.tmpdir=' + $taskTemp) +
                    $taskDefinition.Arguments($taskPhase, $taskMode, $taskMarker, [bool]$RestoreCold)
                if ($taskProduction) {
                    $taskExtra += @("-Dlumi.gametest.completion=$(Join-Path $taskGameDirectory 'client.completed')",
                        $taskLaunch.mainClass) + @($taskLaunch.programArguments)
                } else { $taskExtra += 'net.fabricmc.devlaunchinjector.Main' }
                $taskExtra += @('--username', 'Lumi-crash', '--gameDir', $taskGameDirectory)
                if ($Kind -eq 'upgrade') { $taskExtra = @("-Dlumi.gametest.upgrade.fixture=$taskDirectory") + $taskExtra }
                foreach ($taskArgument in $taskExtra) {
                    $taskArguments += '"' + $taskArgument.Replace('\', '\\').Replace('"', '\"') + '"'
                }
                $taskArgFile = Join-Path $taskDirectory ($taskMode + '.args')
                [IO.File]::WriteAllLines($taskArgFile, $taskArguments, [Text.UTF8Encoding]::new($false))
                $taskLog = Join-Path $taskDirectory ($taskMode + '.stdout.log')
                $taskStarted = [DateTime]::UtcNow.ToString('o')
                $taskProcess = Start-Process -FilePath $Java -ArgumentList ('@"' + $taskArgFile + '"') -WindowStyle Hidden -PassThru -WorkingDirectory $taskGameDirectory -RedirectStandardOutput $taskLog -RedirectStandardError (Join-Path $taskDirectory ($taskMode + '.stderr.log'))
                $taskProcess.Handle | Out-Null
                $taskProcess.Id | Set-Content -LiteralPath (Join-Path $taskDirectory ($taskMode + '.pid'))
                try {
                    $taskDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
                    while (-not $taskProcess.HasExited) {
                        if (Select-String -LiteralPath $taskLog -SimpleMatch -Pattern @(
                                'Client gametests failed with an exception', 'Minecraft has crashed!',
                                'Encountered an unexpected exception') -Quiet) {
                            throw "GameTest failed: $taskLog"
                        }
                        if ([DateTime]::UtcNow -gt $taskDeadline) { throw "Crash gate timed out: $taskLog" }
                        Start-Sleep -Seconds 1
                        $taskProcess.Refresh()
                    }
                    $taskProcess.WaitForExit()
                    $taskExpectedExit = if ($taskMode -eq 'produce') { 86 } else { 0 }
                    if ($taskProcess.ExitCode -ne $taskExpectedExit) {
                        throw "Expected exit $taskExpectedExit, got $($taskProcess.ExitCode): $taskLog"
                    }
                    if ($taskMode -eq 'produce') {
                        $taskDefinition.AssertMarker($taskMarker, $taskPhase)
                        if ($Kind -eq 'restore') {
                            Copy-Item -LiteralPath (Join-Path $taskGameDirectory 'lumi-restore-crash.properties') -Destination (Join-Path $taskDirectory 'producer-metadata.properties')
                        }
                        if ($Kind -eq 'upgrade') {
                            Copy-Item -LiteralPath (Join-Path $taskGameDirectory 'saves/lumi-upgrade-target') -Destination (Join-Path $taskDirectory 'crashed-target') -Recurse
                            $taskMoveSource = [IO.Path]::GetFullPath($taskGameDirectory)
                            $taskMoveTarget = [IO.Path]::GetFullPath((Join-Path $taskDirectory 'producer-run'))
                            $taskAllowedRoot = [IO.Path]::GetFullPath($taskDirectory) + [IO.Path]::DirectorySeparatorChar
                            if (-not $taskMoveSource.StartsWith($taskAllowedRoot) -or -not $taskMoveTarget.StartsWith($taskAllowedRoot)) {
                                throw 'Upgrade snapshot move escaped its phase directory.'
                            }
                            Move-Item -LiteralPath $taskMoveSource -Destination $taskMoveTarget
                            New-Item -ItemType Directory -Path $taskGameDirectory | Out-Null
                        }
                    } elseif (-not (Select-String -LiteralPath $taskLog -SimpleMatch $taskDefinition.Gate($taskPhase) -Quiet)) {
                        throw "Verifier exited without its gate: $taskLog"
                    }
                    if ($taskProduction -and $taskMode -eq 'verify') {
                        [LumiCrashMatrixEvidence]::AssertClientCompletion(
                            (Join-Path $taskGameDirectory 'client.completed'), $taskDefinition.Suite(), $taskLaunch.artifact.sha256)
                    }
                    $taskReceipts[$taskMode] = [ordered]@{
                        exitCode = $taskProcess.ExitCode; startedUtc = $taskStarted
                        finishedUtc = [DateTime]::UtcNow.ToString('o')
                    }
                    if ($taskProduction -and $taskMode -eq 'verify') {
                        $taskReceipts[$taskMode].artifactSha256 = $taskLaunch.artifact.sha256
                    }
                } finally {
                    if (-not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id -Force }
                    $taskProcess.Dispose()
                }
            }
            if ((& git rev-parse HEAD).Trim() -cne $taskSha -or [bool](& git status --porcelain) -ne $taskDirty -or
                [LumiCrashMatrixEvidence]::Inputs($taskRepo, $Java, $taskBuild) -cne $taskFingerprint) {
                throw 'The candidate or launch inputs changed during the crash pair.'
            }
            $taskManifest.results[$taskPhase] = $taskEvidence.Record($taskDirectory, $taskPhase, $taskReceipts)
            $taskManifest.completed += $taskPhase
            $taskManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
            Write-Output "Verified $Kind/$taskPhase`: $taskDirectory"
        }
        if ((& git rev-parse HEAD).Trim() -cne $taskSha -or [bool](& git status --porcelain) -ne $taskDirty -or
            [LumiCrashMatrixEvidence]::Inputs($taskRepo, $Java, $taskBuild) -cne $taskFingerprint) {
            throw 'The candidate or launch inputs changed during the crash matrix.'
        }
        $taskManifest.status = 'passed'
    } catch {
        $taskManifest.status = 'failed'
        $taskManifest.failure = $_.Exception.Message
        throw
    } finally {
        $taskManifest.finishedUtc = [DateTime]::UtcNow.ToString('o')
        $taskManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
    }
    Write-Output "Crash matrix passed ($Kind): $taskRun"
} finally {
    Pop-Location
}
