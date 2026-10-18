# Builds NekyLauncher for Windows:
#   dist\NekyLauncher-windows-x64.zip   (portable: unzip and run NekyLauncher.exe, Java included)
#   dist\NekyLauncher-<ver>.exe         (installer, only if WiX Toolset is installed)
# Requirements: JDK 21+ on PATH (jlink and jpackage come with it).
# Run from PowerShell:  powershell -ExecutionPolicy Bypass -File packaging\package-windows.ps1
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$version = (Select-String -Path build.gradle.kts -Pattern '^version\s*=\s*"(.*)"').Matches[0].Groups[1].Value
$modules = "java.base,java.desktop,java.xml,java.net.http,java.logging,jdk.management,jdk.httpserver,jdk.crypto.ec,jdk.charsets,jdk.localedata"

.\gradlew.bat --no-daemon -q jar
Remove-Item -Recurse -Force build\runtime, build\jpackage, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force dist, build\jpackage\input | Out-Null
Copy-Item build\libs\NekyLauncher.jar build\jpackage\input\

Write-Host "> jlink: trimmed Java runtime"
jlink --add-modules $modules --include-locales=en,pt,es --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output build\runtime

$common = @("--name", "NekyLauncher", "--app-version", $version, "--vendor", "Nekyoh",
            "--description", "Minecraft launcher by Nekyoh", "--copyright", "Copyright (c) Nekyoh",
            "--input", "build\jpackage\input", "--main-jar", "NekyLauncher.jar",
            "--main-class", "com.nekyoh.nekylauncher.NekyLauncher",
            "--runtime-image", "build\runtime", "--icon", "packaging\icon.ico",
            "--java-options", "-Xss2m")

Write-Host "> jpackage: app image"
jpackage --type app-image --dest build\jpackage @common
Compress-Archive -Path build\jpackage\NekyLauncher -DestinationPath dist\NekyLauncher-windows-x64.zip -Force

if (Get-Command candle.exe -ErrorAction SilentlyContinue) {
    Write-Host "> jpackage: installer (.exe)"
    jpackage --type exe --dest dist @common --win-dir-chooser --win-menu --win-menu-group "NekyLauncher" `
             --win-shortcut --win-per-user-install --win-upgrade-uuid "6f1f6c55-5d3e-4a7e-9b51-2a3c8a6e0b11"
} else {
    Write-Host "  (installer skipped: install WiX Toolset 3.x to also build an .exe installer)"
}
Write-Host "Done:"; Get-ChildItem dist
