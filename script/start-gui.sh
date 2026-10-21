#!/usr/bin/env sh
set -eu
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$APP_DIR/HybridFileXfer.jar" ]; then
    printf '%s\n' 'Place this launcher beside HybridFileXfer.jar, or use the packaged desktop application.' >&2
    exit 1
fi
JAVA=java
if [ -x "$APP_DIR/runtime/bin/java" ]; then
    JAVA="$APP_DIR/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
fi
exec "$JAVA" -Dfile.encoding=UTF-8 -jar "$APP_DIR/HybridFileXfer.jar" --gui "$@"
