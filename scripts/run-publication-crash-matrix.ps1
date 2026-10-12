using module ./CrashMatrixDefinition.psm1

param(
    [switch]$RunMinecraft,
    [ValidateSet('save', 'import', 'merge', 'restore')] [string]$Kind = 'save',
    [string[]]$Phase = @(),
    [switch]$RestoreCold,
    [string[]]$ResumeFrom = @(),
    [ValidateRange(120, 7200)] [int]$TimeoutSeconds = 900,
    [string]$Java = (Join-Path $env:JAVA_HOME 'bin/java.exe')
)

$ErrorActionPreference = 'Stop'
if (-not $RunMinecraft) {
    throw 'No tests started. This matrix repeatedly opens Minecraft windows and uses CPU/GPU. Pass -RunMinecraft only when the computer is available for testing.'
}
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskDefinition = [LumiCrashMatrixDefinition]::new($Kind)
$taskPhases = $taskDefinition.Phases
if ($RestoreCold -and $Kind -ne 'restore') { throw '-RestoreCold requires -Kind restore.' }
if ($Phase.Count -gt 0) {
    foreach ($taskPhase in $Phase) {
        if ($taskPhase -notin $taskPhases) { throw "Invalid $Kind cutpoint: $taskPhase" }
    }
    $taskPhases = $Phase
}
if (-not (Test-Path -LiteralPath $Java)) { throw 'A JDK 21 java.exe is required.' }
Push-Location $taskRepo
try {
    $ErrorActionPreference = 'Continue'
    & (Join-Path $taskRepo 'gradlew.bat') prepareMultiplayerLaunch --no-daemon
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw 'Client launch preparation failed.' }
    $taskSha = (& git rev-parse HEAD).Trim()
    $taskDirty = [bool](& git status --porcelain)
    $taskFingerprint = [LumiCrashMatrixEvidence]::Inputs($taskRepo, $Java)
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
            $taskMarker = Join-Path $taskDirectory 'crash.marker'
            $taskReceipts = [ordered]@{}
            foreach ($taskMode in @('produce', 'verify')) {
                $taskArguments = Get-Content -LiteralPath (Join-Path $taskRepo 'build/multiplayer-launch.args')
                $taskExtra = @('-Djava.io.tmpdir=' + $taskTemp) +
                    $taskDefinition.Arguments($taskPhase, $taskMode, $taskMarker, [bool]$RestoreCold) +
                    @('net.fabricmc.devlaunchinjector.Main', '--username', 'Lumi-crash', '--gameDir', $taskGameDirectory)
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
                        if (Select-String -LiteralPath $taskLog -SimpleMatch 'Client gametests failed with an exception' -Quiet) {
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
                    } elseif (-not (Select-String -LiteralPath $taskLog -SimpleMatch $taskDefinition.Gate($taskPhase) -Quiet)) {
                        throw "Verifier exited without its gate: $taskLog"
                    }
                    $taskReceipts[$taskMode] = [ordered]@{
                        exitCode = $taskProcess.ExitCode; startedUtc = $taskStarted
                        finishedUtc = [DateTime]::UtcNow.ToString('o')
                    }
                } finally {
                    if (-not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id -Force }
                    $taskProcess.Dispose()
                }
            }
            if ((& git rev-parse HEAD).Trim() -cne $taskSha -or [bool](& git status --porcelain) -ne $taskDirty -or
                [LumiCrashMatrixEvidence]::Inputs($taskRepo, $Java) -cne $taskFingerprint) {
                throw 'The candidate or launch inputs changed during the crash pair.'
            }
            $taskManifest.results[$taskPhase] = $taskEvidence.Record($taskDirectory, $taskPhase, $taskReceipts)
            $taskManifest.completed += $taskPhase
            $taskManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
            Write-Output "Verified $Kind/$taskPhase`: $taskDirectory"
        }
        if ([LumiCrashMatrixEvidence]::Inputs($taskRepo, $Java) -cne $taskFingerprint) {
            throw 'Launch inputs changed during the crash matrix.'
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
