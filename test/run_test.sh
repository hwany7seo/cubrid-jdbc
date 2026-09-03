#!/bin/bash
#
# Compile and run the verification cases under case/ against the JDBC driver jar
# built at the repository root (../cubrid-jdbc-*.jar, or the ../cubrid_jdbc.jar
# symlink to it).
#
# Usage:
#   ./run_test.sh                  # run every case/*.java
#   ./run_test.sh case/Foo.java    # run only that one case
#   ./run_test.sh Foo.java         # same, "case/" prefix is optional
#
# Connection defaults to jdbc:cubrid:localhost:33000:demodb:dba:: ; override with
# the CUBRID_JDBC_URL / CUBRID_JDBC_USER / CUBRID_JDBC_PASSWORD env vars.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CASE_DIR="$SCRIPT_DIR/case"
BUILD_DIR="$SCRIPT_DIR/build"

find_jar() {
    local jar
    jar="$(ls -t "$ROOT_DIR"/cubrid-jdbc-*.jar 2>/dev/null | grep -v -- '-sources.jar\|-javadoc.jar' | head -1 || true)"
    if [ -z "$jar" ] && [ -e "$ROOT_DIR/cubrid_jdbc.jar" ]; then
        jar="$ROOT_DIR/cubrid_jdbc.jar"
    fi
    echo "$jar"
}

JAR="$(find_jar)"
if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
    echo "error: no built driver jar found under $ROOT_DIR" >&2
    echo "       build it first (e.g. $ROOT_DIR/build.sh)" >&2
    exit 1
fi
echo "Using driver jar: $JAR"

resolve_case() {
    local arg="$1"
    if [ -f "$arg" ]; then
        echo "$arg"
        return 0
    fi
    if [ -f "$SCRIPT_DIR/$arg" ]; then
        echo "$SCRIPT_DIR/$arg"
        return 0
    fi
    if [ -f "$CASE_DIR/$(basename "$arg")" ]; then
        echo "$CASE_DIR/$(basename "$arg")"
        return 0
    fi
    return 1
}

CASES=()
if [ "$#" -gt 0 ]; then
    for arg in "$@"; do
        resolved="$(resolve_case "$arg")" || {
            echo "error: case not found: $arg" >&2
            exit 1
        }
        CASES+=("$resolved")
    done
else
    while IFS= read -r -d '' f; do
        CASES+=("$f")
    done < <(find "$CASE_DIR" -maxdepth 1 -name "*.java" -print0 | sort -z)
fi

if [ "${#CASES[@]}" -eq 0 ]; then
    echo "no test cases found under $CASE_DIR" >&2
    exit 1
fi

mkdir -p "$BUILD_DIR"

OVERALL_STATUS=0
PASSED=0
FAILED=0

for case_path in "${CASES[@]}"; do
    class_name="$(basename "$case_path" .java)"
    rel="${case_path#"$SCRIPT_DIR"/}"

    echo "==================================================================="
    echo "Case: $rel"

    if ! javac -nowarn -cp "$JAR" -d "$BUILD_DIR" "$case_path"; then
        echo "[NOK] $rel (compile error)"
        FAILED=$((FAILED + 1))
        OVERALL_STATUS=1
        continue
    fi

    if java -cp "$BUILD_DIR:$JAR" "$class_name"; then
        PASSED=$((PASSED + 1))
        echo "[OK] $rel"
    else
        echo "[NOK] $rel (exit code $?)"
        FAILED=$((FAILED + 1))
        OVERALL_STATUS=1
    fi
done

echo "==================================================================="
echo "Result: $PASSED passed, $FAILED failed (of ${#CASES[@]})"

exit $OVERALL_STATUS
