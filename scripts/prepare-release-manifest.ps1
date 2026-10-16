param(
    [Parameter(Mandatory)][string]$CandidateDirectory,
    [Parameter(Mandatory)][ValidateLength(1,4096)][string]$Summary,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$PreviousManifest
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Summary)) { throw 'A release summary is required.' }
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskCandidateDirectory = (Resolve-Path -LiteralPath $CandidateDirectory).Path
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $taskOutput) { throw "Release output must be unused: $taskOutput" }
$taskCandidate = Get-Content -LiteralPath (Join-Path $taskCandidateDirectory 'candidate.json') -Raw | ConvertFrom-Json
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskVersionPattern = '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?(?:\+[A-Za-z0-9.-]+)?$'
if ($taskCandidate.schema -ne 1 -or $taskCandidate.groups.Count -ne $taskCatalog.targets.Count -or
    $taskCandidate.modVersion -cnotmatch $taskVersionPattern -or
    $taskCandidate.modVersion.Length -gt 32) { throw 'Invalid grouped candidate.' }
$taskVersions = @()
if ($PreviousManifest) {
    $taskPrevious = Get-Content -LiteralPath $PreviousManifest -Raw | ConvertFrom-Json
    if ($taskPrevious.schema -ne 1 -or $taskPrevious.modId -cne 'lumi' -or
        $taskPrevious.versions -isnot [Array]) { throw 'Invalid previous update manifest.' }
    foreach ($taskEntry in $taskPrevious.versions) {
        if ($taskEntry.loader -cne 'fabric' -or $taskEntry.version -cnotmatch $taskVersionPattern -or
            $taskEntry.version.Length -gt 32 -or [string]::IsNullOrWhiteSpace($taskEntry.summary) -or
            $taskEntry.summary.Length -gt 4096 -or $taskEntry.minecraftVersions -isnot [Array] -or
            @($taskEntry.minecraftVersions).Count -gt 32 -or
            @($taskEntry.minecraftVersions | Where-Object { $_ -isnot [string] -or $_.Length -gt 32 }).Count) { throw 'Invalid historical update entry.' }
        $taskUrls = @($taskEntry.downloadUrl)
        if ('changelogUrl' -cin $taskEntry.PSObject.Properties.Name) { $taskUrls += $taskEntry.changelogUrl }
        foreach ($taskUrl in $taskUrls) {
            $taskUri = [Uri]$taskUrl
            if ([string]::IsNullOrWhiteSpace($taskUrl) -or $taskUrl.Length -gt 512 -or
                -not $taskUri.IsAbsoluteUri -or $taskUri.Scheme -cne 'https' -or $taskUri.Host -ine 'github.com') {
                throw 'Untrusted historical update URL.'
            }
        }
    }
    if ($taskCandidate.modVersion.Split('+')[0] -cin @($taskPrevious.versions | ForEach-Object { $_.version.Split('+')[0] })) {
        throw 'A published Lumi version must not be replaced; increment the version.'
    }
    $taskVersions = @($taskPrevious.versions)
}
$taskTag = 'v' + $taskCandidate.modVersion
$taskPage = 'https://github.com/KristopherZlo/lumi/releases/tag/' + [Uri]::EscapeDataString($taskTag)
$taskAssets = @()
foreach ($taskTarget in $taskCatalog.targets) {
    $taskGroup = @($taskCandidate.groups | Where-Object { $_.target.id -ceq $taskTarget.id })
    if ($taskGroup.Count -ne 1 -or (@($taskGroup[0].target.minecraftVersions) -join ',') -cne
        (@($taskTarget.minecraftVersions) -join ',')) { throw "Wrong release group: $($taskTarget.id)" }
    $taskName = [string]$taskGroup[0].artifact.name
    if ([IO.Path]::GetFileName($taskName) -cne $taskName -or -not $taskName.EndsWith('.jar')) { throw 'Invalid asset basename.' }
    $taskHash = (Get-FileHash -LiteralPath (Join-Path $taskCandidateDirectory $taskName) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($taskHash -cne $taskGroup[0].artifact.sha256) { throw "Candidate changed: $taskName" }
    $taskDownload = 'https://github.com/KristopherZlo/lumi/releases/download/' +
        [Uri]::EscapeDataString($taskTag) + '/' + [Uri]::EscapeDataString($taskName)
    $taskVersions = @([ordered]@{ version = $taskCandidate.modVersion; loader = 'fabric';
        minecraftVersions = @($taskTarget.minecraftVersions); summary = $Summary;
        downloadUrl = $taskDownload; changelogUrl = $taskPage }) + $taskVersions
    $taskAssets += [ordered]@{ group = $taskTarget.id; minecraftVersions = @($taskTarget.minecraftVersions);
        name = $taskName; sha256 = $taskHash; downloadUrl = $taskDownload }
}
if ($taskVersions.Count -gt 128) { throw 'Update manifest exceeds 128 releases; review retained entries explicitly.' }
$taskJson = [ordered]@{ schema = 1; modId = 'lumi'; versions = $taskVersions } | ConvertTo-Json -Depth 12
if ([Text.Encoding]::UTF8.GetByteCount($taskJson) -gt 65536) { throw 'Update manifest exceeds 64 KiB.' }
New-Item -ItemType Directory -Path $taskOutput | Out-Null
[IO.File]::WriteAllText((Join-Path $taskOutput 'lumi-fabric.json'), $taskJson + "`n")
[ordered]@{ schema = 1; status = 'prepared'; modVersion = $taskCandidate.modVersion; tag = $taskTag;
    assets = $taskAssets; published = $false } | ConvertTo-Json -Depth 10 |
    Set-Content -LiteralPath (Join-Path $taskOutput 'release.json') -Encoding UTF8
Write-Output "Prepared $($taskAssets.Count) grouped assets and retained $($taskVersions.Count - $taskAssets.Count) historical update entries: $taskOutput"
