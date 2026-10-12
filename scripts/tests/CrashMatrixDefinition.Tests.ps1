using module ../CrashMatrixDefinition.psm1

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
