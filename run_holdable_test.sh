#!/bin/bash
#
# run_holdable_test.sh
#   holdable cursor close 개선(deferred batch close + TTL 스케줄러) 검증 테스트
#   (HoldableCloseTest) 실행 스크립트.
#
#   드라이버는 build.sh(cmake + ant)로 빌드한 jar(cubrid_jdbc.jar)를 사용한다.
#   빌드 jar 는 version_string(@JDBC_DRIVER_VERSION_STRING@)이 VERSION 기준으로
#   치환되어 있으므로, 드라이버 소스를 직접 컴파일할 때의
#   "Could not parse version_string" 문제가 없다.
#   테스트(test/, package cubrid.test)는 그 jar 를 classpath 로 두고 컴파일한다.
#
#   판정은 broker SQL 로그(SQL_LOG=ON)의 "cursor_close srv_h_id N" 라인으로 한다.
#   테스트가 같은 연결로 마커 쿼리(SELECT 'HCT:<token>:<label>' FROM db_root)를
#   실행해 로그에 경계를 찍고, 경계 사이의 cursor_close 수/묶음/시각을 확인한다.
#
#   연결 대상은 JDBC_URL 환경변수 또는 test/ConnectWorker.java 기본값으로 정한다.
#
# 사용법:
#   ./run_holdable_test.sh [옵션] [-- ] [테스트 인자...]
#
#   옵션:
#     -c, --clean        드라이버(build.sh clean)와 테스트 클래스 삭제 후 다시 빌드
#     -s, --skip-driver  드라이버 빌드 생략(기존 jar 사용), 테스트만 컴파일
#     -n, --no-build     드라이버·테스트 빌드 모두 생략하고 바로 실행
#     -b, --build-only   빌드만 하고 실행하지 않음(DB 불필요, 검증용)
#     -t, --test NAME    실행할 cubrid.test 클래스 (기본: HoldableCloseTest)
#     -h, --help         도움말
#
#   테스트 인자(HoldableCloseTest 로 그대로 전달):
#     [iterations]       포인트-셀렉트 반복 수 (기본 2000)
#     verbose | -v       매 연산마다 [RT-OP] 마커 + 스케줄러 cursor_close 라인 출력
#
#   환경변수:
#     JAVA_HOME          지정 시 해당 JDK 사용
#     JAVA_OPTS          java 에 전달할 추가 옵션 (예: -Dholdable.iters=5000)
#     JDBC_JAR           사용할 드라이버 jar (기본: 빌드 결과 ./cubrid_jdbc.jar).
#                        지정하면 드라이버 빌드를 생략하고 이 jar 를 쓴다.
#     JDBC_URL           접속 URL (예: jdbc:cubrid:localhost:33000:testdb:::)
#     JDBC_USER / JDBC_PASSWORD   접속 사용자 (기본 dba / 빈 값)
#     HOLDABLE_SHARD_ID  shard broker 에서 테스트 테이블을 둘 shard (기본 0)
#                        shard broker 는 드라이버가 판별한다(proxy 연결): shard_id 힌트를 붙이고
#                        shard 시나리오 10~13 을 수행한다(9 는 proxy 가 NEXT_RESULT 미지원이라 생략).
#     HOLDABLE_TTL_MS    스케줄러 TTL ms (기본 10000; 드라이버 기본은 5000)
#     HOLDABLE_SCAN_SEC  스케줄러 스캔 주기 초 (기본 2; 드라이버 기본은 1)
#     BROKER_LOG_DIR     broker SQL 로그 디렉터리 (기본 $CUBRID/log/broker/sql_log)
#
# 예:
#   ./run_holdable_test.sh                    # 드라이버 jar 빌드 + 테스트 컴파일 후 실행
#   ./run_holdable_test.sh 50 verbose         # 소량 + 퍼-op 마커
#   HOLDABLE_TTL_MS=5000 HOLDABLE_SCAN_SEC=1 ./run_holdable_test.sh 50   # 드라이버 기본값
#   ./run_holdable_test.sh -c 5000            # 클린 빌드 후 5000회
#   ./run_holdable_test.sh -s                 # 기존 jar 로 테스트만 다시 컴파일해 실행
#   JAVA_OPTS="-Dholdable.iters=3000" ./run_holdable_test.sh -n
#   ./run_holdable_test.sh -b                 # 빌드만(DB 불필요)
#   ./run_holdable_test.sh -t BatchTest       # 다른 테스트 재사용
#   JDBC_JAR=/path/to/cubrid-jdbc.jar ./run_holdable_test.sh   # 다른 드라이버 jar 로 실행
#   JDBC_URL=jdbc:cubrid:localhost:45511:shard1::: JDBC_USER=shard JDBC_PASSWORD=shard123 \
#     ./run_holdable_test.sh                  # shard broker
#

set -u

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
TEST_DIR="$SCRIPT_DIR/test"                            # 테스트 소스(package cubrid.test)
BUILD_DIR="$SCRIPT_DIR/output/holdable-test-classes"   # output/ 는 .gitignore 대상
DRIVER_JAR="${JDBC_JAR:-$SCRIPT_DIR/cubrid_jdbc.jar}"  # build.sh 가 만드는 심볼릭 링크
TEST_CLASS="HoldableCloseTest"
HOLDABLE_TTL_MS="${HOLDABLE_TTL_MS:-10000}"
HOLDABLE_SCAN_SEC="${HOLDABLE_SCAN_SEC:-2}"
BROKER_LOG_DIR="${BROKER_LOG_DIR:-${CUBRID:+$CUBRID/log/broker/sql_log}}"

DO_DRIVER_BUILD=1
DO_TEST_BUILD=1
DO_RUN=1
DO_CLEAN=0
TEST_ARGS=()

# 다른 jar 를 지정하면 이 저장소의 드라이버를 빌드할 필요가 없다
[ -n "${JDBC_JAR:-}" ] && DO_DRIVER_BUILD=0

# ---- JDK 경로 (build.sh 관례와 동일) ----
if [ "x${JAVA_HOME:-}" != "x" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
JAVAC_BIN="$(command -v javac || true)"
JAVA_BIN="$(command -v java || true)"

usage() { awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"; }

# ---- 인자 파싱 ----
while [ $# -gt 0 ]; do
  case "$1" in
    -c|--clean)       DO_CLEAN=1 ;;
    -s|--skip-driver) DO_DRIVER_BUILD=0 ;;
    -n|--no-build)    DO_DRIVER_BUILD=0; DO_TEST_BUILD=0 ;;
    -b|--build-only)  DO_RUN=0 ;;
    -t|--test)        shift; TEST_CLASS="${1:-}";
                      [ -z "$TEST_CLASS" ] && { echo "[ERROR] -t 옵션에 클래스명이 필요합니다"; exit 2; } ;;
    -h|--help|-\?)    usage; exit 0 ;;
    --)               shift; while [ $# -gt 0 ]; do TEST_ARGS+=("$1"); shift; done; break ;;
    *)                TEST_ARGS+=("$1") ;;   # 나머지는 테스트로 전달
  esac
  shift
done

# ---- 환경 점검 ----
if [ -z "$JAVA_BIN" ]; then
  echo "[ERROR] java 를 찾을 수 없습니다. JAVA_HOME 또는 PATH 를 확인하세요."; exit 2
fi

# ---- 클린 ----
if [ "$DO_CLEAN" -eq 1 ]; then
  if [ "$DO_DRIVER_BUILD" -eq 1 ]; then
    echo "[INFO] clean: 드라이버 빌드 결과 삭제 (build.sh clean)"
    "$SCRIPT_DIR/build.sh" clean > /dev/null
  fi
  echo "[INFO] clean: $BUILD_DIR 삭제"
  rm -rf "$BUILD_DIR"
fi

# ---- 드라이버 jar 빌드 ----
if [ "$DO_DRIVER_BUILD" -eq 1 ]; then
  echo "[INFO] driver build: build.sh (cmake + ant) -> $DRIVER_JAR"
  STAMP="$(mktemp)"
  # build.sh 는 빌드 실패에도 0 으로 끝나므로, jar 가 이번에 새로 만들어졌는지로 판정한다
  if ! "$SCRIPT_DIR/build.sh"; then
    echo "[ERROR] 드라이버 빌드 실패 (build.sh)"; rm -f "$STAMP"; exit 1
  fi
  if [ ! -e "$DRIVER_JAR" ] || [ -z "$(find -L "$DRIVER_JAR" -newer "$STAMP" 2>/dev/null)" ]; then
    echo "[ERROR] 드라이버 jar 가 새로 생성되지 않았습니다: $DRIVER_JAR (build.sh 출력 확인)"
    rm -f "$STAMP"; exit 1
  fi
  rm -f "$STAMP"
  echo "[INFO] driver build OK: $(readlink -f "$DRIVER_JAR")"
fi

if [ ! -f "$DRIVER_JAR" ]; then
  echo "[ERROR] 드라이버 jar 가 없습니다: $DRIVER_JAR (-s/-n 없이 실행해 빌드하거나 JDBC_JAR 지정)"; exit 1
fi

# ---- 테스트 컴파일 (드라이버 jar 를 classpath 로) ----
if [ "$DO_TEST_BUILD" -eq 1 ]; then
  if [ -z "$JAVAC_BIN" ]; then
    echo "[ERROR] javac 를 찾을 수 없습니다. JDK(JAVA_HOME) 를 확인하세요."; exit 2
  fi
  if [ ! -d "$TEST_DIR" ]; then
    echo "[ERROR] 테스트 디렉터리가 없습니다: $TEST_DIR"; exit 2
  fi
  mkdir -p "$BUILD_DIR"
  SRC_LIST="$(mktemp)"
  find "$TEST_DIR" -name '*.java' > "$SRC_LIST"
  echo "[INFO] compile: $(wc -l < "$SRC_LIST") test files (cp=$DRIVER_JAR) -> $BUILD_DIR"
  if ! "$JAVAC_BIN" -encoding UTF-8 -cp "$DRIVER_JAR" -d "$BUILD_DIR" @"$SRC_LIST"; then
    echo "[ERROR] 테스트 컴파일 실패"; rm -f "$SRC_LIST"; exit 1
  fi
  rm -f "$SRC_LIST"
  echo "[INFO] compile OK"
fi

# ---- 실행 ----
if [ "$DO_RUN" -eq 0 ]; then
  echo "[INFO] build-only: 실행 생략"; exit 0
fi

if [ ! -f "$BUILD_DIR/cubrid/test/$TEST_CLASS.class" ]; then
  echo "[ERROR] $TEST_CLASS.class 없음 ($BUILD_DIR). 먼저 빌드하세요(-n 를 뺀 실행)."; exit 1
fi

RUN_OPTS=("-Dcubrid.holdable.cursor.ttl.millis=$HOLDABLE_TTL_MS"
          "-Dcubrid.holdable.cursor.scan.sec=$HOLDABLE_SCAN_SEC")
[ -n "$BROKER_LOG_DIR" ] && RUN_OPTS+=("-Dholdable.brokerlog.dir=$BROKER_LOG_DIR")
[ -n "${HOLDABLE_SHARD_ID:-}" ] && RUN_OPTS+=("-Dholdable.shard.id=$HOLDABLE_SHARD_ID")

CLASSPATH_RUN="$DRIVER_JAR:$BUILD_DIR"

echo "[INFO] run: cubrid.test.$TEST_CLASS ${TEST_ARGS[*]:-}"
echo "[INFO]      driver=$(readlink -f "$DRIVER_JAR")"
echo "[INFO]      ttl=${HOLDABLE_TTL_MS}ms scan=${HOLDABLE_SCAN_SEC}s broker-log=${BROKER_LOG_DIR:-<none>} url=${JDBC_URL:-<ConnectWorker default>}"
echo "----------------------------------------------------------------"
# shellcheck disable=SC2086
if [ "${#TEST_ARGS[@]}" -gt 0 ]; then
  exec "$JAVA_BIN" "${RUN_OPTS[@]}" ${JAVA_OPTS:-} -cp "$CLASSPATH_RUN" "cubrid.test.$TEST_CLASS" "${TEST_ARGS[@]}"
else
  exec "$JAVA_BIN" "${RUN_OPTS[@]}" ${JAVA_OPTS:-} -cp "$CLASSPATH_RUN" "cubrid.test.$TEST_CLASS"
fi
