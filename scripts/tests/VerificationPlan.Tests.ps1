. (Join-Path $PSScriptRoot '../MinecraftVerificationTarget.ps1')
. (Join-Path $PSScriptRoot '../VerificationPlan.ps1')
$ErrorActionPreference = 'Stop'
function Assert-That([bool] $condition, [string] $message) { if (-not $condition) { throw $message } }
$taskCatalog = Get-Content -LiteralPath (Join-Path $PSScriptRoot '../../gradle/minecraft-targets.json') -Raw | ConvertFrom-Json
$taskLegacy = [LumiMinecraftVerificationTarget]::new($taskCatalog, 'legacy-12111', '', '')
$task263 = [LumiMinecraftVerificationTarget]::new($taskCatalog, 'modern-263', '', '')
$taskDevelopment = [LumiVerificationPlan]::new('Development', @())
Assert-That (($taskDevelopment.Gates -join ',') -ceq 'Compile,Core') 'An ordinary edit launches Minecraft.'
$taskCandidate = [LumiVerificationPlan]::new('Candidate', @())
Assert-That ($taskCandidate.Gates -notcontains 'RestoreSoak') 'Candidate implicitly starts a long soak.'
Assert-That ($taskCandidate.Gates -notcontains 'Exact') 'Candidate repeats the ALL exact suite.'
Assert-That ($taskCandidate.Gates -contains 'Render') 'ALL does not establish the focused render contract.'
$taskFocused = [LumiVerificationPlan]::new('Adapter', @('Server', 'Server', 'Tools'))
Assert-That (($taskFocused.Gates -join ',') -ceq 'Server,Tools') 'Explicit gates were expanded or duplicated.'
Assert-That ($taskFocused.Suite('Tools') -ceq 'BUILDER_TOOLS') 'Tool scope runs common history again.'
Assert-That (-not $taskCandidate.Applies('ClientVulkan', $taskLegacy)) 'Legacy acquired a Vulkan check.'
Assert-That ($taskCandidate.Applies('ClientVulkan', $task263)) '26.3 omitted Vulkan.'
$taskDescription = $taskFocused.Describe($task263)
Assert-That ($taskDescription[0].arguments -contains ':adapters:modern-263:runPackagedGameTest') 'Dry plan selects another adapter.'
Assert-That ($taskDescription[1].arguments -contains '-Dlumi.gametest.suite=BUILDER_TOOLS') 'Dry plan lost its suite.'
$taskRejected = $false
try { [LumiVerificationPlan]::new('Adapter', @('Typo')) } catch { $taskRejected = $true }
Assert-That $taskRejected 'Unknown gate was silently ignored.'
Write-Output 'Verification selection checks passed.'
