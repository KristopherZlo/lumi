param(
    [switch]$RunTests,
    [ValidateSet('Core', 'Client', 'Exact', 'Recovery', 'Multiplayer', 'RestoreCrash', 'RestoreColdCrash',
        'SaveCrash', 'MergeCrash', 'ImportCrash', 'UpgradeCrash', 'LoadRegression', 'RestoreSoak', 'SaveMemory',
        'ClientVulkan', 'NativeViewport')]
    [string[]]$Gate = @('Core', 'Client', 'Exact', 'Recovery', 'Multiplayer', 'RestoreCrash', 'RestoreColdCrash',
        'SaveCrash', 'MergeCrash', 'ImportCrash', 'UpgradeCrash', 'LoadRegression', 'RestoreSoak', 'SaveMemory',
        'ClientVulkan', 'NativeViewport'),
    [string]$Target = 'legacy-12111',
    [string]$GameVersion,
    [string]$Artifact,
    [string]$UpgradeFixture,
    [string]$FixtureManifest,
    [string]$ExistingWorldSource,
    [string]$ExistingWorldName = 'Lumi behavior seed 710',
    [string]$ReviewedEvidence,
    [hashtable]$CrashResumeFrom = @{}
)

$ErrorActionPreference = 'Stop'
if (-not $RunTests) {
    throw 'No tests started. Release gates can repeatedly open Minecraft windows and impose sustained CPU/GPU/memory load. Pass -RunTests only when the computer is available for testing.'
}
foreach ($taskResumeGate in $CrashResumeFrom.Keys) {
    if ($taskResumeGate -notin @('RestoreCrash', 'RestoreColdCrash', 'SaveCrash', 'MergeCrash', 'ImportCrash', 'UpgradeCrash')) {
        throw "Unknown crash gate for resumption: $taskResumeGate"
    }
}
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'MinecraftVerificationTarget.ps1')
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskVerification = [LumiMinecraftVerificationTarget]::new($taskCatalog, $Target, $GameVersion, $Artifact)
$taskTargetArguments = @{ Target = $Target; GameVersion = $taskVerification.Definition.minecraftVersion }
if ($taskVerification.Artifact) { $taskTargetArguments.Artifact = $taskVerification.Artifact }
$taskBuild = if ($taskVerification.Modern) { "versions/$Target/build" } else { 'build' }
$taskArtifact = $null
Push-Location $taskRepo
try {
    if (& git status --porcelain) { throw 'Commit the candidate before its release run.' }
    $taskSha = (& git rev-parse HEAD).Trim()
    $taskRun = Join-Path $taskRepo ('build/reports/lumi-release/' +
        [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskRun | Out-Null
    if ($taskVerification.Modern -or $taskVerification.Artifact) {
        $taskArguments = $taskVerification.PrepareArguments()
        $ErrorActionPreference = 'Continue'
        & (Join-Path $taskRepo 'gradlew.bat') @taskArguments --console=plain *> (Join-Path $taskRun 'prepare.log')
        $taskExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($taskExit -ne 0) { throw "Candidate launch preparation failed; see $taskRun" }
        $taskLaunch = Get-Content -LiteralPath (Join-Path $taskBuild 'multiplayer-launch.inputs.json') -Raw | ConvertFrom-Json
        if ($taskLaunch.target.id -cne $Target -or
            $taskLaunch.target.minecraftVersion -cne $taskVerification.Definition.minecraftVersion) {
            throw 'Prepared release target does not match the selected game.'
        }
        $taskArtifact = $taskLaunch.artifact
        $taskTargetArguments.Artifact = $taskArtifact.path
        $taskVerification = [LumiMinecraftVerificationTarget]::new($taskCatalog, $Target,
            $taskVerification.Definition.minecraftVersion, $taskArtifact.path)
    }
    function Test-Candidate {
        return ((& git rev-parse HEAD).Trim() -ceq $taskSha -and -not (& git status --porcelain) -and
            ($null -eq $taskArtifact -or ((Test-Path -LiteralPath $taskArtifact.path) -and
            (Get-FileHash -LiteralPath $taskArtifact.path -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $taskArtifact.sha256)))
    }
    $taskRequired = $taskVerification.RequiredReleaseGates()
    $taskResults = [ordered]@{}
    foreach ($taskName in $taskRequired) {
        $taskResults[$taskName] = [ordered]@{ status = 'unverified'; artifact = ''; detail = '' }
    }
    $taskManifest = [ordered]@{
        schema = 2; gitSha = $taskSha; startedUtc = [DateTime]::UtcNow.ToString('o')
        minecraftTarget = $taskVerification.Definition; artifact = $taskArtifact
        status = 'incomplete'; gates = $taskResults
        buildProperties = @((Get-Content -LiteralPath 'gradle.properties') | ForEach-Object { [string]$_ })
        minecraftTargets = Get-Content -LiteralPath 'gradle/minecraft-targets.json' -Raw | ConvertFrom-Json
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
        if (-not (Test-Candidate)) {
            throw 'The candidate changed during its release run.'
        }
        if ($taskName -eq 'UpgradeCrash' -and -not $taskVerification.Modern) { continue }
        if ($taskName -in @('ClientVulkan', 'NativeViewport') -and $Target -cne 'modern-263') { continue }
        $taskResult = $taskResults[$taskName]
        $taskDirectory = Join-Path $taskRun $taskName
        New-Item -ItemType Directory -Path $taskDirectory | Out-Null
        $taskLog = Join-Path $taskDirectory 'run.log'
        $taskResult.artifact = $taskDirectory
        $taskStarted = [DateTime]::UtcNow
        try {
            $taskGradleArguments = @()
            $taskTasks = @()
            $taskCrashResume = @()
            if ($CrashResumeFrom.ContainsKey($taskName)) { $taskCrashResume = @($CrashResumeFrom[$taskName]) }
            switch ($taskName) {
                'Core' {
                    $taskTasks = @(':core:test', 'compileJava', 'compileTestJava', 'compileGametestJava', 'test', 'runPackagedGameTest')
                    $taskGradleArguments = @("-PlumiCoreTestJava=$($taskVerification.Definition.javaVersion)")
                }
                'Client' {
                    $taskGradleArguments = @('-Dlumi.gametest.suite=ALL')
                    if ($Target -ceq 'modern-263') { $taskGradleArguments += '-PlumiGraphicsBackend=opengl' }
                    $taskTasks = @('runPackagedClientGameTest')
                }
                'ClientVulkan' {
                    $taskGradleArguments = @('-Dlumi.gametest.suite=ALL', '-PlumiGraphicsBackend=vulkan',
                        '-Dlumi.gametest.fixedNativeWindow=true')
                    $taskTasks = @('runPackagedClientGameTest')
                }
                'NativeViewport' {
                    $taskResult.runtimeEvidence = @()
                    foreach ($taskBackend in @('opengl', 'vulkan')) {
                        foreach ($taskLumiMode in @('false', 'true')) {
                            if (-not (Test-Candidate)) { throw 'The candidate changed during native viewport checks.' }
                            $taskNativeLog = Join-Path $taskDirectory ("${taskBackend}-lumi-${taskLumiMode}.log")
                            $taskNativeArguments = $taskVerification.TaskArguments(@('runNativeViewportControl')) + @(
                                "-PlumiGraphicsBackend=$taskBackend", "-PlumiViewportLumi=$taskLumiMode", '-PlumiBuilderTools=none')
                            $ErrorActionPreference = 'Continue'
                            & (Join-Path $taskRepo 'gradlew.bat') @taskNativeArguments --console=plain *> $taskNativeLog
                            $taskExit = $LASTEXITCODE
                            $ErrorActionPreference = 'Stop'
                            if ($taskExit -ne 0) { throw "Native viewport failed; see $taskNativeLog" }
                            $taskNativeCompletion = Get-Content -LiteralPath $taskNativeLog |
                                Where-Object { $_ -like 'Native viewport *passed; evidence: *' } | Select-Object -Last 1
                            if (-not $taskNativeCompletion) { throw "Missing native viewport completion: $taskNativeLog" }
                            $taskResult.runtimeEvidence += $taskNativeCompletion
                        }
                    }
                }
                'Exact' { $taskGradleArguments = @('-Dlumi.gametest.suite=EXACT'); $taskTasks = @('runPackagedClientGameTest') }
                'Recovery' { $taskGradleArguments = @('-Dlumi.gametest.suite=RECOVERY'); $taskTasks = @('runPackagedClientGameTest') }
                'Multiplayer' { & (Join-Path $PSScriptRoot 'run-multiplayer-gate.ps1') -RunMinecraft @taskTargetArguments *> $taskLog }
                'RestoreCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft @taskTargetArguments -Kind restore -ResumeFrom $taskCrashResume *> $taskLog }
                'RestoreColdCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft @taskTargetArguments -Kind restore -RestoreCold -ResumeFrom $taskCrashResume *> $taskLog }
                'SaveCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft @taskTargetArguments -Kind save -ResumeFrom $taskCrashResume *> $taskLog }
                'MergeCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft @taskTargetArguments -Kind merge -ResumeFrom $taskCrashResume *> $taskLog }
                'ImportCrash' { & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft @taskTargetArguments -Kind import -ResumeFrom $taskCrashResume *> $taskLog }
                'UpgradeCrash' {
                    if (-not $UpgradeFixture) { $taskResult.detail = 'Requires a stopped-world UpgradeFixture.'; continue }
                    & (Join-Path $PSScriptRoot 'run-publication-crash-matrix.ps1') -RunMinecraft @taskTargetArguments -Kind upgrade -UpgradeFixture $UpgradeFixture -ResumeFrom $taskCrashResume *> $taskLog
                }
                'LoadRegression' { $taskTasks = @('regressionPerformanceTest') }
                'RestoreSoak' {
                    if (-not $FixtureManifest -or -not $ExistingWorldSource) {
                        $taskResult.detail = 'Requires FixtureManifest and an immutable ExistingWorldSource.'
                        continue
                    }
                    $taskGradleArguments = @('-Dlumi.gametest.suite=BENCHMARK', '-Dlumi.benchmark.enabled=true',
                        '-Dlumi.benchmark.soak=true', '-Dlumi.benchmark.soakCycles=20', '-Dlumi.benchmark.observeSeconds=1800',
                        ('-Dlumi.benchmark.coldManifest=' + [IO.Path]::GetFullPath($FixtureManifest)),
                        ('-Dlumi.benchmark.existingWorldSource=' + [IO.Path]::GetFullPath($ExistingWorldSource)),
                        ('-Dlumi.benchmark.existingWorld=' + $ExistingWorldName))
                    $taskTasks = @('runPackagedBenchmark')
                }
                'SaveMemory' {
                    $taskGradleArguments = @('-Dlumi.gametest.suite=BENCHMARK', '-Dlumi.benchmark.enabled=true',
                        '-Dlumi.benchmark.memory=true', '-Dlumi.benchmark.memoryOnly=true', '-Dlumi.benchmark.memoryGc=true',
                        '-Dlumi.benchmark.heapDumpSave=benchmark-base', '-Dlumi.benchmark.heap=2G',
                        '-Dlumi.benchmark.baseSize=512', '-Dlumi.benchmark.changeSize=512', '-Dlumi.benchmark.layers=16',
                        '-Dlumi.benchmark.commits=1')
                    $taskTasks = @('runPackagedBenchmark')
                }
            }
            if ($taskTasks.Count -gt 0) {
                $taskGradleArguments = $taskVerification.TaskArguments($taskTasks) + $taskGradleArguments
                # Native stderr is evidence; the Gradle exit code decides success.
                $ErrorActionPreference = 'Continue'
                & (Join-Path $taskRepo 'gradlew.bat') @taskGradleArguments --no-daemon *> $taskLog
                $taskExit = $LASTEXITCODE
                $ErrorActionPreference = 'Stop'
                if ($taskExit -ne 0) { throw "Gradle exited with code $taskExit" }
                if (Test-Path -LiteralPath "$taskBuild/reports/lumi-behavior") {
                    $taskBehavior = Join-Path $taskDirectory 'lumi-behavior'
                    New-Item -ItemType Directory -Path $taskBehavior | Out-Null
                    Get-ChildItem -LiteralPath "$taskBuild/reports/lumi-behavior" -Directory |
                        Where-Object { $_.CreationTimeUtc -ge $taskStarted } |
                        ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $taskBehavior -Recurse }
                }
                foreach ($taskReport in @("$taskBuild/test-results", "$taskBuild/reports/tests")) {
                    if (Test-Path -LiteralPath $taskReport) {
                        Copy-Item -LiteralPath $taskReport -Destination $taskDirectory -Recurse
                    }
                }
                # Production worlds already live in unique directories; retain their evidence paths without copying worlds.
                $taskResult.runtimeEvidence = @(Get-Content -LiteralPath $taskLog | Where-Object { $_ -match 'evidence: ' })
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
        if ($null -ne $taskArtifact -and ($taskReviewed.minecraftTarget.id -cne $Target -or
            $taskReviewed.minecraftTarget.minecraftVersion -cne $taskVerification.Definition.minecraftVersion -or
            $taskReviewed.artifact.sha256 -cne $taskArtifact.sha256)) {
            throw 'Reviewed evidence belongs to a different game or player JAR.'
        }
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
    if (-not (Test-Candidate)) {
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
