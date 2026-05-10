param(
    [string]$JavaHome,
    [string[]]$Failpoints = @(
        "before-draft-freeze",
        "after-draft-freeze",
        "after-operation-draft-write",
        "after-patch-data-write",
        "before-version-manifest-write",
        "before-variant-metadata-write",
        "mid-world-operation-apply",
        "light-refresh-drain-start"
    ),
    [int]$MarkerTimeoutSeconds = 240,
    [switch]$FullStack
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$testClientScript = Join-Path $PSScriptRoot "run-test-client.ps1"
$outputRoot = Join-Path $repoRoot "build\crash-safety"

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

function Invoke-TestClientMode {
    param(
        [string]$Mode,
        [hashtable]$Environment = @()
    )

    $previous = @{}
    foreach ($key in $Environment.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, "Process")
        [Environment]::SetEnvironmentVariable($key, [string]$Environment[$key], "Process")
    }
    $previousMode = $env:LUMI_SINGLEPLAYER_TEST_MODE
    try {
        $env:LUMI_SINGLEPLAYER_TEST_MODE = $Mode
        $arguments = @("-GradleTasks", "runClientGameTest")
        if ($JavaHome) {
            $arguments += @("-JavaHome", $JavaHome)
        }
        if ($FullStack) {
            $arguments += "-FullStack"
        }
        & $testClientScript @arguments
    } finally {
        if ($null -eq $previousMode) {
            Remove-Item Env:\LUMI_SINGLEPLAYER_TEST_MODE -ErrorAction SilentlyContinue
        } else {
            $env:LUMI_SINGLEPLAYER_TEST_MODE = $previousMode
        }
        foreach ($key in $Environment.Keys) {
            [Environment]::SetEnvironmentVariable($key, $previous[$key], "Process")
        }
    }
}

function Invoke-CrashSample {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Failpoint,
        [Parameter(Mandatory = $true)]
        [string]$RunDirectory
    )

    $marker = Join-Path $RunDirectory "$Failpoint.marker"
    $log = Join-Path $RunDirectory "$Failpoint.log"
    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $testClientScript,
        "-GradleTasks", "runClientGameTest"
    )
    if ($JavaHome) {
        $arguments += @("-JavaHome", $JavaHome)
    }
    if ($FullStack) {
        $arguments += "-FullStack"
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new("powershell.exe")
    foreach ($argument in $arguments) {
        [void]$startInfo.ArgumentList.Add($argument)
    }
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.UseShellExecute = $false
    $startInfo.Environment["LUMI_SINGLEPLAYER_TEST_MODE"] = "crash-safety"
    $startInfo.Environment["LUMI_TEST_FAILPOINT_ENABLED"] = "true"
    $startInfo.Environment["LUMI_TEST_FAILPOINT"] = $Failpoint
    $startInfo.Environment["LUMI_TEST_FAILPOINT_ACTION"] = "sleep"
    $startInfo.Environment["LUMI_TEST_FAILPOINT_SLEEP_MILLIS"] = "60000"
    $startInfo.Environment["LUMI_TEST_FAILPOINT_MARKER"] = $marker

    $process = [System.Diagnostics.Process]::Start($startInfo)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddSeconds($MarkerTimeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $marker) {
            Stop-ProcessTree -ProcessId $process.Id
            break
        }
        if ($process.HasExited) {
            break
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-Path -LiteralPath $marker)) {
        Stop-ProcessTree -ProcessId $process.Id
        throw "Failpoint '$Failpoint' did not write marker within $MarkerTimeoutSeconds seconds."
    }

    $process.WaitForExit(10000) | Out-Null
    [System.IO.File]::WriteAllText(
        $log,
        "Failpoint: $Failpoint`nExitCode: $($process.ExitCode)`n`n$($stdoutTask.Result)`n$($stderrTask.Result)"
    )
}

New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$runDirectory = Join-Path $outputRoot (Get-Date -Format "yyyyMMdd-HHmmss")
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null

Push-Location $repoRoot
try {
    foreach ($failpoint in $Failpoints) {
        Invoke-CrashSample -Failpoint $failpoint -RunDirectory $runDirectory
        Invoke-TestClientMode -Mode "crash-safety" -Environment @{
            LUMI_TEST_CRASH_VERIFY = "true"
        }
    }
    Write-Host "Crash safety logs: $runDirectory"
} finally {
    Pop-Location
}
