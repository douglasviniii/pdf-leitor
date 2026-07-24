$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = Join-Path $root "tools\jdk-21.0.11+10"
$mavenBin = Join-Path $root "tools\apache-maven-3.9.16\bin"
$mavenRepo = Join-Path $root ".m2-repository"
$env:Path = "$env:JAVA_HOME\bin;$mavenBin;$env:Path"

mvn "-Dmaven.repo.local=$mavenRepo" package
