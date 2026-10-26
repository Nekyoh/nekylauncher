#!/usr/bin/env bash
# Builds NekyLauncher for Linux:
#   dist/NekyLauncher-linux-x64.tar.gz  (portable app with its own Java runtime)
#   dist/nekylauncher_<ver>_amd64.deb   (if dpkg-deb/fakeroot are available)
# Requirements: JDK 21+ (jlink and jpackage come with it).
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(grep -E '^version *= *"' build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')"
MODULES="java.base,java.desktop,java.xml,java.net.http,java.logging,jdk.management,jdk.httpserver,jdk.crypto.ec,jdk.charsets,jdk.localedata"

${GRADLE:-./gradlew} --no-daemon -q jar
rm -rf build/runtime build/jpackage dist
mkdir -p dist build/jpackage/input
cp build/libs/NekyLauncher.jar build/jpackage/input/

echo "> jlink: trimmed Java runtime"
jlink --add-modules "$MODULES" --include-locales=en,pt,es \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
      --output build/runtime

common=(--name NekyLauncher --app-version "$VERSION" --vendor "Nekyoh"
        --description "Minecraft launcher by Nekyoh"
        --copyright "Copyright (c) Nekyoh"
        --input build/jpackage/input --main-jar NekyLauncher.jar
        --main-class com.nekyoh.nekylauncher.NekyLauncher
        --runtime-image build/runtime --icon packaging/icon.png
        --java-options "-Xss2m")

echo "> jpackage: app image"
# -Dneky.dist tells the built-in updater which kind of file updates this copy
jpackage --type app-image --dest build/jpackage "${common[@]}" --java-options "-Dneky.dist=linux-portable"
tar -C build/jpackage -czf "dist/NekyLauncher-linux-x64.tar.gz" NekyLauncher

if command -v dpkg-deb >/dev/null && command -v fakeroot >/dev/null; then
  echo "> jpackage: .deb"
  jpackage --type deb --dest dist "${common[@]}" --java-options "-Dneky.dist=linux-deb" \
           --linux-shortcut --linux-menu-group Game --linux-app-category games \
           --linux-package-name nekylauncher || echo "  (deb skipped)"
fi
echo "Done:"; ls -lh dist
