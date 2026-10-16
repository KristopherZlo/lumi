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
Assert-That ($taskLegacy.RequiredReleaseGates() -notcontains 'UpgradeCrash') 'Legacy must not require history conversion from an older supported game.'
Assert-That ($taskNative.RequiredReleaseGates() -contains 'UpgradeCrash') 'Native release must require process-crash conversion coverage.'
$task263 = [LumiMinecraftVerificationTarget]::new($taskCatalog, 'modern-263', '', '')
foreach ($taskBackendGate in @('ClientVulkan', 'NativeViewport')) {
    Assert-That ($task263.RequiredReleaseGates() -contains $taskBackendGate) "26.3 release can omit $taskBackendGate."
    Assert-That ($taskLegacy.RequiredReleaseGates() -notcontains $taskBackendGate) 'Java 21 must not acquire Vulkan gates.'
    Assert-That ($taskNative.RequiredReleaseGates() -notcontains $taskBackendGate) '26.2 must not acquire 26.3 native-window gates.'
}
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
    $taskFrozenLegacy = [LumiMinecraftVerificationTarget]::new($taskCatalog, 'legacy-12111', '', $taskFixture)
    Assert-That (-not $taskFrozenLegacy.Modern) 'Frozen legacy launch must use the root build.'
    Assert-That ($taskFrozenLegacy.PrepareArguments() -contains ("-PlumiArtifact=" + [IO.Path]::GetFullPath($taskFixture))) 'Frozen legacy JAR missing.'
} finally {
    Remove-Item -LiteralPath $taskFixture
}
Write-Output 'Minecraft verification target checks passed.'
