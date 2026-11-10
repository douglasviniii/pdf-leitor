$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host "[Delvind PDF] $Message"
}

$localAppData = [Environment]::GetFolderPath("LocalApplicationData")
$candidates = @(
    (Join-Path $localAppData "Delvind PDF"),
    (Join-Path $localAppData "Programs\Delvind PDF"),
    (Join-Path $env:ProgramFiles "Delvind PDF"),
    (Join-Path ${env:ProgramFiles(x86)} "Delvind PDF")
) | Where-Object { $_ -and (Test-Path $_) }

$installDir = $null
foreach ($candidate in $candidates) {
    $javaw = Get-ChildItem -LiteralPath $candidate -Recurse -Filter "javaw.exe" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "\\runtime\\bin\\javaw\.exe$" } |
        Select-Object -First 1
    $jar = Get-ChildItem -LiteralPath $candidate -Recurse -Filter "delvind-pdf-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "\\app\\delvind-pdf-.*\.jar$" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($javaw -and $jar) {
        $installDir = $candidate
        break
    }
}

if (-not $javaw -or -not $jar) {
    throw "Nao encontrei a instalacao do Delvind PDF com runtime Java. Instale o MSI novo primeiro e rode este corretor de novo."
}

Write-Step "Instalacao encontrada em: $installDir"
Write-Step "Java encontrado em: $($javaw.FullName)"
Write-Step "JAR encontrado em: $($jar.FullName)"

$supportDir = Join-Path $localAppData "Delvind PDF"
New-Item -ItemType Directory -Force -Path $supportDir | Out-Null

$runner = Join-Path $supportDir "Abrir Delvind PDF.cmd"
$runnerContent = @"
@echo off
setlocal
set "JAVA=$($javaw.FullName)"
set "JAR=$($jar.FullName)"
start "" "%JAVA%" -Dfile.encoding=UTF-8 -cp "%JAR%" br.com.pdfleitor.app.DelfosPdfLauncher %*
"@
[System.IO.File]::WriteAllText($runner, $runnerContent, [System.Text.Encoding]::ASCII)

$desktop = [Environment]::GetFolderPath("Desktop")
if ($desktop -and (Test-Path $desktop)) {
    Copy-Item -LiteralPath $runner -Destination (Join-Path $desktop "Delvind PDF.cmd") -Force
}

$openCommand = '"' + $runner + '" "%1"'
$icon = '"' + (Join-Path $installDir "Delvind PDF.exe") + '",0'

function Reg-AddDefault {
    param([string]$Key, [string]$Value)
    & reg.exe add $Key /ve /d $Value /f | Out-Null
}

function Reg-Add {
    param([string]$Key, [string]$Name, [string]$Type, [string]$Value)
    & reg.exe add $Key /v $Name /t $Type /d $Value /f | Out-Null
}

Write-Step "Registrando abertura de PDF sem usar o Delvind PDF.exe nativo..."
Reg-AddDefault "HKCU\Software\Classes\DelvindPDF.Document" "Documento PDF"
Reg-AddDefault "HKCU\Software\Classes\DelvindPDF.Document\DefaultIcon" $icon
Reg-AddDefault "HKCU\Software\Classes\DelvindPDF.Document\shell\open\command" $openCommand
Reg-Add "HKCU\Software\Classes\.pdf\OpenWithProgids" "DelvindPDF.Document" "REG_NONE" ""
Reg-Add "HKCU\Software\Classes\MIME\Database\Content Type\application/pdf" "Extension" "REG_SZ" ".pdf"

Write-Step "Registrando protocolo ACROBAT usado por sistemas antigos..."
Reg-AddDefault "HKCU\Software\Classes\ACROBAT" "URL:Acrobat Protocol"
Reg-Add "HKCU\Software\Classes\ACROBAT" "URL Protocol" "REG_SZ" ""
Reg-AddDefault "HKCU\Software\Classes\ACROBAT\DefaultIcon" $icon
Reg-AddDefault "HKCU\Software\Classes\ACROBAT\shell\open\command" $openCommand

Write-Step "Registrando aplicativo na lista do Windows..."
Reg-Add "HKCU\Software\RegisteredApplications" "Delvind PDF" "REG_SZ" "Software\Classes\DelvindPDF.Capabilities"
Reg-Add "HKCU\Software\Classes\DelvindPDF.Capabilities" "ApplicationName" "REG_SZ" "Delvind PDF"
Reg-Add "HKCU\Software\Classes\DelvindPDF.Capabilities" "ApplicationDescription" "REG_SZ" "Leitor de PDF Delvind"
Reg-Add "HKCU\Software\Classes\DelvindPDF.Capabilities\FileAssociations" ".pdf" "REG_SZ" "DelvindPDF.Document"
Reg-Add "HKCU\Software\Classes\DelvindPDF.Capabilities\URLAssociations" "ACROBAT" "REG_SZ" "ACROBAT"

Write-Step "Removendo watcher antigo de downloads..."
& reg.exe delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "Delvind PDF Download Watcher" /f 2>$null | Out-Null

Write-Step "Abrindo configuracao do Windows para confirmar Delvind PDF como padrao."
Start-Process "ms-settings:defaultapps"

Write-Host ""
Write-Host "CORRECAO APLICADA."
Write-Host "Se o Windows ainda pedir, escolha Delvind PDF como padrao para .pdf."
Write-Host "Comando usado:"
Write-Host $openCommand
Write-Host ""
Pause
