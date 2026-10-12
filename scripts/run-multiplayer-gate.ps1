param(
    [ValidateRange(120, 7200)] [int]$TimeoutSeconds = 900,
    [string]$Java = (Join-Path $env:JAVA_HOME 'bin/java.exe')
)

$ErrorActionPreference = 'Stop'
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not (Test-Path -LiteralPath $Java)) { throw 'A JDK 21 java.exe is required.' }
Push-Location $taskRepo
try {
    $ErrorActionPreference = 'Continue'
    & (Join-Path $taskRepo 'gradlew.bat') prepareMultiplayerLaunch --no-daemon
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw 'Multiplayer launch preparation failed.' }
    $taskRun = Join-Path $taskRepo ('build/reports/lumi-multiplayer/' +
        [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskRun | Out-Null
    $taskMailbox = Join-Path $taskRun 'mailbox'
    New-Item -ItemType Directory -Path $taskMailbox | Out-Null
    $taskManifest = [ordered]@{
        schema = 1; gitSha = (& git rev-parse HEAD); startedUtc = [DateTime]::UtcNow.ToString('o')
        status = 'unverified'; server = 'dedicated-in-owner-jvm'; clients = 2
        dirty = [bool](& git status --porcelain)
        buildProperties = @((Get-Content -LiteralPath (Join-Path $taskRepo 'gradle.properties')) | ForEach-Object { [string]$_ })
    }
    $taskManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskRun 'manifest.json') -Encoding UTF8
    $taskProcesses = @()
    $taskLogs = @()
    try {
        foreach ($taskRole in @('owner', 'peer')) {
            $taskDirectory = Join-Path $taskRun $taskRole
            New-Item -ItemType Directory -Path $taskDirectory | Out-Null
            $taskGameDirectory = Join-Path $taskDirectory 'run/clientGameTest'
            New-Item -ItemType Directory -Path $taskGameDirectory -Force | Out-Null
            $taskTempDirectory = Join-Path $taskDirectory 'tmp'
            New-Item -ItemType Directory -Path $taskTempDirectory | Out-Null
            # The project already accepts the test-server EULA in fabricApi.configureTests.
            [IO.File]::WriteAllText((Join-Path $taskGameDirectory 'eula.txt'), "eula=true`n")
            $taskArguments = Get-Content -LiteralPath (Join-Path $taskRepo 'build/multiplayer-launch.args')
            $taskExtra = @(('-Djava.io.tmpdir=' + $taskTempDirectory), '-Dlumi.gametest.suite=MULTIPLAYER',
                ('-Dlumi.multiplayer.role=' + $taskRole),
                ('-Dlumi.multiplayer.mailbox=' + $taskMailbox),
                'net.fabricmc.devlaunchinjector.Main', '--username', ('Lumi-' + $taskRole),
                '--gameDir', $taskGameDirectory)
            foreach ($taskArgument in $taskExtra) {
                $taskArguments += '"' + $taskArgument.Replace('\', '\\').Replace('"', '\"') + '"'
            }
            $taskArgFile = Join-Path $taskDirectory 'launch.args'
            [IO.File]::WriteAllLines($taskArgFile, $taskArguments, [Text.UTF8Encoding]::new($false))
            $taskProcesses += Start-Process -FilePath $Java -ArgumentList ('@"' + $taskArgFile + '"') -WindowStyle Hidden -PassThru -WorkingDirectory $taskGameDirectory -RedirectStandardOutput (Join-Path $taskDirectory 'stdout.log') -RedirectStandardError (Join-Path $taskDirectory 'stderr.log')
            $taskProcesses.Id | Set-Content -LiteralPath (Join-Path $taskRun 'pids.txt')
            $taskLogs += Join-Path $taskDirectory 'stdout.log'
        }
        $taskDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ($true) {
            foreach ($taskLog in $taskLogs) {
                if (Select-String -LiteralPath $taskLog -SimpleMatch 'Client gametests failed with an exception' -Quiet) {
                    throw "Client GameTest failed: $taskLog"
                }
            }
            foreach ($taskProcess in $taskProcesses) {
                $taskProcess.Refresh()
                if ($taskProcess.HasExited) {
                    $taskProcess.WaitForExit()
                    if ($taskProcess.ExitCode -ne 0) { throw "Client PID $($taskProcess.Id) failed: $taskRun" }
                }
            }
            if (@($taskProcesses | Where-Object { -not $_.HasExited }).Count -eq 0) { break }
            if ([DateTime]::UtcNow -gt $taskDeadline) { throw "Multiplayer gate timed out: $taskRun" }
            Start-Sleep -Seconds 1
        }
        if (-not (Select-String -LiteralPath (Join-Path $taskRun 'owner/stdout.log') -SimpleMatch 'multiplayer_shared_history: succeeded' -Quiet)) {
            throw "Clients exited without the multiplayer gate: $taskRun"
        }
        $taskManifest.status = 'passed'
    } catch {
        $taskManifest.status = 'failed'
        $taskManifest.failure = $_.Exception.Message
        throw
    } finally {
        foreach ($taskProcess in $taskProcesses) {
            $taskProcess.Refresh()
            if (-not $taskProcess.HasExited) { Stop-Process -Id $taskProcess.Id -Force }
        }
        $taskManifest.finishedUtc = [DateTime]::UtcNow.ToString('o')
        $taskManifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $taskRun 'manifest.json') -Encoding UTF8
    }
    Write-Output "Multiplayer gate passed: $taskRun"
} finally {
    Pop-Location
}
