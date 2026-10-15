. (Join-Path $PSScriptRoot '../MinecraftVerificationTarget.ps1')
$ErrorActionPreference = 'Stop'
function Assert-That([bool] $condition, [string] $message) {
    if (-not $condition) { throw $message }
}
function Assert-Rejected([scriptblock] $action) {
    try { & $action } catch { return }
    throw 'Invalid verification target was accepted.'
}
$taskCatalog = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskLegacy = [LumiMinecraftVerificationTarget]::new($taskCatalog, 'legacy-12111', '', '')
Assert-That (-not $taskLegacy.Modern) 'Legacy launch must keep its Java 21 path.'
Assert-That ($taskLegacy.PrepareArguments() -contains '-PlumiTarget=legacy-12111') 'Legacy target missing.'
Assert-That ($taskLegacy.TaskArguments(@('test')) -contains ':test') 'Legacy test must not select child projects.'
$taskNative = [LumiMinecraftVerificationTarget]::new($taskCatalog, 'modern-262', '', '')
Assert-That ($taskNative.PrepareArguments() -contains ':adapters:modern-262:prepareMultiplayerLaunch') 'Native task missing.'
Assert-That ($taskNative.TaskArguments(@(':core:test', 'runPackagedGameTest')) -contains ':core:test') 'Absolute task changed.'
Assert-That ($taskNative.TaskArguments(@('runPackagedGameTest')) -contains ':adapters:modern-262:runPackagedGameTest') 'Wrong native server task.'
Assert-Rejected { [LumiMinecraftVerificationTarget]::new($taskCatalog, 'missing', '', '') }
Assert-Rejected { [LumiMinecraftVerificationTarget]::new($taskCatalog, 'modern-262', '26.1', '') }
Assert-Rejected { [LumiMinecraftVerificationTarget]::new($taskCatalog, 'modern-261', '26.1.2', '') }
Assert-Rejected { [LumiMinecraftVerificationTarget]::new($taskCatalog, 'modern-261', '26.1.2', 'missing.jar') }
$taskFixture = Join-Path ([IO.Path]::GetTempPath()) ('lumi-frozen-' + [Guid]::NewGuid().ToString('N') + '.jar')
try {
    [IO.File]::WriteAllBytes($taskFixture, [byte[]]@(1))
    $taskPatch = [LumiMinecraftVerificationTarget]::new($taskCatalog, 'modern-261', '26.1.2', $taskFixture)
    Assert-That ($taskPatch.Definition.minecraftVersion -ceq '26.1.2') 'Runtime release was lost.'
    Assert-That ($taskPatch.PrepareArguments() -contains '-PlumiGameVersion=26.1.2') 'Selected runtime argument missing.'
    Assert-That ($taskPatch.PrepareArguments() -contains ("-PlumiArtifact=" + [IO.Path]::GetFullPath($taskFixture))) 'Frozen JAR missing.'
    Assert-That ($taskCatalog.targets[1].minecraftVersion -ceq '26.1') 'Verification mutated the compile catalog.'
    Assert-Rejected { [LumiMinecraftVerificationTarget]::new($taskCatalog, 'legacy-12111', '', $taskFixture) }
} finally {
    Remove-Item -LiteralPath $taskFixture
}
Write-Output 'Minecraft verification target checks passed.'
