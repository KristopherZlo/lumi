param(
    [ValidateRange(5, 100)]
    [int]$Samples = 5,
    [ValidateRange(16, 10000)]
    [int]$BaseSize = 512,
    [ValidateRange(16, 10000)]
    [int]$ChangeSize = 512,
    [ValidateRange(1, 64)]
    [int]$Layers = 16,
    [ValidateRange(1, 1000)]
    [int]$Commits = 1
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$build = [IO.Path]::GetFullPath((Join-Path $repo 'build'))
$state = [IO.Path]::GetFullPath((Join-Path $build 'cold-restore'))
if (-not $state.StartsWith($build + [IO.Path]::DirectorySeparatorChar)) {
    throw "Cold benchmark state escaped the build directory: $state"
}
if (Test-Path -LiteralPath $state) {
    Remove-Item -LiteralPath $state -Recurse -Force
}
New-Item -ItemType Directory -Path $state | Out-Null

$gradle = Join-Path $repo 'gradlew.bat'
$manifest = Join-Path $state 'fixture.properties'
$worldName = 'Lumi behavior seed 710'
$runWorld = Join-Path $build "run/clientGameTest/saves/$worldName"
$fixture = Join-Path $state 'fixture'

function Invoke-ClientGameTest([string[]]$Properties) {
    $arguments = @(
        '-Dlumi.gametest.suite=BENCHMARK',
        '-Dlumi.benchmark.enabled=true'
    ) + $Properties + @('runClientGameTest', '--no-daemon')
    & $gradle @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Client GameTest failed with exit code $LASTEXITCODE"
    }
}

function Get-TreeDigest([string]$Root) {
    $prefix = $Root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    $entries = Get-ChildItem -LiteralPath $Root -Recurse -File |
        Where-Object Name -ne 'session.lock' |
        Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($prefix.Length)
            "$relative=$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)"
        }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($entries -join "`n"))
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return [BitConverter]::ToString(
            $algorithm.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Read-Properties([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line.Length -eq 0 -or $line.StartsWith('#')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) {
            continue
        }
        $values[$line.Substring(0, $separator)] = $line.Substring($separator + 1)
    }
    return $values
}

Push-Location $repo
try {
    Invoke-ClientGameTest @(
        '-Dlumi.benchmark.coldMode=fixture',
        "-Dlumi.benchmark.coldManifest=$manifest",
        "-Dlumi.benchmark.baseSize=$BaseSize",
        "-Dlumi.benchmark.changeSize=$ChangeSize",
        "-Dlumi.benchmark.layers=$Layers",
        "-Dlumi.benchmark.commits=$Commits",
        '-Dlumi.benchmark.restoreSamples=2',
        '-Dlumi.benchmark.chunkPath=natural'
    )
    if (-not (Test-Path -LiteralPath $runWorld)) {
        throw "Fixture JVM did not leave the expected world: $runWorld"
    }
    Copy-Item -LiteralPath $runWorld -Destination $fixture -Recurse
    $fixtureDigest = Get-TreeDigest $fixture
    Add-Content -LiteralPath $manifest -Encoding UTF8 -Value "fixtureDigest=$fixtureDigest"

    $results = @()
    for ($sample = 1; $sample -le $Samples; $sample++) {
        if ((Get-TreeDigest $fixture) -ne $fixtureDigest) {
            throw 'Immutable cold fixture changed between samples'
        }
        $result = Join-Path $state ("sample-{0:d2}.properties" -f $sample)
        Invoke-ClientGameTest @(
            '-Dlumi.benchmark.coldMode=measure',
            "-Dlumi.benchmark.coldManifest=$manifest",
            "-Dlumi.benchmark.coldResult=$result",
            "-Dlumi.benchmark.existingWorldSource=$fixture",
            "-Dlumi.benchmark.existingWorld=$worldName",
            '-Dlumi.benchmark.heap=1G'
        )
        $results += Read-Properties $result
    }
} finally {
    Pop-Location
}

$violations = [Collections.Generic.List[string]]::new()
$pids = $results | ForEach-Object { $_.pid } | Sort-Object -Unique
if ($pids.Count -ne $Samples) {
    $violations.Add("Expected $Samples distinct JVM PIDs, got $($pids.Count)")
}
foreach ($result in $results) {
    if ($result.fixtureDigest -ne $fixtureDigest) {
        $violations.Add("PID $($result.pid) used a different fixture digest")
    }
    if ($result.priorRestores -ne '0') {
        $violations.Add("PID $($result.pid) ran a Restore before the sample")
    }
    if ($result.exact -ne 'true') {
        $violations.Add("PID $($result.pid) did not reach the exact Initial endpoint")
    }
    $configuredBlocks = [long]$BaseSize * $BaseSize * $Layers
    if ([long]$result.expectedBlocks -ne $configuredBlocks) {
        $violations.Add(
            "PID $($result.pid) fixture blocks actual=$($result.expectedBlocks), " +
            "expected=$configuredBlocks")
    }
    if ([long]$result.changedBlocks -ne [long]$result.expectedBlocks) {
        $violations.Add(
            "PID $($result.pid) changedBlocks actual=$($result.changedBlocks), " +
            "expected=$($result.expectedBlocks)")
    }
    if ([long]$result.storedChunks -le 0) {
        $violations.Add("PID $($result.pid) exercised no stored chunks")
    }
    $appliedChunks = [long]$result.loadedChunks + [long]$result.storedChunks
    if ($appliedChunks -lt [long]$result.expectedChunks) {
        $violations.Add(
            "PID $($result.pid) applied chunks actual=$appliedChunks, " +
            "expectedAtLeast=$($result.expectedChunks)")
    }
    if ([long]$result.confirmationToEnqueueMillis -gt 250) {
        $violations.Add(
            "PID $($result.pid) confirmationToEnqueueMillis actual=" +
            "$($result.confirmationToEnqueueMillis), limit=250")
    }
    if ([long]$result.maximumServerTickNanos -gt 50000000) {
        $violations.Add(
            "PID $($result.pid) maximumServerTickNanos actual=" +
            "$($result.maximumServerTickNanos), limit=50000000")
    }
}
$ordered = $results |
    Sort-Object { [long]$_.confirmationToClientAckMillis }
$rank = [Math]::Ceiling(0.95 * $ordered.Count) - 1
$p95 = [long]$ordered[$rank].confirmationToClientAckMillis
if ($p95 -gt 10000) {
    $violations.Add(
        "fresh-JVM cold P95 actual=$p95 ms, limit=10000 ms, " +
        "samplePid=$($ordered[$rank].pid)")
}

$results | Format-Table pid, jvmStartMillis,
    confirmationToClientAckMillis, confirmationToEnqueueMillis,
    enqueueToTerminalMillis, changedBlocks, loadedChunks, storedChunks,
    maximumServerTickNanos
Write-Host "fixtureDigest=$fixtureDigest;freshJvmColdP95Millis=$p95;samples=$Samples"
if ($violations.Count -gt 0) {
    throw ($violations -join '; ')
}
