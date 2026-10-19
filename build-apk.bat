@echo off
REM ===========================================================================
REM  Android 一键打包（Windows 入口）
REM
REM  真正的逻辑在 build-apk.sh 里。这里只负责找到 bash 并转交。
REM  Windows 10/11 自带 WSL，装 Git for Windows 也会带 Git Bash。
REM
REM  用法：双击本文件，或在 cmd 里执行 build-apk.bat
REM ===========================================================================
setlocal

cd /d "%~dp0"

REM ---- 依次找 bash：Git Bash → WSL → 系统 PATH ----
set "BASH="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH if exist "%LOCALAPPDATA%\Programs\Git\bin\bash.exe" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not defined BASH (
  where bash >nul 2>&1 && set "BASH=bash"
)

if not defined BASH (
  echo.
  echo   [X] 没找到 bash
  echo.
  echo   请先安装 Git for Windows：https://git-scm.com/download/win
  echo   安装时保持默认选项即可（会带上 Git Bash）
  echo.
  pause
  exit /b 1
)

REM 🔴 必须传 -lc 之外的方式：只用 -c，避免加载用户 profile 影响环境
"%BASH%" -c "cd '%~dp0' && ./build-apk.sh"
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" (
  echo   [X] 构建失败，退出码 %RC%
) else (
  echo   [OK] 构建完成，APK 在 dist\ 目录
)
echo.
pause
exit /b %RC%
