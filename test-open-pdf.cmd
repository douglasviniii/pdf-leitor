@echo off
set LOG=C:\pdf leitor\browser-open-test.log
echo [%date% %time%] called: %*>>"%LOG%"
start "" "C:\Users\Home\AppData\Local\Delvind PDF\Delvind PDF.exe" %*
