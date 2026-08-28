# JDBC lock timeout 작업 정리

CCI의 `cci_set_lock_timeout()`에 해당하는 기능을 JDBC에서 쓸 수 있는지 확인하다가
발견한 문제들과, 그에 대한 수정 및 남은 과제를 정리한 문서다.

- 대상 저장소: `cubrid-jdbc` (branch: 작업 브랜치)
- 참조 저장소: `cubrid-cci`, `cubrid` (broker/server)
- 검증 환경: CUBRID 11.4 브로커 `test-db-server:33000`, `demodb`, JDK 8 (`-source/-target 1.8`)

---

## 0. 출발점

JDBC에는 이미 `CUBRIDConnection.setLockTimeout(int)`가 있고(8.4.0부터),
CCI와 완전히 동일한 프로토콜(`CAS_FC_SET_DB_PARAMETER(5)` + `CCI_PARAM_LOCK_TIMEOUT(2)`)을 쓴다.
따라서 "JDBC에서 lock timeout을 못 쓴다"는 아니었고, 실제로 확인해 보니
**설정은 되지만 유지되지 않는** 문제들이 있었다.

```java
// 기본 사용법 (JDBC 4.2 unwrap 사용)
CUBRIDConnection cc = con.unwrap(CUBRIDConnection.class);
cc.setLockTimeout(3000);   // ms
cc.setLockTimeout(0);      // NO WAIT
cc.setLockTimeout(-1);     // 무한 대기
cc.setLockTimeout(-2);     // 브로커 기본값으로 복귀
```

값의 의미는 브로커의 `fn_set_db_parameter()`(`cubrid/src/broker/cas_function.c`) 기준이다.

| 전송 값 | 서버 동작 |
|---|---|
| `> 0` | 해당 밀리초 대기 |
| `0` | NO WAIT |
| `-1` | 무한 대기 |
| `-2` | `cas_default_lock_timeout`(CAS 기동 시 읽은 `cubrid.conf`의 `lock_timeout`)으로 치환 |
| `< -1` | `CAS_ER_ARGS` 오류 |

---

## 1. 발견한 문제

### (a) 재접속 시 재적용이 동작하지 않음 — 실동작 버그

CAS는 클라이언트가 붙을 때마다 `ux_set_default_setting()`으로 isolation level과 lock timeout을
브로커 기본값으로 되돌린다. 그래서 드라이버는 (재)접속 직후에 값을 다시 밀어 넣어야 한다.
CCI는 `net_connect_srv()` 끝에서 `qe_set_db_parameter()`를 **직접** 호출해 이를 처리한다.

JDBC에도 같은 자리에 코드가 있었지만 무력화되어 있었다.

```java
// UClientSideConnection.reconnectWorker() — 수정 전
int isolationLevel = currentIsolationLevel();
if (isolationLevel != CUBRIDIsolationLevel.TRAN_UNKNOWN_ISOLATION)
    setIsolationLevel(isolationLevel);

int lockTimeout = getLockTimeout();
if (lockTimeout != LOCK_TIMEOUT_NOT_USED) setLockTimeout(lockTimeout);
```

`setLockTimeout()` / `setIsolationLevel()`의 첫머리에 있는 중복 요청 제거 가드가
`lastLockTimeout == timeout`, `lastIsolationLevel == level`을 보고 **항상 즉시 return** 한다.
복원하려는 값이 곧 캐시된 값이므로 가드에 100% 걸린다.

결과: alt-hosts failover, 소켓 재접속 이후 lock timeout과 isolation level이 조용히
브로커 기본값으로 되돌아간다. isolation level 쪽은 드라이버 캐시(`getTransactionIsolation()`)가
계속 이전 값을 반환하므로 애플리케이션에서는 **보이지 않는 다운그레이드**가 된다.

### (b) `-2` 상태 추적이 어긋남 + 클라이언트 값 검증 없음

```java
// UConnection.setLockTimeout() — 수정 전
if (timeout < 0) lastLockTimeout = LOCK_TIMEOUT_INFINITE;
else lastLockTimeout = timeout;
```

`setLockTimeout(-2)`(브로커 기본값 복귀)를 보내면 서버는 기본값으로 설정하는데
드라이버는 `-1`(무한 대기)로 기록한다. 그 뒤 `setLockTimeout(-1)`을 호출하면
중복 제거 가드에 걸려 전송되지 않고, 서버는 기본값을 그대로 유지한다.
CCI는 `con_handle->lock_timeout = i_val`로 원값을 보존한다(`cas_cci.c`).

또한 `< -2` 값을 그대로 서버에 보내 `CAS_ER_ARGS`를 받아 왔다.

### (c) URL / DataSource 속성이 없음

CCI에는 데이터소스 속성 `default_lock_timeout`이 있어 접속 시 자동 적용되지만
(`cci_datasource_borrow`), JDBC에는 대응 속성이 없어 코드 수정 없이 적용할 방법이 없었다.

### (d) 풀이 물리 커넥션을 재사용할 때 값이 다음 borrower에게 승계됨

CAS는 클라이언트가 새로 붙을 때만 초기화된다. `CUBRIDPooledConnection`은 논리 커넥션만
교체하고 물리 커넥션(소켓)을 유지하므로 CAS가 브로커 루프로 돌아가지 않고,
따라서 초기화 지점을 지나치지 않는다.

실측 결과:

```
=== case 1: 별개의 물리 커넥션 (close -> 재접속) ===
  con A CAS = 2/pid1276004, con B CAS = 2/pid1276004  (같은 CAS 재사용)
  con B lock wait = 4882ms  -> 승계 안 됨 (브로커 기본값 = 무한 대기)

=== case 2: 같은 물리 커넥션을 풀이 재사용 ===
  borrow #1 CAS = 2/pid1276004   (setLockTimeout(300) 후 논리 close)
  borrow #2 CAS = 2/pid1276004
  borrow #2 getLockTimeout() = -2      <- 드라이버가 보는 값
  borrow #2 lock wait = 395ms          <- 서버에 실제로 남은 값
```

즉 **물리 커넥션이 다르면 문제 없고**, 같은 물리 커넥션을 논리적으로 나눠 쓸 때만 문제다.
드라이버 뷰(`-2`)와 서버 상태(300ms)가 어긋나는 것까지 포함된다.
CCI도 데이터소스에 `default_lock_timeout`을 지정하지 않으면 같은 구멍이 있다.

---

## 2. 수정 내용

총 5개 파일, +125 / -20.

### 2.1 `UConnection.java`

**중복 제거 가드를 우회하는 원시 전송 경로 분리** — CCI의 `qe_set_db_parameter` 대응

```java
private void sendSetDbParameter(int paramName, int value) throws UJciException, IOException {
    outBuffer.newRequest(output, UFunctionCode.SET_DB_PARAMETER);
    outBuffer.addInt(paramName);
    outBuffer.addInt(value);
    send_recv_msg();
}
```

`setIsolationLevel()` / `setLockTimeout()`도 이 함수를 공유하도록 정리했다.

**(a) `applyDbParameters()` — (재)접속 직후 baseline 재적용**

```java
protected void applyDbParameters() throws UJciException, IOException {
    int isolationLevel = currentIsolationLevel();
    if (isolationLevel != CUBRIDIsolationLevel.TRAN_UNKNOWN_ISOLATION) {
        sendSetDbParameter(DB_PARAM_ISOLATION_LEVEL, isolationLevel);
    }

    int lockTimeout =
            lockTimeoutSetByUser ? lastLockTimeout : connectionProperties.getLockTimeout();
    if (lockTimeout != LOCK_TIMEOUT_NOT_USED) {
        sendSetDbParameter(DB_PARAM_LOCK_TIMEOUT, lockTimeout);
        lastLockTimeout = lockTimeout;
    }
}
```

**(b) 원값 보존 + 값 검증**

- `lastLockTimeout = timeout;` 으로 원값을 그대로 저장 (`-2`가 `-1`로 뭉개지지 않음)
- `timeout < -2`는 서버에 보내기 전에 `ER_INVALID_ARGUMENT`로 거부
- `lockTimeoutSetByUser` 플래그 추가.
  `-2`가 "한 번도 설정 안 함"과 "명시적으로 브로커 기본값으로 복귀" 두 가지를 동시에 의미해서,
  후자에서 재접속 시 URL 속성값이 되살아나는 것을 막기 위한 구분자다.

**(d) `resetDbParameters()` — 풀 borrow 시 baseline 복원**

Connector/J의 `ConnectionImpl.resetServerState()`에 해당한다. CUBRID 프로토콜에는
`COM_RESET_CONNECTION` 같은 세션 전체 초기화가 없으므로 `SET_DB_PARAMETER`로
되돌릴 수 있는 두 값만 다시 밀어 넣는다.

- **lock timeout**: URL `lockTimeout` 속성값을 전송. 속성이 없으면 `-2`를 보내고
  CAS가 `cas_default_lock_timeout`으로 치환하므로 브로커 기본값까지 완전 복원된다.
- **isolation level**: 프로토콜에 "기본값으로 돌아가라"는 sentinel이 없어서,
  **첫 borrow 시점에 CAS가 보고하는 값을 기본값으로 학습**해 두고 이후 borrow에서 그 값으로 되돌린다.
  두 생성 경로(`CUBRIDConnectionPoolDataSource.getPooledConnection()`) 모두 갓 만든 커넥션을
  넘기므로 첫 borrow 시점에 CAS가 기본 상태임이 보장된다. 학습은 커넥션당 `GET_DB_PARAMETER` 1회.

값이 이미 baseline과 같으면 전송을 생략하므로 첫 borrow에서는 추가 왕복이 없다.

**`setCUBRIDConnection()` 기준선 조정**

```java
lastLockTimeout = connectionProperties.getLockTimeout();   // 기존: LOCK_TIMEOUT_NOT_USED
lockTimeoutSetByUser = false;
```

이 메서드는 물리 커넥션이 새 논리 커넥션으로 재포장될 때 추적값을 초기화하는데,
접속 시 `applyDbParameters()`가 이미 URL 속성값을 CAS에 밀어 넣은 뒤이므로
sentinel(`-2`)이 아니라 그 값이 올바른 기준선이다.
이 조정 전에는 `?lockTimeout=1500`으로 접속해도 `getLockTimeout()`이 `-2`를 반환했다.

**상수 공개**

`LOCK_TIMEOUT_NOT_USED` / `LOCK_TIMEOUT_INFINITE`를 `protected` → `public`으로.
`driver` 패키지의 `ConnectionProperties`에서 참조해야 하고, 사용자 코드에서도 쓸 수 있다.

### 2.2 `UClientSideConnection.java`

`reconnectWorker()`의 무력화된 6줄을 `applyDbParameters()` 호출로 교체.
`connectDB()` 직후, `needReconnection = false` 다음 자리 — CAS가 방금 기본값으로 초기화된
직후이자 소켓이 사용 가능해진 첫 지점이다. CCI가 `net_connect_srv()` 끝에 두는 것과 같은 위치다.

### 2.3 `ConnectionProperties.java`

**(c) `lockTimeout` 커넥션 속성 추가**

```java
/* milliseconds; 0 means no wait, -1 infinite wait, -2 keep the broker default */
IntegerConnectionProperty lockTimeout =
        new IntegerConnectionProperty(
                "lockTimeout",
                UConnection.LOCK_TIMEOUT_NOT_USED,
                UConnection.LOCK_TIMEOUT_NOT_USED,
                Integer.MAX_VALUE);
```

기본값 `-2`, 하한 `-2`. static 블록이 리플렉션으로 자동 등록하므로 필드 선언만으로 동작한다.

```
jdbc:cubrid:host:33000:demodb:dba::?lockTimeout=3000
```

이름·ms 단위·`-1`=무한 의미는 mssql-jdbc의 `lockTimeout` 속성과 동일하다.

### 2.4 `CUBRIDConnection.java`

`getLockTimeout()` 공개 게터 추가. 기존에는 `getUConnection().getLockTimeout()`을 경유해야 했다.

### 2.5 `CUBRIDPooledConnection.java`

`getConnection()`에서 새 wrapper를 만들기 직전에 리셋을 삽입.
Connector/J의 `MysqlPooledConnection.getConnection(boolean, boolean)`이
`resetServerState()`를 호출하는 것과 같은 자리다.

```java
if (u_con.check_cas() == false) {
    u_con.resetConnection();
}

u_con.resetDbParameters();
UError error = u_con.getRecentError();
if (error.getErrorCode() != UErrorCode.ER_NO_ERROR) {
    throw new CUBRIDException(error);
}

cubConnection = new CUBRIDConnectionWrapperPooling(u_con, null, null, this);
```

`CUBRIDXAConnection`은 `getConnection()`을 자체 오버라이드하므로 이 경로를 타지 않는다.
XA 트랜잭션 컨텍스트를 건드리지 않도록 의도적으로 그대로 두었다.

---

## 3. 값의 흐름

값은 세 곳에 나뉘어 있다.

| 위치 | 내용 | 수명 |
|---|---|---|
| `connectionProperties.lockTimeout` | URL `?lockTimeout=N`. 접속 후 불변 | 물리 커넥션 |
| `lastLockTimeout` + `lockTimeoutSetByUser` | 드라이버가 "CAS에 밀어 넣었다고 믿는" 값 | 물리 커넥션 |
| CAS의 `tran_reset_wait_times()` | 서버의 실제 값 | CAS ← 클라이언트 attach마다 초기화 |

```
DriverManager.getConnection(url?lockTimeout=1500)
  │
  ├─ ConnectionProperties.setProperties()      → property = 1500
  ├─ u_con.setConnectionProperties()
  └─ tryConnect() → checkReconnect() → reconnect() → reconnectWorker()
        ├─ connectBroker()                소켓 연결
        ├─ connectDB()                    CAS attach
        │     └─(서버) cas_handle_db_connection → cas_post_db_connect
        │              → ux_set_default_setting()
        │                 ★ CAS의 lock_timeout = 브로커 기본값
        ├─ needReconnection = false; isClosed = false
        └─ applyDbParameters()            ★ 그래서 바로 여기서 다시 밀어 넣는다
              └─ SET_DB_PARAMETER(2, 1500);  lastLockTimeout = 1500

new CUBRIDConnection(...) → setCUBRIDConnection()
  └─ lastIsolationLevel = UNKNOWN
     lastLockTimeout = property (1500)
     lockTimeoutSetByUser = false

cc.setLockTimeout(500)
  ├─ 500 < -2 ?                       → ER_INVALID_ARGUMENT (서버 왕복 없음)
  ├─ lastLockTimeout(1500) == 500 ?   → 같으면 왕복 생략
  ├─ checkReconnect()                 → 끊겨 있었다면 위 흐름이 먼저 돌아 1500 복원
  └─ SET_DB_PARAMETER(2, 500)
       lastLockTimeout = 500; lockTimeoutSetByUser = true

풀 borrow (CUBRIDPooledConnection.getConnection())
  └─ resetDbParameters()
       ├─ 첫 borrow면 GET_DB_PARAMETER로 CAS 기본 isolation 학습
       ├─ isolation이 바뀌어 있으면 기본값으로 복원
       └─ lock timeout을 URL 속성값(없으면 -2)으로 복원

Connection.close() → u_con.close()
  └─ CAS가 브로커 루프로 복귀 → 다음 클라이언트 attach 시 ux_set_default_setting()
```

---

## 4. 검증

실제 브로커(`test-db-server:33000/demodb`)에 붙여서, 잠금을 쥔 커넥션과 대기하는 커넥션을 두고
**실제 대기 시간을 측정**하는 방식으로 확인했다. 이 서버는 `cubrid.conf`에 `lock_timeout`이
없어 기본값이 `-1`(무한)이므로, 값이 유실되면 무한 대기 → 쿼리 타임아웃으로 드러난다.
isolation level은 드라이버 캐시가 아니라 `GET_DB_PARAMETER`로 CAS 값을 직접 읽어 확인했다.

### 패치 전/후 비교

| 항목 | 패치 전 | 패치 후 |
|---|---|---|
| `?lockTimeout=1500` 실제 대기 | 속성 자체가 없음 | 1518ms |
| `setLockTimeout(500)` 실제 대기 | 597ms | 597ms |
| `setLockTimeout(600)` + 재접속 | 7965ms (무한 대기, 8s 쿼리 타임아웃) | 626ms |
| `?lockTimeout=700` + 재접속 | 7998ms | 793ms |
| SERIALIZABLE + 재접속 (서버 실제 값) | 4 (READ COMMITTED로 되돌아감) | 6 (유지) |
| `-2` 저장값 | `-1` | `-2` |
| `setLockTimeout(-3)` | 서버 왕복 후 `CAS_ER_ARGS` | 클라이언트에서 즉시 거부 |
| 풀 borrow #2 lock timeout | 395ms (승계) | 5003ms (baseline 복원) |
| 풀 borrow #2 isolation | SERIALIZABLE 승계 | 4 (기본값 복원) |

### 최종 테스트 결과

| 테스트 | 대상 | 결과 |
|---|---|---|
| `LockTimeoutTest` | 기본 동작 + (b) + (c) | 9 / 9 PASS |
| `ReconnectTest` | (a) 재접속 재적용 | 3 / 3 PASS |
| `PoolResetTest` | (d) borrow 리셋 | 6 / 6 PASS |

빌드는 `build.xml`과 동일한 `-source 1.8 -target 1.8 -encoding UTF-8`(JDK 8 javac)로
경고 없이 통과한다.

---

## 5. 남아있는 문제 / 후속 과제

### 5.1 외부 커넥션 풀 (HikariCP, DBCP2 등)

이들은 `javax.sql.PooledConnection` 경로를 타지 않고 `CUBRIDConnection` 객체를 그대로
빌려주므로 `resetDbParameters()`가 호출되지 않는다. 따라서 borrower A가 런타임에 설정한
lock timeout이 borrower B에게 그대로 넘어간다. (드라이버 뷰와 서버 상태는 일치하므로
2.1의 불일치 문제는 없다.)

현재 가능한 회피책:

- URL `?lockTimeout=N`으로 고정 — borrow마다 값이 같으므로 실질적으로 문제되지 않는다.
- 애플리케이션이 borrow 직후 `setLockTimeout()`을 직접 호출.

개선안: `CUBRIDConnection`에 `resetDbParameters()`를 공개 메서드로 노출해 풀 통합 코드나
애플리케이션이 직접 호출할 수 있게 한다(한 줄).

### 5.2 JDBC 4.3 `beginRequest()` / `endRequest()`

풀 borrow/return을 드라이버에 알리는 표준 훅이지만 현재 구현하지 않았다. 확인한 사실:

- JDK 8의 `java.sql.Connection`에는 두 메서드가 없다(Java 9 = JDBC 4.3에서 추가).
  JDK 8로 컴파일한 **호출자**는 컴파일 자체가 안 된다.
- 그러나 `@Override` 없이 평범한 public 메서드로 선언하면 JDK 8 javac가 통과시키고,
  JVM 메서드 결정은 이름+디스크립터로 하므로 **같은 jar이 JDK 9+ 런타임에 올라가면
  자동으로 default 메서드를 오버라이드한다**. `target 1.8` 빌드 그대로 검증 완료.
- 다만 **HikariCP 5.1.0과 commons-dbcp2 2.12.0은 `beginRequest`를 아예 참조하지 않는다**
  (jar 바이트코드 확인). 즉 JDK를 올려도 주류 풀에서는 실효가 없다.

→ 비용은 0이지만 당장 얻는 것도 없어 **보류**. Oracle 드라이버(`PhysicalConnection`)는
   구현하고 있고 UCP/DRCP 계열에서 호출된다.

### 5.3 isolation level 기본값 학습 방식

`resetDbParameters()`는 첫 borrow 시점의 CAS 값을 기본값으로 학습한다.
현재 두 생성 경로 모두 갓 만든 커넥션을 넘기므로 안전하지만, 향후
`CUBRIDPooledConnection`에 "이미 사용된 커넥션을 감싸는" 생성 경로가 추가되면
잘못된 값을 기본값으로 학습할 수 있다. `SET_DB_PARAMETER`에 isolation용
"기본값 복귀" sentinel(lock timeout의 `-2`에 해당)이 없어서 생기는 제약이다.

근본 해결은 프로토콜/브로커 쪽에 sentinel을 추가하는 것이고,
드라이버 단에서는 `isolationLevel` URL 속성을 추가해 명시적 기준선을 갖게 하는 방법도 있다.

### 5.4 CCI 쪽 동일 문제

CCI의 `cci_datasource_borrow`는 `ds->default_lock_timeout != -2`일 때만 재적용하므로,
데이터소스에 기본값을 지정하지 않으면 이전 borrower의 런타임 설정이 그대로 남는다.
JDBC와 동일한 문제이며, 이번 작업에서는 손대지 않았다.

### 5.5 `SET SYSTEM PARAMETERS` 경로의 단위 문제 (기존 동작, 참고)

드라이버 API 대신 SQL로 설정하는 경우:

```sql
SET SYSTEM PARAMETERS 'lock_timeout=3000'   -- 단위 미지정 시 ms
```

이 경로는 `prm_msec_to_sec()`가 초 단위로 **올림**하므로 ms 정밀도가 없다
(`'lock_timeout=1500'` → 실제 2000ms). ms 정밀도가 필요하면 `setLockTimeout()`을 써야 한다.
ODBC 계열 드라이버(pyodbc, PDO_ODBC, DBD::ODBC, go-odbc, ruby-odbc)는 lock timeout API가
없어 이 SQL 경로만 쓸 수 있다.

---

## 6. 참고 — 다른 DBMS 드라이버 비교

| DBMS | 서버 측 설정 | 드라이버 전용 API/속성 | 단위 |
|---|---|---|---|
| MySQL | `innodb_lock_wait_timeout`, `lock_wait_timeout` | 없음 → `sessionVariables=...` | 초 |
| Oracle | 세션 단위 행 잠금 타임아웃 없음 | 없음 → `FOR UPDATE WAIT n / NOWAIT` | 초 |
| PostgreSQL | `lock_timeout` GUC | 없음 → `options=-c lock_timeout=3000` | ms |
| SQL Server | `SET LOCK_TIMEOUT n` | mssql-jdbc `lockTimeout` (기본 -1) | ms |
| CUBRID | `SET SYSTEM PARAMETERS 'lock_timeout'` | `setLockTimeout()` + `lockTimeout` 속성 | ms |

세션 상태 리셋 패턴:

- **패턴 A — 재접속마다 baseline 재적용**:
  Connector/J `createNewIO()` → `initializePropsFromServer()` → `NativeSession.setSessionVariables()`.
  이번 (a) 수정과 같은 구조.
- **패턴 B — borrow 시 세션 상태 리셋**:
  Connector/J `MysqlPooledConnection.getConnection()` → `resetServerState()`.
  이번 (d) 수정과 같은 자리. CCI의 `cci_datasource_borrow`도 같은 패턴.
- **패턴 C — JDBC 4.3 request boundary**:
  Oracle `PhysicalConnection.beginRequest()/endRequest()`. 5.2 참고. 미적용.

(MySQL·Oracle 항목은 로컬 jar 바이트코드로 확인했고, PostgreSQL·SQL Server는
 드라이버가 없어 문서 지식 기준이다.)
