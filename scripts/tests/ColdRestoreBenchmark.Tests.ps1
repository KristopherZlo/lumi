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

    # Exercise the existing aggregation without launching Minecraft.
    $taskSource = [IO.File]::ReadAllText($taskScript)
    $taskAggregation = [scriptblock]::Create($taskSource.Substring(
        $taskSource.IndexOf('$violations = [Collections.Generic.List[string]]::new()')))
    $state = $taskRoot
    $fixtureDigest = 'unchanged-fixture'
    $Samples = 5
    $BaseSize = 512
    $Layers = 16
    $taskEvidence = [ordered]@{}
    $results = @(1..5 | ForEach-Object {
        [pscustomobject]@{
            pid = $_; jvmStartMillis = $_; fixtureDigest = $fixtureDigest
            priorRestores = '0'; exact = 'true'; expectedBlocks = 4194304
            changedBlocks = 4194304; storedChunks = 1024; loadedChunks = 0
            expectedChunks = 1024; confirmationToEnqueueMillis = 300
            maximumServerTickNanos = 60000000; extraHeapBytes = 1024
            confirmationToClientAckMillis = 12000; applicationNanos = 1000000000
        }
    })
    . $taskAggregation | Out-Null
    $taskReceipt = Get-Content -LiteralPath (Join-Path $state 'manifest.json') -Raw | ConvertFrom-Json
    if ($taskReceipt.status -ne 'passed' -or
            $taskReceipt.absoluteLatencyTargets -ne 'deferred-to-separate-optimization' -or
            $taskReceipt.exceededOptimizationTargets.Count -ne 12 -or
            $taskReceipt.freshJvmColdP95Millis -ne 12000) {
        throw 'Deferred absolute latency targets were not preserved separately.'
    }
    $results[0].extraHeapBytes = 1073741825
    Assert-Rejected { . $taskAggregation | Out-Null } 'extraHeapBytes'
    $results[0].extraHeapBytes = 1024
    $results[0].exact = 'false'
    Assert-Rejected { . $taskAggregation | Out-Null } 'exact Initial endpoint'
    $results[0].exact = 'true'
    $results[0].maximumServerTickNanos = 0
    Assert-Rejected { . $taskAggregation | Out-Null } 'no complete server tick'
    $results[0].maximumServerTickNanos = 60000000
    $results[0].pid = 2
    Assert-Rejected { . $taskAggregation | Out-Null } 'distinct JVM PIDs'
    Write-Output 'Cold benchmark input, deferred latency and retained safety checks passed.'
} finally {
    if (Test-Path -LiteralPath (Join-Path $taskRoot 'manifest.json')) {
        Remove-Item -LiteralPath (Join-Path $taskRoot 'manifest.json')
    }
    Remove-Item -LiteralPath $taskJar, $taskSentinel
    Remove-Item -LiteralPath $taskRoot
}
