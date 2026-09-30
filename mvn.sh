#!/usr/bin/env bash
# Bash counterpart of mvn.bat (Linux, macOS, Git Bash/MSYS2, Cygwin).
# Loads .env (next to this script), pins JAVA_HOME / MAVEN_HOME / MAVEN_SETTINGS from it and runs
# Maven with the forwarded arguments. On Git Bash/Cygwin, Windows paths in .env (C:\...) are
# converted with cygpath; on Linux/macOS .env must contain native paths.
set -u

echo "mvn script version 1.0.3"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

if [ ! -f "$ENV_FILE" ]; then
    echo "Error: .env file not found at \"$ENV_FILE\""
    exit 1
fi

# Clear values that may have come from the system.
unset JAVA_HOME MAVEN_HOME MAVEN_SETTINGS MAVEN_ARGS MAVEN_OPTS JAVA_OPTS

# Converts a Windows path (C:\... or C:/...) to the POSIX form of Git Bash/Cygwin.
# Any other value is returned unchanged.
to_posix_path() {
    local value="$1"
    if [[ "$value" =~ ^[A-Za-z]:[\\/] ]] && command -v cygpath >/dev/null 2>&1; then
        cygpath -u "$value"
    else
        printf '%s\n' "$value"
    fi
}

# Load every variable from .env, not only the Maven variables.
# Values are taken literally (never evaluated), so special characters are preserved.
while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"                       # .env may have CRLF line endings
    case "$line" in ''|'#'*) continue ;; esac
    key="${line%%=*}"
    value="${line#*=}"
    [ "$key" = "$line" ] && continue           # no "=" in the line
    if [[ ! "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]]; then
        echo "Warning: ignoring invalid variable name in .env: \"$key\""
        continue
    fi
    export "$key=$value"
done < "$ENV_FILE"

if [ -z "${JAVA_HOME:-}" ]; then
    echo "Error: JAVA_HOME is not set in \"$ENV_FILE\"."
    exit 1
fi
JAVA_HOME="$(to_posix_path "$JAVA_HOME")"
export JAVA_HOME

if [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java"
elif [ -x "$JAVA_HOME/bin/java.exe" ]; then
    JAVA_EXE="$JAVA_HOME/bin/java.exe"
else
    echo "Error: invalid JAVA_HOME:"
    echo "\"$JAVA_HOME\""
    exit 1
fi

if [ -z "${MAVEN_HOME:-}" ]; then
    echo "Error: MAVEN_HOME is not set in \"$ENV_FILE\"."
    exit 1
fi
MAVEN_HOME="$(to_posix_path "$MAVEN_HOME")"
export MAVEN_HOME
MVN_EXE="$MAVEN_HOME/bin/mvn"

if [ ! -f "$MVN_EXE" ]; then
    echo "Error: MAVEN_HOME points to an invalid location:"
    echo "\"$MAVEN_HOME\""
    exit 1
fi

if [ -n "${MAVEN_SETTINGS:-}" ]; then
    MAVEN_SETTINGS="$(to_posix_path "$MAVEN_SETTINGS")"
    export MAVEN_SETTINGS
    if [ ! -f "$MAVEN_SETTINGS" ]; then
        echo "Error: MAVEN_SETTINGS points to a file that does not exist:"
        echo "\"$MAVEN_SETTINGS\""
        exit 1
    fi
fi

if [ "$#" -eq 0 ]; then
    echo "Usage: $(basename "$0") [maven arguments]"
    echo "Example: $(basename "$0") test \"-Dtest=com.example.MyTest\""
    exit 1
fi

# Force Maven and all child processes to use the JDK from .env.
export PATH="$JAVA_HOME/bin:$PATH"

echo "JAVA_HOME: $JAVA_HOME"
echo "Java executable: \"$JAVA_EXE\""
echo "Maven executable: \"$MVN_EXE\""

command=(sh "$MVN_EXE")
if [ -n "${MAVEN_SETTINGS:-}" ]; then
    echo "Maven settings: \"$MAVEN_SETTINGS\""
    command+=(-s "$MAVEN_SETTINGS")
else
    echo "Maven settings: default"
fi

# MAVEN_ARGS is split on whitespace on purpose, like %MAVEN_ARGS% in mvn.bat.
# shellcheck disable=SC2206
[ -n "${MAVEN_ARGS:-}" ] && command+=(${MAVEN_ARGS})
command+=("$@")

echo
"$JAVA_EXE" -version
echo

echo "Running: ${command[*]}"
echo
"${command[@]}"
exit $?
