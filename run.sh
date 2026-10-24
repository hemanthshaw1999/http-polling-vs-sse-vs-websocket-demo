#!/bin/sh
set -eu
SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)

# Homebrew OpenJDK installs are not always registered with macOS's /usr/bin/java shim.
# On other Unix systems, the user's working java command or JAVA_HOME is used unchanged.
if [ -z "${JAVA_HOME:-}" ] && ! java -version >/dev/null 2>&1 && command -v brew >/dev/null 2>&1; then
  BREW_JDK_PREFIX=$(brew --prefix openjdk@21 2>/dev/null || true)
  if [ -x "$BREW_JDK_PREFIX/libexec/openjdk.jdk/Contents/Home/bin/java" ]; then
    JAVA_HOME="$BREW_JDK_PREFIX/libexec/openjdk.jdk/Contents/Home"
    export JAVA_HOME
  fi
fi

exec "$SCRIPT_DIR/mvnw" spring-boot:run "$@"
