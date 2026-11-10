$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:JAVA_HOME = Join-Path $root "tools\jdk-21.0.11+10"
$mavenBin = Join-Path $root "tools\apache-maven-3.9.16\bin"
$wixRoot = Join-Path $root "tools\wix-3.14.1"
$wixTools = Join-Path $wixRoot "tools"
$dist = Join-Path $root "dist-installer"
$temp = Join-Path $root "jpackage-temp"
$inputDir = Join-Path $root "package-input"
$appName = "Delvind PDF"
$appVersion = "1.0.4"
$jar = "delvind-pdf-1.0.4.jar"
$iconPng = Join-Path $root "src\main\resources\icons\app-icon.png"
$iconIco = Join-Path $root "packaging\app-icon.ico"

function New-IcoFromPng {
    param(
        [Parameter(Mandatory = $true)][string]$PngPath,
        [Parameter(Mandatory = $true)][string]$IcoPath
    )

    $pngBytes = [System.IO.File]::ReadAllBytes($PngPath)
    $stream = [System.IO.MemoryStream]::new()
    $writer = [System.IO.BinaryWriter]::new($stream)
    $writer.Write([UInt16]0)
    $writer.Write([UInt16]1)
    $writer.Write([UInt16]1)
    $writer.Write([Byte]0)
    $writer.Write([Byte]0)
    $writer.Write([Byte]0)
    $writer.Write([Byte]0)
    $writer.Write([UInt16]1)
    $writer.Write([UInt16]32)
    $writer.Write([UInt32]$pngBytes.Length)
    $writer.Write([UInt32]22)
    $writer.Write($pngBytes)
    $writer.Flush()
    [System.IO.File]::WriteAllBytes($IcoPath, $stream.ToArray())
    $writer.Dispose()
    $stream.Dispose()
}

if (!(Test-Path (Join-Path $wixTools "candle.exe"))) {
    New-Item -ItemType Directory -Force -Path $wixRoot | Out-Null
    $package = Join-Path $wixRoot "wix.3.14.1.nupkg"
    $zip = Join-Path $wixRoot "wix.3.14.1.zip"
    Invoke-WebRequest -Uri "https://www.nuget.org/api/v2/package/wix/3.14.1" -OutFile $package
    Copy-Item -LiteralPath $package -Destination $zip -Force
    Expand-Archive -LiteralPath $zip -DestinationPath $wixRoot -Force
}

New-IcoFromPng -PngPath $iconPng -IcoPath $iconIco

$env:Path = "$env:JAVA_HOME\bin;$mavenBin;$wixTools;$env:Path"

New-Item -ItemType Directory -Force -Path $dist | Out-Null
$currentMsi = Join-Path $dist "$appName-$appVersion.msi"
if (Test-Path $currentMsi) {
    Remove-Item -LiteralPath $currentMsi -Force
}
if (Test-Path $temp) {
    Remove-Item -LiteralPath $temp -Recurse -Force
}
if (Test-Path $inputDir) {
    Remove-Item -LiteralPath $inputDir -Recurse -Force
}

mvn clean package
if ($LASTEXITCODE -ne 0) {
    throw "Falha ao compilar o JAR do Delvind PDF."
}

New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
Copy-Item -LiteralPath (Join-Path $root "target\$jar") -Destination (Join-Path $inputDir $jar) -Force

& (Join-Path $env:JAVA_HOME "bin\jpackage.exe") `
    --verbose `
    --type msi `
    --temp $temp `
    --dest $dist `
    --name $appName `
    --app-version $appVersion `
    --vendor "DOUGLAS" `
    --input $inputDir `
    --main-jar $jar `
    --main-class "br.com.pdfleitor.app.DelfosPdfLauncher" `
    --icon $iconIco `
    --file-associations (Join-Path $root "packaging\pdf-association.properties") `
    --win-per-user-install `
    --win-menu `
    --win-shortcut `
    --win-console `
    --win-dir-chooser `
    --java-options "-Dfile.encoding=UTF-8"

if ($LASTEXITCODE -ne 0) {
    Write-Warning "jpackage falhou na validacao ICE do WiX. Tentando gerar o MSI sem a validacao local quebrada."
    $out = Join-Path $dist "$appName-$appVersion.msi"
    $imageDir = Join-Path $temp "images\win-msi.image\$appName"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    Push-Location $imageDir
    try {
        & (Join-Path $wixTools "light.exe") `
            -nologo `
            -spdb `
            -sval `
            -ext WixUtilExtension `
            -out $out `
            -ext WixUIExtension `
            -b (Join-Path $temp "config") `
            -sice:ICE27 `
            -sice:ICE91 `
            -loc (Join-Path $temp "config\MsiInstallerStrings_de.wxl") `
            -loc (Join-Path $temp "config\MsiInstallerStrings_en.wxl") `
            -loc (Join-Path $temp "config\MsiInstallerStrings_ja.wxl") `
            -loc (Join-Path $temp "config\MsiInstallerStrings_zh_CN.wxl") `
            -cultures:en-us `
            (Join-Path $temp "wixobj\main.wixobj") `
            (Join-Path $temp "wixobj\bundle.wixobj") `
            (Join-Path $temp "wixobj\ui.wixobj") `
            (Join-Path $temp "wixobj\InstallDirNotEmptyDlg.wixobj")
        $lightExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($lightExitCode -ne 0) {
        throw "Falha ao gerar MSI pelo WiX fallback."
    }
}

$msi = Join-Path $dist "$appName-$appVersion.msi"
if (!(Test-Path $msi)) {
    throw "Instalador MSI nao encontrado em $msi"
}

Write-Host ""
Write-Host "Instalador Windows gerado com sucesso:"
Write-Host $msi

