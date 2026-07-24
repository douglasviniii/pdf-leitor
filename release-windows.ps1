$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = Join-Path $root "tools\jdk-21.0.11+10"
$mavenBin = Join-Path $root "tools\apache-maven-3.9.16\bin"
$mavenRepo = Join-Path $root ".m2-repository"
$env:Path = "$env:JAVA_HOME\bin;$mavenBin;$env:Path"

$dist = Join-Path $root "dist"
$inputDir = Join-Path $root "package-input"
$appName = "Delvind PDF"
$appImage = Join-Path $dist $appName
$jar = "delvind-pdf-0.4.21.jar"
$iconIco = Join-Path $root "packaging\app-icon.ico"

if (Test-Path $dist) {
    Remove-Item -LiteralPath $dist -Recurse -Force
}

mvn "-Dmaven.repo.local=$mavenRepo" clean package

if (Test-Path $inputDir) {
    Remove-Item -LiteralPath $inputDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
Copy-Item -LiteralPath (Join-Path $root "target\$jar") -Destination (Join-Path $inputDir $jar) -Force

& (Join-Path $env:JAVA_HOME "bin\jpackage.exe") `
    --type app-image `
    --dest $dist `
    --name $appName `
    --app-version "0.4.21" `
    --vendor "DOUGLAS" `
    --input $inputDir `
    --main-jar $jar `
    --main-class "br.com.pdfleitor.app.DelfosPdfLauncher" `
    --icon $iconIco `
    --java-options "-Dfile.encoding=UTF-8"

$launcher = Join-Path $appImage "Delvind PDF.exe"
if (!(Test-Path $launcher)) {
    throw "Release gerado, mas o executavel nao foi encontrado: $launcher"
}

$zip = Join-Path $dist "Delvind-PDF-0.4.21-windows-portable.zip"
Compress-Archive -LiteralPath $appImage -DestinationPath $zip -Force

Write-Host ""
Write-Host "Release Windows gerado com sucesso:"
Write-Host $launcher
Write-Host $zip

