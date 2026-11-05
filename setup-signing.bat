@echo off
:: ============================================================
::  MB File Manager - one-time signing setup (Windows)
::  Double-click this file. It does everything automatically:
::    1. creates your private signing key (with a random password)
::    2. saves the key + password as GitHub Secrets
::  After this, every new version installs as an UPDATE.
::  Needs: Java (keytool) and GitHub CLI (gh). Runs as admin.
:: ============================================================

:: --- run as administrator ---
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)
cd /d "%~dp0"

where keytool >nul 2>&1 || (echo [!] Java not found. Install it from https://adoptium.net and run again. & pause & exit /b 1)
where gh >nul 2>&1 || (echo [!] GitHub CLI not found. Install it from https://cli.github.com and run again. & pause & exit /b 1)

gh auth status >nul 2>&1 || gh auth login

if exist mbfm.jks (
    echo [i] mbfm.jks already exists - keeping your existing key.
    echo     Delete it only if you really want a NEW key.
    pause & exit /b 0
)

:: random password
for /f %%p in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"') do set PASS=%%p

echo Creating your signing key...
keytool -genkeypair -keystore mbfm.jks -alias mbfm -keyalg RSA -keysize 2048 -validity 10000 ^
  -storepass %PASS% -keypass %PASS% -dname "CN=MB Network, O=MB Network, C=US" || (pause & exit /b 1)

echo Saving secrets to GitHub...
powershell -NoProfile -Command "[Convert]::ToBase64String([IO.File]::ReadAllBytes('mbfm.jks'))" | gh secret set SIGNING_KEY_BASE64 -R mbnetworku/MBFileManager
echo %PASS%| gh secret set SIGNING_STORE_PASSWORD -R mbnetworku/MBFileManager
echo %PASS%| gh secret set SIGNING_KEY_PASSWORD -R mbnetworku/MBFileManager
echo mbfm| gh secret set SIGNING_KEY_ALIAS -R mbnetworku/MBFileManager

:: keep a private backup of the password next to the key
echo %PASS%> mbfm-password.txt

echo.
echo ============================================================
echo  DONE! Keep mbfm.jks and mbfm-password.txt somewhere SAFE.
echo  Never upload them. If you lose them, updates stop working.
echo ============================================================
pause
