. (Join-Path $PSScriptRoot '../CrashMatrixDefinition.ps1')
. (Join-Path $PSScriptRoot '../VerificationEvidence.ps1')
$ErrorActionPreference = 'Stop'
function Assert-That([bool] $value, [string] $message) { if (-not $value) { throw $message } }
function Assert-Rejected([scriptblock] $action) { try { & $action } catch { return }; throw 'Invalid evidence accepted.' }
$taskRoot = Join-Path ([IO.Path]::GetTempPath()) ('lumi-scope-evidence-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $taskRoot | Out-Null
try {
    $taskLog = Join-Path $taskRoot 'run.log'
    [IO.File]::WriteAllText($taskLog, 'BUILD SUCCESSFUL')
    $taskEvidence = [LumiVerificationEvidence]::new(@{ jar = 'a'; tests = 'b'; jvm = 'c'; fixture = 'd'; verification = 'e' })
    Assert-Rejected { $taskEvidence.Record($taskRoot, $taskLog, 'SMOKE', ('a' * 64)) }
    $taskProof = @{ fingerprint = $taskEvidence.Fingerprint; paths = @($taskLog)
        sha256 = [LumiCrashMatrixEvidence]::Digest([LumiCrashMatrixEvidence]::Inventory(@($taskLog))) }
    $taskManifest = Join-Path $taskRoot 'manifest.json'
    @{ schema = 3; gitSha = 'documentation-only-old-commit'; gates = @{ Smoke = @{
        status = 'passed'; artifact = $taskRoot; proof = $taskProof } } } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskManifest -Encoding UTF8
    Assert-That ($taskEvidence.Find(@($taskManifest), 'Smoke').status -ceq 'reused') 'Git provenance invalidated identical inputs.'
    foreach ($taskInput in @('jar', 'tests', 'jvm', 'fixture', 'verification')) {
        $taskInputs = @{ jar = 'a'; tests = 'b'; jvm = 'c'; fixture = 'd'; verification = 'e' }
        $taskInputs[$taskInput] = 'changed'
        $taskChanged = [LumiVerificationEvidence]::new($taskInputs)
        Assert-That ($null -eq $taskChanged.Find(@($taskManifest), 'Smoke')) "$taskInput failed to invalidate reuse."
    }
    [IO.File]::AppendAllText($taskLog, 'tampered')
    Assert-Rejected { $taskEvidence.Find(@($taskManifest), 'Smoke') }
    [IO.File]::Delete($taskLog)
    Assert-Rejected { $taskEvidence.Find(@($taskManifest), 'Smoke') }
    $taskRuntime = Join-Path $taskRoot 'runtime'
    $taskOutput = Join-Path $taskRoot 'gate'
    New-Item -ItemType Directory -Path (Join-Path $taskRuntime 'logs'), $taskOutput | Out-Null
    $taskJar = Join-Path $taskRuntime 'lumi.jar'
    [IO.File]::WriteAllText($taskJar, 'candidate')
    $taskHash = (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $taskReceipt = Join-Path $taskRuntime 'lumi-evidence.json'
    $taskCompletion = Join-Path $taskRuntime 'client.completed'
    [IO.File]::WriteAllText($taskCompletion, "SMOKE`n$taskHash")
    [IO.File]::WriteAllText((Join-Path $taskRuntime 'logs/latest.log'), 'exact assertions')
    @{ status = 'passed'; suite = 'SMOKE'; artifact = @{ path = $taskJar; sha256 = $taskHash } } |
        ConvertTo-Json | Set-Content -LiteralPath $taskReceipt -Encoding UTF8
    $taskRunLog = Join-Path $taskOutput 'run.log'
    [IO.File]::WriteAllText($taskRunLog, "Packaged client 1.21.11/SMOKE: passed; evidence: $taskReceipt")
    $taskRecord = $taskEvidence.Record($taskOutput, $taskRunLog, 'SMOKE', $taskHash)
    Assert-That ($taskRecord.paths -contains $taskCompletion) 'Completion evidence was not retained for integrity checks.'
    Assert-Rejected { $taskEvidence.Record($taskOutput, $taskRunLog, 'ALL', $taskHash) }
    [IO.File]::Delete($taskCompletion)
    Assert-Rejected { $taskEvidence.Record($taskOutput, $taskRunLog, 'SMOKE', $taskHash) }
    Assert-That (-not [LumiVerificationEvidence]::Reusable('RestoreSoak')) 'A warm reused run can replace a cold/soak sample.'
} finally {
    if (-not [IO.Path]::GetFullPath($taskRoot).StartsWith([IO.Path]::GetTempPath(), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence test cleanup escaped the temporary directory.'
    }
    Remove-Item -LiteralPath $taskRoot -Recurse -Force
}
Write-Output 'Verification evidence invalidation and tamper checks passed.'
