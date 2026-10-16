$ErrorActionPreference = 'Stop'
$taskDirectory = Join-Path ([IO.Path]::GetTempPath()) ('lumi-native-samples-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskDirectory | Out-Null
$taskMetadata = Join-Path $taskDirectory 'observation.properties'
$taskScript = Join-Path $PSScriptRoot '../collect-native-client-samples.ps1'
foreach ($taskCase in @(
    @{ data = "status=passed`npid=$PID"; reason = 'running ordinary-client observation' },
    @{ data = "status=running`npid=invalid"; reason = 'running ordinary-client observation' },
    @{ data = "status=running`npid=$PID"; reason = 'not an enabled ordinary Minecraft' }
)) {
    [IO.File]::WriteAllText($taskMetadata, $taskCase.data)
    $taskRejected = $false
    try { & $taskScript -ObservationDirectory $taskDirectory } catch {
        if ($_.Exception.Message -notlike "*$($taskCase.reason)*") { throw }
        $taskRejected = $true
    }
    if (-not $taskRejected) { throw "Unsafe native observer input was accepted: $taskCase" }
    if (@(Get-ChildItem -LiteralPath $taskDirectory -Filter 'native-*').Count) { throw 'Rejected input created output' }
}
Write-Output 'Native client sample guards passed: completed/invalid/wrong process rejected without output.'
