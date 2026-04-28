param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineCommand,

    [Parameter(Mandatory = $true)]
    [string]$LumiCommand,

    [ValidateRange(1, 20)]
    [int]$Runs = 1,

    [int]$KeepUpRegressionMs = 250,

    [switch]$FailOnRegression,

    [string]$OutputRoot,

    [string[]]$BaselineExtraLogs = @(),

    [string[]]$LumiExtraLogs = @(
        "run\test-client\logs\latest.log",
        "build\run\clientGameTest\logs\latest.log"
    ),

    [switch]$RequireLumiActionRun
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
        $offsets += [PSCustomObject]@{
            OriginalPath = $path
            Path = $resolved
            Offset = $length
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
        if ($currentLength -le $offset.Offset) {
            Add-Content -Path $LogPath -Value "No new content."
            continue
        }

        $stream = [System.IO.File]::Open($offset.Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        try {
            [void]$stream.Seek($offset.Offset, [System.IO.SeekOrigin]::Begin)
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
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $start.Stop()

    $log = @(
        "Command: $Command"
        "ExitCode: $($process.ExitCode)"
        "WallClockMs: $($start.ElapsedMilliseconds)"
        ""
        $stdoutTask.Result
        $stderrTask.Result
    ) -join [Environment]::NewLine
    [System.IO.File]::WriteAllText($LogPath, $log)
    Append-ExtraLogs -LogPath $LogPath -Offsets $extraLogOffsets

    return [PSCustomObject]@{
        ExitCode = $process.ExitCode
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
        "Lumi singleplayer testing (?<result>passed|completed with failures): (?<passed>\d+) passed, (?<failed>\d+) failed"
    )

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
    foreach ($match in $actionMatches) {
        $actionChecksPassed += [int]$match.Groups["passed"].Value
        $actionChecksFailed += [int]$match.Groups["failed"].Value
    }

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
        ActionRuns = $actionMatches.Count
        ActionChecksPassed = $actionChecksPassed
        ActionChecksFailed = $actionChecksFailed
    }
}

function Invoke-Scenario {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [Parameter(Mandatory = $true)]
        [string]$Command,

        [Parameter(Mandatory = $true)]
        [string]$RunDirectory,

        [string[]]$ExtraLogs
    )

    $samples = @()
    for ($index = 1; $index -le $Runs; $index++) {
        $logPath = Join-Path $RunDirectory ("{0}-run-{1}.log" -f $Name, $index)
        Write-Host "Running $Name sample $index/$Runs"
        $run = Invoke-LoggedCommand -Command $Command -LogPath $logPath -ExtraLogs $ExtraLogs
        $samples += Measure-Log -LogPath $logPath -WallClockMs $run.WallClockMs -ExitCode $run.ExitCode
    }
    return $samples
}

function New-Summary {
    param(
        [Parameter(Mandatory = $true)]
        [object[]]$Samples
    )

    $wall = ($Samples | Measure-Object -Property WallClockMs -Average -Maximum)
    return [PSCustomObject]@{
        Runs = $Samples.Count
        FailedRuns = ($Samples | Where-Object { $_.ExitCode -ne 0 }).Count
        AverageWallClockMs = [int64]$wall.Average
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
        "| Scenario | Runs | Failed | Avg wall ms | Max wall ms | Keep-up events | Max behind ms | Long ticks | Max long tick ms | WARN | ERROR | Lumi WARN | Render failures | Action runs | Action failed |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        ("| Baseline | {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} |" -f
            $Result.Baseline.Summary.Runs,
            $Result.Baseline.Summary.FailedRuns,
            $Result.Baseline.Summary.AverageWallClockMs,
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
        ("| Lumi | {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} | {10} | {11} | {12} | {13} |" -f
            $Result.Lumi.Summary.Runs,
            $Result.Lumi.Summary.FailedRuns,
            $Result.Lumi.Summary.AverageWallClockMs,
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
        "Lumi action checks: $($Result.Lumi.Summary.ActionChecksPassed) passed, $($Result.Lumi.Summary.ActionChecksFailed) failed",
        "",
        "Raw logs and JSON: ``$($Result.OutputDirectory)``"
    )
    Set-Content -Path $Path -Value $lines
}

$runDirectory = New-RunDirectory
$baselineSamples = Invoke-Scenario -Name "baseline" -Command $BaselineCommand -RunDirectory $runDirectory -ExtraLogs $BaselineExtraLogs
$lumiSamples = Invoke-Scenario -Name "lumi" -Command $LumiCommand -RunDirectory $runDirectory -ExtraLogs $LumiExtraLogs

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
        MaxKeepUpDeltaMs = $lumiSummary.MaxKeepUpMs - $baselineSummary.MaxKeepUpMs
        MaxLongTickDeltaMs = $lumiSummary.MaxLongTickMs - $baselineSummary.MaxLongTickMs
        RenderPipelineFailureDelta = $lumiSummary.RenderPipelineFailures - $baselineSummary.RenderPipelineFailures
    }
}

$jsonPath = Join-Path $runDirectory "summary.json"
$markdownPath = Join-Path $runDirectory "summary.md"
$result | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath
Write-MarkdownSummary -Path $markdownPath -Result $result

Write-Host "Wrote $jsonPath"
Write-Host "Wrote $markdownPath"

$hasFailedRuns = $baselineSummary.FailedRuns -gt 0 -or $lumiSummary.FailedRuns -gt 0
$hasRegression = $lumiSummary.MaxKeepUpMs -gt ($baselineSummary.MaxKeepUpMs + $KeepUpRegressionMs) `
    -or $lumiSummary.MaxLongTickMs -gt ($baselineSummary.MaxLongTickMs + $KeepUpRegressionMs) `
    -or $lumiSummary.RenderPipelineFailures -gt $baselineSummary.RenderPipelineFailures
$missingRequiredActionRun = $RequireLumiActionRun `
    -and ($lumiSummary.ActionRuns -eq 0 -or $lumiSummary.ActionChecksFailed -gt 0)

if ($missingRequiredActionRun) {
    Write-Error "Lumi action run is required but no passing singleplayer action suite result was found."
}

if ($hasFailedRuns -or ($FailOnRegression -and $hasRegression) -or $missingRequiredActionRun) {
    exit 1
}
