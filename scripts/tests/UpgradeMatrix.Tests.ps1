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
Assert-Rejected { & $taskScript -RunMinecraft @taskArguments } 'Candidate changed'
if (Test-Path -LiteralPath $taskArguments.OutputDirectory) { throw 'Invalid inputs created a matrix output.' }
New-Item -ItemType Directory -Path $taskArguments.OutputDirectory | Out-Null
Assert-Rejected { & $taskScript -RunMinecraft @taskArguments } 'Upgrade output must be unused'
Write-Output "Upgrade matrix input guards passed: $taskDirectory"
