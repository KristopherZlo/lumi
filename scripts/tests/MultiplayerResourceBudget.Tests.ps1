$ErrorActionPreference = 'Stop'
$taskScript = Join-Path $PSScriptRoot '../run-multiplayer-gate.ps1'
try {
    & $taskScript
    throw 'Multiplayer unexpectedly started without opt-in'
} catch {
    if ($_.Exception.Message -notlike 'No tests started.*') { throw }
}
& {
    function Get-CimInstance {
        param([string]$ClassName)
        if ($ClassName -cne 'Win32_OperatingSystem') { throw 'Unexpected preflight query' }
        [pscustomobject]@{ FreePhysicalMemory = 512 * 1024 }
    }
    try {
        & $taskScript -RunMinecraft
        throw 'Multiplayer unexpectedly launched on a memory-starved host'
    } catch {
        if ($_.Exception.Message -notlike 'Multiplayer gate needs at least 4 GiB*') { throw }
    }
}
Write-Output 'Multiplayer opt-in and low-memory refusal passed without starting Java.'
