class LumiVerificationEvidence {
    [string] $Fingerprint

    LumiVerificationEvidence([object] $inputs) {
        $this.Fingerprint = [LumiCrashMatrixEvidence]::Digest($inputs)
    }

    static [bool] Reusable([string] $gate) {
        return $gate -in @('Server', 'Smoke', 'Client', 'Render', 'Tools', 'UI', 'Interaction',
            'Exact', 'Recovery', 'ClientVulkan')
    }

    [object] Record([string] $directory, [string] $log, [string] $suite, [string] $artifactHash) {
        $lines = [IO.File]::ReadAllLines($log)
        $paths = @($directory)
        $receipts = @($lines | Where-Object { $_ -match '^Packaged .+; evidence: .+\.json$' })
        if ($receipts.Count -eq 0) { throw 'Missing packaged runtime evidence; a zero-case run cannot qualify.' }
        foreach ($line in $receipts) {
            $path = ($line -split '; evidence: ', 2)[1]
            $record = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($record.status -cne 'passed' -or
                ($artifactHash -and $record.artifact.sha256 -cne $artifactHash) -or
                (Get-FileHash -LiteralPath $record.artifact.path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $record.artifact.sha256) {
                throw "Invalid packaged artifact receipt: $path"
            }
            $paths += $path
            if ($suite) {
                if ($record.suite -cne $suite) { throw "Wrong client suite: $path" }
                if ($suite -ceq 'ALL') {
                    foreach ($name in @('gate exact_restore', 'gate exact_restore_reopen', 'gate recovery_reopen')) {
                        if ($record.cases -cnotcontains $name) { throw "ALL lacks required assertion $name : $path" }
                    }
                }
                $completion = Join-Path (Split-Path $path -Parent) 'client.completed'
                [LumiCrashMatrixEvidence]::AssertClientCompletion($completion, $suite, $record.artifact.sha256)
                $paths += $completion
            } else {
                if ($record.scope -cne 'server-gametest' -or $record.tests -le 0) { throw "Empty server case selection: $path" }
                $paths += Join-Path (Split-Path $path -Parent) 'gametest-results.xml'
            }
            $paths += Join-Path (Split-Path $path -Parent) 'logs/latest.log'
        }
        $inventory = [LumiCrashMatrixEvidence]::Inventory($paths)
        if (@($inventory | Where-Object { $_.type -eq 'missing' }).Count) { throw 'Incomplete retained runtime evidence.' }
        return [ordered]@{ fingerprint = $this.Fingerprint; paths = $paths
            sha256 = [LumiCrashMatrixEvidence]::Digest($inventory) }
    }

    [object] Find([string[]] $manifests, [string] $gate) {
        foreach ($path in $manifests) {
            $manifest = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($manifest.schema -ne 3) { throw "Unsupported reusable evidence schema: $path" }
            $property = $manifest.gates.PSObject.Properties[$gate]
            if ($null -eq $property) { continue }
            $result = $property.Value
            if ($result.status -cnotin @('passed', 'reused')) { continue }
            $proof = $result.proof
            if ($null -eq $proof -or $proof.fingerprint -cne $this.Fingerprint) { continue }
            if (@($proof.paths).Count -eq 0 -or
                [LumiCrashMatrixEvidence]::Digest([LumiCrashMatrixEvidence]::Inventory(@($proof.paths))) -cne $proof.sha256) {
                throw "Reusable evidence is missing or changed: $path / $gate"
            }
            return [ordered]@{ status = 'reused'; artifact = $result.artifact; proof = $proof
                sourceManifest = [IO.Path]::GetFullPath($path); detail = 'Identical executable inputs and intact retained evidence.' }
        }
        return $null
    }
}
