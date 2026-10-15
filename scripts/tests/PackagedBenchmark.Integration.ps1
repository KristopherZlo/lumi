param(
    [switch]$RunMinecraft,
    [string]$Target = 'modern-262',
    [string]$GameVersion,
    [string]$Artifact
)
$ErrorActionPreference = 'Stop'
if (-not $RunMinecraft) { throw 'Pass -RunMinecraft to check packaged benchmark modes and open a test client.' }
. (Join-Path $PSScriptRoot '../MinecraftVerificationTarget.ps1')
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskProfile = [LumiMinecraftVerificationTarget]::new($taskCatalog, $Target, $GameVersion, $Artifact)
$taskOutput = Join-Path $taskRepo ('build/reports/lumi-benchmark-modes/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskOutput | Out-Null
$taskCases = @(
    @{ name = 'disabled'; task = 'runPackagedBenchmark'; args = @(); message = 'Packaged benchmark requires BENCHMARK suite' },
    @{ name = 'functional'; task = 'runPackagedClientGameTest'; args = @('-Dlumi.gametest.suite=SMOKE', '-Dlumi.benchmark.enabled=true'); message = 'functional suites require full scenarios' },
    @{ name = 'traversal'; task = 'runPackagedBenchmark'; args = @('-Dlumi.gametest.suite=BENCHMARK', '-Dlumi.benchmark.enabled=true', '-Dlumi.benchmark.existingWorldSource=.', '-Dlumi.benchmark.existingWorld=../escape'); message = 'must be one save-folder name' }
)
if ($taskProfile.Modern) {
    $taskOther = @($taskCatalog.targets | Where-Object { $_.id -cne $Target })[0].minecraftVersions[0]
    $taskCases += @{ name = 'wrong-game'; task = 'runPackagedBenchmark';
        args = @('-Dlumi.gametest.suite=BENCHMARK', '-Dlumi.benchmark.enabled=true', "-PlumiGameVersion=$taskOther");
        message = "is outside target $Target" }
}
Push-Location $taskRepo
try {
    foreach ($taskCase in $taskCases) {
        $taskLog = Join-Path $taskOutput ($taskCase.name + '.log')
        $taskArguments = $taskProfile.TaskArguments(@($taskCase.task)) + $taskCase.args + @('--console=plain', '--stacktrace')
        $ErrorActionPreference = 'Continue'
        & (Join-Path $taskRepo 'gradlew.bat') @taskArguments *> $taskLog
        $taskExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($taskExit -eq 0 -or -not (Select-String -LiteralPath $taskLog -SimpleMatch $taskCase.message -Quiet)) {
            throw "Guard failed: $($taskCase.name); see $taskLog"
        }
    }
    $taskArguments = $taskProfile.TaskArguments(@('runPackagedBenchmark')) + @(
        '-Dlumi.gametest.suite=BENCHMARK', '-Dlumi.benchmark.enabled=true',
        '-Dlumi.benchmark.baseSize=16', '-Dlumi.benchmark.changeSize=16',
        '-Dlumi.benchmark.layers=1', '-Dlumi.benchmark.commits=2', '-Dlumi.benchmark.restoreSamples=2', '--console=plain')
    $ErrorActionPreference = 'Continue'
    & (Join-Path $taskRepo 'gradlew.bat') @taskArguments *> (Join-Path $taskOutput 'benchmark.log')
    $taskExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($taskExit -ne 0) { throw "Packaged benchmark smoke failed; see $taskOutput" }
    Write-Output "Packaged benchmark guards and smoke passed: $taskOutput"
} finally {
    Pop-Location
}
