$ErrorActionPreference = 'Stop'
$taskScript = Join-Path $PSScriptRoot '../run-cold-restore-benchmark.ps1'
$taskRoot = Join-Path ([IO.Path]::GetTempPath()) ('lumi-cold-guard-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskRoot | Out-Null
$taskJar = Join-Path $taskRoot 'candidate.jar'
$taskSentinel = Join-Path $taskRoot 'keep.txt'
[IO.File]::WriteAllText($taskJar, 'guard-only')
[IO.File]::WriteAllText($taskSentinel, 'prior benchmark evidence')
function Assert-Rejected([scriptblock]$Action, [string]$Expected) {
    try { & $Action } catch {
        if ($_.Exception.Message -notlike "*$Expected*") { throw }
        return
    }
    throw 'Unsafe benchmark input was accepted.'
}
try {
    Assert-Rejected { & $taskScript -Artifact $taskJar } 'Pass -RunMinecraft'
    Assert-Rejected { & $taskScript -RunMinecraft -Artifact $taskJar -Target modern-261 -GameVersion 26.3 } 'outside target'
    Assert-Rejected { & $taskScript -RunMinecraft -Artifact $taskJar -OutputDirectory $taskRoot } 'must be unused'
    if ([IO.File]::ReadAllText($taskSentinel) -cne 'prior benchmark evidence') { throw 'Existing evidence was modified.' }
    Write-Output 'Cold benchmark input and evidence preservation checks passed.'
} finally {
    Remove-Item -LiteralPath $taskJar, $taskSentinel
    Remove-Item -LiteralPath $taskRoot
}
