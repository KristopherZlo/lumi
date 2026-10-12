param(
    [switch]$RunTests,
    [ValidateSet('Core', 'Exact', 'Recovery', 'Multiplayer', 'RestoreCrash',
        'SaveCrash', 'MergeCrash', 'ImportCrash', 'LoadRegression', 'RestoreSoak', 'SaveMemory')]
    [string[]]$Gate = @('Core', 'Exact', 'Recovery', 'Multiplayer', 'RestoreCrash',
        'SaveCrash', 'MergeCrash', 'ImportCrash', 'LoadRegression', 'RestoreSoak', 'SaveMemory'),
    [string]$FixtureManifest,
    [string]$ExistingWorldSource,
    [string]$ExistingWorldName = 'Lumi behavior seed 710',
    [string]$ReviewedEvidence
)

$ErrorActionPreference = 'Stop'
if (-not $RunTests) {
    throw 'No tests started. Release gates can repeatedly open Minecraft windows and impose sustained CPU/GPU/memory load. Pass -RunTests only when the computer is available for testing.'
}
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location $taskRepo
try {
    if (& git status --porcelain) { throw 'Commit the candidate before its release run.' }
    $taskSha = (& git rev-parse HEAD).Trim()
    $taskRun = Join-Path $taskRepo ('build/reports/lumi-release/' +
        [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskRun | Out-Null
    $taskRequired = @('Core', 'Exact', 'Recovery', 'Multiplayer', 'RestoreCrash',
        'SaveCrash', 'MergeCrash', 'ImportCrash', 'LoadRegression', 'RestoreSoak', 'SaveMemory',
        'IdleAB', 'ForegroundCooling', 'SaveRetainedBound', 'BuilderToolsIntegrated')
    $taskResults = [ordered]@{}
    foreach ($taskName in $taskRequired) {
        $taskResults[$taskName] = [ordered]@{ status = 'unverified'; artifact = ''; detail = '' }
    }
    $taskManifest = [ordered]@{
        schema = 1; gitSha = $taskSha; startedUtc = [DateTime]::UtcNow.ToString('o')
        status = 'incomplete'; gates = $taskResults
        buildProperties = @((Get-Content -LiteralPath 'gradle.properties') | ForEach-Object { [string]$_ })
        os = (Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version, BuildNumber)
        cpu = @(Get-CimInstance Win32_Processor | Select-Object Name, NumberOfLogicalProcessors)
        gpu = @(Get-CimInstance Win32_VideoController | Select-Object Name, DriverVersion)
        java = $env:JAVA_HOME
    }
    $taskManifestPath = Join-Path $taskRun 'manifest.json'
    function Write-Manifest {
        $taskManifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskManifestPath -Encoding UTF8
    }
    Write-Manifest
    foreach ($taskName in $Gate) {
        if ((& git rev-parse HEAD).Trim() -ne $taskSha -or (& git status --porcelain)) {
            throw 'The candidate changed during its release run.'
        }
        $taskResult = $taskResults[$taskName]
        $taskDirectory = Join-Path $taskRun $taskName
        New-Item -ItemType Directory -Path $taskDirectory | Out-Null
        $taskLog = Join-Path $taskDirectory 'run.log'
        $taskResult.artifact = $taskDirectory
        $taskStarted = [DateTime]::UtcNow
        try {
            $taskGradleArguments = @()
            switch ($taskName) {
                'Core' { $taskGradleArguments = @('compileJava', 'compileTestJava', 'compileGametestJava', 'test', 'runGameTest') }
                'Exact' { $taskGradleArguments = @('-Dlumi.gametest.suite=EXACT', 'runClientGameTest') }
                'Recovery' { $taskGradleArguments = @('-Dlumi.gametest.suite=RECOVERY', 'runClientGameTest') }
                'Multiplayer' { & (Join-Path $PSScriptRoot 'run-multiplayer-gate.ps1') -RunMinecraft *> $taskLog }
                'RestoreCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft -Kind restore *> $taskLog }
                'SaveCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft -Kind save *> $taskLog }
                'MergeCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft -Kind merge *> $taskLog }
                'ImportCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft -Kind import *> $taskLog }
                'LoadRegression' { $taskGradleArguments = @('regressionPerformanceTest') }
                'RestoreSoak' {
                    if (-not $FixtureManifest -or -not $ExistingWorldSource) {
                        $taskResult.detail = 'Requires FixtureManifest and an immutable ExistingWorldSource.'
                        continue
                    }
                    $taskGradleArguments = @('-Dlumi.gametest.suite=BENCHMARK', '-Dlumi.benchmark.enabled=true',
                        '-Dlumi.benchmark.soak=true', '-Dlumi.benchmark.soakCycles=20', '-Dlumi.benchmark.observeSeconds=1800',
                        ('-Dlumi.benchmark.coldManifest=' + [IO.Path]::GetFullPath($FixtureManifest)),
                        ('-Dlumi.benchmark.existingWorldSource=' + [IO.Path]::GetFullPath($ExistingWorldSource)),
                        ('-Dlumi.benchmark.existingWorld=' + $ExistingWorldName), 'runClientGameTest')
                }
                'SaveMemory' {
                    $taskGradleArguments = @('-Dlumi.gametest.suite=BENCHMARK', '-Dlumi.benchmark.enabled=true',
                        '-Dlumi.benchmark.memory=true', '-Dlumi.benchmark.memoryOnly=true', '-Dlumi.benchmark.memoryGc=true',
                        '-Dlumi.benchmark.heapDumpSave=benchmark-base', '-Dlumi.benchmark.heap=2G',
                        '-Dlumi.benchmark.baseSize=512', '-Dlumi.benchmark.changeSize=512', '-Dlumi.benchmark.layers=16',
                        '-Dlumi.benchmark.commits=1', 'runClientGameTest')
                }
            }
            if ($taskGradleArguments.Count -gt 0) {
                # Native stderr is evidence; the Gradle exit code decides success.
                $ErrorActionPreference = 'Continue'
                & (Join-Path $taskRepo 'gradlew.bat') @taskGradleArguments --no-daemon *> $taskLog
                $taskExit = $LASTEXITCODE
                $ErrorActionPreference = 'Stop'
                if ($taskExit -ne 0) { throw "Gradle exited with code $taskExit" }
                if (Test-Path -LiteralPath 'build/reports/lumi-behavior') {
                    $taskBehavior = Join-Path $taskDirectory 'lumi-behavior'
                    New-Item -ItemType Directory -Path $taskBehavior | Out-Null
                    Get-ChildItem -LiteralPath 'build/reports/lumi-behavior' -Directory |
                        Where-Object { $_.CreationTimeUtc -ge $taskStarted } |
                        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $taskBehavior -Recurse }
                }
                foreach ($taskArtifact in @('build/run/clientGameTest',
                    'build/test-results', 'build/reports/tests', 'build/run/gameTest/logs')) {
                    if (Test-Path -LiteralPath $taskArtifact) {
                        Copy-Item -LiteralPath $taskArtifact -Destination $taskDirectory -Recurse
                    }
                }
            }
            $taskResult.status = 'passed'
            $taskResult.detail = 'Command completed; scope is defined by this gate, not the whole release.'
        } catch {
            $taskResult.status = 'failed'
            $taskResult.detail = $_.Exception.Message
        } finally {
            Write-Manifest
        }
        Write-Output "$taskName`: $($taskResult.status)"
    }
    if ($ReviewedEvidence) {
        $taskReviewed = Get-Content -LiteralPath $ReviewedEvidence -Raw | ConvertFrom-Json
        if ($taskReviewed.gitSha -ne $taskSha) { throw 'Reviewed evidence belongs to another candidate.' }
        foreach ($taskItem in $taskReviewed.gates) {
            if ($taskItem.name -notin @('IdleAB', 'ForegroundCooling', 'SaveRetainedBound', 'BuilderToolsIntegrated') -or
                $taskItem.status -ne 'passed' -or [string]::IsNullOrWhiteSpace($taskItem.reviewedBy)) {
                throw 'Reviewed evidence requires a known manual gate, passed status, and a reviewer.'
            }
            $taskEvidence = [IO.Path]::GetFullPath($taskItem.artifact)
            if ((Get-FileHash -LiteralPath $taskEvidence -Algorithm SHA256).Hash -ne $taskItem.sha256) {
                throw "Reviewed artifact changed: $taskEvidence"
            }
            Copy-Item -LiteralPath $taskEvidence -Destination (Join-Path $taskRun ($taskItem.name + '-evidence.json'))
            $taskResults[$taskItem.name] = [ordered]@{
                status = 'passed'; artifact = $taskEvidence; detail = ('Reviewed by ' + $taskItem.reviewedBy)
            }
        }
    }
    if ((& git rev-parse HEAD).Trim() -ne $taskSha -or (& git status --porcelain)) {
        $taskManifest.status = 'invalid-candidate'
    } elseif (@($taskResults.Values | Where-Object { $_.status -eq 'failed' }).Count -gt 0) {
        $taskManifest.status = 'failed'
    } elseif (@($taskResults.Values | Where-Object { $_.status -ne 'passed' }).Count -eq 0) {
        $taskManifest.status = 'passed'
    }
    $taskManifest.finishedUtc = [DateTime]::UtcNow.ToString('o')
    Write-Manifest
    Write-Output "Release status: $($taskManifest.status); evidence: $taskRun"
    if ($taskManifest.status -ne 'passed') { throw 'Release readiness has unverified, failed, or invalid gates. See manifest.json.' }
} finally {
    Pop-Location
}
