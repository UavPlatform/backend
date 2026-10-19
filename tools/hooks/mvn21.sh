#!/bin/sh
# Run the project Maven wrapper with JDK 21.
# On Windows the POSIX wrapper cannot see D:\Java\JDK\bin\java (only java.exe exists),
# so this helper always uses mvnw.cmd through cmd.exe there.
set -eu

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_ROOT="$(cd "$SELF_DIR/../.." && pwd)"
MODULE="$BACKEND_ROOT/drone-backend"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    JAVA_HOME="${DRONE_JAVA_HOME:-D:\\Java\\JDK}"
    export JAVA_HOME
    MODULE_WIN="$(cygpath -w "$MODULE" 2>/dev/null || printf '%s' "$MODULE")"
    cd "$MODULE"
    exec cmd //c "$MODULE_WIN\\mvnw.cmd $*"
    ;;
  *)
    # CI provides JAVA_HOME via setup-java; let the wrapper use PATH otherwise.
    cd "$MODULE"
    exec sh ./mvnw "$@"
    ;;
esac
