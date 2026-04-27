param(
    [string]$Username = "LumiTestClient",
    [string]$JavaHome,
    [switch]$FullStack,
    [string[]]$GradleTasks = @("installTestClientMods", "runTestClient")
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

function Get-JavaMajorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$JavaExe
    )

    $versionLine = (& cmd.exe /d /c ('"{0}" -version 2>&1' -f $JavaExe) | Select-Object -First 1)
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

Push-Location $repoRoot
try {
    $selectedJava = Find-JavaHome -RequestedJavaHome $JavaHome
    if (-not $selectedJava) {
        throw "No compatible JDK 21+ installation was found. Install Java 21+ or pass -JavaHome 'C:\Path\To\JDK'."
    }

    $env:JAVA_HOME = $selectedJava.JavaHome
    $env:PATH = (Join-Path $selectedJava.JavaHome "bin") + ";" + $env:PATH

    Write-Host "Using JAVA_HOME=$($selectedJava.JavaHome)"

    $arguments = @()
    $arguments += $GradleTasks
    $arguments += "-Plumi.testUsername=$Username"

    if ($FullStack) {
        $arguments += "-Plumi.testClientFullStack=true"
    }

    & .\gradlew.bat @arguments
} finally {
    Pop-Location
}
