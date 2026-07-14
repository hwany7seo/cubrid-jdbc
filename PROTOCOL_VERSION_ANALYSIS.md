# CUBRID JDBC 프로토콜 버전 분기 코드 분석

> 대상: `cubrid-jdbc-test/src/jdbc` (JCI 계층 + JDBC 드라이버 계층)
> 현재 프로토콜: `CAS_PROTOCOL_VERSION = PROTOCOL_V13` (`UConnection.java:105`)
> 작성일: 2026-07-15

---

## 1. 개요

이 드라이버는 브로커(CAS)와의 핸드셰이크에서 받은 `BROKER_INFO_PROTO_VERSION`으로
`brokerVersion`을 계산한 뒤, 요청/응답의 **와이어 포맷**과 **동작 로직**을 프로토콜 버전에
따라 분기한다. 버전 범위는 `PROTOCOL_V0` ~ `PROTOCOL_V13`(정의는 `PROTOCOL_V10` 결번).

분기에 사용되는 헬퍼는 4종이다.

| 헬퍼 | 의미 | 정의 위치 |
|------|------|-----------|
| `protoVersionIsAbove(v)` | `brokerVersion >= v` | `UConnection.java:1918` |
| `protoVersionIsUnder(v)` | `brokerVersion <  v` | `UConnection.java:1911` |
| `protoVersionIsSame(v)`  | `brokerVersion == v` | `UConnection.java:1904` |
| `protoVersionIsLower(v)` (static) | `protocolVersion < v` | `UConnection.java:2079` |

추가로, 핸드셰이크 시점에는 **두 가지 버전 인코딩 스킴**이 공존한다
(`UClientSideConnection.java:432-443`):

- `CAS_PROTO_INDICATOR` 비트가 있으면 → `makeProtoVersion()` (신 스킴, 단일 버전 번호)
- 없으면 → `makeBrokerVersion(major, minor, patch)` (구 스킴, 3바이트 버전)

이 이중 스킴 자체가 버전 분기 복잡도의 근원 중 하나다.

---

## 2. 버전별 분기 코드 인벤토리 (PROTOCOL_V13 신규 기능 제외)

> V13 전용 기능(홀더블 커서 지연 일괄 종료: `supportsBatchedCursorClose()`,
> `addDeferredCursorClose()`, `flushDeferredCursorClose()`,
> `closeExpiredHoldableCursors()` 등)은 요청대로 **제외**했다.

복잡도 기준:
- **낮음** — 지역적인 값 치환 1건, 스트림 파싱 위험 없음
- **중간** — 와이어 포맷 파싱에 영향 / 다분기 / 재시도·타임아웃 로직
- **높음** — 여러 파일에 중복 / 큰 switch 복제 / 파싱 실패 시 커넥션 전체 오염 / 스레드·재연결

### 2.1 핸드셰이크 / 버전 인코딩

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 1 | `UClientSideConnection.java:432-443` | 구/신 스킴 | `makeProtoVersion` vs `makeBrokerVersion(major,minor,patch)` — 버전 번호 해석 방식이 이원화 | **높음** |
| 2 | `UClientSideConnection.java:445` | V4+ | V4+는 `casId`를 읽고, 이하는 `casId=-1` | 중간 |
| 3 | `UClientSideConnection.java:451` | V3+ | V3+는 20바이트 `sessionId` 수신, 이하는 4바이트 `oldSessionId` 수신 | **높음** |
| 4 | `UClientSideConnection.java:457` | V7+ | 격리수준 min/max 초기값이 다름(READ_COMMITTED vs COMMIT_CLASS_UNCOMMIT_INSTANCE) | 중간 |
| 5 | `UConnection.java:2096-2107` (`checkReconnect`) | `brokerInfoVersion()==0` / V3+ / 그 외 | 세션 ID 직렬화 3분기 ("0" / 20바이트 복사 / oldSessionId 문자열) | **높음** |

### 2.2 세션 ID / 에러 메시지 포맷 (V3 · V4)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 6 | `UError.java:77` | V3+ | 에러 메시지의 세션 표기: `sessionId` 번호 vs `oldSessionId` | 낮음 |
| 7 | `UError.java:92` | V4+ | 에러 메시지에 `casId` 포함 여부(포맷 문자열 자체가 다름) | 낮음 |

### 2.3 에러 코드 변환 (V2)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 8 | `UInputBuffer.java:180-191` (`convertErrorByVersion`) | `!V2 && !renewedErrorCode` | 구 에러코드를 신 코드로 `-9000` 보정 | 중간 |

### 2.4 쿼리 타임아웃 (V1 · V2 · V4)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 9 | `UStatement.java:797-808` | V2+ / V1+ / 그 외 | 타임아웃 전송 단위 3분기: ms / 초(ceil) / 미전송 | 중간 |
| 10 | `CUBRIDStatement.java:862-869` | `protoVersionIsAbove(1)==false` 또는 비-CUBRID | 클라이언트측 `CUBRIDCancelQueryThread` 기동 여부 결정 | 중간 |
| 11 | `UConnection.java:575` (`batchExecute`) | V4+ | 배치 실행에 잔여 타임아웃(int) 전송 | 중간 |
| 12 | `UStatement.java:1084` (`writeExecuteBatchRequest`) | V4+ | 배치 프리페어드 실행에 잔여 타임아웃 전송 | 중간 |

### 2.5 응답 파싱 / 컬럼 메타 (V2)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 13 | `UStatement.java:816-828` (`readResultMeta`) | V2+ | V2+에서만 include-column-info 블록을 파싱 | 중간 |

### 2.6 커서 종료 오퍼레이션 코드 (V2)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 14 | `UStatement.java:685-687` | V2 | `CURSOR_CLOSE` vs `CURSOR_CLOSE_FOR_PROTOCOL_V2` | 중간 |
| 15 | `UConnection.java:1984-1986` | V2 | 동일 오퍼레이션 코드 분기(중복) | 중간 |
| 16 | `UConnection.java:2040-2042` | V2 | 동일 오퍼레이션 코드 분기(중복) | 중간 |

> ⚠️ 동일한 `CURSOR_CLOSE_FOR_PROTOCOL_V2` 분기가 **3곳에 중복**되어 있음.

### 2.7 홀더블 결과셋 지원 판정 (V2)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 17 | `UConnection.java:1713-1719` (`supportHoldableResult`) | 브로커 플래그 OR `protoVersionIsSame(V2)` | V2를 특례로 true 처리 | 낮음 |

### 2.8 샤드 정보 (V5)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 18 | `UConnection.java:530` (`getSchemaInfo`) | V5+ | `shard_id` 전송 | 중간 |
| 19 | `UConnection.java:611` (`batchExecute`) | V5+ | 응답에서 `shardId` 파싱 | 중간 |
| 20 | `UStatement.java:909` (`executeInternal`) | V5+ | 응답에서 `shardId` 파싱 | 중간 |
| 21 | `UStatement.java:1139` (`executeBatchInternal`) | V5+ | 응답에서 `shardId` 파싱 | 중간 |
| 22 | `UStatement.java:2359-2362` (`read_fetch_data`) | V5+ | FETCH 응답에서 `isFetchCompleted` 바이트 파싱 | 중간 |

### 2.9 격리 수준 매핑 (V7)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 23 | `CUBRIDConnection.java:300-348` (`setTransactionIsolation`) | V7+ | JDBC→CUBRID 격리수준 매핑 switch **2벌** (신/구) | **높음** |
| 24 | `CUBRIDConnection.java:379-410` (`getTransactionIsolation`) | V7+ | CUBRID→JDBC 매핑 switch **2벌** | **높음** |
| 25 | `CUBRIDDatabaseMetaData.java:692-705` (`supportsTransactionIsolationLevel`) | V7+ | 지원 격리수준 집합이 다름 | 중간 |

### 2.10 실행 재시도 / XASL 캐시 (V7)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 26 | `UStatement.java:1010-1035` (`executeInternal`) | V7+ | V7+는 `XASL_CACHE_PINNED`로 1회 재시도, 이하는 무한 루프 재시도 | **높음** |
| 27 | `UStatement.java:1202-1230` (`executeBatch`) | V7+ | 동일 패턴(무한 루프 vs 1회) | **높음** |

### 2.11 연결 생존 확인 / keepalive (V9)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 28 | `UConnection.java:1385-1400` (`isValid`) | V9 미만 | V9 미만은 `!isClosed`만 반환, 이상은 `statusBroker` 사용 | 중간 |
| 29 | `UTimedDataInputStream.java:152-161` | V9 미만 | 읽기 타임아웃 시: V9 미만은 `pingBroker`, 이상은 `statusBroker`+재시도 | 중간 |

### 2.12 SRV_HANDLE ID 폭 (V11)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 30 | `UStatement.java:315-319` (`MAKE_OUT_RS`) | V11 미만 | `addInt((int)id)` vs `addLong(id)` | 중간 |
| 31 | `UStatement.java:2326-2330` (`readData`, U_TYPE_RESULTSET) | V11 미만 | `readInt` vs `readLong` | 중간 |

> 관련 주석: `CUBRIDOutResultSet.java:42` ("if under PROTOCOL_V11 → SRV_HANDLE id").

### 2.13 Oracle 호환 숫자 동작 (V12)

| # | 위치 | 기준 | 동작 차이 | 복잡도 |
|---|------|------|-----------|--------|
| 32 | `UConnection.java:1721-1729` (`isOracleCompatNumberBehavior`) | V12+ | V12+만 브로커 시스템 파라미터 플래그 확인, 이하는 항상 false | 낮음 |

---

## 3. 복잡도 요약

| 구분 | 분기 지점 수 |
|------|:---:|
| 총 분기 지점 (V13 신규 제외) | **32** |
| 높음 | 7 |
| 중간 | 20 |
| 낮음 | 5 |

**가장 위험/복잡한 핫스팟**

1. **핸드셰이크 이중 버전 스킴 + 세션 ID 3분기** (#1, #3, #5)
   — 파싱을 틀리면 커넥션 전체가 오염되는 최고 위험군. 세 곳이 서로 얽혀 있음.
2. **격리 수준 매핑 switch 2벌 × 3개 메서드** (#23~#25)
   — 폐기된 격리수준(`TRAN_COMMIT_CLASS_UNCOMMIT_INSTANCE` 등)이 공개 API 표면에 노출.
3. **실행 재시도 로직의 무한 루프 경로** (#26, #27)
   — 구버전 경로가 `loop=true`(무한 재시도)라 잠재적 hang 위험.
4. **와이어 포맷 산발 분기** (#18~#22, #30~#31)
   — 샤드 필드·핸들 폭이 여러 메서드에 흩어져 있어 프로토콜 변경 시 누락 위험.
5. **중복 분기** — `CURSOR_CLOSE_FOR_PROTOCOL_V2`(3곳), 샤드 파싱(4곳),
   격리 매핑(3곳)처럼 같은 조건이 반복 등장.

---

## 4. PROTOCOL_V13 이상만 지원할 때의 장점

> 전제: `brokerVersion`이 항상 `>= PROTOCOL_V13`이므로 모든
> `protoVersionIsAbove(V1..V12)`는 상수 `true`, `protoVersionIsUnder/isLower(≤V12)`는
> 상수 `false`, `protoVersionIsSame(V1..V12)`는 상수 `false`로 접힌다.

### 4.1 코드 단순화 / 복잡도 감소
- **32개 분기 지점 제거** → 각 `if/else`가 참 분기만 남고, 죽은(else) 경로가 사라짐.
  본 파일 기준 순환복잡도가 대폭 감소.
- 같은 조건의 **중복 분기 제거**: `CURSOR_CLOSE_FOR_PROTOCOL_V2`(3곳),
  샤드 파싱(4곳), 격리 매핑 switch(3곳)가 각각 단일 경로로 통합.

### 4.2 와이어 포맷 고정 → 파싱 위험 제거
- 세션 ID는 **항상 20바이트**(#3, #5, #6) — `oldSessionId`(int) 경로와
  `brokerInfoVersion()==0` 특례 삭제.
- `casId`는 **항상 존재**(#2, #7).
- 샤드 필드는 **항상 존재**(#18~#22) — 조건부 read 제거로 스트림 정렬 오류 위험 소멸.
- SRV_HANDLE는 **항상 long**(#30, #31) — int 캐스팅 경로 삭제.
- 쿼리 타임아웃은 **항상 ms 단위**(#9) — 초/ceil 분기 및 미전송 경로 삭제.

### 4.3 버전 인코딩 단일화
- `CAS_PROTO_INDICATOR` 신 스킴만 남으므로 **`makeBrokerVersion(major,minor,patch)`
  경로 전체 삭제 가능**(#1). 핸드셰이크의 이중 해석 로직이 사라짐.

### 4.4 격리 수준 API 정리
- V7 이전 switch(폐기 격리수준 포함)를 제거하여
  **READ_COMMITTED / REPEATABLE_READ / SERIALIZABLE** 3종만 남김(#23~#25).
- `setIsolationLevelMin/Max`의 버전 분기(#4)도 상수화.

### 4.5 에러 처리 단순화
- `convertErrorByVersion`의 `-9000` 보정 로직과 renewed-error-code 확인 제거(#8)
  — 에러 코드가 항상 신 스킴.

### 4.6 생존 확인 / keepalive 일원화
- `pingBroker` 기반 구경로 제거, **`statusBroker` 단일 경로**로 통일(#28, #29)
  — 더 정확한 커넥션 상태 판정.

### 4.7 취소(cancel) 일원화
- 항상 `cancelBrokerEx` 사용, `cancelBroker` 제거(핸드셰이크 #2와 별개로
  `UConnection.java:1401-1406`).

### 4.8 실행 재시도 안정화
- 항상 V7+ 경로(`XASL_CACHE_PINNED` 1회 재시도)만 남기고
  **무한 루프 재시도 경로 제거**(#26, #27) — 잠재적 hang 제거.

### 4.9 클라이언트측 취소 스레드 경감
- CUBRID + V13 조합에서는 서버가 쿼리 타임아웃을 강제하므로,
  `CUBRIDCancelQueryThread` 기동 조건이 사실상 비-CUBRID 케이스로 축소(#10)
  — 스레드 생성/경합 감소.

### 4.10 홀더블 결과 · V13 신규 기능 단일 경로화
- `supportHoldableResult()`가 항상 true로 수렴(#17) → 폴백 불필요.
- **V13 지연 커서 종료 기능**(`supportsBatchedCursorClose()`)이 유일 경로가 되어,
  즉시 종료(round-trip per close) 폴백 코드를 유지할 필요가 없어짐 →
  이번 신규 작업의 유지보수 부담 감소.

### 4.11 테스트/QA 매트릭스 축소
- V0~V12 브로커 대상 회귀 테스트 조합이 제거되어 검증 비용이 크게 감소.

---

## 5. 트레이드오프 / 고려사항

- **하위 호환성 상실**: PROTOCOL_V13 미만 브로커(구버전 CUBRID/CAS)에는
  접속 불가. 지원 종료 대상 서버 버전을 명확히 공지해야 함.
- **단계적 제거 권장**: 상수 접힘(`if(true)`)만 먼저 반영하고, 죽은 코드는
  후속 커밋에서 제거하면 리뷰/회귀 위험을 낮출 수 있음.
- **핸드셰이크 방어 로직**: 버전 협상 자체는 남겨두고, V13 미만이면 명시적
  에러(예: `ER_CONNECTION`)로 실패시키는 가드를 두는 편이 안전.

---

## 6. 참고 — 분석 중 발견한 컴파일 오류 (V13 신규 코드)

요청 범위(V13 제외) 밖이지만, 현재 작업 파일에서 컴파일 불가 코드를 발견하여 남긴다.

- `UConnection.java` `closeExpiredHoldableCursors()`
  (대략 `2020-2075` 라인): 로컬 리스트를 `closeList`로 선언·사용하면서
  `if (victims == null)` 로 **선언되지 않은 식별자 `victims`** 를 참조.
  `victims` → `closeList` 로 수정 필요(리팩터링 잔재로 추정).
