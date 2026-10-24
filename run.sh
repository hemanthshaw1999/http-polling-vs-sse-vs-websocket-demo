#!/bin/sh
set -eu

if [ -z "${JAVA_HOME:-}" ] && [ -x /opt/homebrew/opt/openjdk@21/bin/java ]; then
  JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  export JAVA_HOME
fi

exec mvn -Dmaven.repo.local=target/maven-repo spring-boot:run "$@"
