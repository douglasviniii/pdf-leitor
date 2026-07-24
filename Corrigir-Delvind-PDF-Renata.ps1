$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host "[Delvind PDF] $Message"
}

$localAppData = [Environment]::GetFolderPath("LocalApplicationData")
$candidates = @(
    "C:\Delvind PDF 0.4.18",
    (Join-Path $localAppData "Delvind PDF"),
    (Join-Path $localAppData "Programs\Delvind PDF"),
    (Join-Path $env:ProgramFiles "Delvind PDF"),
    (Join-Path ${env:ProgramFiles(x86)} "Delvind PDF")
) | Where-Object { $_ }

$executable = $null
foreach ($candidate in $candidates) {
    $candidateExe = Join-Path $candidate "Delvind PDF.exe"
    if (Test-Path -LiteralPath $candidateExe) {
        $executable = Get-Item -LiteralPath $candidateExe
        break
    }
}

if (-not $executable) {
    throw "Nao encontrei a instalacao do Delvind PDF 0.4.18 ou mais novo."
}

$installDir = $executable.DirectoryName
$supportDir = Join-Path $localAppData "Delvind PDF Support"
$runner = Join-Path $supportDir "Abrir Delvind PDF.cmd"
New-Item -ItemType Directory -Force -Path $supportDir | Out-Null

$runnerContent = @"
@echo off
setlocal
pushd "$installDir"
start "" "$($executable.FullName)" %*
popd
"@
[System.IO.File]::WriteAllText($runner, $runnerContent, [System.Text.Encoding]::Default)

Write-Step "Instalacao encontrada em: $installDir"
Write-Step "Criando launcher com diretorio de trabalho correto."

$desktop = [Environment]::GetFolderPath("Desktop")
if ($desktop -and (Test-Path -LiteralPath $desktop)) {
    $shortcutPath = Join-Path $desktop "Delvind PDF.lnk"
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $runner
    $shortcut.WorkingDirectory = $installDir
    $shortcut.IconLocation = "$($executable.FullName),0"
    $shortcut.Save()
}

$openCommand = '"' + $runner + '" "%1"'
$watchCommand = '"' + $runner + '" --watch-downloads'
$icon = '"' + $executable.FullName + '",0'

function Reg-AddDefault {
    param([string]$Key, [string]$Value)
    $registryPath = "Registry::HKEY_CURRENT_USER\" + $Key.Substring("HKCU\".Length)
    New-Item -Path $registryPath -Force | Out-Null
    Set-Item -Path $registryPath -Value $Value
}

function Reg-Add {
    param([string]$Key, [string]$Name, [string]$Type, [string]$Value)
    $registryPath = "Registry::HKEY_CURRENT_USER\" + $Key.Substring("HKCU\".Length)
    New-Item -Path $registryPath -Force | Out-Null
    if ($Type -eq "REG_NONE") {
        New-ItemProperty -Path $registryPath -Name $Name -PropertyType Binary -Value ([byte[]]@()) -Force | Out-Null
    } else {
        New-ItemProperty -Path $registryPath -Name $Name -PropertyType String -Value $Value -Force | Out-Null
    }
}

Write-Step "Registrando abertura de PDF."
Reg-AddDefault "HKCU\Software\Classes\DelvindPDF.Document" "Documento PDF"
Reg-AddDefault "HKCU\Software\Classes\DelvindPDF.Document\DefaultIcon" $icon
Reg-AddDefault "HKCU\Software\Classes\DelvindPDF.Document\shell\open\command" $openCommand
Reg-AddDefault "HKCU\Software\Classes\Applications\Delvind PDF.exe\shell\open\command" $openCommand
Reg-Add "HKCU\Software\Classes\Applications\Delvind PDF.exe\SupportedTypes" ".pdf" "REG_SZ" ""
Reg-Add "HKCU\Software\Classes\.pdf\OpenWithProgids" "DelvindPDF.Document" "REG_NONE" ""
Reg-Add "HKCU\Software\Classes\MIME\Database\Content Type\application/pdf" "Extension" "REG_SZ" ".pdf"

Write-Step "Registrando protocolo ACROBAT."
Reg-AddDefault "HKCU\Software\Classes\ACROBAT" "URL:Acrobat Protocol"
Reg-Add "HKCU\Software\Classes\ACROBAT" "URL Protocol" "REG_SZ" ""
Reg-AddDefault "HKCU\Software\Classes\ACROBAT\DefaultIcon" $icon
Reg-AddDefault "HKCU\Software\Classes\ACROBAT\shell\open\command" $openCommand

Write-Step "Registrando aplicativo e watcher de downloads."
Reg-Add "HKCU\Software\RegisteredApplications" "Delvind PDF" "REG_SZ" "Software\Classes\DelvindPDF.Capabilities"
Reg-Add "HKCU\Software\Classes\DelvindPDF.Capabilities" "ApplicationName" "REG_SZ" "Delvind PDF"
Reg-Add "HKCU\Software\Classes\DelvindPDF.Capabilities\FileAssociations" ".pdf" "REG_SZ" "DelvindPDF.Document"
Reg-Add "HKCU\Software\Classes\DelvindPDF.Capabilities\URLAssociations" "ACROBAT" "REG_SZ" "ACROBAT"
Reg-Add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" "Delvind PDF Download Watcher" "REG_SZ" $watchCommand

Write-Host ""
Write-Host "CORRECAO APLICADA."
Write-Host "Launcher: $runner"
