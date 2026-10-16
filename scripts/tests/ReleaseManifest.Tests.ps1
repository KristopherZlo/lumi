$ErrorActionPreference = 'Stop'
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskScript = Join-Path $taskRepo 'scripts/prepare-release-manifest.ps1'
$taskRoot = Join-Path $taskRepo ('build/reports/lumi-release-manifest/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskRoot | Out-Null
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskGroups = @($taskCatalog.targets | ForEach-Object {
    $taskName = $_.id + '.jar'
    [IO.File]::WriteAllBytes((Join-Path $taskRoot $taskName), [byte[]]@(1,2,3))
    [ordered]@{ target = $_; artifact = @{ name = $taskName;
        sha256 = (Get-FileHash -LiteralPath (Join-Path $taskRoot $taskName) -Algorithm SHA256).Hash.ToLowerInvariant() } }
})
[ordered]@{ schema = 1; modVersion = '0.3.0'; groups = $taskGroups } | ConvertTo-Json -Depth 10 |
    Set-Content -LiteralPath (Join-Path $taskRoot 'candidate.json') -Encoding UTF8
$taskOld = @{ version = '0.2.0'; loader = 'fabric'; minecraftVersions = @('1.20.1'); summary = ('Last release ' + [char]0x0410 + [char]0x0411);
    downloadUrl = 'https://github.com/KristopherZlo/lumi/releases/download/v0.2.0/old.jar' }
$taskPrevious = Join-Path $taskRoot 'previous.json'
[IO.File]::WriteAllText($taskPrevious, (@{ schema = 1; modId = 'lumi'; versions = @($taskOld) } | ConvertTo-Json -Depth 8))
$taskOutput = Join-Path $taskRoot 'prepared'
& $taskScript -CandidateDirectory $taskRoot -Summary 'Grouped release' -OutputDirectory $taskOutput -PreviousManifest $taskPrevious
$taskManifest = Get-Content -LiteralPath (Join-Path $taskOutput 'lumi-fabric.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($taskTarget in $taskCatalog.targets) {
    foreach ($taskGame in $taskTarget.minecraftVersions) {
        $taskEntry = @($taskManifest.versions | Where-Object { $_.version -ceq '0.3.0' -and $taskGame -cin $_.minecraftVersions })
        if ($taskEntry.Count -ne 1 -or -not $taskEntry[0].downloadUrl.EndsWith($taskTarget.id + '.jar')) {
            throw "Wrong grouped update route for $taskGame"
        }
    }
}
if (@($taskManifest.versions | Where-Object { '1.20.1' -cin $_.minecraftVersions }).Count -ne 1 -or
    $taskManifest.versions[-1].downloadUrl -cne $taskOld.downloadUrl -or
    $taskManifest.versions[-1].summary -cne $taskOld.summary) { throw 'Retired-game release was lost or changed.' }
try {
    & $taskScript -CandidateDirectory $taskRoot -Summary 'Update' -OutputDirectory (Join-Path $taskRoot 'overwrite') `
        -PreviousManifest (Join-Path $taskOutput 'lumi-fabric.json')
    throw 'Existing release version was accepted'
} catch { if ($_.Exception.Message -notlike '*must not be replaced*') { throw } }
$taskOld.downloadUrl = 'https://example.com/old.jar'
@{ schema = 1; modId = 'lumi'; versions = @($taskOld) } | ConvertTo-Json -Depth 8 |
    Set-Content -LiteralPath $taskPrevious -Encoding UTF8
try {
    & $taskScript -CandidateDirectory $taskRoot -Summary 'Update' -OutputDirectory (Join-Path $taskRoot 'untrusted') -PreviousManifest $taskPrevious
    throw 'Untrusted previous URL was accepted'
} catch { if ($_.Exception.Message -notlike 'Untrusted historical update URL*') { throw } }
[IO.File]::WriteAllBytes((Join-Path $taskRoot $taskGroups[0].artifact.name), [byte[]]@(9))
try {
    & $taskScript -CandidateDirectory $taskRoot -Summary 'Update' -OutputDirectory (Join-Path $taskRoot 'tampered')
    throw 'Tampered candidate was accepted'
} catch { if ($_.Exception.Message -notlike 'Candidate changed:*') { throw } }
Write-Output "Grouped update routes, retained EOL entries, immutable versions and artifact hashes passed: $taskRoot"
