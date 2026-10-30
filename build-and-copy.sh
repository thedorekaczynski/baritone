#!/usr/bin/env bash
set -e

export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.2.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"

TARGET_MODS_DIR="/c/Users/ronny/AppData/Roaming/PrismLauncher/instances/26.3 fabric/minecraft/mods"

MOD_VERSION=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
if [ -z "$MOD_VERSION" ]; then
    echo "Failed to read mod_version from gradle.properties"
    exit 1
fi

JAR_NAME="baritone-${MOD_VERSION}.jar"

./gradlew build

mkdir -p "$TARGET_MODS_DIR"
cp -f "build/libs/$JAR_NAME" "$TARGET_MODS_DIR/$JAR_NAME"

echo "Copied build/libs/$JAR_NAME to $TARGET_MODS_DIR/$JAR_NAME"
