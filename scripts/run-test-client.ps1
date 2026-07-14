param(
    [string]$Username = "LumiTestClient",
    [string]$JavaHome,
    [string[]]$GradleTasks = @("installTestClientMods", "runTestClient"),
    [string[]]$JvmArgs = @(),
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraJvmArgs = @()
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

function Get-JavaMajorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$JavaExe
    )

    $versionLine = (& cmd.exe /d /c ('"{0}" -version 2>&1' -f $JavaExe) |
        Where-Object { $_ -match '"\d+(?:\.\d+)*' } |
        Select-Object -First 1)
    if (-not $versionLine) {
        return $null
    }

    if ($versionLine -match '"(?<version>\d+(?:\.\d+)*)') {
        $rawVersion = $Matches.version
        if ($rawVersion.StartsWith("1.")) {
            return [int]($rawVersion.Split(".")[1])
        }

        return [int]($rawVersion.Split(".")[0])
    }

    return $null
}

function Find-JavaHome {
    param(
        [string]$RequestedJavaHome
    )

    if ($RequestedJavaHome) {
        $javaExe = Join-Path $RequestedJavaHome "bin\java.exe"
        if (-not (Test-Path $javaExe)) {
            return $null
        }

        $major = Get-JavaMajorVersion -JavaExe $javaExe
        if ($null -eq $major -or $major -lt 21) {
            return $null
        }

        return [PSCustomObject]@{
            JavaHome = $RequestedJavaHome
            JavaExe = $javaExe
            Major = $major
        }
    }

    $candidates = [System.Collections.Generic.List[string]]::new()

    if ($env:JAVA_HOME) {
        $candidates.Add($env:JAVA_HOME)
    }

    foreach ($path in @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java"
    )) {
        if (Test-Path $path) {
            Get-ChildItem $path -Directory | ForEach-Object {
                $candidates.Add($_.FullName)
            }
        }
    }

    $resolved = foreach ($candidate in $candidates | Select-Object -Unique) {
        $javaExe = Join-Path $candidate "bin\java.exe"
        if (-not (Test-Path $javaExe)) {
            continue
        }

        $major = Get-JavaMajorVersion -JavaExe $javaExe
        if ($null -eq $major -or $major -lt 21) {
            continue
        }

        [PSCustomObject]@{
            JavaHome = $candidate
            JavaExe = $javaExe
            Major = $major
        }
    }

    return $resolved | Sort-Object @{ Expression = { if ($_.Major -eq 21) { 0 } else { 1 } } }, Major, JavaHome | Select-Object -First 1
}

function Resolve-JvmArguments {
    param(
        [string[]]$ExplicitJvmArgs,
        [string[]]$RemainingArguments
    )

    $resolved = [System.Collections.Generic.List[string]]::new()

    foreach ($argument in $ExplicitJvmArgs) {
        if ([string]::IsNullOrWhiteSpace($argument)) {
            continue
        }

        $resolved.Add($argument)
    }

    for ($index = 0; $index -lt $RemainingArguments.Count; $index++) {
        $argument = $RemainingArguments[$index]
        if ([string]::IsNullOrWhiteSpace($argument) -or $argument -eq "--%") {
            continue
        }

        if ($argument.StartsWith("-D")) {
            if (($index + 1) -lt $RemainingArguments.Count -and $RemainingArguments[$index + 1].StartsWith(".")) {
                $argument = $argument + $RemainingArguments[$index + 1]
                $index++
            }

            $resolved.Add($argument)
            continue
        }

        throw "Unsupported extra argument '$argument'. Pass JVM flags with -JvmArgs '-Dname=value' or as quoted -Dname=value arguments."
    }

    return $resolved.ToArray()
}

function Join-JavaToolOptions {
    param(
        [string]$Existing,
        [string[]]$Additional
    )

    $parts = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($Existing)) {
        $parts.Add($Existing.Trim())
    }

    foreach ($argument in $Additional) {
        if ([string]::IsNullOrWhiteSpace($argument)) {
            continue
        }
        if ($argument -match "\s") {
            throw "JVM argument '$argument' contains whitespace. Set JAVA_TOOL_OPTIONS manually for complex quoting."
        }

        $parts.Add($argument)
    }

    return ($parts -join " ")
}

Push-Location $repoRoot
$originalJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $selectedJava = Find-JavaHome -RequestedJavaHome $JavaHome
    if (-not $selectedJava) {
        throw "No compatible JDK 21+ installation was found. Install Java 21+ or pass -JavaHome 'C:\Path\To\JDK'."
    }

    $env:JAVA_HOME = $selectedJava.JavaHome
    $env:PATH = (Join-Path $selectedJava.JavaHome "bin") + ";" + $env:PATH

    Write-Host "Using JAVA_HOME=$($selectedJava.JavaHome)"

    $resolvedJvmArgs = @(Resolve-JvmArguments -ExplicitJvmArgs $JvmArgs -RemainingArguments $ExtraJvmArgs)
    if ($resolvedJvmArgs.Count -gt 0) {
        $env:JAVA_TOOL_OPTIONS = Join-JavaToolOptions -Existing $env:JAVA_TOOL_OPTIONS -Additional $resolvedJvmArgs
        Write-Host "Using extra JVM args: $($resolvedJvmArgs -join ' ')"
    }

    $arguments = @()
    $arguments += $GradleTasks
    $arguments += "-Plumi.testUsername=$Username"
    $arguments += "--no-daemon"

    & .\gradlew.bat @arguments
    $gradleExitCode = $LASTEXITCODE
    if ($gradleExitCode -ne 0) {
        throw "Gradle tasks failed with exit code $gradleExitCode."
    }
} finally {
    if ($null -eq $originalJavaToolOptions) {
        Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    } else {
        $env:JAVA_TOOL_OPTIONS = $originalJavaToolOptions
    }

    Pop-Location
}
