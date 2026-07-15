param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineCommand,

    [Parameter(Mandatory = $true)]
    [string]$LumiCommand,

    [ValidateRange(1, 20)]
    [int]$Runs = 1,

    [int]$KeepUpRegressionMs = 250,

    [int]$WallClockRegressionMs = 3000,

    [int]$WallClockRegressionPercent = 10,

    [int]$IdleCpuRegressionMs = 75,

    [int]$IdleCpuRegressionPercent = 20,

    [int]$IdleWallRegressionMs = 250,

    [int]$TeleportLoadRegressionMs = 500,

    [int]$TeleportLoadRegressionPercent = 15,

    [int]$TeleportRenderTickRegression = 2,

    [switch]$FailOnRegression,

    [string]$OutputRoot,

    [string[]]$BaselineExtraLogs = @(
        "build\run\baselineClientGameTest\logs\latest.log"
    ),

    [string[]]$LumiExtraLogs = @(
        "run\test-client\logs\latest.log",
        "build\run\clientGameTest\logs\latest.log"
    ),

    [switch]$RequireLumiActionRun,

    [switch]$RequireBaselineActionRun,

    [switch]$RequireIdleSamples,

    [ValidateRange(60, 7200)]
    [int]$SampleTimeoutSeconds = 900,

    [ValidateRange(5, 300)]
    [int]$HeartbeatSeconds = 30,

    [ValidateRange(0, 900)]
    [int]$SampleStallTimeoutSeconds = 0,

    [ValidateRange(0, 2)]
    [int]$SampleRetries = 0
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot "build\runtime-load"
}

function New-RunDirectory {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $path = Join-Path $OutputRoot $stamp
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    return $path
}

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Path))
}

function Get-FilePrefix {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [int]$Length
    )

    if ($Length -eq 0 -or -not [System.IO.File]::Exists($Path)) {
        return ""
    }

    $bytes = [byte[]]::new($Length)
    $stream = [System.IO.File]::OpenRead($Path)
    try {
        $read = 0
        while ($read -lt $Length) {
            $count = $stream.Read($bytes, $read, $Length - $read)
            if ($count -eq 0) {
                break
            }
            $read += $count
        }
    } finally {
        $stream.Dispose()
    }
    return [Convert]::ToBase64String($bytes)
}

function Stop-ProcessTree {
    param(
        [Parameter(Mandatory = $true)]
        [int]$ProcessId
    )

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -ProcessId $child.ProcessId
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Get-LogOffsets {
    param(
        [string[]]$Paths
    )

    $offsets = @()
    foreach ($path in $Paths) {
        if ([string]::IsNullOrWhiteSpace($path)) {
            continue
        }
        $resolved = Resolve-RepoPath -Path $path
        $length = [int64]0
        if ([System.IO.File]::Exists($resolved)) {
            $length = [int64](Get-Item -LiteralPath $resolved).Length
        }
        $prefixLength = [int][Math]::Min($length, 4096)
        $offsets += [PSCustomObject]@{
            OriginalPath = $path
            Path = $resolved
            Offset = $length
            PrefixLength = $prefixLength
            Prefix = Get-FilePrefix -Path $resolved -Length $prefixLength
            LastWriteTimeUtc = if ([System.IO.File]::Exists($resolved)) {
                (Get-Item -LiteralPath $resolved).LastWriteTimeUtc
            } else {
                [DateTime]::MinValue
            }
        }
    }
    return $offsets
}

function Append-ExtraLogs {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LogPath,

        [object[]]$Offsets
    )

    foreach ($offset in $Offsets) {
        Add-Content -Path $LogPath -Value ""
        Add-Content -Path $LogPath -Value ("===== Extra log: {0} =====" -f $offset.OriginalPath)
        if (-not [System.IO.File]::Exists($offset.Path)) {
            Add-Content -Path $LogPath -Value "Extra log was not created."
            continue
        }

        $currentLength = [int64](Get-Item -LiteralPath $offset.Path).Length
        $appendOffset = $offset.Offset
        $currentPrefix = Get-FilePrefix -Path $offset.Path -Length $offset.PrefixLength
        if ($currentLength -lt $offset.Offset -or $currentPrefix -ne $offset.Prefix) {
            $appendOffset = [int64]0
        }
        if ($currentLength -le $appendOffset) {
            Add-Content -Path $LogPath -Value "No new content."
            continue
        }

        $stream = [System.IO.File]::Open($offset.Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            [void]$stream.Seek($appendOffset, [System.IO.SeekOrigin]::Begin)
            $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true)
            $content = $reader.ReadToEnd()
            Add-Content -Path $LogPath -Value $content
        } finally {
            $stream.Dispose()
        }
    }
}

function Invoke-LoggedCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Command,

        [Parameter(Mandatory = $true)]
        [string]$LogPath,

        [string[]]$ExtraLogs
    )

    $extraLogOffsets = Get-LogOffsets -Paths $ExtraLogs
    $start = [System.Diagnostics.Stopwatch]::StartNew()
    $processInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $processInfo.FileName = "cmd.exe"
    $processInfo.Arguments = "/d /c " + $Command
    $processInfo.WorkingDirectory = $repoRoot
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $processInfo.UseShellExecute = $false

    $process = [System.Diagnostics.Process]::Start($processInfo)
    if ($null -eq $process) {
        throw "Failed to start runtime-load command: $Command"
    }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddSeconds($SampleTimeoutSeconds)
    $nextHeartbeat = [DateTime]::UtcNow.AddSeconds($HeartbeatSeconds)
    $timedOut = $false
    $stalled = $false
    $lastLogActivity = [DateTime]::MinValue
    while (-not $process.WaitForExit(1000)) {
        $now = [DateTime]::UtcNow
        foreach ($offset in $extraLogOffsets) {
            if (-not [System.IO.File]::Exists($offset.Path)) {
                continue
            }
            $lastWriteTime = (Get-Item -LiteralPath $offset.Path).LastWriteTimeUtc
            if ($lastWriteTime -gt $offset.LastWriteTimeUtc -and $lastWriteTime -gt $lastLogActivity) {
                $lastLogActivity = $lastWriteTime
            }
        }
        if ($now -ge $nextHeartbeat) {
            Write-Host ("Still running sample for {0}s: {1}" -f [int]$start.Elapsed.TotalSeconds, $Command)
            $nextHeartbeat = $now.AddSeconds($HeartbeatSeconds)
        }
        if ($SampleStallTimeoutSeconds -gt 0 `
                -and $lastLogActivity -ne [DateTime]::MinValue `
                -and ($now - $lastLogActivity).TotalSeconds -ge $SampleStallTimeoutSeconds) {
            $timedOut = $true
            $stalled = $true
            Write-Warning ("Runtime-load sample log stalled for {0}s: {1}" -f $SampleStallTimeoutSeconds, $Command)
            Stop-ProcessTree -ProcessId $process.Id
            $process.WaitForExit(10000) | Out-Null
            break
        } elseif ($now -ge $deadline) {
            $timedOut = $true
            Write-Warning ("Runtime-load sample timed out after {0}s: {1}" -f $SampleTimeoutSeconds, $Command)
            Stop-ProcessTree -ProcessId $process.Id
            $process.WaitForExit(10000) | Out-Null
            break
        }
    }
    $start.Stop()

    $stdout = ""
    $stderr = ""
    try {
        $stdout = $stdoutTask.Result
    } catch {
        $stdout = "Failed to read stdout: $($_.Exception.Message)"
    }
    try {
        $stderr = $stderrTask.Result
    } catch {
        $stderr = "Failed to read stderr: $($_.Exception.Message)"
    }
    $exitCode = if ($timedOut) {
        124
    } elseif ($process.HasExited) {
        $process.ExitCode
    } else {
        -1
    }

    $log = @(
        "Command: $Command"
        "ExitCode: $exitCode"
        "WallClockMs: $($start.ElapsedMilliseconds)"
        "TimedOut: $timedOut"
        "Stalled: $stalled"
        ""
        $stdout
        $stderr
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($LogPath, $log)
    Append-ExtraLogs -LogPath $LogPath -Offsets $extraLogOffsets

    return [PSCustomObject]@{
        ExitCode = $exitCode
        WallClockMs = [int64]$start.ElapsedMilliseconds
    }
}

function Measure-Log {
    param(
        [Parameter(Mandatory = $true)]
        [string]$LogPath,

        [Parameter(Mandatory = $true)]
        [int64]$WallClockMs,

        [Parameter(Mandatory = $true)]
        [int]$ExitCode
    )

    $content = Get-Content -Raw -Path $LogPath
    $separator = [Environment]::NewLine + [Environment]::NewLine
    $payloadStart = $content.IndexOf($separator)
    if ($payloadStart -ge 0) {
        $content = $content.Substring($payloadStart + $separator.Length)
    }
    $keepUpMatches = [regex]::Matches(
        $content,
        "Can't keep up! Is the server overloaded\? Running (?<ms>\d+)ms or (?<ticks>\d+) ticks? behind"
    )
    $longTickMatches = [regex]::Matches(
        $content,
        "A single server tick took (?<seconds>\d+(?:\.\d+)?) seconds"
    )
    $actionMatches = [regex]::Matches(
        $content,
        "(?:Lumi singleplayer testing|Lumi baseline gameplay testing|Lumi idle startup testing|Lumi baseline idle startup testing) (?<result>passed|completed with failures): (?<passed>\d+) passed, (?<failed>\d+) failed"
    )
    $teleportMatches = [regex]::Matches(
        $content,
        "Lumi (?:baseline )?idle teleport load: index=(?<index>\d+), seed=-?\d+, elapsedMs=(?<ms>\d+), renderWaitTicks=(?<ticks>\d+)"
    )
    $idleMatches = [regex]::Matches(
        $content,
        "Lumi (?:baseline )?idle tick sample: ticks=(?<ticks>\d+), wallMs=(?<wall>\d+), serverCpuMs=(?<cpu>\d+)"
    )
    $worldStartMatches = [regex]::Matches($content, "Starting integrated minecraft server version")
    $uniqueActionMatches = [System.Collections.Generic.List[object]]::new()
    $seenActionLines = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($match in $actionMatches) {
        if ($seenActionLines.Add($match.Value)) {
            $uniqueActionMatches.Add($match)
        }
    }
    $teleportMatchesByIndex = @{}
    foreach ($match in $teleportMatches) {
        $teleportMatchesByIndex[$match.Groups["index"].Value] = $match
    }
    $uniqueTeleportMatches = @($teleportMatchesByIndex.Values)

    $maxKeepUpMs = 0
    $totalKeepUpMs = 0
    foreach ($match in $keepUpMatches) {
        $ms = [int]$match.Groups["ms"].Value
        $maxKeepUpMs = [Math]::Max($maxKeepUpMs, $ms)
        $totalKeepUpMs += $ms
    }

    $maxLongTickMs = 0
    foreach ($match in $longTickMatches) {
        $ms = [int]([double]$match.Groups["seconds"].Value * 1000.0)
        $maxLongTickMs = [Math]::Max($maxLongTickMs, $ms)
    }

    $actionChecksPassed = 0
    $actionChecksFailed = 0
    foreach ($match in $uniqueActionMatches) {
        $actionChecksPassed += [int]$match.Groups["passed"].Value
        $actionChecksFailed += [int]$match.Groups["failed"].Value
    }

    $teleportLoadMs = @($uniqueTeleportMatches | ForEach-Object { [int64]$_.Groups["ms"].Value })
    $teleportRenderTicks = @($uniqueTeleportMatches | ForEach-Object { [int]$_.Groups["ticks"].Value })
    $idleMatch = if ($idleMatches.Count -eq 0) { $null } else { $idleMatches[$idleMatches.Count - 1] }
    $teleportLoadTotal = [int64](($teleportLoadMs | Measure-Object -Sum).Sum)
    $teleportRenderTotal = [int](($teleportRenderTicks | Measure-Object -Sum).Sum)

    return [PSCustomObject]@{
        LogPath = $LogPath
        ExitCode = $ExitCode
        WallClockMs = $WallClockMs
        KeepUpEvents = $keepUpMatches.Count
        MaxKeepUpMs = $maxKeepUpMs
        TotalKeepUpMs = $totalKeepUpMs
        LongTickEvents = $longTickMatches.Count
        MaxLongTickMs = $maxLongTickMs
        WarnCount = ([regex]::Matches($content, "\bWARN\b")).Count
        ErrorCount = ([regex]::Matches($content, "\bERROR\b")).Count
        LumiWarnCount = ([regex]::Matches($content, "\(Lumi\).*\bWARN\b|\bWARN\b.*\(Lumi\)")).Count
        RenderPipelineFailures = ([regex]::Matches($content, "render pipeline failure|Not building!")).Count
        ActionRuns = $uniqueActionMatches.Count
        ActionChecksPassed = $actionChecksPassed
        ActionChecksFailed = $actionChecksFailed
        TeleportLoads = $uniqueTeleportMatches.Count
        TotalTeleportLoadMs = $teleportLoadTotal
        MaxTeleportLoadMs = [int64](($teleportLoadMs | Measure-Object -Maximum).Maximum)
        AverageTeleportLoadMs = if ($uniqueTeleportMatches.Count -eq 0) { 0 } else { [int64]($teleportLoadTotal / $uniqueTeleportMatches.Count) }
        TotalTeleportRenderTicks = $teleportRenderTotal
        MaxTeleportRenderTicks = [int](($teleportRenderTicks | Measure-Object -Maximum).Maximum)
        AverageTeleportRenderTicks = if ($uniqueTeleportMatches.Count -eq 0) { 0 } else { [int]($teleportRenderTotal / $uniqueTeleportMatches.Count) }
        IdleSamples = if ($null -eq $idleMatch) { 0 } else { 1 }
        IdleTicks = if ($null -eq $idleMatch) { 0 } else { [int]$idleMatch.Groups["ticks"].Value }
        IdleWallMs = if ($null -eq $idleMatch) { 0 } else { [int64]$idleMatch.Groups["wall"].Value }
        IdleServerCpuMs = if ($null -eq $idleMatch) { 0 } else { [int64]$idleMatch.Groups["cpu"].Value }
        WorldStarts = $worldStartMatches.Count
    }
}

function Invoke-ScenarioSample {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Command,

        [Parameter(Mandatory = $true)]
        [string]$RunDirectory,

        [int]$PairIndex,

        [string[]]$ExtraLogs
    )

    $attempts = 1 + $SampleRetries
    $sample = $null
    for ($attempt = 1; $attempt -le $attempts; $attempt++) {
        $suffix = if ($attempts -eq 1) { "" } else { "-attempt-$attempt" }
        $logPath = Join-Path $RunDirectory ("{0}-run-{1}{2}.log" -f $Name, $PairIndex, $suffix)
        Write-Host "Running $Name sample $PairIndex/$Runs (attempt $attempt/$attempts)"
        $run = Invoke-LoggedCommand -Command $Command -LogPath $logPath -ExtraLogs $ExtraLogs
        $sample = Measure-Log -LogPath $logPath -WallClockMs $run.WallClockMs -ExitCode $run.ExitCode
        if ($sample.ExitCode -ne 124 -or $sample.WorldStarts -gt 0 -or $attempt -eq $attempts) {
            break
        }
        Write-Warning "$Name sample $PairIndex never started a world; retrying once."
        Start-Sleep -Seconds 5
    }
    $sample | Add-Member -NotePropertyName PairIndex -NotePropertyValue $PairIndex
    return $sample
}

function Invoke-PairedScenarios {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RunDirectory
    )

    $baselineSamples = @()
    $lumiSamples = @()
    for ($index = 1; $index -le $Runs; $index++) {
        if (($index % 2) -eq 1) {
            $baselineSamples += Invoke-ScenarioSample -Name "baseline" -Command $BaselineCommand -RunDirectory $RunDirectory -PairIndex $index -ExtraLogs $BaselineExtraLogs
            $lumiSamples += Invoke-ScenarioSample -Name "lumi" -Command $LumiCommand -RunDirectory $RunDirectory -PairIndex $index -ExtraLogs $LumiExtraLogs
        } else {
            $lumiSamples += Invoke-ScenarioSample -Name "lumi" -Command $LumiCommand -RunDirectory $RunDirectory -PairIndex $index -ExtraLogs $LumiExtraLogs
            $baselineSamples += Invoke-ScenarioSample -Name "baseline" -Command $BaselineCommand -RunDirectory $RunDirectory -PairIndex $index -ExtraLogs $BaselineExtraLogs
        }
    }
    return [PSCustomObject]@{
        Baseline = $baselineSamples
        Lumi = $lumiSamples
    }
}

function Get-Median {
    param([double[]]$Values)

    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 0) {
        return 0.0
    }
    $middle = [int][Math]::Floor($sorted.Count / 2.0)
    if (($sorted.Count % 2) -eq 1) {
        return [double]$sorted[$middle]
    }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

function Get-PairedMedianDelta {
    param(
        [object[]]$Baseline,
        [object[]]$Lumi,
        [string]$Property
    )

    $deltas = for ($index = 0; $index -lt [Math]::Min($Baseline.Count, $Lumi.Count); $index++) {
        [double]$Lumi[$index].$Property - [double]$Baseline[$index].$Property
    }
    return Get-Median -Values $deltas
}

function New-Summary {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Samples
    )

    $wall = ($Samples | Measure-Object -Property WallClockMs -Average -Maximum)
    $teleportLoads = [int](($Samples | Measure-Object -Property TeleportLoads -Sum).Sum)
    $totalTeleportLoadMs = [int64](($Samples | Measure-Object -Property TotalTeleportLoadMs -Sum).Sum)
    $totalTeleportRenderTicks = [int](($Samples | Measure-Object -Property TotalTeleportRenderTicks -Sum).Sum)
    return [PSCustomObject]@{
        Runs = $Samples.Count
        FailedRuns = @($Samples | Where-Object { $_.ExitCode -ne 0 }).Count
        AverageWallClockMs = [int64]$wall.Average
        MedianWallClockMs = [int64](Get-Median -Values @($Samples | ForEach-Object { [double]$_.WallClockMs }))
        MaxWallClockMs = [int64]$wall.Maximum
        KeepUpEvents = [int](($Samples | Measure-Object -Property KeepUpEvents -Sum).Sum)
        MaxKeepUpMs = [int](($Samples | Measure-Object -Property MaxKeepUpMs -Maximum).Maximum)
        TotalKeepUpMs = [int](($Samples | Measure-Object -Property TotalKeepUpMs -Sum).Sum)
        LongTickEvents = [int](($Samples | Measure-Object -Property LongTickEvents -Sum).Sum)
        MaxLongTickMs = [int](($Samples | Measure-Object -Property MaxLongTickMs -Maximum).Maximum)
        WarnCount = [int](($Samples | Measure-Object -Property WarnCount -Sum).Sum)
        ErrorCount = [int](($Samples | Measure-Object -Property ErrorCount -Sum).Sum)
        LumiWarnCount = [int](($Samples | Measure-Object -Property LumiWarnCount -Sum).Sum)
        RenderPipelineFailures = [int](($Samples | Measure-Object -Property RenderPipelineFailures -Sum).Sum)
        ActionRuns = [int](($Samples | Measure-Object -Property ActionRuns -Sum).Sum)
        ActionChecksPassed = [int](($Samples | Measure-Object -Property ActionChecksPassed -Sum).Sum)
        ActionChecksFailed = [int](($Samples | Measure-Object -Property ActionChecksFailed -Sum).Sum)
        TeleportLoads = $teleportLoads
        AverageTeleportLoadMs = if ($teleportLoads -eq 0) { 0 } else { [int64]($totalTeleportLoadMs / $teleportLoads) }
        MedianTeleportLoadMs = [int64](Get-Median -Values @($Samples | ForEach-Object { [double]$_.AverageTeleportLoadMs }))
        MaxTeleportLoadMs = [int64](($Samples | Measure-Object -Property MaxTeleportLoadMs -Maximum).Maximum)
        AverageTeleportRenderTicks = if ($teleportLoads -eq 0) { 0 } else { [int]($totalTeleportRenderTicks / $teleportLoads) }
        MedianTeleportRenderTicks = [int](Get-Median -Values @($Samples | ForEach-Object { [double]$_.AverageTeleportRenderTicks }))
        MaxTeleportRenderTicks = [int](($Samples | Measure-Object -Property MaxTeleportRenderTicks -Maximum).Maximum)
        IdleSamples = [int](($Samples | Measure-Object -Property IdleSamples -Sum).Sum)
        MedianIdleWallMs = [int64](Get-Median -Values @($Samples | Where-Object { $_.IdleSamples -gt 0 } | ForEach-Object { [double]$_.IdleWallMs }))
        MedianIdleServerCpuMs = [int64](Get-Median -Values @($Samples | Where-Object { $_.IdleSamples -gt 0 } | ForEach-Object { [double]$_.IdleServerCpuMs }))
    }
}

function Write-MarkdownSummary {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [object]$Result
    )

    $lines = @(
        "# Lumi Runtime Load Comparison",
        "",
        "| Scenario | Runs | Failed | Avg wall ms | Median wall ms | Max wall ms | Keep-up events | Max behind ms | Long ticks | Max long tick ms | WARN | ERROR | Lumi WARN | Render failures | Action runs | Action failed |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ("| Baseline | {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} | {14} |" -f
            $Result.Baseline.Summary.Runs,
            $Result.Baseline.Summary.FailedRuns,
            $Result.Baseline.Summary.AverageWallClockMs,
            $Result.Baseline.Summary.MedianWallClockMs,
            $Result.Baseline.Summary.MaxWallClockMs,
            $Result.Baseline.Summary.KeepUpEvents,
            $Result.Baseline.Summary.MaxKeepUpMs,
            $Result.Baseline.Summary.LongTickEvents,
            $Result.Baseline.Summary.MaxLongTickMs,
            $Result.Baseline.Summary.WarnCount,
            $Result.Baseline.Summary.ErrorCount,
            $Result.Baseline.Summary.LumiWarnCount,
            $Result.Baseline.Summary.RenderPipelineFailures,
            $Result.Baseline.Summary.ActionRuns,
            $Result.Baseline.Summary.ActionChecksFailed),
        ("| Lumi | {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} | {14} |" -f
            $Result.Lumi.Summary.Runs,
            $Result.Lumi.Summary.FailedRuns,
            $Result.Lumi.Summary.AverageWallClockMs,
            $Result.Lumi.Summary.MedianWallClockMs,
            $Result.Lumi.Summary.MaxWallClockMs,
            $Result.Lumi.Summary.KeepUpEvents,
            $Result.Lumi.Summary.MaxKeepUpMs,
            $Result.Lumi.Summary.LongTickEvents,
            $Result.Lumi.Summary.MaxLongTickMs,
            $Result.Lumi.Summary.WarnCount,
            $Result.Lumi.Summary.ErrorCount,
            $Result.Lumi.Summary.LumiWarnCount,
            $Result.Lumi.Summary.RenderPipelineFailures,
            $Result.Lumi.Summary.ActionRuns,
            $Result.Lumi.Summary.ActionChecksFailed),
        "",
        "Baseline command: ``$($Result.Baseline.Command)``",
        "",
        "Lumi command: ``$($Result.Lumi.Command)``",
        "",
        "Baseline action checks: $($Result.Baseline.Summary.ActionChecksPassed) passed, $($Result.Baseline.Summary.ActionChecksFailed) failed",
        "",
        "Lumi action checks: $($Result.Lumi.Summary.ActionChecksPassed) passed, $($Result.Lumi.Summary.ActionChecksFailed) failed",
        "",
        "| Scenario | Teleports | Avg load ms | Median run load ms | Max load ms | Avg render ticks | Median run render ticks | Max render ticks |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ("| Baseline | {0} | {1} | {2} | {3} | {4} | {5} | {6} |" -f
            $Result.Baseline.Summary.TeleportLoads,
            $Result.Baseline.Summary.AverageTeleportLoadMs,
            $Result.Baseline.Summary.MedianTeleportLoadMs,
            $Result.Baseline.Summary.MaxTeleportLoadMs,
            $Result.Baseline.Summary.AverageTeleportRenderTicks,
            $Result.Baseline.Summary.MedianTeleportRenderTicks,
            $Result.Baseline.Summary.MaxTeleportRenderTicks),
        ("| Lumi | {0} | {1} | {2} | {3} | {4} | {5} | {6} |" -f
            $Result.Lumi.Summary.TeleportLoads,
            $Result.Lumi.Summary.AverageTeleportLoadMs,
            $Result.Lumi.Summary.MedianTeleportLoadMs,
            $Result.Lumi.Summary.MaxTeleportLoadMs,
            $Result.Lumi.Summary.AverageTeleportRenderTicks,
            $Result.Lumi.Summary.MedianTeleportRenderTicks,
            $Result.Lumi.Summary.MaxTeleportRenderTicks),
        "",
        "| Scenario | Idle samples | Median idle wall ms | Median server CPU ms |",
        "| --- | ---: | ---: | ---: |",
        ("| Baseline | {0} | {1} | {2} |" -f
            $Result.Baseline.Summary.IdleSamples,
            $Result.Baseline.Summary.MedianIdleWallMs,
            $Result.Baseline.Summary.MedianIdleServerCpuMs),
        ("| Lumi | {0} | {1} | {2} |" -f
            $Result.Lumi.Summary.IdleSamples,
            $Result.Lumi.Summary.MedianIdleWallMs,
            $Result.Lumi.Summary.MedianIdleServerCpuMs),
        "",
        "Paired median deltas: wall $($Result.Regression.PairedMedianWallClockDeltaMs) ms; idle wall $($Result.Regression.PairedMedianIdleWallDeltaMs) ms; idle server CPU $($Result.Regression.PairedMedianIdleServerCpuDeltaMs) ms; teleport load $($Result.Regression.PairedMedianTeleportLoadDeltaMs) ms; teleport render $($Result.Regression.PairedMedianTeleportRenderTickDelta) ticks.",
        "",
        "Raw logs and JSON: ``$($Result.OutputDirectory)``"
    )
    Set-Content -Path $Path -Value $lines
}

$runDirectory = New-RunDirectory
$pairedSamples = Invoke-PairedScenarios -RunDirectory $runDirectory
$baselineSamples = @($pairedSamples.Baseline)
$lumiSamples = @($pairedSamples.Lumi)

$baselineSummary = New-Summary -Samples $baselineSamples
$lumiSummary = New-Summary -Samples $lumiSamples
$result = [PSCustomObject]@{
    GeneratedAt = (Get-Date).ToString("o")
    OutputDirectory = $runDirectory
    Baseline = [PSCustomObject]@{
        Command = $BaselineCommand
        Samples = $baselineSamples
        Summary = $baselineSummary
    }
    Lumi = [PSCustomObject]@{
        Command = $LumiCommand
        Samples = $lumiSamples
        Summary = $lumiSummary
    }
    Regression = [PSCustomObject]@{
        AverageWallClockDeltaMs = $lumiSummary.AverageWallClockMs - $baselineSummary.AverageWallClockMs
        AverageTeleportLoadDeltaMs = $lumiSummary.AverageTeleportLoadMs - $baselineSummary.AverageTeleportLoadMs
        AverageTeleportRenderTickDelta = $lumiSummary.AverageTeleportRenderTicks - $baselineSummary.AverageTeleportRenderTicks
        MaxKeepUpDeltaMs = $lumiSummary.MaxKeepUpMs - $baselineSummary.MaxKeepUpMs
        MaxLongTickDeltaMs = $lumiSummary.MaxLongTickMs - $baselineSummary.MaxLongTickMs
        RenderPipelineFailureDelta = $lumiSummary.RenderPipelineFailures - $baselineSummary.RenderPipelineFailures
        PairedMedianWallClockDeltaMs = [int64](Get-PairedMedianDelta -Baseline $baselineSamples -Lumi $lumiSamples -Property "WallClockMs")
        PairedMedianIdleWallDeltaMs = [int64](Get-PairedMedianDelta -Baseline $baselineSamples -Lumi $lumiSamples -Property "IdleWallMs")
        PairedMedianIdleServerCpuDeltaMs = [int64](Get-PairedMedianDelta -Baseline $baselineSamples -Lumi $lumiSamples -Property "IdleServerCpuMs")
        PairedMedianTeleportLoadDeltaMs = [int64](Get-PairedMedianDelta -Baseline $baselineSamples -Lumi $lumiSamples -Property "AverageTeleportLoadMs")
        PairedMedianTeleportRenderTickDelta = [int](Get-PairedMedianDelta -Baseline $baselineSamples -Lumi $lumiSamples -Property "AverageTeleportRenderTicks")
    }
}

$jsonPath = Join-Path $runDirectory "summary.json"
$markdownPath = Join-Path $runDirectory "summary.md"
$result | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath
Write-MarkdownSummary -Path $markdownPath -Result $result

Write-Host "Wrote $jsonPath"
Write-Host "Wrote $markdownPath"

$hasFailedRuns = $baselineSummary.FailedRuns -gt 0 -or $lumiSummary.FailedRuns -gt 0
$wallClockAllowance = [Math]::Max($WallClockRegressionMs, $baselineSummary.MedianWallClockMs * $WallClockRegressionPercent / 100.0)
$idleCpuAllowance = [Math]::Max($IdleCpuRegressionMs, $baselineSummary.MedianIdleServerCpuMs * $IdleCpuRegressionPercent / 100.0)
$teleportLoadAllowance = [Math]::Max($TeleportLoadRegressionMs, $baselineSummary.MedianTeleportLoadMs * $TeleportLoadRegressionPercent / 100.0)
$hasRegression = $lumiSummary.MaxKeepUpMs -gt ($baselineSummary.MaxKeepUpMs + $KeepUpRegressionMs) `
    -or $lumiSummary.MaxLongTickMs -gt ($baselineSummary.MaxLongTickMs + $KeepUpRegressionMs) `
    -or $lumiSummary.RenderPipelineFailures -gt $baselineSummary.RenderPipelineFailures `
    -or $result.Regression.PairedMedianWallClockDeltaMs -gt $wallClockAllowance `
    -or $result.Regression.PairedMedianIdleWallDeltaMs -gt $IdleWallRegressionMs `
    -or $result.Regression.PairedMedianIdleServerCpuDeltaMs -gt $idleCpuAllowance `
    -or $result.Regression.PairedMedianTeleportLoadDeltaMs -gt $teleportLoadAllowance `
    -or $result.Regression.PairedMedianTeleportRenderTickDelta -gt $TeleportRenderTickRegression
$missingIdleSamples = $RequireIdleSamples `
    -and ($baselineSummary.IdleSamples -lt $Runs -or $lumiSummary.IdleSamples -lt $Runs)
$missingRequiredLumiActionRun = $RequireLumiActionRun `
    -and ($lumiSummary.ActionRuns -eq 0 -or $lumiSummary.ActionChecksFailed -gt 0)
$missingRequiredBaselineActionRun = $RequireBaselineActionRun `
    -and ($baselineSummary.ActionRuns -eq 0 -or $baselineSummary.ActionChecksFailed -gt 0)

if ($missingRequiredLumiActionRun) {
    Write-Error "Lumi action run is required but no passing singleplayer action suite result was found."
}

if ($missingRequiredBaselineActionRun) {
    Write-Error "Baseline action run is required but no passing baseline gameplay suite result was found."
}

if ($FailOnRegression -and $Runs -lt 3) {
    Write-Error "A blocking runtime-load comparison requires at least three paired runs."
}

if ($missingIdleSamples) {
    Write-Error "Every paired run must report an idle server-thread CPU sample."
}

if ($hasFailedRuns -or ($FailOnRegression -and ($hasRegression -or $Runs -lt 3)) -or $missingIdleSamples -or $missingRequiredLumiActionRun -or $missingRequiredBaselineActionRun) {
    exit 1
}
