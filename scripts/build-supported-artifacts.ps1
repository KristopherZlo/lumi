param(
    [switch]$RunBuild,
    [string]$OutputDirectory,
    [string[]]$LegacyGradleArguments = @()
)
$ErrorActionPreference = 'Stop'
if (-not $RunBuild) { throw 'Pass -RunBuild to build the supported artifact groups.' }
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $taskRepo ('build/distributions/lumi-candidate-' + [Guid]::NewGuid().ToString('N'))
}
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskOutput) { throw "Candidate output must be unused: $taskOutput" }
$taskReports = Join-Path $taskOutput '.reports'
New-Item -ItemType Directory -Path $taskReports | Out-Null
Push-Location $taskRepo
try {
    foreach ($taskTarget in @($taskCatalog.targets | Where-Object { $_.javaVersion -eq 21 })) {
        $taskArguments = @($LegacyGradleArguments) + @(':writeMinecraftBuildManifest',
            "-PlumiTarget=$($taskTarget.id)", "-PlumiBuildManifestDirectory=$taskReports", '--console=plain')
        $ErrorActionPreference = 'Continue'
        & (Join-Path $taskRepo 'gradlew.bat') @taskArguments
        $taskExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($taskExit -ne 0) { throw "Build failed: $($taskTarget.id)" }
    }
    $taskNative = @($taskCatalog.targets | Where-Object { $_.javaVersion -gt 21 })
    if ($taskNative.Count -gt 0) {
        $taskArguments = @('-p', 'modern') + @($taskNative | ForEach-Object {
            ":adapters:$($_.id):writeMinecraftBuildManifest"
        }) + @("-PlumiBuildManifestDirectory=$taskReports", '--console=plain')
        $ErrorActionPreference = 'Continue'
        & (Join-Path $taskRepo 'gradlew.bat') @taskArguments
        $taskExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($taskExit -ne 0) { throw 'Native artifact build failed.' }
    }
    $taskGroups = @()
    $taskVersion = $null
    $taskNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($taskTarget in $taskCatalog.targets) {
        $taskReport = Get-Content -LiteralPath (Join-Path $taskReports ($taskTarget.id + '.json')) -Raw | ConvertFrom-Json
        if ($taskReport.schema -ne 1 -or $taskReport.target.id -cne $taskTarget.id -or
            (@($taskReport.target.minecraftVersions) -join ',') -cne (@($taskTarget.minecraftVersions) -join ',')) {
            throw "Wrong build manifest: $($taskTarget.id)"
        }
        if ($null -eq $taskVersion) { $taskVersion = $taskReport.modVersion }
        if ($taskVersion -cne $taskReport.modVersion) { throw 'Group artifacts have different Lumi versions.' }
        $taskArtifact = [string]$taskReport.artifact.path
        $taskName = [string]$taskReport.artifact.name
        if ([IO.Path]::GetFileName($taskName) -cne $taskName -or -not $taskName.EndsWith('.jar') -or
            -not $taskNames.Add($taskName)) { throw 'Artifact filenames must be unique JAR basenames.' }
        $taskHash = (Get-FileHash -LiteralPath $taskArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($taskHash -cne $taskReport.artifact.sha256) { throw "Artifact changed after verification: $taskArtifact" }
        $taskDestination = Join-Path $taskOutput $taskName
        Copy-Item -LiteralPath $taskArtifact -Destination $taskDestination
        if ((Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $taskHash) {
            throw "Candidate copy changed: $taskDestination"
        }
        $taskGroups += [ordered]@{ target = $taskReport.target; artifact = [ordered]@{ name = $taskName; sha256 = $taskHash } }
    }
    [ordered]@{ schema = 1; status = 'candidate'; modVersion = $taskVersion; groups = $taskGroups } |
        ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskOutput 'candidate.json') -Encoding UTF8
    Write-Output "Built $($taskGroups.Count) group JARs; candidate: $taskOutput"
} finally {
    Pop-Location
}
