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
        } else { throw "Unknown crash matrix: $kind" }
    }

    [string[]] Arguments([string] $phase, [string] $mode, [string] $marker, [bool] $cold) {
        if ($phase -notin $this.Phases -or $mode -notin @('produce', 'verify')) {
            throw 'Unknown crash phase or mode.'
        }
        if ($cold -and $this.Kind -ne 'restore') { throw 'Cold fixture requires the Restore matrix.' }
        if ($this.Kind -eq 'restore') {
            $arguments = @('-Dlumi.gametest.suite=RESTORE_CRASH',
                "-Dlumi.gametest.restoreCrashMode=$mode", "-Dlumi.gametest.restoreCrashPhase=$phase",
                "-Dlumi.gametest.restoreCrashExpectedPhase=$phase", "-Dlumi.gametest.restoreCrashMarker=$marker")
            if ($cold) { $arguments += '-Dlumi.gametest.restoreCrashCold=true' }
            return $arguments
        }
        return @('-Dlumi.gametest.suite=PERSISTENCE_CRASH', "-Dlumi.persistence.kind=$($this.Kind)",
            "-Dlumi.persistence.phase=$phase", "-Dlumi.persistence.mode=$mode", "-Dlumi.persistence.marker=$marker")
    }

    [void] AssertMarker([string] $path, [string] $phase) {
        if (-not [IO.File]::Exists($path)) { throw "Crash marker is missing: $path" }
        $lines = [IO.File]::ReadAllLines($path)
        if ($this.Kind -eq 'restore') {
            if ([IO.File]::ReadAllText($path) -cne $phase) { throw "Wrong Restore marker: $path" }
        } elseif ((@($lines | Where-Object { $_ -ceq "kind=$($this.Kind)" }).Count -ne 1) -or
            (@($lines | Where-Object { $_ -ceq "phase=$phase" }).Count -ne 1)) {
            throw "Wrong publication marker: $path"
        }
    }

    [string] Gate([string] $phase) {
        if ($phase -notin $this.Phases) { throw "Unknown crash phase: $phase" }
        if ($this.Kind -eq 'restore') { return "restore_crash_${phase}: succeeded" }
        return "publication_crash_$($this.Kind)_${phase}: succeeded"
    }
}
