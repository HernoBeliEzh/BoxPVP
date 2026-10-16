param(
    [ValidateSet('build', 'runClient', 'runServer', 'clean')]
    [string]$Task = 'build'
)

$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$javaCandidates = @($env:JAVA_HOME)
$javaRoots = @(
    "$env:ProgramFiles\Microsoft",
    "$env:ProgramFiles\Eclipse Adoptium",
    "$env:ProgramFiles\Java",
    "$env:USERPROFILE\.jdks"
)

foreach ($javaRoot in $javaRoots) {
    if (Test-Path -LiteralPath $javaRoot) {
        $javaCandidates += Get-ChildItem -LiteralPath $javaRoot -Directory | Select-Object -ExpandProperty FullName
    }
}

$selectedJavaHome = $javaCandidates | Where-Object {
    if ($_ -and (Test-Path -LiteralPath "$_\release")) {
        (Get-Content -LiteralPath "$_\release") -match '^JAVA_VERSION="21[.\"]'
    }
} | Select-Object -First 1

if (-not $selectedJavaHome) {
    throw 'JDK 21 was not found. Install JDK 21 or set JAVA_HOME to its directory.'
}

try {
    $env:JAVA_HOME = $selectedJavaHome
    $env:PATH = "$selectedJavaHome\bin;$previousPath"
    & "$PSScriptRoot\gradlew.bat" -p $PSScriptRoot $Task
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}
