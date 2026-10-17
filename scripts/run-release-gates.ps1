param(
    [switch]$RunTests,
    [switch]$Plan,
    [switch]$RequireReleaseReady,
    [ValidateSet('Development', 'Adapter', 'Candidate', 'Long')]
    [string]$Profile = 'Candidate',
    [ValidateSet('Compile', 'Core', 'Server', 'Smoke', 'Render', 'Tools', 'UI', 'Interaction',
        'Client', 'Exact', 'Recovery', 'Multiplayer', 'RestoreCrash', 'RestoreColdCrash',
        'SaveCrash', 'MergeCrash', 'ImportCrash', 'UpgradeCrash', 'LoadRegression', 'RestoreSoak', 'SaveMemory',
        'ClientVulkan', 'NativeViewport')]
    [string[]]$Gate = @(),
    [string]$Target = 'legacy-12111',
    [string]$GameVersion,
    [string]$Artifact,
    [ValidateSet('none', 'worldedit', 'axiom', 'both')]
    [string]$BuilderTools = 'both',
    [string]$ServerFilter = '*',
    [string[]]$ReuseFrom = @(),
    [string]$UpgradeFixture,
    [string]$FixtureManifest,
    [string]$ExistingWorldSource,
    [string]$ExistingWorldName = 'Lumi behavior seed 710',
    [string]$ReviewedEvidence,
    [hashtable]$CrashResumeFrom = @{}
)

$ErrorActionPreference = 'Stop'
if (-not $RunTests -and -not $Plan) {
    throw 'No tests started. Release gates can repeatedly open Minecraft windows and impose sustained CPU/GPU/memory load. Pass -RunTests only when the computer is available for testing.'
}
foreach ($taskResumeGate in $CrashResumeFrom.Keys) {
    if ($taskResumeGate -notin @('RestoreCrash', 'RestoreColdCrash', 'SaveCrash', 'MergeCrash', 'ImportCrash', 'UpgradeCrash')) {
        throw "Unknown crash gate for resumption: $taskResumeGate"
    }
}
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'MinecraftVerificationTarget.ps1')
. (Join-Path $PSScriptRoot 'VerificationPlan.ps1')
. (Join-Path $PSScriptRoot 'CrashMatrixDefinition.ps1')
. (Join-Path $PSScriptRoot 'VerificationEvidence.ps1')
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskVerification = [LumiMinecraftVerificationTarget]::new($taskCatalog, $Target, $GameVersion, $Artifact)
$taskPlan = [LumiVerificationPlan]::new($Profile, $Gate)
$Gate = $taskPlan.Gates
if ($Gate -contains 'Tools' -and $BuilderTools -eq 'none') { throw 'Tools requires a builder tool.' }
if ($Plan) {
    [ordered]@{ profile = $Profile; target = $taskVerification.Definition
        selected = $taskPlan.Describe($taskVerification); builderTools = $BuilderTools
        serverFilter = $ServerFilter; reuseFrom = $ReuseFrom; releaseReady = $false } | ConvertTo-Json -Depth 8
    return
}
$taskTargetArguments = @{ Target = $Target; GameVersion = $taskVerification.Definition.minecraftVersion }
if ($taskVerification.Artifact) { $taskTargetArguments.Artifact = $taskVerification.Artifact }
$taskBuild = if ($taskVerification.Modern) { "versions/$Target/build" } else { 'build' }
$taskArtifact = $null
Push-Location $taskRepo
try {
    function Get-CodeChanges { return @(& git status --porcelain | Where-Object { $_ -notmatch '\.md"?$' }) }
    if (Get-CodeChanges) { throw 'Commit code changes before verification. Local Markdown may remain uncommitted.' }
    $taskSha = (& git rev-parse HEAD).Trim()
    $taskRun = Join-Path $taskRepo ('build/reports/lumi-release/' +
        [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $taskRun | Out-Null
    $taskNeedsLaunch = @($Gate | Where-Object { $_ -notin @('Compile', 'Core', 'LoadRegression') }).Count -gt 0
    if ($taskNeedsLaunch -and ($taskVerification.Modern -or $taskVerification.Artifact)) {
        $taskArguments = $taskVerification.PrepareArguments() + "-PlumiBuilderTools=$BuilderTools"
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
        return ((& git rev-parse HEAD).Trim() -ceq $taskSha -and -not (Get-CodeChanges) -and
            ($null -eq $taskArtifact -or ((Test-Path -LiteralPath $taskArtifact.path) -and
            (Get-FileHash -LiteralPath $taskArtifact.path -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $taskArtifact.sha256)))
    }
    $taskEvidenceInputs = $null
    if ($taskArtifact) {
        $taskEvidenceInputs = [ordered]@{
            launch = [LumiCrashMatrixEvidence]::Inputs($taskRepo, $taskLaunch.javaExecutable, (Join-Path $taskRepo $taskBuild))
            target = $taskVerification.Definition; artifact = $taskArtifact.sha256
            builderTools = $BuilderTools; serverFilter = $ServerFilter
            fixtures = [LumiCrashMatrixEvidence]::Inventory(@(@($UpgradeFixture, $FixtureManifest, $ExistingWorldSource) | Where-Object { $_ }))
            existingWorldName = $ExistingWorldName
        }
    } elseif ($ReuseFrom.Count) { throw 'Evidence reuse requires a frozen -Artifact.' }
    $taskRequired = $taskVerification.RequiredReleaseGates()
    $taskResults = [ordered]@{}
    foreach ($taskName in @($taskRequired + $Gate | Select-Object -Unique)) {
        $taskResults[$taskName] = [ordered]@{ status = 'unverified'; artifact = ''; detail = '' }
    }
    $taskManifest = [ordered]@{
        schema = 3; gitSha = $taskSha; startedUtc = [DateTime]::UtcNow.ToString('o')
        profile = $Profile; selected = $Gate; releaseReady = $false
        reusableInputs = $taskEvidenceInputs
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
    function Complete-IncludedGates($result) {
        if ($taskName -notin @('Client', 'ClientVulkan')) { return }
        foreach ($included in @('Exact', 'Recovery')) {
            $taskResults[$included] = [ordered]@{ status = 'reused'; artifact = $result.artifact
                proof = $result.proof; detail = "Assertions completed inside $taskName; no second JVM required." }
        }
    }
    Write-Manifest
    foreach ($taskName in $Gate) {
        if (-not (Test-Candidate)) {
            throw 'The candidate changed during its release run.'
        }
        $taskResult = $taskResults[$taskName]
        if (-not $taskPlan.Applies($taskName, $taskVerification)) {
            $taskResult.status = 'not-applicable'
            $taskResult.detail = 'This gate does not apply to the selected adapter.'
            continue
        }
        $taskEvidence = $null
        if ($taskEvidenceInputs -and [LumiVerificationEvidence]::Reusable($taskName)) {
            $taskEvidence = [LumiVerificationEvidence]::new([ordered]@{ inputs = $taskEvidenceInputs; gate = $taskName })
            $taskReused = $taskEvidence.Find($ReuseFrom, $taskName)
            if ($taskReused) {
                $taskResults[$taskName] = $taskReused
                Complete-IncludedGates $taskReused
                Write-Manifest
                Write-Output "$taskName`: reused"
                continue
            }
        }
        $taskResult.detail = 'No matching intact evidence; selected for execution.'
        $taskDirectory = Join-Path $taskRun $taskName
        New-Item -ItemType Directory -Path $taskDirectory | Out-Null
        $taskLog = Join-Path $taskDirectory 'run.log'
        $taskResult.artifact = $taskDirectory
        $taskStarted = [DateTime]::UtcNow
        try {
            $taskGradleArguments = @()
            $taskTasks = $taskPlan.Tasks($taskName)
            $taskSuite = $taskPlan.Suite($taskName)
            if ($taskSuite) { $taskGradleArguments = @("-Dlumi.gametest.suite=$taskSuite") }
            if ($taskName -eq 'Server') { $taskGradleArguments += "-Dfabric-api.gametest.filter=$ServerFilter" }
            $taskCrashResume = @()
            if ($CrashResumeFrom.ContainsKey($taskName)) { $taskCrashResume = @($CrashResumeFrom[$taskName]) }
            switch ($taskName) {
                'Core' {
                    $taskGradleArguments = @("-PlumiCoreTestJava=$($taskVerification.Definition.javaVersion)")
                }
                'Client' {
                    if ($Target -ceq 'modern-263') { $taskGradleArguments += '-PlumiGraphicsBackend=opengl' }
                }
                'ClientVulkan' {
                    $taskGradleArguments += @('-PlumiGraphicsBackend=vulkan',
                        '-Dlumi.gametest.fixedNativeWindow=true')
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
                $taskGradleArguments = $taskVerification.TaskArguments($taskTasks) + $taskGradleArguments + "-PlumiBuilderTools=$BuilderTools"
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
                if ([LumiVerificationEvidence]::Reusable($taskName)) {
                    $taskRecorder = if ($taskEvidence) { $taskEvidence } else { [LumiVerificationEvidence]::new(@{}) }
                    $taskResult.proof = $taskRecorder.Record($taskDirectory, $taskLog, $taskSuite, $taskArtifact.sha256)
                    if (-not $taskEvidence) { $taskResult.proof.fingerprint = '' }
                }
            }
            $taskResult.status = 'passed'
            $taskResult.detail = 'Command completed; scope is defined by this gate, not the whole release.'
            Complete-IncludedGates $taskResult
        } catch {
            $taskResult.status = 'failed'
            $taskResult.detail = $_.Exception.Message
        } finally {
            Write-Manifest
        }
        Write-Output "$taskName`: $($taskResult.status)"
        if ($taskResult.status -eq 'failed') { break }
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
    } elseif (@($Gate | Where-Object { $taskResults[$_].status -notin @('passed', 'reused', 'not-applicable') }).Count -eq 0) {
        $taskManifest.status = 'passed'
    }
    $taskManifest.releaseReady = $ServerFilter -ceq '*' -and @($taskRequired | Where-Object {
        $taskResults[$_].status -notin @('passed', 'reused')
    }).Count -eq 0
    $taskManifest.finishedUtc = [DateTime]::UtcNow.ToString('o')
    Write-Manifest
    Write-Output "Selected checks: $($taskManifest.status); release ready: $($taskManifest.releaseReady); evidence: $taskRun"
    if ($taskManifest.status -ne 'passed' -or ($RequireReleaseReady -and -not $taskManifest.releaseReady)) {
        throw 'Requested verification has unverified, failed, or invalid gates. See manifest.json.'
    }
} finally {
    Pop-Location
}
