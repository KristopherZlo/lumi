$ErrorActionPreference = 'Stop'
$taskScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../run-upgrade-matrix.ps1'))
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskDirectory = Join-Path $taskRepo ('build/reports/lumi-upgrade-inputs/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskDirectory | Out-Null
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskGroups = @($taskCatalog.targets | ForEach-Object {
    [ordered]@{ target = $_; artifact = @{ name = $_.id + '.jar'; sha256 = '0' * 64 } }
})
[ordered]@{ schema = 1; status = 'candidate'; modVersion = '0.2.0'; groups = $taskGroups } |
    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskDirectory 'candidate.json') -Encoding UTF8
[IO.File]::WriteAllBytes((Join-Path $taskDirectory $taskGroups[0].artifact.name), [byte[]]@(1))
foreach ($taskGame in @('1.21.11', '26.2')) {
    $taskSource = Join-Path $taskDirectory $taskGame
    New-Item -ItemType Directory -Path $taskSource | Out-Null
    [IO.File]::WriteAllText((Join-Path $taskSource 'fixture.properties'), 'input-guard-only')
}
function Assert-Rejected([scriptblock]$action, [string]$message) {
    try { & $action } catch {
        if ($_.Exception.Message.Contains($message)) { return }
        throw
    }
    throw "Upgrade input was accepted: $message"
}
$taskArguments = @{ CandidateDirectory = $taskDirectory; SourceDirectory = $taskDirectory;
    OutputDirectory = Join-Path $taskDirectory 'matrix' }
Assert-Rejected { & $taskScript @taskArguments } 'Pass -RunMinecraft'
Assert-Rejected { & $taskScript -RunMinecraft @taskArguments -TargetGame '99.0' } 'Unknown upgrade target'
Assert-Rejected { & $taskScript -RunMinecraft @taskArguments -SourceGame '99.0' } 'Unknown upgrade source'
Assert-Rejected { & $taskScript -RunMinecraft @taskArguments -SourceGame '26.2' -TargetGame '26.1' } 'No forward upgrade route'
Assert-Rejected { & $taskScript -RunMinecraft @taskArguments } 'Candidate changed'
# Retiring a runtime must not remove its stopped-world conversion fixture.
$taskRetiredRepo = Join-Path $taskDirectory 'retired-repo'
New-Item -ItemType Directory -Path (Join-Path $taskRetiredRepo 'scripts'), (Join-Path $taskRetiredRepo 'gradle') | Out-Null
Copy-Item -LiteralPath $taskScript, (Join-Path $PSScriptRoot '../MinecraftVerificationTarget.ps1') -Destination (Join-Path $taskRetiredRepo 'scripts')
$taskRetiredCatalog = $taskCatalog | ConvertTo-Json -Depth 10 | ConvertFrom-Json
$taskRetiredCatalog.targets = @($taskRetiredCatalog.targets | Where-Object { $_.id -ceq 'modern-263' })
$taskRetiredCatalog.defaultTarget = 'modern-263'
$taskRetiredCatalog | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskRetiredRepo 'gradle/minecraft-targets.json') -Encoding UTF8
$taskRetiredCandidate = Join-Path $taskDirectory 'retired-candidate'
New-Item -ItemType Directory -Path $taskRetiredCandidate | Out-Null
[ordered]@{ schema = 1; groups = @($taskGroups | Where-Object { $_.target.id -ceq 'modern-263' }) } |
    ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskRetiredCandidate 'candidate.json') -Encoding UTF8
[IO.File]::WriteAllBytes((Join-Path $taskRetiredCandidate 'modern-263.jar'), [byte[]]@(1))
Assert-Rejected { & (Join-Path $taskRetiredRepo 'scripts/run-upgrade-matrix.ps1') -RunMinecraft `
    -CandidateDirectory $taskRetiredCandidate -SourceDirectory $taskDirectory -OutputDirectory (Join-Path $taskRetiredCandidate 'matrix') `
    -SourceGame 26.2 -TargetGame 26.3 } 'Candidate changed'
if (Test-Path -LiteralPath $taskArguments.OutputDirectory) { throw 'Invalid inputs created a matrix output.' }
New-Item -ItemType Directory -Path $taskArguments.OutputDirectory | Out-Null
Assert-Rejected { & $taskScript -RunMinecraft @taskArguments } 'Upgrade output must be unused'
$taskChainScript = Join-Path $PSScriptRoot '../run-upgrade-chain.ps1'
Assert-Rejected { & $taskChainScript @taskArguments } 'Pass -RunMinecraft'
Assert-Rejected { & $taskChainScript -RunMinecraft @taskArguments } 'Upgrade chain output must be unused'
Write-Output "Upgrade matrix input guards passed: $taskDirectory"
