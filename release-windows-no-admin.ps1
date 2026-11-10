$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = Join-Path $root "tools\jdk-21.0.11+10"
$mavenBin = Join-Path $root "tools\apache-maven-3.9.16\bin"
$wixTools = Join-Path $root "tools\wix-3.14.1\tools"
$dist = Join-Path $root "dist-final"
$temp = Join-Path $root "jpackage-final-temp"
$inputDir = Join-Path $root "package-final-input"
$resourceDir = Join-Path $root "packaging\windows-professional"
$appName = "Delvind PDF"
$appVersion = "1.0.4"
$jar = "delvind-pdf-$appVersion.jar"
$output = Join-Path $dist "Instalar-Delvind-PDF-$appVersion.msi"

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$mavenBin;$wixTools;$env:Path"

foreach ($path in @($temp, $inputDir)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}
New-Item -ItemType Directory -Force -Path $dist, $inputDir | Out-Null

mvn clean package
if ($LASTEXITCODE -ne 0) { throw "Falha ao compilar o Delvind PDF." }

Copy-Item -LiteralPath (Join-Path $root "target\$jar") -Destination (Join-Path $inputDir $jar) -Force

& (Join-Path $jdk "bin\jpackage.exe") `
    --verbose `
    --type msi `
    --temp $temp `
    --dest $dist `
    --name $appName `
    --app-version $appVersion `
    --vendor "DOUGLAS" `
    --description "Leitor profissional de PDF Delvind" `
    --input $inputDir `
    --main-jar $jar `
    --main-class "br.com.pdfleitor.app.DelfosPdfLauncher" `
    --icon (Join-Path $root "packaging\app-icon.ico") `
    --resource-dir $resourceDir `
    --file-associations (Join-Path $root "packaging\pdf-association.properties") `
    --win-per-user-install `
    --win-menu `
    --win-menu-group "Delvind PDF" `
    --win-shortcut `
    --win-upgrade-uuid "dbf02dba-0d17-4ce1-bb66-68fb06c94263" `
    --java-options "-Dfile.encoding=UTF-8"

$generated = Join-Path $dist "$appName-$appVersion.msi"
if ($LASTEXITCODE -ne 0) {
    Write-Warning "A validacao ICE local falhou; finalizando o MSI com os objetos ja compilados."
    $imageDir = Join-Path $temp "images\win-msi.image\$appName"
    Push-Location $imageDir
    try {
        & (Join-Path $wixTools "light.exe") `
            -nologo -spdb -sval `
            -ext WixUtilExtension `
            -ext WixUIExtension `
            -out $generated `
            -b (Join-Path $temp "config") `
            -loc (Join-Path $temp "config\MsiInstallerStrings_de.wxl") `
            -loc (Join-Path $temp "config\MsiInstallerStrings_en.wxl") `
            -loc (Join-Path $temp "config\MsiInstallerStrings_ja.wxl") `
            -loc (Join-Path $temp "config\MsiInstallerStrings_zh_CN.wxl") `
            -cultures:en-us `
            (Join-Path $temp "wixobj\main.wixobj") `
            (Join-Path $temp "wixobj\bundle.wixobj") `
            (Join-Path $temp "wixobj\ui.wixobj")
        if ($LASTEXITCODE -ne 0) { throw "Falha ao gerar o instalador final." }
    } finally {
        Pop-Location
    }
}

if (!(Test-Path -LiteralPath $generated)) { throw "O MSI final nao foi gerado." }
Move-Item -LiteralPath $generated -Destination $output -Force
Write-Host "Instalador final gerado: $output"
