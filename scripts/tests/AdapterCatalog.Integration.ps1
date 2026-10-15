$ErrorActionPreference = 'Stop'
$taskRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskRoot = Join-Path $taskRepo ('build/reports/lumi-adapter-catalog/' + [Guid]::NewGuid().ToString('N'))
$taskCatalog = Get-Content -LiteralPath (Join-Path $taskRepo 'gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
foreach ($taskCase in @('retire', 'extend')) {
    $taskDirectory = Join-Path $taskRoot $taskCase
    New-Item -ItemType Directory -Path (Join-Path $taskDirectory 'modern'), (Join-Path $taskDirectory 'gradle'),
        (Join-Path $taskDirectory 'core') | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskRepo 'modern/settings.gradle') -Destination (Join-Path $taskDirectory 'modern')
    [IO.File]::WriteAllText((Join-Path $taskDirectory 'build-modern.gradle'), "plugins { id 'base' }`n")
    $taskFixture = $taskCatalog | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    if ($taskCase -eq 'retire') {
        $taskFixture.targets = @($taskFixture.targets | Where-Object { $_.id -cne 'modern-262' })
    } else {
        $taskNew = $taskFixture.targets[-1] | ConvertTo-Json -Depth 10 | ConvertFrom-Json
        $taskNew.id = 'modern-future'
        $taskNew.minecraftVersion = '99.0'
        $taskNew.minecraftVersions = @('99.0')
        $taskFixture.targets += $taskNew
    }
    $taskFixture | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $taskDirectory 'gradle/minecraft-targets.json') -Encoding UTF8
    $taskLog = Join-Path $taskDirectory 'projects.log'
    $ErrorActionPreference = 'Continue'
    & (Join-Path $taskRepo 'gradlew.bat') -p (Join-Path $taskDirectory 'modern') projects --console=plain *> $taskLog
    $taskExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($taskExit -ne 0) { throw "Catalog configuration failed: $taskCase; see $taskLog" }
    $taskOutput = Get-Content -LiteralPath $taskLog -Raw
    foreach ($taskId in @('modern-261', 'modern-262', 'modern-263', 'modern-future')) {
        $taskExpected = $taskId -cin @($taskFixture.targets.id)
        if ($taskOutput.Contains("Project ':adapters:$taskId'") -ne $taskExpected) {
            throw "Wrong adapter membership for $taskCase / $taskId; see $taskLog"
        }
    }
}
Write-Output "Catalog-driven adapter retirement and extension passed: $taskRoot"
