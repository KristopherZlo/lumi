class LumiVerificationPlan {
    [string] $Profile
    [string[]] $Gates

    LumiVerificationPlan([string] $profile, [string[]] $selectedGates) {
        $this.Profile = $profile
        if ($selectedGates.Count -eq 0) {
            $selectedGates = switch ($profile) {
                'Development' { @('Compile', 'Core') }
                'Adapter' { @('Compile', 'Core', 'Server', 'Smoke') }
                'Candidate' { @('Core', 'Server', 'Client', 'Render', 'Multiplayer', 'ClientVulkan', 'NativeViewport') }
                'Long' { @('RestoreCrash', 'RestoreColdCrash', 'SaveCrash', 'MergeCrash', 'ImportCrash',
                    'UpgradeCrash', 'LoadRegression', 'RestoreSoak', 'SaveMemory') }
                default { throw "Unknown verification profile: $profile" }
            }
        }
        $known = @('Compile', 'Core', 'Server', 'Smoke', 'Render', 'Tools', 'UI', 'Interaction',
            'Client', 'Exact', 'Recovery', 'Multiplayer', 'RestoreCrash', 'RestoreColdCrash',
            'SaveCrash', 'MergeCrash', 'ImportCrash', 'UpgradeCrash', 'LoadRegression',
            'RestoreSoak', 'SaveMemory', 'ClientVulkan', 'NativeViewport')
        foreach ($gate in $selectedGates) {
            if ($gate -cnotin $known) { throw "Unknown verification gate: $gate" }
        }
        $this.Gates = @($selectedGates | Select-Object -Unique)
    }

    [bool] Applies([string] $gate, [object] $target) {
        if ($gate -eq 'UpgradeCrash') { return $target.Modern }
        if ($gate -in @('ClientVulkan', 'NativeViewport')) { return $target.Definition.id -ceq 'modern-263' }
        return $true
    }

    [string] Suite([string] $gate) {
        switch ($gate) {
            'Client' { return 'ALL' }
            'ClientVulkan' { return 'ALL' }
            'Smoke' { return 'SMOKE' }
            'Render' { return 'COMPARE_RENDER' }
            'Tools' { return 'BUILDER_TOOLS' }
            'UI' { return 'UI' }
            'Interaction' { return 'INTERACTION' }
            'Exact' { return 'EXACT' }
            'Recovery' { return 'RECOVERY' }
        }
        return ''
    }

    [string[]] Tasks([string] $gate) {
        if ($this.Suite($gate)) { return @('runPackagedClientGameTest') }
        switch ($gate) {
            'Compile' { return @('compileJava', 'compileTestJava', 'compileGametestJava') }
            'Core' { return @(':core:test', 'test') }
            'Server' { return @('runPackagedGameTest') }
            'LoadRegression' { return @('regressionPerformanceTest') }
            'RestoreSoak' { return @('runPackagedBenchmark') }
            'SaveMemory' { return @('runPackagedBenchmark') }
        }
        return @()
    }

    [object[]] Describe([object] $target) {
        $rows = @()
        foreach ($gate in $this.Gates) {
            $tasks = $this.Tasks($gate)
            $arguments = @()
            if ($tasks.Count -gt 0) { $arguments = $target.TaskArguments($tasks) }
            $suite = $this.Suite($gate)
            if ($suite) { $arguments += "-Dlumi.gametest.suite=$suite" }
            if ($gate -eq 'ClientVulkan') {
                $arguments += @('-PlumiGraphicsBackend=vulkan', '-Dlumi.gametest.fixedNativeWindow=true')
            }
            $rows += [ordered]@{ gate = $gate; applicable = $this.Applies($gate, $target)
                suite = $suite; arguments = $arguments; runner = $(if ($tasks.Count) { 'gradlew.bat' } else { $gate }) }
        }
        return $rows
    }
}
