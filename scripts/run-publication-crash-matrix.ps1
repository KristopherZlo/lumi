param(
    [ValidateSet('save', 'import')] [string]$Kind = 'save',
    [string[]]$Phase = @(),
    [ValidateRange(120, 7200)] [int]$TimeoutSeconds = 900,
    [string]$Java = (Join-Path $env:JAVA_HOME 'bin/java.exe')
)

$ErrorActionPreference = 'Stop'
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskPhases = @('objects-staged', 'pack-forced', 'pack-published', 'index-forced',
    'objects-published', 'commit-written', 'ref-forced', 'ref-published')
if ($Kind -eq 'save') {
    $taskPhases += @('journal-created', 'working-cleared', 'journal-complete', 'cleanup')
}
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
    $taskRun = Join-Path $taskRepo ('build/reports/lumi-publication-crash/' +
        [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskRun | Out-Null
    $taskManifest = [ordered]@{
        schema = 1; gitSha = (& git rev-parse HEAD); dirty = [bool](& git status --porcelain)
        kind = $Kind; phases = $taskPhases; startedUtc = [DateTime]::UtcNow.ToString('o')
        status = 'unverified'; completed = @()
        buildProperties = @((Get-Content -LiteralPath (Join-Path $taskRepo 'gradle.properties')) | ForEach-Object { [string]$_ })
    }
    $taskManifestPath = Join-Path $taskRun 'manifest.json'
    $taskManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
    try {
        foreach ($taskPhase in $taskPhases) {
            $taskDirectory = Join-Path $taskRun $taskPhase
            $taskGameDirectory = Join-Path $taskDirectory 'run/clientGameTest'
            $taskTemp = Join-Path $taskDirectory 'tmp'
            New-Item -ItemType Directory -Path $taskGameDirectory, $taskTemp -Force | Out-Null
            $taskMarker = Join-Path $taskDirectory 'crash.marker'
            foreach ($taskMode in @('produce', 'verify')) {
                $taskArguments = Get-Content -LiteralPath (Join-Path $taskRepo 'build/multiplayer-launch.args')
                $taskExtra = @(('-Djava.io.tmpdir=' + $taskTemp), '-Dlumi.gametest.suite=PERSISTENCE_CRASH',
                    ('-Dlumi.persistence.kind=' + $Kind), ('-Dlumi.persistence.phase=' + $taskPhase),
                    ('-Dlumi.persistence.mode=' + $taskMode), ('-Dlumi.persistence.marker=' + $taskMarker),
                    'net.fabricmc.devlaunchinjector.Main', '--username', 'Lumi-crash', '--gameDir', $taskGameDirectory)
                foreach ($taskArgument in $taskExtra) {
                    $taskArguments += '"' + $taskArgument.Replace('\', '\\').Replace('"', '\"') + '"'
                }
                $taskArgFile = Join-Path $taskDirectory ($taskMode + '.args')
                [IO.File]::WriteAllLines($taskArgFile, $taskArguments, [Text.UTF8Encoding]::new($false))
                $taskLog = Join-Path $taskDirectory ($taskMode + '.stdout.log')
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
                        if (-not (Test-Path -LiteralPath $taskMarker) -or
                            -not (Select-String -LiteralPath $taskMarker -SimpleMatch ('phase=' + $taskPhase) -Quiet) -or
                            -not (Select-String -LiteralPath $taskMarker -SimpleMatch ('kind=' + $Kind) -Quiet)) {
                            throw "The process did not reach the selected cutpoint: $taskDirectory"
                        }
                    } elseif (-not (Select-String -LiteralPath $taskLog -SimpleMatch ('publication_crash_' + $Kind + '_' + $taskPhase + ': succeeded') -Quiet)) {
                        throw "Verifier exited without its gate: $taskLog"
                    }
                } finally {
                    if (-not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id -Force }
                    $taskProcess.Dispose()
                }
            }
            $taskManifest.completed += $taskPhase
            $taskManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
            Write-Output "Verified $Kind/$taskPhase`: $taskDirectory"
        }
        $taskManifest.status = 'passed'
    } catch {
        $taskManifest.status = 'failed'
        $taskManifest.failure = $_.Exception.Message
        throw
    } finally {
        $taskManifest.finishedUtc = [DateTime]::UtcNow.ToString('o')
        $taskManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
    }
    Write-Output "Publication crash matrix passed: $taskRun"
} finally {
    Pop-Location
}
