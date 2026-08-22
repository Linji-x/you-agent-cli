[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $AppArgs
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$mavenVersion = '3.9.11'
$toolsDir = Join-Path $projectRoot '.tools'
$mavenHome = Join-Path $toolsDir "apache-maven-$mavenVersion"
$mavenCommand = Join-Path $mavenHome 'bin\mvn.cmd'

if (-not (Test-Path -LiteralPath $mavenCommand)) {
    New-Item -ItemType Directory -Path $toolsDir -Force | Out-Null
    $archive = Join-Path $toolsDir "apache-maven-$mavenVersion-bin.zip"
    $downloadUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
    Write-Host "Bootstrapping Maven $mavenVersion..."
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archive
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsDir -Force
    Remove-Item -LiteralPath $archive
}

Push-Location $projectRoot
try {
    $verify = $AppArgs.Count -eq 1 -and $AppArgs[0] -eq '--verify'
    if ($verify) {
        & $mavenCommand -q clean test package
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
