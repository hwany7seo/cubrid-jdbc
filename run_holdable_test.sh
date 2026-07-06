#!/bin/bash
#
# run_holdable_test.sh
#   holdable cursor close 오버헤드 테스트(HoldableCloseTest) 실행 스크립트.
#
#   이 저장소의 src/jdbc 아래에는 드라이버 소스(cubrid/jdbc/**)와
#   테스트(cubrid/test/**)가 함께 있다. 이 스크립트는 그 둘을 소스에서 함께
#   컴파일하므로, 드라이버에 왕복 카운팅 로그를 추가하면 별도 jar 빌드 없이
#   바로 반영되어 테스트 출력의 [RT-SCENARIO]/[RT-OP] 마커와 대조할 수 있다.
#
#   연결 대상(호스트/DB/계정)은 src/jdbc/cubrid/test/ConnectWorker.java 에서 조정한다.
#
# 사용법:
#   ./run_holdable_test.sh [옵션] [-- ] [테스트 인자...]
#
#   옵션:
#     -c, --clean        빌드 디렉터리 삭제 후 재컴파일
#     -n, --no-build     컴파일 생략하고 기존 클래스로 바로 실행
#     -b, --build-only   컴파일만 하고 실행하지 않음(DB 불필요, 검증용)
#     -t, --test NAME    실행할 cubrid.test 클래스 (기본: HoldableCloseTest)
#     -h, --help         도움말
#
#   테스트 인자(HoldableCloseTest 로 그대로 전달):
#     [iterations]       포인트-셀렉트 반복 수 (기본 2000)
#     verbose | -v       매 연산마다 [RT-OP] 마커 출력(작은 N 에서 로그 대조용)
#
#   환경변수:
#     JAVA_HOME          지정 시 해당 JDK 사용
#     JAVA_OPTS          java 에 전달할 추가 옵션 (예: -Dholdable.iters=5000)
#     RTLOG              드라이버 [JDBC-Driver] 왕복 로그 on/off (기본 true; RTLOG=false 로 끄기)
#
# 예:
#   ./run_holdable_test.sh                    # 빌드 후 기본 실행
#   ./run_holdable_test.sh 50 verbose         # 소량 + 퍼-op 마커
#   ./run_holdable_test.sh -c 5000            # 클린 빌드 후 5000회
#   JAVA_OPTS="-Dholdable.iters=3000" ./run_holdable_test.sh -n
#   ./run_holdable_test.sh -b                 # 컴파일만(DB 불필요)
#   ./run_holdable_test.sh -t BatchTest       # 다른 테스트 재사용
#

set -u

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
SRC_DIR="$SCRIPT_DIR/src/jdbc"
BUILD_DIR="$SCRIPT_DIR/output/holdable-classes"   # output/ 는 .gitignore 대상
RESOURCE="$SRC_DIR/sql-error-codes.xml"
TEST_CLASS="HoldableCloseTest"
RTLOG="${RTLOG:-true}"   # 드라이버 [JDBC-Driver] 왕복 로그 (RTLOG=false 로 끄기)

DO_BUILD=1
DO_RUN=1
DO_CLEAN=0
TEST_ARGS=()

# ---- JDK 경로 (build.sh 관례와 동일) ----
if [ "x${JAVA_HOME:-}" != "x" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
JAVAC_BIN="$(command -v javac || true)"
JAVA_BIN="$(command -v java || true)"

usage() { sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# ---- 인자 파싱 ----
while [ $# -gt 0 ]; do
  case "$1" in
    -c|--clean)      DO_CLEAN=1 ;;
    -n|--no-build)   DO_BUILD=0 ;;
    -b|--build-only) DO_RUN=0 ;;
    -t|--test)       shift; TEST_CLASS="${1:-}";
                     [ -z "$TEST_CLASS" ] && { echo "[ERROR] -t 옵션에 클래스명이 필요합니다"; exit 2; } ;;
    -h|--help|-\?)   usage; exit 0 ;;
    --)              shift; while [ $# -gt 0 ]; do TEST_ARGS+=("$1"); shift; done; break ;;
    *)               TEST_ARGS+=("$1") ;;   # 나머지는 테스트로 전달
  esac
  shift
done

# ---- 환경 점검 ----
if [ -z "$JAVA_BIN" ]; then
  echo "[ERROR] java 를 찾을 수 없습니다. JAVA_HOME 또는 PATH 를 확인하세요."; exit 2
fi

# ---- 클린 ----
if [ "$DO_CLEAN" -eq 1 ]; then
  echo "[INFO] clean: $BUILD_DIR 삭제"
  rm -rf "$BUILD_DIR"
fi

# ---- 컴파일 ----
if [ "$DO_BUILD" -eq 1 ]; then
  if [ -z "$JAVAC_BIN" ]; then
    echo "[ERROR] javac 를 찾을 수 없습니다. JDK(JAVA_HOME) 를 확인하세요."; exit 2
  fi
  if [ ! -d "$SRC_DIR" ]; then
    echo "[ERROR] 소스 디렉터리가 없습니다: $SRC_DIR"; exit 2
  fi
  mkdir -p "$BUILD_DIR"
  SRC_LIST="$(mktemp)"
  find "$SRC_DIR" -name '*.java' > "$SRC_LIST"
  echo "[INFO] compile: $(wc -l < "$SRC_LIST") files (driver + tests) -> $BUILD_DIR"
  if ! "$JAVAC_BIN" -encoding UTF-8 -d "$BUILD_DIR" @"$SRC_LIST"; then
    echo "[ERROR] 컴파일 실패"; rm -f "$SRC_LIST"; exit 1
  fi
  rm -f "$SRC_LIST"
  # 드라이버가 클래스패스 루트에서 읽는 리소스 복사(jar 루트 배치와 동일)
  if [ -f "$RESOURCE" ]; then
    cp -f "$RESOURCE" "$BUILD_DIR/"
  fi
  echo "[INFO] compile OK"
fi

# ---- 실행 ----
if [ "$DO_RUN" -eq 0 ]; then
  echo "[INFO] build-only: 실행 생략"; exit 0
fi

if [ ! -f "$BUILD_DIR/cubrid/test/$TEST_CLASS.class" ]; then
  echo "[ERROR] $TEST_CLASS.class 없음 ($BUILD_DIR). 먼저 빌드하세요(-n 를 뺀 실행)."; exit 1
fi

echo "[INFO] run: cubrid.test.$TEST_CLASS ${TEST_ARGS[*]:-}  (JdbcDriverLog=$RTLOG)"
echo "----------------------------------------------------------------"
# shellcheck disable=SC2086
if [ "${#TEST_ARGS[@]}" -gt 0 ]; then
  exec "$JAVA_BIN" "-DJdbcDriverLog=$RTLOG" ${JAVA_OPTS:-} -cp "$BUILD_DIR" "cubrid.test.$TEST_CLASS" "${TEST_ARGS[@]}"
else
  exec "$JAVA_BIN" "-DJdbcDriverLog=$RTLOG" ${JAVA_OPTS:-} -cp "$BUILD_DIR" "cubrid.test.$TEST_CLASS"
fi
