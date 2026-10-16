class LumiMinecraftVerificationTarget {
    [object] $Definition
    [bool] $Modern
    [string] $Artifact

    LumiMinecraftVerificationTarget([object] $catalog, [string] $target, [string] $game, [string] $artifact) {
        $selected = @($catalog.targets | Where-Object { $_.id -ceq $target })
        if ($selected.Count -ne 1) { throw "Unknown target: $target" }
        $this.Definition = $selected[0] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        $this.Modern = $this.Definition.javaVersion -gt 21
        if ([string]::IsNullOrWhiteSpace($game)) { $game = $this.Definition.minecraftVersion }
        if ($game -cnotin $this.Definition.minecraftVersions) { throw "$game is outside target $target" }
        if ($game -cne $this.Definition.minecraftVersion -and [string]::IsNullOrWhiteSpace($artifact)) {
            throw 'Alternate-game verification requires a frozen artifact.'
        }
        if (-not [string]::IsNullOrWhiteSpace($artifact)) {
            $this.Artifact = [IO.Path]::GetFullPath($artifact)
            if (-not [IO.File]::Exists($this.Artifact) -or [IO.Path]::GetExtension($this.Artifact) -cne '.jar') {
                throw "A frozen distributable JAR is required: $artifact"
            }
        }
        $this.Definition.minecraftVersion = $game
    }

    [string[]] PrepareArguments() {
        return $this.TaskArguments(@('prepareMultiplayerLaunch'))
    }

    [string[]] TaskArguments([string[]] $tasks) {
        $arguments = @()
        if ($this.Modern) { $arguments += @('-p', 'modern') }
        foreach ($task in $tasks) {
            if ($task.StartsWith(':')) { $arguments += $task }
            elseif ($this.Modern) { $arguments += ":adapters:$($this.Definition.id):$task" }
            else { $arguments += ":$task" }
        }
        $arguments += "-PlumiTarget=$($this.Definition.id)"
        if ($this.Modern) { $arguments += "-PlumiGameVersion=$($this.Definition.minecraftVersion)" }
        if ($this.Artifact) { $arguments += "-PlumiArtifact=$($this.Artifact)" }
        return $arguments
    }
}
