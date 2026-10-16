class LumiCrashMatrixDefinition {
    [string] $Kind
    [string[]] $Phases

    LumiCrashMatrixDefinition([string] $kind) {
        $this.Kind = $kind
        if ($kind -eq 'restore' -or $kind -eq 'merge') {
            $this.Phases = @('prepared', 'applying', 'write', 'barrier',
                'force', 'verify', 'publication', 'cleanup')
            if ($kind -eq 'merge') { $this.Phases = @('object-written', 'commit-written') + $this.Phases }
        } elseif ($kind -eq 'save' -or $kind -eq 'import') {
            $this.Phases = @('objects-staged', 'pack-forced', 'pack-published', 'index-forced',
                'objects-published', 'commit-written', 'ref-forced', 'ref-published')
            if ($kind -eq 'save') {
                $this.Phases += @('journal-created', 'working-cleared', 'journal-complete', 'cleanup')
            } else { $this.Phases += 'preview-written' }
        } elseif ($kind -eq 'upgrade') {
            $this.Phases = @('ALLOCATED', 'WORLD_OBJECT', 'COMMIT', 'METADATA',
                'VERIFICATION', 'VERIFIED', 'PUBLISHED', 'COMPLETED')
        } else { throw "Unknown crash matrix: $kind" }
    }

    [string[]] Arguments([string] $phase, [string] $mode, [string] $marker, [bool] $cold) {
        if ($phase -notin $this.Phases -or $mode -notin @('produce', 'verify')) {
            throw 'Unknown crash phase or mode.'
        }
        if ($cold -and $this.Kind -ne 'restore') { throw 'Cold fixture requires the Restore matrix.' }
        if ($this.Kind -eq 'upgrade') {
            $arguments = @('-Dlumi.gametest.suite=UPGRADE')
            if ($mode -eq 'produce') {
                return $arguments + @('-Dlumi.gametest.upgrade.stage=startup',
                    "-Dlumi.gametest.upgrade.cut=$phase", "-Dlumi.gametest.upgrade.marker=$marker")
            }
            return $arguments + @('-Dlumi.gametest.upgrade.stage=resume', "-Dlumi.gametest.upgrade.expectedCut=$phase")
        }
        if ($this.Kind -eq 'restore') {
            $arguments = @('-Dlumi.gametest.suite=RESTORE_CRASH',
                "-Dlumi.gametest.restoreCrashMode=$mode")
            if ($mode -eq 'produce') {
                $arguments += @("-Dlumi.gametest.restoreCrashPhase=$phase", "-Dlumi.gametest.restoreCrashMarker=$marker")
            } else { $arguments += "-Dlumi.gametest.restoreCrashExpectedPhase=$phase" }
            if ($cold) { $arguments += '-Dlumi.gametest.restoreCrashCold=true' }
            return $arguments
        }
        return @('-Dlumi.gametest.suite=PERSISTENCE_CRASH', "-Dlumi.persistence.kind=$($this.Kind)",
            "-Dlumi.persistence.phase=$phase", "-Dlumi.persistence.mode=$mode", "-Dlumi.persistence.marker=$marker")
    }

    [string] Suite() {
        if ($this.Kind -eq 'upgrade') { return 'UPGRADE' }
        if ($this.Kind -eq 'restore') { return 'RESTORE_CRASH' }
        return 'PERSISTENCE_CRASH'
    }

    [void] AssertMarker([string] $path, [string] $phase) {
        if (-not [IO.File]::Exists($path)) { throw "Crash marker is missing: $path" }
        $lines = [IO.File]::ReadAllLines($path)
        if ($this.Kind -eq 'restore' -or $this.Kind -eq 'upgrade') {
            if ([IO.File]::ReadAllText($path) -cne $phase) { throw "Wrong crash marker: $path" }
        } elseif ((@($lines | Where-Object { $_ -ceq "kind=$($this.Kind)" }).Count -ne 1) -or
            (@($lines | Where-Object { $_ -ceq "phase=$phase" }).Count -ne 1)) {
            throw "Wrong publication marker: $path"
        }
    }

    [string] Gate([string] $phase) {
        if ($phase -notin $this.Phases) { throw "Unknown crash phase: $phase" }
        if ($this.Kind -eq 'restore') { return "restore_crash_${phase}: succeeded" }
        if ($this.Kind -eq 'upgrade') { return "Lumi history upgrade restart ${phase}: succeeded" }
        return "publication_crash_$($this.Kind)_${phase}: succeeded"
    }
}

class LumiCrashMatrixEvidence {
    [string] $Fingerprint
    [string] $GitSha
    [bool] $Dirty
    [LumiCrashMatrixDefinition] $Definition

    LumiCrashMatrixEvidence([string] $fingerprint, [string] $gitSha, [bool] $dirty,
        [LumiCrashMatrixDefinition] $definition) {
        $this.Fingerprint = $fingerprint
        $this.GitSha = $gitSha
        $this.Dirty = $dirty
        $this.Definition = $definition
    }

    static [object[]] Inventory([string[]] $roots) {
        $inventory = [Collections.Generic.List[object]]::new()
        foreach ($root in $roots) {
            $path = [IO.Path]::GetFullPath($root)
            if (-not (Test-Path -LiteralPath $path)) {
                $inventory.Add([ordered]@{ path = $path; type = 'missing' })
            } elseif (Test-Path -LiteralPath $path -PathType Container) {
                $inventory.Add([ordered]@{ path = $path; type = 'directory' })
                foreach ($file in (Get-ChildItem -LiteralPath $path -Recurse -File -Force | Sort-Object FullName)) {
                    $inventory.Add([ordered]@{ path = $file.FullName; hash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash })
                }
            } else {
                $inventory.Add([ordered]@{ path = $path; hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash })
            }
        }
        return $inventory.ToArray()
    }

    static [string] Digest([object] $value) {
        $hash = [Security.Cryptography.SHA256]::Create()
        try {
            $json = ConvertTo-Json -InputObject $value -Depth 10 -Compress
            return [BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($json))).Replace('-', '')
        } finally { $hash.Dispose() }
    }

    static [void] AssertClientCompletion([string] $path, [string] $suite, [string] $hash) {
        if ($hash -cnotmatch '^[a-f0-9]{64}$' -or -not [IO.File]::Exists($path) -or
            ([IO.FileInfo]::new($path)).Length -gt 256 -or
            [IO.File]::ReadAllText($path) -cne "$suite`n$hash") {
            throw "Missing or mismatched packaged client completion: $path"
        }
    }

    static [string] Inputs([string] $repo, [string] $java) {
        return [LumiCrashMatrixEvidence]::Inputs($repo, $java, (Join-Path $repo 'build'))
    }

    static [string] Inputs([string] $repo, [string] $java, [string] $build) {
        return [LumiCrashMatrixEvidence]::Inputs($repo, $java, $build, '')
    }

    static [string] Inputs([string] $repo, [string] $java, [string] $build, [string] $fixture) {
        $descriptor = Join-Path $build 'multiplayer-launch.inputs.json'
        $launch = Get-Content -LiteralPath $descriptor -Raw | ConvertFrom-Json
        if ($launch.schema -ne 1) { throw 'Unknown prepared-launch input schema.' }
        $jdk = Split-Path (Split-Path ([IO.Path]::GetFullPath($java)) -Parent) -Parent
        $roots = @($launch.classpath) + @($descriptor, (Join-Path $build 'multiplayer-launch.args'))
        foreach ($script in @('scripts/CrashMatrixDefinition.ps1', 'scripts/run-publication-crash-matrix.ps1',
                'scripts/MinecraftVerificationTarget.ps1', 'gradle/client-verification.gradle', 'gradle/packaged-verification.gradle')) {
            $roots += Join-Path $repo $script
        }
        if ($fixture) { $roots += [IO.Path]::GetFullPath($fixture) }
        if ($launch.PSObject.Properties.Name -contains 'mods') { $roots += @($launch.mods) }
        if ($launch.PSObject.Properties.Name -contains 'configuration') {
            $roots += @($launch.configuration)
        } else {
            foreach ($name in @('launch.cfg', 'remapClasspath.txt', 'log4j.xml')) {
                $roots += Join-Path $repo ".gradle/loom-cache/$name"
            }
        }
        foreach ($part in @('bin', 'lib', 'conf', 'release')) { $roots += Join-Path $jdk $part }
        $identity = [ordered]@{
            files = [LumiCrashMatrixEvidence]::Inventory($roots); java = [IO.Path]::GetFullPath($java)
            machine = $env:COMPUTERNAME; os = [Environment]::OSVersion.VersionString
            gpu = @(Get-CimInstance Win32_VideoController | Sort-Object Name | Select-Object Name, DriverVersion)
            javaOptions = @($env:JAVA_TOOL_OPTIONS, $env:JDK_JAVA_OPTIONS, $env:_JAVA_OPTIONS)
        }
        return [LumiCrashMatrixEvidence]::Digest($identity)
    }

    [string] ArtifactDigest([string] $directory) {
        $roots = @()
        foreach ($name in @('crash.marker', 'produce.args', 'verify.args', 'produce.stdout.log',
            'produce.stderr.log', 'verify.stdout.log', 'verify.stderr.log', 'run')) {
            $path = Join-Path $directory $name
            if (-not (Test-Path -LiteralPath $path)) { throw "Missing crash artifact: $path" }
            $roots += $path
        }
        if ($this.Definition.Kind -eq 'restore') {
            $path = Join-Path $directory 'producer-metadata.properties'
            if (-not (Test-Path -LiteralPath $path)) { throw "Missing producer metadata: $path" }
            $roots += $path
        }
        if ($this.Definition.Kind -eq 'upgrade') {
            foreach ($name in @('producer-run', 'fixture.properties', 'source-a', 'source-b', 'crashed-target')) {
                $path = Join-Path $directory $name
                if (-not (Test-Path -LiteralPath $path)) { throw "Missing upgrade artifact: $path" }
                $roots += $path
            }
        }
        return [LumiCrashMatrixEvidence]::Digest([LumiCrashMatrixEvidence]::Inventory($roots))
    }

    [object] Record([string] $directory, [string] $phase, [object] $processes) {
        $proof = [pscustomobject]@{
            phase = $phase; directory = [IO.Path]::GetFullPath($directory)
            processes = $processes; artifactSha256 = ''
        }
        $this.AssertOutcome($proof, $phase)
        $proof.artifactSha256 = $this.ArtifactDigest($directory)
        return $proof
    }

    hidden [void] AssertOutcome([object] $proof, [string] $phase) {
        if ($null -eq $proof -or $proof.phase -cne $phase -or
            $null -eq $proof.processes.produce -or $null -eq $proof.processes.verify -or
            $proof.processes.produce.exitCode -ne 86 -or $proof.processes.verify.exitCode -ne 0) {
            throw "Missing successful process receipts: $phase"
        }
        $directory = [IO.Path]::GetFullPath($proof.directory)
        if ($null -ne $proof.processes.verify.artifactSha256) {
            [LumiCrashMatrixEvidence]::AssertClientCompletion(
                (Join-Path $directory 'run/clientGameTest/client.completed'),
                $this.Definition.Suite(), $proof.processes.verify.artifactSha256)
        }
        $this.Definition.AssertMarker((Join-Path $directory 'crash.marker'), $phase)
        $log = Join-Path $directory 'verify.stdout.log'
        if (-not (Select-String -LiteralPath $log -SimpleMatch $this.Definition.Gate($phase) -Quiet)) {
            throw "Missing verifier gate: $phase"
        }
        foreach ($mode in @('produce', 'verify')) {
            if (Select-String -LiteralPath (Join-Path $directory "$mode.stdout.log") -SimpleMatch 'Client gametests failed with an exception' -Quiet) {
                throw "Failed client: $phase/$mode"
            }
        }
    }

    [void] Assert([object] $proof, [string] $phase) {
        $this.AssertOutcome($proof, $phase)
        $directory = [IO.Path]::GetFullPath($proof.directory)
        if ($this.ArtifactDigest($directory) -cne $proof.artifactSha256) {
            throw "Crash artifacts changed: $phase"
        }
    }

    [hashtable] Resume([string[]] $paths, [string[]] $phases, [bool] $cold) {
        $results = @{}
        foreach ($path in $paths) {
            if ($this.Dirty) { throw 'Crash evidence can only be reused for a clean candidate.' }
            $manifest = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
            if ($manifest.schema -ne 2 -or $manifest.dirty -ne $false -or
                $manifest.inputFingerprint -cne $this.Fingerprint -or
                $manifest.kind -cne $this.Definition.Kind -or $manifest.restoreCold -ne $cold) {
                throw "Crash evidence has a different candidate/configuration or lacks receipts: $path"
            }
            foreach ($phase in @($manifest.completed)) {
                if ($phase -notin $this.Definition.Phases) { throw "Unknown recorded phase: $phase" }
                if ($phase -notin $phases) { continue }
                if ($null -eq $manifest.results) { throw "Missing phase receipts: $path" }
                $property = $manifest.results.PSObject.Properties[$phase]
                if ($null -eq $property) { throw "Missing phase receipt: $phase" }
                $this.Assert($property.Value, $phase)
                $results[$phase] = $property.Value
            }
        }
        return $results
    }
}
