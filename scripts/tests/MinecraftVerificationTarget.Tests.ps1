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
Assert-That ($taskLegacy.RequiredReleaseGates() -contains 'Server') 'Core units no longer imply native server coverage.'
Assert-That ($taskLegacy.RequiredReleaseGates() -contains 'Render') 'ALL does not execute the focused renderer.'
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
    # Disposable support changes must keep one family artifact and its compile baseline.
    $taskLifecycle = $taskCatalog | ConvertTo-Json -Depth 10 | ConvertFrom-Json
    $taskFamily = @($taskLifecycle.targets | Where-Object id -CEQ 'modern-261')[0]
    $taskFamily.minecraftVersions += '26.1.99'
    foreach ($taskGame in $taskFamily.minecraftVersions) {
        $taskSelection = [LumiMinecraftVerificationTarget]::new($taskLifecycle, 'modern-261', $taskGame, $taskFixture)
        Assert-That ($taskSelection.Artifact -ceq $taskPatch.Artifact) 'A family patch selected another artifact.'
        Assert-That ($taskSelection.PrepareArguments() -contains ':adapters:modern-261:prepareMultiplayerLaunch') 'A family patch selected another adapter.'
        Assert-That ($taskSelection.PrepareArguments() -contains "-PlumiGameVersion=$taskGame") 'A family patch lost its runtime selection.'
    }
    $taskFamily.minecraftVersions = @($taskFamily.minecraftVersions | Where-Object { $_ -cne '26.1' })
    Assert-Rejected { [LumiMinecraftVerificationTarget]::new($taskLifecycle, 'modern-261', '26.1', $taskFixture) }
    Assert-Rejected { [LumiMinecraftVerificationTarget]::new($taskLifecycle, 'modern-261', '26.1.100', $taskFixture) }
    $taskAfterRetirement = [LumiMinecraftVerificationTarget]::new($taskLifecycle, 'modern-261', '26.1.1', $taskFixture)
    Assert-That ($taskAfterRetirement.Artifact -ceq $taskPatch.Artifact) 'Retirement replaced the family artifact.'
    Assert-That ($taskFamily.minecraftVersion -ceq '26.1') 'Retiring a runtime changed the compile baseline.'
    Assert-That ($taskCatalog.targets[1].minecraftVersions -cnotcontains '26.1.99') 'Lifecycle fixtures changed the real catalog.'
} finally {
    Remove-Item -LiteralPath $taskFixture
}
Write-Output 'Minecraft verification target checks passed.'
