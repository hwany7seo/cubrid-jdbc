# Holdable Cursor Close 개선 계획 (C/V13 최종안 기준)

> 최종 채택: **C안 = PROTOCOL_V13 + 배치(multi-id) CAS_FC_CURSOR_CLOSE.** 서버(source/cubrid, fork/cubrid)·JDBC(cubrid-jdbc-test) 적용 완료, 서버 V13 테스트 중.
> 본 문서는 현재 C/V13 구현에서 발견된 문제점과 개선 방법을 정리한다.
> 작성일: 2026-07-09

---

## 구현 상태 (2026-07-09): A + B 적용 완료 (cubrid-jdbc-test)

- **A — statement별 플래그로 전환**: `UStatement.cursorClosePending` 도입. `closeCursor`→플래그 set(+연결 `deferredCursorCloseCount`), **모든 execute(`executeInternal`/`executeBatchInternal`)·`close`→clear**. `flushDeferredCursorClose`는 커넥션의 `pooled_ustmts`를 순회하여 **`cursorClosePending && !isClosed`인 statement만** 모아 V13 batch(`CAS_FC_CURSOR_CLOSE` multi-id)로 전송. 핸들 id 목록(`deferred_cursor_close_handle`) 완전 제거.
  - 효과: 재실행된(살아있는) 커서는 플래그=false → **flush 대상에서 제외**(문제 ②b 해결). 플래그가 statement에 종속 → 핸들 소멸 시 소멸(문제 ④ 해결).
- **B — commit 시 holdable 커서 미close**: `UClientSideConnection.endTransaction`의 commit-flush 제거. rollback만 `clearDeferredCursorClose()`. commit은 커서를 건드리지 않음(문제 ②a 해결).
- 서버 무변경(V13 multi-id `fn_cursor_close` 그대로). `javac` 전체 컴파일 성공(126개, exit 0).
- **남은 항목**: C(누적 방어 — PREPARE flush + 백스톱 문서화), D(cubrid-jdbc 정식 이식), 회귀 테스트(§4) 추가.

---

## 0. 현재 구조 요약 (cubrid-jdbc-test)

| 요소 | 위치 | 동작 |
|---|---|---|
| defer | `UStatement.closeCursor` | V13이면 `addDeferredCursorClose(serverHandler)` (전송 안 함) |
| 목록 | `UConnection.deferred_cursor_close_handle` | 커넥션 단위 **핸들 id 목록** |
| 임계치 flush | `addDeferredCursorClose` | size ≥ `DEFERRED_CURSOR_CLOSE_MAX(32)` → batch flush |
| batch flush | `flushDeferredCursorClose` | 한 CURSOR_CLOSE 메시지에 N개 id (V13 서버가 순회) |
| remove(재실행) | `executeInternal`(880), `executeBatchInternal`(1101) | 해당 핸들 id 제거 |
| remove(핸들close) | `close`(592) | 해당 핸들 id 제거 |
| commit flush / rollback clear | `UClientSideConnection.endTransaction` | ACTIVE일 때 commit→flush, rollback→clear |
| 소켓종료 clear | `clientSocketClose` | 목록 clear |

---

## 1. 문제점

### 문제 ① — 오용 시 커서/핸들 무한 누적
**재현(사용자 제공):** autocommit=true, 루프 안에서 매번 `prepare`(새 핸들, holdable) + execute, **`rs.close()`·`ps.close()` 없음.**

- `rs.close()`가 없으니 `closeCursor`가 호출 안 됨 → **deferred 목록은 빈 상태.**
- 매 iteration 새 핸들에 holdable 결과가 열리고, 이전 핸들은 재실행도 close도 안 되어 **서버에 계속 쌓임**(`num_holdable_results` 증가, list file/temp 누적, 핸들 슬롯 점유).
- **근본 원인:** 애플리케이션이 커서/스테이트먼트를 닫지 않음. deferred 최적화와 무관한 누수(최적화가 유발/악화하지 않음).
- 현재 유일한 방어: 서버 백스톱 `current_handle_count >= max_prepared_stmt_count` → 연결 강제 종료([cas.c:657](../../cubrid/src/broker/cas.c#L657)). 클라이언트 측 방어 없음.

### 문제 ② — HOLD_CURSORS_OVER_COMMIT인데 commit이 커서를 닫음
`endTransaction`이 commit 시 `flushDeferredCursorClose()`를 호출한다. 두 가지 원인:

- **(2a) 원칙 위반:** holdable의 계약은 "커서가 commit을 넘어 생존"이다. commit에서 커서를 닫는 동작 자체가 이 계약과 충돌한다.
- **(2b) 설계 취약점(핵심):** deferred 목록이 **serverHandler(핸들 id)로 키잉**된다. "이 핸들의 *현재* 결과를 닫는다"는 매핑은, 재실행 시 `removeDeferredCursorClose`가 호출되어야만 유지된다. 그러나 remove 커버리지는 **EXECUTE / EXECUTE_BATCH / 핸들 close 3곳뿐**이다. 핸들의 결과를 다시 여는 **다른 경로(멀티결과 `nextResult`, out-result-set, 향후 prepare-and-execute 등)**를 타면 stale 엔트리가 목록에 남고, 이후 flush(commit 또는 MAX)에서 **살아있는(닫히면 안 되는) holdable 결과를 닫아버린다.** → 사용자가 관찰한 "commit이 커서를 닫는" 현상의 유력한 메커니즘.

### 문제 ③ — close는 했으나 commit/재실행이 없는 경우 (누적 변형)
non-autocommit, 루프에서 매번 새 `prepare` + `rs.close()`, commit 없음:
- 매 close가 새 핸들 id를 deferred에 추가. 재실행 없음(새 핸들) → remove 안 됨. commit 없음 → flush 안 됨.
- MAX(32)까지 증가 후 batch flush. **MAX가 유일한 상한.** MAX 제거 시 무한 증가.

### 문제 ④ — 핸들 id 재사용 정합성
핸들 해제 후 서버가 그 id를 새 prepare에 재사용할 수 있다. deferred에 stale id가 남아 있으면 flush 시 **다른 쿼리의 새 커서를 닫을** 위험. 현재는 `close`/execute의 remove로 방어하나, 문제 ②b와 같은 커버리지 취약점을 공유한다.

### 문제 ⑤ — WIP 정리 (cubrid-jdbc 정식 저장소)
`cubrid-jdbc/.../UStatement.java`의 `closeCursor`에 `outBuffer.addInt(q);`(미정의 변수 `q`) 등 미완성 편집 존재. 정식 이식 시 정리 필요.

---

## 2. 개선 방법

### 개선 A — deferred 마커를 "핸들 id 목록" → "statement별 플래그"로 전환 (문제 ②b, ④ 근본 해결)
- `UStatement`에 `boolean cursorClosePending` 플래그 도입.
  - `closeCursor` → `cursorClosePending = true` (전송 안 함).
  - **모든 execute 경로 진입 시** `cursorClosePending = false` (새 결과가 이전 것을 대체; 같은 객체이므로 자연스럽게 리셋).
  - flush → `pooled_ustmts` 순회하며 `cursorClosePending && !isClosed`인 것만 serverHandler를 모아 **V13 batch CURSOR_CLOSE 1회** 전송 후 플래그 clear.
- **효과:**
  - 재실행된(살아있는) 커서는 플래그가 false → **절대 flush되지 않음** → 문제 ②b 소멸.
  - 플래그가 statement에 종속 → 핸들 소멸 시 함께 사라짐 → **핸들 id 재사용 안전**(문제 ④) 자동 해결.
  - 커버리지 취약점 제거: "결과를 다시 여는 모든 경로"를 열거할 필요 없이, execute 진입점에서 한 번만 clear.
  - V13 batch와 완전 호환(순회하여 id를 모아 한 메시지로 전송).

### 개선 B — commit 시 holdable 커서를 닫지 않음 (문제 ②a)
- `endTransaction`의 **commit-flush 제거**(또는 holdable 연결에서 skip).
- deferred 커서는 다음 경로로만 해소: **재실행(서버 `hm_qresult_end`) · statement close · MAX flush · 연결 종료.**
- rollback은 현행 유지(서버가 전부 해제하므로 목록 clear).
- 근거: 개선 A로 "app이 닫은 커서만" 대상이 되지만, holdable 원칙상 commit 시점에 커서 close 왕복을 발생시키지 않는 것이 옳다. 자원 회수는 MAX·재실행·teardown이 담당.

### 개선 C — 오용 누적 방어 (문제 ①, ③)
- **PREPARE 시에도 flush**: 기존 `deferred_close_handle`가 PREPARE에 piggyback되는 것과 동일하게([UConnection.java:1324](src/jdbc/cubrid/jdbc/jci/UConnection.java#L1324)), deferred 커서 flush도 PREPARE 직전에 수행 → 목록 성장 억제(문제 ③).
- **MAX 유지**(안전밸브).
- **서버 백스톱 확인·문서화**: `max_prepared_stmt_count` 도달 시 연결 강제 종료로 누수 상한.
- **"전혀 안 닫는" 오용(문제 ①)**: 근본적으로 앱 버그(rs/ps 미close). 클라이언트가 안전하게 자동 회수할 방법은 제한적(살아있는 holdable을 함부로 닫으면 계약 위반). 현실적 대응:
  - 서버 백스톱 + 문서화(권장 사용법: try-with-resources 또는 statement 재사용).
  - (옵션) 열린 holdable 결과 수가 임계 초과 시 `[JDBC-Driver]` 경고 로그.
  - (옵션·중장기) `pooled_ustmts`를 WeakReference화하여 GC된(버려진) statement의 서버 핸들을 지연 close — 별도 검토.

### 개선 D — 정식 저장소 이식 정리 (문제 ⑤)
- `cubrid-jdbc`에 C/V13을 이식할 때 `addInt(q)` 등 WIP 오류 정리, cubrid-jdbc-test의 검증된 구현을 기준으로 반영.

---

## 3. 적용 범위

| 대상 | 변경 |
|---|---|
| JDBC `UStatement` | `cursorClosePending` 플래그, closeCursor/execute*/close 에서 set·clear |
| JDBC `UConnection` | deferred 목록 → 순회 기반 flush(개선 A), PREPARE flush(개선 C), MAX 유지 |
| JDBC `UClientSideConnection` | endTransaction commit-flush 제거(개선 B), rollback clear 유지 |
| 서버 | **변경 없음** — 기존 V13 multi-id `fn_cursor_close` 그대로(순회 대상이 statement 플래그로 바뀔 뿐 와이어는 동일) |

---

## 4. 회귀 테스트 (HoldableCloseTest 확장)

1. **holdable commit 생존**: non-autocommit, execute→(close 안 함)→commit→계속 fetch로 전체 행 도달. (문제 ②)
2. **재실행 supersede**: 같은 ps 재실행 시 CURSOR_CLOSE 왕복 0, 결과 정합. (개선 A)
3. **핸들 id 재사용**: ps close→새 ps(같은 id 가능)→새 결과 완전성. (문제 ④)
4. **오용 누적 상한**: prepare-loop + no close → 서버 백스톱까지 정상, 크래시/무한증가 없음. (문제 ①)
5. **close-but-no-commit**: prepare-loop + rs.close + no commit → MAX/PREPARE flush로 상한. (문제 ③)
6. **멀티결과/SP**: nextResult로 결과 전환 시 이전 결과가 flush 대상에서 정확히 빠지는지. (문제 ②b 회귀)

---

## 5. 우선순위

1. **개선 A + B** (정합성·holdable 계약) — 최우선. 문제 ②·④ 해결.
2. **개선 C** (누적 방어) — 문제 ①·③ 완화.
3. **개선 D** (정식 이식) — C/V13 안정화 후.
