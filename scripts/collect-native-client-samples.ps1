param(
    [Parameter(Mandatory)] [string]$ObservationDirectory,
    [ValidateRange(10, 86400)] [int]$TimeoutSeconds = 900
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $ObservationDirectory).Path
$taskMetadata = Join-Path $taskRoot 'observation.properties'
function Read-Observation {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($taskMetadata)) {
        if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
            $parts = $line -split '=', 2
            $values[$parts[0]] = $parts[1]
        }
    }
    return $values
}
$taskInitial = Read-Observation
if ($taskInitial.status -cne 'running' -or $taskInitial.pid -notmatch '^\d+$') {
    throw 'Collector requires a running ordinary-client observation with a process ID.'
}
$taskClientPid = [int]$taskInitial.pid
$taskProcess = Get-Process -Id $taskClientPid
$taskIdentity = Get-CimInstance Win32_Process -Filter "ProcessId=$taskClientPid"
if ($taskProcess.ProcessName -notin @('java', 'javaw') -or
        $taskIdentity.CommandLine -notlike '*-Dlumi.observation.enabled=true*' -or
        $taskIdentity.CommandLine -notlike '*-Dlumi.observation.output=*' -or
        -not $taskIdentity.CommandLine.Contains($taskRoot)) {
    throw 'Observation PID is not an enabled ordinary Minecraft measurement process.'
}
$taskStart = $taskProcess.StartTime
$taskSamples = Join-Path $taskRoot 'native-process.csv'
$taskEngines = Join-Path $taskRoot 'native-gpu-engines.csv'
$taskReceipt = Join-Path $taskRoot 'native-process.json'
foreach ($taskPath in @($taskSamples, $taskEngines, $taskReceipt)) {
    if (Test-Path -LiteralPath $taskPath) { throw "Native collector output already exists: $taskPath" }
}
if (-not ('LumiObservationForeground' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class LumiObservationForeground {
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);
    public static uint ProcessId() { uint process; GetWindowThreadProcessId(GetForegroundWindow(), out process); return process; }
}
'@
}
$taskReport = [ordered]@{ status = 'running'; pid = $taskClientPid; processStartedUtc = $taskStart.ToUniversalTime().ToString('o');
    scope = 'External process/GPU/foreground samples; no idle equivalence or foreground-cooling verdict'; samples = 0 }
$taskWriter = [IO.StreamWriter]::new([IO.File]::Open($taskSamples, [IO.FileMode]::CreateNew), [Text.UTF8Encoding]::new($false))
$taskGpuWriter = $null
try {
    $taskGpuWriter = [IO.StreamWriter]::new([IO.File]::Open($taskEngines, [IO.FileMode]::CreateNew), [Text.UTF8Encoding]::new($false))
    $taskWriter.WriteLine('utc,processCpuTicks,workingSetBytes,privateBytes,foreground,gpuEngineCount,gpuBusiestEnginePercent')
    $taskGpuWriter.WriteLine('utc,engine,utilizationPercent')
    $taskTimer = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        $taskState = Read-Observation
        if ($taskState.status -in @('passed', 'failed')) {
            if ($taskState.status -cne 'passed' -or $taskReport.samples -lt 2) { throw 'Ordinary observation failed or native coverage is missing.' }
            $taskReport.status = 'completed'
            break
        }
        if ($taskTimer.Elapsed.TotalSeconds -gt $TimeoutSeconds) { throw 'Native process collection timed out.' }
        $taskProcess.Refresh()
        if ($taskProcess.HasExited -or $taskProcess.StartTime -ne $taskStart) { throw 'Observed process exited or its PID was reused.' }
        $taskUtc = [DateTime]::UtcNow.ToString('o')
        $taskGpu = @(Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine -Filter "Name LIKE 'pid_${taskClientPid}_%'" |
            Where-Object { $_.Name.StartsWith("pid_${taskClientPid}_", [StringComparison]::Ordinal) })
        foreach ($taskEngine in $taskGpu) {
            $taskGpuWriter.WriteLine("$taskUtc,$($taskEngine.Name),$($taskEngine.UtilizationPercentage)")
        }
        $taskMaximum = if ($taskGpu.Count) { ($taskGpu | Measure-Object UtilizationPercentage -Maximum).Maximum } else { '' }
        $taskForeground = [int]([LumiObservationForeground]::ProcessId() -eq $taskClientPid)
        $taskWriter.WriteLine("$taskUtc,$($taskProcess.TotalProcessorTime.Ticks),$($taskProcess.WorkingSet64),$($taskProcess.PrivateMemorySize64),$taskForeground,$($taskGpu.Count),$taskMaximum")
        $taskWriter.Flush()
        $taskGpuWriter.Flush()
        $taskReport.samples++
        Start-Sleep -Milliseconds 1000
    }
} catch {
    $taskReport.status = 'failed'
    $taskReport.failure = $_.Exception.Message
    throw
} finally {
    $taskWriter.Dispose()
    if ($taskGpuWriter) { $taskGpuWriter.Dispose() }
    $taskReport.finishedUtc = [DateTime]::UtcNow.ToString('o')
    $taskReport | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $taskReceipt -Encoding UTF8
}
