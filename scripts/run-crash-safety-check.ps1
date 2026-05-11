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

function ConvertTo-ProcessArgument {
    param(
        [AllowNull()]
        [string]$Argument
    )

    if ($null -eq $Argument -or $Argument.Length -eq 0) {
        return '""'
    }
    if ($Argument -notmatch '[\s"]') {
        return $Argument
    }
    return '"' + ($Argument -replace '"', '\"') + '"'
}

function Join-ProcessArguments {
    param(
        [string[]]$Arguments
    )

    return (($Arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join " ")
}

function Set-ProcessEnvironmentVariable {
    param(
        [Parameter(Mandatory = $true)]
        [System.Diagnostics.ProcessStartInfo]$StartInfo,
        [Parameter(Mandatory = $true)]
        [string]$Name,
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    if ($StartInfo.PSObject.Properties["Environment"]) {
        $StartInfo.Environment[$Name] = $Value
        return
    }
    $StartInfo.EnvironmentVariables[$Name] = $Value
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
        $arguments = @{
            GradleTasks = @("runClientGameTest")
        }
        if ($JavaHome) {
            $arguments.JavaHome = $JavaHome
        }
        if ($FullStack) {
            $arguments.FullStack = $true
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
        $arguments = @(
            "-NoProfile",
            "-ExecutionPolicy", "Bypass",
            "-File", $testClientScript,
            "-JavaHome", $JavaHome,
            "-GradleTasks", "runClientGameTest"
        )
    }
    if ($FullStack) {
        $arguments += "-FullStack"
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "powershell.exe"
    $startInfo.Arguments = Join-ProcessArguments -Arguments $arguments
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.UseShellExecute = $false
    Set-ProcessEnvironmentVariable -StartInfo $startInfo -Name "LUMI_SINGLEPLAYER_TEST_MODE" -Value "crash-safety"
    Set-ProcessEnvironmentVariable -StartInfo $startInfo -Name "LUMI_TEST_FAILPOINT_ENABLED" -Value "true"
    Set-ProcessEnvironmentVariable -StartInfo $startInfo -Name "LUMI_TEST_FAILPOINT" -Value $Failpoint
    Set-ProcessEnvironmentVariable -StartInfo $startInfo -Name "LUMI_TEST_FAILPOINT_ACTION" -Value "sleep"
    Set-ProcessEnvironmentVariable -StartInfo $startInfo -Name "LUMI_TEST_FAILPOINT_SLEEP_MILLIS" -Value "60000"
    Set-ProcessEnvironmentVariable -StartInfo $startInfo -Name "LUMI_TEST_FAILPOINT_MARKER" -Value $marker

    $process = [System.Diagnostics.Process]::Start($startInfo)
    if ($null -eq $process) {
        throw "Failed to start crash sample process for failpoint '$Failpoint'."
    }
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
    $failpointCount = $Failpoints.Count
    $failpointIndex = 0
    foreach ($failpoint in $Failpoints) {
        $failpointIndex++
        Write-Host "Crash safety failpoint $failpointIndex/$failpointCount crash sample: $failpoint"
        Invoke-CrashSample -Failpoint $failpoint -RunDirectory $runDirectory
        Write-Host "Crash safety failpoint $failpointIndex/$failpointCount recovery verify: $failpoint"
        Invoke-TestClientMode -Mode "crash-safety" -Environment @{
            LUMI_TEST_CRASH_VERIFY = "true"
        }
        Write-Host "Crash safety failpoint $failpointIndex/$failpointCount passed: $failpoint"
    }
    Write-Host "Crash safety logs: $runDirectory"
} finally {
    Pop-Location
}
