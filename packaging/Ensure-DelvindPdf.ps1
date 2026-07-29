$ErrorActionPreference = 'Stop'

$supportDirectory = Join-Path $env:LOCALAPPDATA 'Delvind PDF Support'
$masterLauncher = Join-Path $supportDirectory 'DelvindPdfNativeLauncher.exe'
$installDirectory = Join-Path $env:LOCALAPPDATA 'Delvind PDF'
$installedLauncher = Join-Path $installDirectory 'Delvind PDF.exe'

if (-not (Test-Path -LiteralPath $masterLauncher)) {
    exit 1
}

New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
$mustRestore = -not (Test-Path -LiteralPath $installedLauncher)
if (-not $mustRestore) {
    $masterHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $masterLauncher).Hash
    $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $installedLauncher).Hash
    $mustRestore = $masterHash -ne $installedHash
}

if ($mustRestore) {
    Get-Process -Name 'Delvind PDF' -ErrorAction SilentlyContinue |
        Stop-Process -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $masterLauncher -Destination $installedLauncher -Force
}

$openCommand = '"' + $installedLauncher + '" "%1"'
$registryValues = @{
    'HKCU:\Software\Classes\.pdf' = 'DelvindPDF.Document'
    'HKCU:\Software\Classes\DelvindPDF.Document' = 'Documento PDF'
    'HKCU:\Software\Classes\DelvindPDF.Document\DefaultIcon' = '"' + $installedLauncher + '",0'
    'HKCU:\Software\Classes\DelvindPDF.Document\shell\open\command' = $openCommand
    'HKCU:\Software\Classes\Applications\Delvind PDF.exe\shell\open\command' = $openCommand
}

foreach ($entry in $registryValues.GetEnumerator()) {
    New-Item -Path $entry.Key -Force | Out-Null
    Set-Item -LiteralPath $entry.Key -Value $entry.Value
}

Start-Process -FilePath $installedLauncher -ArgumentList '--watch-downloads'
