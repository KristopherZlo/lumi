. (Join-Path $PSScriptRoot '../CrashMatrixDefinition.ps1')

$ErrorActionPreference = 'Stop'
function Assert-That([bool] $condition, [string] $message) {
    if (-not $condition) { throw $message }
}
function Assert-Rejected([scriptblock] $action) {
    $rejected = $false
    try { & $action } catch { $rejected = $true }
    Assert-That $rejected 'Invalid matrix input was accepted.'
}

foreach ($kind in @('save', 'import', 'merge', 'restore')) {
    $matrix = [LumiCrashMatrixDefinition]::new($kind)
    $counts = @{ save = 12; import = 9; merge = 10; restore = 8 }
    Assert-That ($matrix.Phases.Count -eq $counts[$kind]) "Wrong phase count: $kind"
    foreach ($phase in $matrix.Phases) {
        foreach ($mode in @('produce', 'verify')) {
            $arguments = $matrix.Arguments($phase, $mode, 'marker', $false)
            $prefix = if ($kind -eq 'restore') { 'lumi.gametest.restoreCrash' } else { 'lumi.persistence.' }
            Assert-That ($arguments -contains "-D${prefix}$(if ($kind -eq 'restore') { 'Mode' } else { 'mode' })=$mode") 'Mode missing.'
            Assert-That ($arguments -contains "-D${prefix}$(if ($kind -eq 'restore') { 'Phase' } else { 'phase' })=$phase") 'Phase missing.'
        }
        Assert-That ($matrix.Gate($phase).EndsWith(': succeeded')) 'Verifier gate missing.'
    }
    Assert-Rejected { $matrix.Arguments('wrong', 'produce', 'marker', $false) }
    Assert-Rejected { $matrix.Arguments($matrix.Phases[0], 'wrong', 'marker', $false) }
    if ($kind -ne 'restore') { Assert-Rejected { $matrix.Arguments($matrix.Phases[0], 'produce', 'marker', $true) } }
}

$restore = [LumiCrashMatrixDefinition]::new('restore')
$cold = $restore.Arguments('write', 'verify', 'marker', $true)
Assert-That ($cold -contains '-Dlumi.gametest.restoreCrashExpectedPhase=write') 'Expected phase missing.'
Assert-That ($cold -contains '-Dlumi.gametest.restoreCrashCold=true') 'Cold coverage missing.'
$scratch = Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid().ToString('N') + '.marker')
try {
    [IO.File]::WriteAllText($scratch, 'write')
    $restore.AssertMarker($scratch, 'write')
    Assert-Rejected { $restore.AssertMarker($scratch, 'verify') }
    $save = [LumiCrashMatrixDefinition]::new('save')
    [IO.File]::WriteAllLines($scratch, @('#timestamp', 'kind=save', 'phase=ref-forced'))
    $save.AssertMarker($scratch, 'ref-forced')
    Assert-Rejected { $save.AssertMarker($scratch, 'ref') }
    Assert-Rejected { ([LumiCrashMatrixDefinition]::new('import')).AssertMarker($scratch, 'ref-forced') }
} finally { [IO.File]::Delete($scratch) }
Write-Output 'Crash matrix definition checks passed.'

$scratchRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$scratch = Join-Path $scratchRoot ('lumi-crash-evidence-' + [Guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path (Join-Path $scratch 'run') -Force | Out-Null
    foreach ($name in @('produce.args', 'verify.args', 'produce.stdout.log', 'produce.stderr.log', 'verify.stderr.log')) {
        [IO.File]::WriteAllText((Join-Path $scratch $name), '')
    }
    $verifyLog = Join-Path $scratch 'verify.stdout.log'
    [IO.File]::WriteAllText($verifyLog, $save.Gate('ref-forced'))
    [IO.File]::WriteAllLines((Join-Path $scratch 'crash.marker'), @('kind=save', 'phase=ref-forced'))
    $evidence = [LumiCrashMatrixEvidence]::new('inputs', 'candidate', $false, $save)
    $processes = @{ produce = @{ exitCode = 86 }; verify = @{ exitCode = 0 } }
    $proof = $evidence.Record($scratch, 'ref-forced', $processes)
    $manifest = [ordered]@{
        schema = 2; gitSha = 'candidate'; dirty = $false; inputFingerprint = 'inputs'
        kind = 'save'; restoreCold = $false; status = 'failed'; completed = @('ref-forced')
        results = @{ 'ref-forced' = $proof }
    }
    $manifestPath = Join-Path $scratchRoot ([Guid]::NewGuid().ToString('N') + '.json')
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $resumed = $evidence.Resume(@($manifestPath), @('ref-forced', 'cleanup'), $false)
    Assert-That ($resumed.Count -eq 1 -and $resumed.ContainsKey('ref-forced')) 'Partial run was not resumed exactly.'
    Assert-Rejected { $evidence.Resume(@($manifestPath), @('ref-forced'), $true) }
    Assert-Rejected { ([LumiCrashMatrixEvidence]::new('other-inputs', 'candidate', $false, $save)).Resume(@($manifestPath), @('ref-forced'), $false) }
    Assert-Rejected { ([LumiCrashMatrixEvidence]::new('inputs', 'other-candidate', $false, $save)).Resume(@($manifestPath), @('ref-forced'), $false) }
    Assert-Rejected { ([LumiCrashMatrixEvidence]::new('inputs', 'candidate', $true, $save)).Resume(@($manifestPath), @('ref-forced'), $false) }
    [IO.File]::AppendAllText($verifyLog, 'changed')
    Assert-Rejected { $evidence.Resume(@($manifestPath), @('ref-forced'), $false) }
    [IO.File]::WriteAllText($verifyLog, $save.Gate('ref-forced'))
    [IO.File]::WriteAllText((Join-Path $scratch 'run/world.dat'), 'modified world')
    Assert-Rejected { $evidence.Resume(@($manifestPath), @('ref-forced'), $false) }
    [IO.File]::Delete((Join-Path $scratch 'run/world.dat'))
    Assert-Rejected { $evidence.Record($scratch, 'ref-forced', @{ produce = @{ exitCode = 0 }; verify = @{ exitCode = 0 } }) }
    $manifest.schema = 1
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Assert-Rejected { $evidence.Resume(@($manifestPath), @('ref-forced'), $false) }
} finally {
    if ($manifestPath) { [IO.File]::Delete($manifestPath) }
    if (-not [IO.Path]::GetFullPath($scratch).StartsWith($scratchRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Scratch cleanup escaped the temporary directory.'
    }
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
Write-Output 'Crash matrix evidence checks passed.'
