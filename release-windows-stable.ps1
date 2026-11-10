$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdk = Join-Path $root "tools\jdk-21.0.11+10"
$mavenBin = Join-Path $root "tools\apache-maven-3.9.16\bin"
$wixTools = Join-Path $root "tools\wix-3.14.1\tools"
$csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$dist = Join-Path $root "dist-final"
$work = Join-Path $root "stable-package-work"
$inputDir = Join-Path $work "input"
$imageDest = Join-Path $work "image"
$image = Join-Path $imageDest "Delvind PDF"
$temp = Join-Path $work "msi-temp"
$launcher = Join-Path $work "Delvind PDF.exe"
$resourceDir = Join-Path $root "packaging\windows-professional"
$version = "1.0.4"
$jar = "delvind-pdf-$version.jar"
$output = Join-Path $dist "Instalar-Delvind-PDF-$version.msi"

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$mavenBin;$wixTools;$env:Path"

if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $dist, $inputDir, $imageDest | Out-Null

mvn clean package
if ($LASTEXITCODE -ne 0) { throw "Falha ao compilar o Delvind PDF." }
Copy-Item -LiteralPath (Join-Path $root "target\$jar") -Destination (Join-Path $inputDir $jar) -Force

& (Join-Path $jdk "bin\jpackage.exe") `
    --type app-image `
    --dest $imageDest `
    --name "Delvind PDF" `
    --app-version $version `
    --vendor "DOUGLAS" `
    --input $inputDir `
    --main-jar $jar `
    --main-class "br.com.pdfleitor.app.DelfosPdfLauncher" `
    --icon (Join-Path $root "packaging\app-icon.ico") `
    --java-options "-Dfile.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) { throw "Falha ao criar a imagem do aplicativo." }

Copy-Item -LiteralPath (Join-Path $jdk "bin\javaw.exe") -Destination (Join-Path $image "runtime\bin\javaw.exe") -Force

$launcherIcon = Join-Path $root "packaging\app-icon.ico"
$launcherSource = Join-Path $root "packaging\launcher\DelvindPdfLauncher.cs"
& $csc `
    "/nologo" `
    "/target:winexe" `
    "/platform:x64" `
    "/optimize+" `
    "/reference:System.Windows.Forms.dll" `
    "/win32icon:$launcherIcon" `
    "/out:$launcher" `
    $launcherSource
if ($LASTEXITCODE -ne 0) { throw "Falha ao compilar o inicializador estavel." }
Copy-Item -LiteralPath $launcher -Destination (Join-Path $image "Delvind PDF.exe") -Force

& (Join-Path $jdk "bin\jpackage.exe") `
    --verbose `
    --type msi `
    --app-image $image `
    --temp $temp `
    --dest $dist `
    --name "Delvind PDF" `
    --app-version $version `
    --vendor "DOUGLAS" `
    --description "Leitor profissional de PDF Delvind" `
    --resource-dir $resourceDir `
    --file-associations (Join-Path $root "packaging\pdf-association.properties") `
    --win-per-user-install `
    --win-menu `
    --win-menu-group "Delvind PDF" `
    --win-shortcut `
    --win-upgrade-uuid "dbf02dba-0d17-4ce1-bb66-68fb06c94263"
if ($LASTEXITCODE -ne 0) { throw "Falha ao gerar o MSI estável." }

$generated = Join-Path $dist "Delvind PDF-$version.msi"
if (!(Test-Path -LiteralPath $generated)) { throw "MSI estável não encontrado." }
Move-Item -LiteralPath $generated -Destination $output -Force
Write-Host "Instalador estável gerado: $output"
