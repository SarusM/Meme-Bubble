@echo off
rem Двойной клик — откроется окно. Либо перетащи .gif-файлы прямо на этот .bat.
chcp 65001 >nul
python "%~dp0shrink_gif.py" %*
if errorlevel 1 pause
if not "%~1"=="" pause
