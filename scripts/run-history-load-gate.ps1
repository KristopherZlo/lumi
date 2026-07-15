param(
    [int]$MaxTickMillis = 50,
    [int]$MaxHeapUsedMiB = 2304,
    [string]$JavaHome,
    [switch]$SkipRun
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$runRoot = Join-Path $repositoryRoot 'build/run/clientGameTest'
$loadLog = Join-Path $runRoot 'logs/lumi-load.log'
$clientLoadLog = Join-Path $runRoot 'logs/lumi-client-load.log'
$latestLog = Join-Path $runRoot 'logs/latest.log'
$testClientScript = Join-Path $PSScriptRoot 'run-test-client.ps1'

function Require([bool]$condition, [string]$message) {
    if (-not $condition) {
        throw "History load gate failed: $message"
    }
}

function Read-Metric([string]$summary, [string]$name) {
    $match = [regex]::Match($summary, "(?:^|, )$([regex]::Escape($name))=(\d+)")
    Require $match.Success "missing restore metric '$name'"
    return [int64]$match.Groups[1].Value
}

function Maximum-LogValue([string]$text, [string]$name) {
    $escapedName = [regex]::Escape($name)
    $values = [regex]::Matches(
        $text,
        "name=`"$escapedName`"[^\r\n]*(?:elapsedMillis|maxMillis)=(\d+)"
    ) | ForEach-Object { [int]$_.Groups[1].Value }
    Require ($values.Count -gt 0) "missing timing samples for '$name'"
    return ($values | Measure-Object -Maximum).Maximum
}

function Has-IntermediateProgress([string]$text, [string]$label, [string]$stage) {
    $pattern = "World operation $([regex]::Escape($label)) stage=$stage progress=(\d+)/(\d+)"
    foreach ($match in [regex]::Matches($text, $pattern)) {
        $completed = [int64]$match.Groups[1].Value
        $total = [int64]$match.Groups[2].Value
        if ($completed -gt 0 -and $completed -lt $total) {
            return $true
        }
    }
    return $false
}

Push-Location $repositoryRoot
try {
    if (-not $SkipRun) {
        $arguments = @{
            GradleTasks = @(
                'runClientGameTest',
                '-PlumiClientGameTestSuite=load',
                "-PlumiLoadGateMaxHeapMiB=$MaxHeapUsedMiB"
            )
        }
        if ($JavaHome) {
            $arguments.JavaHome = $JavaHome
        }
        & $testClientScript @arguments
        Require ($LASTEXITCODE -eq 0) "Gradle load suite exited with code $LASTEXITCODE"
    }

    Require (Test-Path -LiteralPath $loadLog) "missing $loadLog"
    Require (Test-Path -LiteralPath $clientLoadLog) "missing $clientLoadLog"
    Require (Test-Path -LiteralPath $latestLog) "missing $latestLog"

    $loadText = Get-Content -LiteralPath $loadLog -Raw
    $clientLoadText = Get-Content -LiteralPath $clientLoadLog -Raw
    $latestText = Get-Content -LiteralPath $latestLog -Raw

    $journey = [regex]::Match(
        $loadText,
        'area="history-load" name="complete" detail="([^"]+)"'
    )
    Require $journey.Success 'missing completed history-load journey'
    Require ($journey.Groups[1].Value -match 'changes50k=50000') '50k comparison was not exact'
    Require ($journey.Groups[1].Value -match 'changes100k=100000') '100k comparison was not exact'
    Require ($journey.Groups[1].Value -match 'exactStates=3') 'three exact restore states were not verified'

    $restoreMatches = [regex]::Matches(
        $loadText,
        'type="operation-metrics" label="restore-version"[^\r\n]*metrics="([^"]+)"'
    )
    Require ($restoreMatches.Count -eq 3) "expected 3 restore metric records, found $($restoreMatches.Count)"
    $verifiedBlockCounts = @()
    foreach ($restoreMatch in $restoreMatches) {
        $summary = $restoreMatch.Groups[1].Value
        $processed = Read-Metric $summary 'processedBlocks'
        $matched = Read-Metric $summary 'verificationMatched'
        Require ($matched -eq $processed) "restore verified $matched of $processed blocks"
        Require ((Read-Metric $summary 'verificationMismatched') -eq 0) 'restore verification mismatch'
        Require ((Read-Metric $summary 'applyFailures') -eq 0) 'restore apply failure'
        Require ((Read-Metric $summary 'maxApplyTickMs') -le $MaxTickMillis) 'restore apply tick exceeded limit'
        Require ((Read-Metric $summary 'maxPreloadTickMs') -le $MaxTickMillis) 'restore preload tick exceeded limit'
        $verifiedBlockCounts += $matched
    }
    $verifiedBlockCounts = $verifiedBlockCounts | Sort-Object
    Require (($verifiedBlockCounts -join ',') -eq '50000,100000,100000') 'unexpected restore verification sizes'

    $managerTickMax = Maximum-LogValue $loadText 'WorldOperationManager.tick'
    $lumiEndTickMax = Maximum-LogValue $loadText 'LumaMod.endServerTick'
    Require ($managerTickMax -le $MaxTickMillis) "WorldOperationManager.tick reached ${managerTickMax} ms"
    Require ($lumiEndTickMax -le $MaxTickMillis) "LumaMod.endServerTick reached ${lumiEndTickMax} ms"

    $heapValues = [regex]::Matches($clientLoadText, 'heapUsedMiB=(\d+)') |
        ForEach-Object { [int]$_.Groups[1].Value }
    $heapMaxValues = [regex]::Matches($clientLoadText, 'heapMaxMiB=(\d+)') |
        ForEach-Object { [int]$_.Groups[1].Value }
    Require ($heapValues.Count -gt 0) 'missing client heap samples'
    Require ($heapMaxValues.Count -gt 0) 'missing client heap limit samples'
    $heapMax = ($heapValues | Measure-Object -Maximum).Maximum
    $configuredHeapMax = ($heapMaxValues | Measure-Object -Maximum).Maximum
    Require ($configuredHeapMax -le $MaxHeapUsedMiB) "test JVM heap limit was ${configuredHeapMax} MiB"
    Require ($heapMax -le $MaxHeapUsedMiB) "heap usage reached ${heapMax} MiB"

    Require (Has-IntermediateProgress $latestText 'save-version' 'WRITING') 'save progress was not observable'
    Require (Has-IntermediateProgress $latestText 'restore-version' 'APPLYING') 'restore progress was not observable'

    Write-Host "History load gate passed: exact=50k/100k, maxTick=${managerTickMax}ms, maxLumiEndTick=${lumiEndTickMax}ms, maxHeap=${heapMax}MiB, heapLimit=${configuredHeapMax}MiB"
} finally {
    Pop-Location
}
