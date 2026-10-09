[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $AppArgs
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$mavenVersion = '3.9.11'
$mavenSha512 = '03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076'
$toolsDir = Join-Path $projectRoot '.tools'
$mavenHome = Join-Path $toolsDir "apache-maven-$mavenVersion"
$mavenCommand = Join-Path $mavenHome 'bin\mvn.cmd'

if (-not (Test-Path -LiteralPath $mavenCommand)) {
    New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
    $archive = Join-Path $toolsDir "apache-maven-$mavenVersion-bin.zip"
    $downloadUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
    Write-Host "Bootstrapping Maven $mavenVersion..."
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archive
    $actualSha512 = (Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash.ToLowerInvariant()
    if ($actualSha512 -ne $mavenSha512) {
        Remove-Item -LiteralPath $archive
        throw "Maven archive SHA-512 mismatch"
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsDir -Force
    Remove-Item -LiteralPath $archive
}

Push-Location $projectRoot
try {
    $verify = $AppArgs.Count -eq 1 -and $AppArgs[0] -eq '--verify'
    if ($verify) {
        & $mavenCommand -q clean verify
    }
    else {
        & $mavenCommand -q -DskipTests package
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($verify) {
        & java -jar 'target\you-agent-cli.jar' --demo
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & java -jar 'target\you-agent-cli.jar' --benchmark
    }
    else {
        & java -jar 'target\you-agent-cli.jar' @AppArgs
    }
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
