#!/bin/sh
# Simple gradlew wrapper for GitHub Actions
# This will download gradle if not present

if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    mkdir -p gradle/wrapper
    curl -L -o gradle/wrapper/gradle-wrapper.jar https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar
fi

# Run with system gradle if available, otherwise download
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
else
    # Download gradle distribution
    GRADLE_VERSION=8.2
    GRADLE_HOME="$HOME/.gradle/wrapper/dists/gradle-$GRADLE_VERSION-bin"
    if [ ! -d "$GRADLE_HOME" ]; then
        mkdir -p "$GRADLE_HOME"
        curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o /tmp/gradle.zip
        unzip -q /tmp/gradle.zip -d "$GRADLE_HOME"
    fi
    GRADLE_BIN=$(find "$GRADLE_HOME" -name "gradle" -type f | head -1)
    exec "$GRADLE_BIN" "$@"
fi
