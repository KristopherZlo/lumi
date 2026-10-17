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

foreach ($kind in @('save', 'import', 'merge', 'restore', 'upgrade')) {
    $matrix = [LumiCrashMatrixDefinition]::new($kind)
    $counts = @{ save = 12; import = 9; merge = 10; restore = 8; upgrade = 8 }
    Assert-That ($matrix.Phases.Count -eq $counts[$kind]) "Wrong phase count: $kind"
    foreach ($phase in $matrix.Phases) {
        foreach ($mode in @('produce', 'verify')) {
            $arguments = $matrix.Arguments($phase, $mode, 'marker', $false)
            if ($kind -eq 'upgrade') {
                if ($mode -eq 'produce') {
                    Assert-That ($arguments -contains "-Dlumi.gametest.upgrade.cut=$phase") 'Upgrade cut missing.'
                    Assert-That ($arguments -contains '-Dlumi.gametest.upgrade.stage=startup') 'Startup stage missing.'
                } else {
                    Assert-That ($arguments -contains "-Dlumi.gametest.upgrade.expectedCut=$phase") 'Restart identity missing.'
                    Assert-That ($arguments -contains '-Dlumi.gametest.upgrade.stage=resume') 'Resume stage missing.'
                    Assert-That (@($arguments | Where-Object { $_ -match '^-Dlumi.gametest.upgrade.(cut|marker)=' }).Count -eq 0) 'Upgrade verifier rearmed the cut.'
                }
                continue
            }
            $prefix = if ($kind -eq 'restore') { 'lumi.gametest.restoreCrash' } else { 'lumi.persistence.' }
            Assert-That ($arguments -contains "-D${prefix}$(if ($kind -eq 'restore') { 'Mode' } else { 'mode' })=$mode") 'Mode missing.'
            if ($kind -eq 'restore' -and $mode -eq 'verify') {
                Assert-That ($arguments -contains "-Dlumi.gametest.restoreCrashExpectedPhase=$phase") 'Expected phase missing.'
                Assert-That (@($arguments | Where-Object { $_ -match '^-Dlumi.gametest.restoreCrash(Phase|Marker)=' }).Count -eq 0) 'Verifier rearmed the crash injection.'
            } else {
                Assert-That ($arguments -contains "-D${prefix}$(if ($kind -eq 'restore') { 'Phase' } else { 'phase' })=$phase") 'Phase missing.'
            }
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
    [IO.File]::WriteAllText($scratch, 'ALLOCATED')
    $upgrade = [LumiCrashMatrixDefinition]::new('upgrade')
    $upgrade.AssertMarker($scratch, 'ALLOCATED')
    Assert-Rejected { $upgrade.AssertMarker($scratch, 'VERIFIED') }
    $hash = 'a' * 64
    [IO.File]::WriteAllText($scratch, "PERSISTENCE_CRASH`n$hash")
    [LumiCrashMatrixEvidence]::AssertClientCompletion($scratch, $save.Suite(), $hash)
    Assert-Rejected { [LumiCrashMatrixEvidence]::AssertClientCompletion($scratch, $restore.Suite(), $hash) }
    Assert-Rejected { [LumiCrashMatrixEvidence]::AssertClientCompletion($scratch, $save.Suite(), ('b' * 64)) }
    [IO.File]::WriteAllText($scratch, "PERSISTENCE_CRASH`n$hash`n")
    Assert-Rejected { [LumiCrashMatrixEvidence]::AssertClientCompletion($scratch, $save.Suite(), $hash) }
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
    Assert-Rejected { $evidence.Record($scratch, 'ref-forced', @{
        produce = @{ exitCode = 86 }; verify = @{ exitCode = 0; artifactSha256 = ('a' * 64) }
    }) }
    $manifest = [ordered]@{
        schema = 2; gitSha = 'candidate'; dirty = $false; inputFingerprint = 'inputs'
        kind = 'save'; restoreCold = $false; status = 'failed'; completed = @('ref-forced')
        results = @{ 'ref-forced' = $proof }
    }
    $manifestPath = Join-Path $scratchRoot ([Guid]::NewGuid().ToString('N') + '.json')
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $resumed = $evidence.Resume(@($manifestPath), @('ref-forced', 'cleanup'), $false)
    $documentationCommit = [LumiCrashMatrixEvidence]::new('inputs', 'documentation-only-new-commit', $false, $save)
    Assert-That ($documentationCommit.Resume(@($manifestPath), @('ref-forced'), $false).ContainsKey('ref-forced')) 'Git provenance invalidated identical crash inputs.'
    Assert-That ($resumed.Count -eq 1 -and $resumed.ContainsKey('ref-forced')) 'Partial run was not resumed exactly.'
    Assert-Rejected { $evidence.Resume(@($manifestPath), @('ref-forced'), $true) }
    Assert-Rejected { ([LumiCrashMatrixEvidence]::new('other-inputs', 'candidate', $false, $save)).Resume(@($manifestPath), @('ref-forced'), $false) }
    Assert-Rejected { ([LumiCrashMatrixEvidence]::new('inputs', 'candidate', $true, $save)).Resume(@($manifestPath), @('ref-forced'), $false) }
    [IO.File]::AppendAllText($verifyLog, 'changed')
    Assert-Rejected { $evidence.Resume(@($manifestPath), @('ref-forced'), $false) }
    [IO.File]::WriteAllText((Join-Path $scratch 'crash.marker'), 'ALLOCATED')
    [IO.File]::WriteAllText($verifyLog, $upgrade.Gate('ALLOCATED'))
    $upgradeEvidence = [LumiCrashMatrixEvidence]::new('inputs', 'candidate', $false, $upgrade)
    Assert-Rejected { $upgradeEvidence.Record($scratch, 'ALLOCATED', $processes) }
    foreach ($name in @('producer-run', 'source-a', 'source-b', 'crashed-target')) {
        New-Item -ItemType Directory -Path (Join-Path $scratch $name) | Out-Null
    }
    [IO.File]::WriteAllText((Join-Path $scratch 'fixture.properties'), 'head=source')
    $upgradeProof = $upgradeEvidence.Record($scratch, 'ALLOCATED', $processes)
    $upgradeEvidence.Assert($upgradeProof, 'ALLOCATED')
    [IO.File]::WriteAllText((Join-Path $scratch 'crashed-target/history.bin'), 'changed candidate')
    Assert-Rejected { $upgradeEvidence.Assert($upgradeProof, 'ALLOCATED') }
    [IO.File]::WriteAllText($verifyLog, $save.Gate('ref-forced'))
    [IO.File]::WriteAllText((Join-Path $scratch 'run/world.dat'), 'modified world')
    Assert-Rejected { $evidence.Resume(@($manifestPath), @('ref-forced'), $false) }
    [IO.File]::Delete((Join-Path $scratch 'run/world.dat'))
    Assert-Rejected { $evidence.Record($scratch, 'ref-forced', @{ produce = @{ exitCode = 0 }; verify = @{ exitCode = 0 } }) }
    $manifest.schema = 1
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Assert-Rejected { $evidence.Resume(@($manifestPath), @('ref-forced'), $false) }
    $mod = Join-Path $scratch 'mod.jar'
    [IO.File]::WriteAllText($mod, 'candidate')
    @{ schema = 1; classpath = @(); configuration = @(); mods = @($mod) } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $scratch 'multiplayer-launch.inputs.json') -Encoding UTF8
    [IO.File]::WriteAllText((Join-Path $scratch 'multiplayer-launch.args'), '')
    $originalAudioBackend = $env:ALSOFT_DRIVERS
    try {
        $env:ALSOFT_DRIVERS = 'null'
        $audioFingerprint = [LumiCrashMatrixEvidence]::Inputs($scratch, (Join-Path $scratch 'jdk/bin/java.exe'), $scratch)
        $env:ALSOFT_DRIVERS = 'wasapi'
        Assert-That ([LumiCrashMatrixEvidence]::Inputs($scratch, (Join-Path $scratch 'jdk/bin/java.exe'), $scratch) -cne $audioFingerprint) 'Audio backend changes did not invalidate crash reuse.'
    } finally { $env:ALSOFT_DRIVERS = $originalAudioBackend }
    $fingerprint = [LumiCrashMatrixEvidence]::Inputs($scratch, (Join-Path $scratch 'jdk/bin/java.exe'), $scratch)
    [IO.File]::AppendAllText($mod, 'modified')
    Assert-That ([LumiCrashMatrixEvidence]::Inputs($scratch, (Join-Path $scratch 'jdk/bin/java.exe'), $scratch) -cne $fingerprint) 'Production mod changes did not invalidate launch inputs.'
    $fixture = Join-Path $scratch 'upgrade-world'
    New-Item -ItemType Directory -Path $fixture | Out-Null
    [IO.File]::WriteAllText((Join-Path $fixture 'level.dat'), 'old world')
    $fingerprint = [LumiCrashMatrixEvidence]::Inputs($scratch, (Join-Path $scratch 'jdk/bin/java.exe'), $scratch, $fixture)
    [IO.File]::WriteAllText((Join-Path $fixture 'level.dat'), 'another world')
    Assert-That ([LumiCrashMatrixEvidence]::Inputs($scratch, (Join-Path $scratch 'jdk/bin/java.exe'), $scratch, $fixture) -cne $fingerprint) 'Upgrade fixture changes did not invalidate crash reuse.'
    $fingerprint = [LumiCrashMatrixEvidence]::Inputs($scratch, (Join-Path $scratch 'jdk/bin/java.exe'), $scratch)
    New-Item -ItemType Directory -Path (Join-Path $scratch 'scripts') | Out-Null
    [IO.File]::WriteAllText((Join-Path $scratch 'scripts/run-publication-crash-matrix.ps1'), 'changed runner')
    Assert-That ([LumiCrashMatrixEvidence]::Inputs($scratch, (Join-Path $scratch 'jdk/bin/java.exe'), $scratch) -cne $fingerprint) 'Crash runner changes did not invalidate reuse.'
} finally {
    if ($manifestPath) { [IO.File]::Delete($manifestPath) }
    if (-not [IO.Path]::GetFullPath($scratch).StartsWith($scratchRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Scratch cleanup escaped the temporary directory.'
    }
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
Write-Output 'Crash matrix evidence checks passed.'
