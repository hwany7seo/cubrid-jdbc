# Holdable Cursor Close 최적화 — 설계서

> 근거: [holdable_work.md](holdable_work.md). 목적은 holdable(HOLD_CURSORS_OVER_COMMIT)에서
> SELECT 당 broker 왕복을 **2 → 1** 로 줄이되(즉 per-close `CURSOR_CLOSE` 별도 왕복 제거),
> holdable 의미(커밋 이후 fetch 가능)를 보존하는 것.
> 상태: 작성일 2026-07-09.
>
> **⚠️ 최종 범위(2026-07-28)** — 스케줄러 범위가 **좁혀짐**:
> - **제거**: 방치(=유저가 안 닫은/open) holdable 커서를 닫던 스케줄러 = 옛 **Case 2 / P3 / P4 / §3 registry / §5 스케줄러 트리거 / §6 전체**. HikariCP·DBCP가 반납 시 holdable 커서를 자체 정리함을 실측 확인했기 때문.
> - **유지·재도입(item 3(a))**: 유저가 **rs.close()로 명시적으로 닫아** deferred 배치에 쌓였으나 **max 5 미만이라 flush 못 한** 커서를, 장수 연결이 서버에 붙들지 않도록 **TTL(운영 24h/테스트 3min, 스캔 60s/30s) 경과 시 flush**. 방치/live 커서는 절대 건드리지 않음 → [§9-2](#9-2-item-3a--deferred-배치-ttl-flush).
>
> 최종 구현 = **P1(지연 배치 close + 재실행 supersede + H1) + item 3(a) deferred-배치 TTL flush + 서버 V13**.
> 아래 §3·§5·§6·§10에 남은 "방치 커서 스케줄러/registry/lastAccess/open-time TTL" 서술은 **삭제된 설계의 이력**이다(§9-2의 deferred-배치 flush와 혼동 금지). 근거·범위: [§9-1](#9-1-구현-순서-확정-9의-23은-최후에-하나씩)·[§9-2](#9-2-item-3a--deferred-배치-ttl-flush)·[§9-3-1](#9-3-1-외부-풀-안전망-스케줄러-제거-후) 참조.

---

## 1. 반드시 지킬 불변식 (holdable_work.md 규칙)

| # | 규칙 | 의미 |
|---|---|---|
| H1 | commit 이후 fetch/scroll에서 커서 **유지** | commit은 커서를 절대 닫지 않는다 |
| H2 | 명시적 `CURSOR_CLOSE`는 **모아서**(batch) 처리 | rs.close()는 즉시 전송하지 않고 지연·묶음 |
| H3 | **연결 종료** 시 반드시 닫는다 | 물리/논리 연결 종료 시 열린 holdable 커서 flush |
| N1 | non-holdable은 commit·트랜잭션 종료 시 **자동 close** | 기존 동작 그대로(서버가 txn 종료 시 해제) |

추가 안전: **비정상 종료** 시 서버가 강제 rollback으로 전량 해제하므로 누수 없음(클라는 tracking만 정리).

---

## 2. 세 가지 사용 패턴과 전략

### Case 1 — Prepare 1회 + executeQuery 반복(재사용)
- 같은 핸들 재실행 시 서버 `ux_execute`가 진입 즉시 `hm_qresult_end`로 **이전 결과를 해제**(검증됨).
- ⇒ 드라이버는 이전 커서에 대한 `CURSOR_CLOSE`를 **보낼 필요 없음**. 재실행이 "supersede".
- 전략: rs.close() 지연분이 있으면 재실행 시 **폐기(supersede)**, 없으면 그냥 재실행.

### Case 2 — Prepare + execute 반복, 유저가 안 닫음(방치)
- 매 prepare = 새 핸들 = 새 커서. 재사용 여부를 알 수 없음.
- ~~전략: 스케줄러(데몬)가 TTL 경과 시 닫는다.~~ → **스케줄러 제거(2026-07-28).** HikariCP/DBCP 등 외부 풀에서
  명시적 close 없이도 반납 시 정상 정리됨을 실측 확인. 방치 커서는 **(a) 재실행 supersede, (b) 연결 종료 시
  CLOSE_USTATEMENT, (c) 외부 풀의 statement 정리, (d) 비정상 종료 시 서버 free_all** 로 회수 → 드라이버 스케줄러 불필요.
  (자세한 근거·삭제 범위: [§9-1](#9-1-구현-순서-확정-9의-23은-최후에-하나씩))

### Case 3 — 유저가 정상적으로 rs.close()
- 전략: 즉시 전송하지 않고 **연결별 목록에 모아** batch 처리(**max 5**개 도달 시 flush). (H2)
- **item 3(a)**: 5개 미만으로 남아 flush 못 한 배치는 데몬이 **TTL(24h/테스트 3min) 경과 시 flush** → 장수 연결이 유저가 닫은 커서를 서버에 오래 붙들지 않게. 오직 `cursorClosePending`(rs.close된) 커서만 대상([§9-2](#9-2-item-3a--deferred-배치-ttl-flush)).

유지되는 실제 `CURSOR_CLOSE` 발생 이벤트:
**재실행(supersede, 무전송) · 명시적 close 배치(5개) · 연결 종료 · (rollback은 무전송 폐기).**

---

## 3. 상태 모델 & 자료구조

### UStatement (per prepared statement)
```
long  cursorLastAccessMillis;   // executeQuery/fetch/scroll 시 갱신 (TTL 기준 = last-access)
enum  cursorState { NONE, OPEN, CLOSE_PENDING }  // NONE=결과 없음, OPEN=live, CLOSE_PENDING=유저 close 지연
```
- `OPEN`: executeQuery로 결과가 열림(holdable).
- `CLOSE_PENDING`: rs.close() 호출됨(지연). batch flush 대상.
- 재실행 → 상태 OPEN 유지, lastAccess 갱신, 이전 CLOSE_PENDING이면 폐기(supersede).

### UConnection (per physical connection)
```
// 이 연결에서 열린 holdable 커서를 가진 statement들
// (pooled_ustmts를 재사용해도 되고, 별도 경량 목록도 가능)
int   deferredCloseCount;       // CLOSE_PENDING 개수 (max 5 트리거용)
```
- flush는 이 연결의 statement들을 순회하여 대상(CLOSE_PENDING 또는 TTL 만료 OPEN)을 모아 **V13 batch** 전송.

### 전역 스케줄러 registry
```
// 데몬이 스캔. 열린 holdable 커서를 가진 연결 집합.
static Set<WeakReference<UConnection>> holdableConnRegistry;
```
- **WeakReference**: 앱이 연결을 버리면(누수/GC) 항목이 자동 소멸 → 연결을 되살리지 않음, 메모리 누수 방지.
- 연결이 holdable 커서를 처음 열 때 등록, 모두 닫히면(또는 연결 종료) 해제.

> 핵심: **per-statement 플래그/타임스탬프가 authoritative.** 전역 registry는 "어떤 연결을 볼지"만 알려주고, 실제 닫을지는 statement 상태로 판정 → **핸들 id 재사용/재실행에 안전**.

---

## 4. 와이어 프로토콜

- **주안(권장): PROTOCOL_V13 multi-id `CAS_FC_CURSOR_CLOSE`** — 한 메시지에 N개 핸들 → **배치당 왕복 1회**. 서버 `fn_cursor_close`가 `argc`개 순회(이미 구현·테스트됨). 클라는 브로커가 V13 이상일 때만 배치.
- **폴백: 구 브로커(V12 이하)** — 종전처럼 핸들별 즉시 `CURSOR_CLOSE`(배치 이득 없음, 정합성 유지).
- 신·구 클라 × 신·구 브로커 4조합 호환(클라가 `protoVersionIsAbove(V13)`로 게이트).

> 결정 필요: 서버 무변경으로 가려면 배치를 "개별 메시지 N개 연속 전송"으로 대체 가능(왕복 수는 배치=개수, 단 시점만 모음). 서버 V13이 준비돼 있으므로 **V13 배치 권장**.

---

## 5. 트리거 & 생명주기

| 이벤트 | 동작 |
|---|---|
| `executeQuery`(holdable) | 커서 상태 OPEN, lastAccess=now, 연결을 registry 등록. **이전 CLOSE_PENDING이면 폐기(supersede)** |
| `fetch`/`moveCursor`/scroll | lastAccess=now (활성 커서는 TTL 갱신 → 스케줄러가 안 닫음) |
| `rs.close()` (H2) | 상태 CLOSE_PENDING, deferredCloseCount++. **count ≥ 5 → flush batch** (전송 후 해당 항목 제거) |
| `commit` (H1) | **아무것도 하지 않음** — 커서 유지. (non-holdable만 서버가 자동 해제) |
| `rollback` | 서버가 전량 해제 → tracking **폐기(무전송)** |
| `stmt.close()` | 그 statement 항목 제거(핸들 close가 서버 결과도 해제) |
| `conn.close()` (H3) | 연결의 **모든 열린 holdable 커서 flush**(batch), registry 해제 |
| 스케줄러 (Case 2) | lastAccess 후 TTL 경과분 flush(batch), 풀 안전([§6](#6-스케줄러-데몬-설계-핵심)) |
| 비정상 종료 | 서버 강제 rollback으로 전량 해제 → tracking clear, 무전송 |

non-holdable(N1): 종전과 동일 — 드라이버가 지연/tracking하지 않음. 서버가 txn 종료 시 auto-close.

---

## 6. 스케줄러(데몬) 설계 — 핵심

기존 `JdbcCacheWorker`(UJCIManager, daemon, 주기 스캔) 패턴을 그대로 사용.

### 동작
1. 데몬이 주기적으로(예: 운영 60s, 테스트 20s) `holdableConnRegistry`를 스캔.
2. 각 연결에 대해 **`synchronized (uconn)`** 획득 후:
   - 연결이 끊겼으면 registry에서 제거(무전송).
   - lastAccess 후 TTL(운영 24h/테스트 3min) 경과한 OPEN/CLOSE_PENDING 커서를 모아 **V13 batch로 close**.
   - 닫은 항목 제거. 남은 게 없으면 연결을 registry에서 해제.

### 커넥션 풀 안전성 (holdable_work.md 요구)
- **동시성**: 데몬은 앱과 **같은 락**(`synchronized(uconn)`, send_recv 경로와 동일)으로 직렬화 → 소켓 교착/프로토콜 오염 없음. 앱이 사용 중이면 데몬은 대기(또는 아래 skip-if-busy).
- **skip-if-busy(권장 개선)**: 연결에 경량 `inUse`(AtomicBoolean, 앱 요청 진입/이탈 시 set/clear)를 두고, 데몬은 사용 중이면 이번 주기 **건너뛰고 다음 주기 재시도** → 앱 hot-path 지연 방지.
- **정합성(핸들 재사용)**: 닫기 전 statement 상태로 재검증 — statement가 닫혔거나(핸들 해제) 재실행되어(supersede) 다른 커서면 **닫지 않음**. per-statement 상태가 authoritative이므로 stale 핸들로 새 커서를 닫는 일 없음.
- **풀 재사용 시 영향 없음**: Task A가 방치한 커서 H1을 데몬이 닫아도, (a) 락으로 Task B 요청과 직렬화, (b) H1은 Task B의 커서와 다른 핸들이라 Task B 무영향. 오히려 자원 회수로 바람직.
- **논리 종료(풀 반납) 훅**: 풀 반납/`reset` 시 그 연결의 holdable 커서를 flush·해제하면(H3의 논리 종료 해석) 데몬 의존을 줄이고 반납 즉시 정리 가능. → **통합 지점 검증 필요**([§9](#9-검토--결정-필요-사항)).
- **네트워크 블로킹 완화**: 데몬의 close 전송에 타임아웃/실패 시 조용히 registry 정리(연결 이상으로 간주).

### TTL = "마지막 접근" 기준
- open이 아니라 **last-access**(executeQuery/fetch/scroll) 기준으로 만료 판정 → 활발히 쓰이는 장수 커서는 안 닫고, 진짜 방치된 것만 닫음.

---

## 7. 정합성 & 엣지 케이스

- **재실행 supersede**: 같은 핸들 재실행 시 서버가 이전 결과 해제 → 지연분 폐기(무전송). (Case 1)
- **핸들 id 재사용**: statement close 시 항목 제거 + per-statement 상태 판정 → 재사용된 핸들의 새 커서를 stale 항목이 닫지 않음.
- **rollback**: 서버 전량 해제 → tracking 폐기(무전송). commit과 대칭.
- **commit(H1)**: 무동작 → 커서 생존. (이전 설계의 commit-flush는 **금지** — H1 위반)
- **multi-result/SP(`nextResult`)**: 한 핸들의 결과 전환도 lastAccess 갱신 + 이전 결과는 서버가 관리. tracking은 핸들 단위 OPEN 유지.
- **non-holdable(N1)**: 경로 미변경.
- **연결 종료(H3)**: 물리 종료·논리 종료(풀 반납) 모두 flush.

---

## 8. 설정값

| 항목 | 운영 | 테스트 | 비고 |
|---|---|---|---|
| 커서 TTL(last-access) | 24h | **3min** | 시스템 프로퍼티/커넥션 프로퍼티로 조정 |
| 데몬 스캔 주기 | 60s | 20s | TTL보다 충분히 짧게 |
| 명시적 close 배치 max | **5** | 5 | H2 |
| 배치 전송 | V13 multi-id | — | 구 브로커는 per-close 폴백 |

---

## 9. 검토 · 결정 필요 사항

1. **와이어**: V13 배치(서버 준비됨) vs 서버 무변경(개별 전송 버스트). → 배치 권장.
2. ✅ **(확정: direct-send)** **스케줄러 전송 방식**: 데몬이 connection 모니터(`synchronized(this)`) 하에서 직접 배치 close. 만료=유휴 커서라 활성 작업과 경합 거의 없음. P3 구현 완료.
3. ✅ **(확정: 스케줄러 제거, 풀/연결종료 위임)** **외부 풀 반납 훅**: HikariCP/DBCP 등 외부 풀은 반납 시 자체 statement 정리를 수행하며, 명시적 close 없이도 holdable 커서가 정상 회수됨을 실측 확인(2026-07-28). 드라이버 스케줄러(P3/P4)는 **제거**하고, 방치 커서 회수는 풀·연결종료·서버 free_all에 위임([§9-3-1](#9-3-1-외부-풀-안전망-스케줄러-제거-후)).
4. **TTL 기준**: open 시각 vs last-access. → last-access 권장(활성 커서 보호).
5. **테스트 값**: TTL 3min, 스캔 20s로 시작.

## 9-1. 구현 순서 (확정: §9의 2·3번은 최후에 하나씩)

§9의 **2번(스케줄러 전송 방식)·3번(외부 풀 반납 훅)은 지금 확정하지 않고, 가장 마지막 단계에서 하나씩 순차 구현·적용**한다. 그 앞의 확정된 부분을 먼저 단계적으로 진행한다.

| 단계 | 범위 | 핵심 |
|---|---|---|
| **P1** ✅ (2026-07-09 구현) | Case 3 + Case 1 + H1 | per-statement 지연 close, 배치 max 5(V13), 재실행 supersede, **commit 무동작(H1)**, rollback 폐기, V13 게이트/폴백 |
| ~~**P2**~~ ✅ **이미 충족 (별도 구현 불필요)** | H3 (연결 종료) | 물리 close(`CUBRIDConnection.close`)·CUBRID/SPI풀 논리반납(`closeConnection`) 모두 `clear()`→`closeAllStatements()`가 각 holdable statement를 **CLOSE_USTATEMENT로 닫아 서버 결과 해제**하고, 이어 `CON_CLOSE`/서버 teardown이 전량 해제. 별도 flush는 CLOSE_USTATEMENT와 **중복 왕복**이라 불필요. (외부 plain-wrap 풀 반납은 eviction+P3/P4 스케줄러 담당) |
| ~~**P3**~~ ❌ **제거 (2026-07-28)** | §9-2 Case 2 스케줄러 | 한때 구현(데몬이 WeakRef 레지스트리 스캔 → TTL 경과 holdable 배치 close). **HikariCP/DBCP가 반납 시 자체 정리함을 실측 확인하여 제거.** `UJCIManager`(registry/데몬 tick), `UConnection.registerHoldableCursors`/`closeExpiredHoldableCursors`/`holdableRegistered`, `UStatement.holdableCursorOpenMillis` 전부 삭제. |
| ~~**P4**~~ ❌ **제거 (2026-07-28)** | §9-3 외부 풀(plain-wrap) 안전망 | P3 스케줄러에 의존하던 하드닝(executeInternal open-time 갱신/else 초기화)도 함께 제거. 외부 풀 안전망은 **스케줄러 없이** 풀 자체 정리 + 연결종료 + 서버 free_all 3계층으로 충족([§9-3-1](#9-3-1-외부-풀-안전망-스케줄러-제거-후)). |

- **P1 + 기존 연결종료 경로(=P2, 이미 충족) + item 3(a)([§9-2](#9-2-item-3a--deferred-배치-ttl-flush))** 가 최종 구현 범위다. 서버 V13(구현됨)만으로 holdable 왕복 2→1 + 불변식(H1/H2/H3)을 충족한다.
- ~~P3~P4는 방치(Case 2) 스케줄러~~ → **제거.** 방치 커서 회수는 드라이버 스케줄러 없이 풀·연결종료·서버가 담당([§9-3-1](#9-3-1-외부-풀-안전망-스케줄러-제거-후)). 단 **유저가 닫은** deferred 배치의 지연 flush는 [§9-2](#9-2-item-3a--deferred-배치-ttl-flush)로 유지.

## 9-2. item 3(a) — deferred 배치 TTL flush

**문제(제거된 Case 2와 다름)**: Case 3에서 유저가 `rs.close()`하면 즉시 전송하지 않고 연결별로 모아 **5개째에 배치 flush**한다(H2). 그런데 한 연결에서 명시적으로 닫힌 커서가 **5개 미만(예: 4개)** 으로만 쌓이면, 그 배치는 **연결이 끊기거나 풀에 반납될 때까지 flush되지 않는다.** 장수(long-lived) 연결에서는 유저가 이미 닫은 커서를 서버가 그동안 계속 붙들게 된다.

**해결**: 데몬(`JdbcCacheWorker`)이 주기적으로, deferred 배치의 **가장 오래된 대기 항목이 TTL을 넘긴** 연결에 대해 `flushDeferredCursorClose()`를 호출한다.

| 항목 | 값(운영/테스트) | 시스템 프로퍼티 |
|---|---|---|
| TTL(가장 오래된 pending 기준) | 24h / 3min | `-Dcubrid.deferred.cursor.close.ttl.millis`(기본 86400000) |
| 스캔 주기 | 60s / 30s | `-Dcubrid.deferred.cursor.close.scan.sec`(기본 60) |

**동작·안전성**:
- **대상은 오직 `cursorClosePending`(유저가 rs.close한) 커서.** open/live/방치(안 닫은) 커서는 절대 flush 대상이 아니다 → 제거된 Case 2와 근본적으로 다르고, 사용 중 커서를 닫을 위험이 없다.
- **TTL 시계**: `addDeferredCursorClose`에서 배치가 0→1이 될 때 `deferredCloseFirstMillis=now`. flush/clear/모두-remove 시 0. 즉 **가장 오래된 pending의 대기시간**을 TTL로 상한.
- **직렬화**: `flushDeferredCursorClose`를 `synchronized(UConnection)`로 감쌈. 데몬 flush·앱의 at-5 flush·execute/fetch가 **모두 같은 UConnection 모니터**로 직렬화 → 소켓 경합 없음. 대상 판정은 plain getter(`isClosed()`/`getServerHandle()`)만 읽어 락 역전 없음.
- **registry**: 연결이 처음 deferred close를 넣을 때 WeakRef로 1회 등록(`deferredCloseRegistered` 래치). GC/누수 안전은 옛 registry와 동일 논리.
- flush 후 배치가 5에 도달했을 때와 동일하게 서버 `CURSOR_CLOSE`만 나가고, statement 핸들은 유지(핸들 해제는 stmt.close/연결 종료 담당).

**변경 파일**: `UStatement`(불변 — closeCursor는 그대로 addDeferredCursorClose), `UConnection`(`deferredCloseFirstMillis`/`deferredCloseRegistered`, add/remove/clear/flush 갱신, `flushExpiredDeferredCursorClose`), `UJCIManager`(`deferred_close_conn_list`, `registerDeferredCloseConn`, `scanExpiredDeferredCloses`, 데몬 tick).

## 9-3-1. 외부 풀 안전망 — 스케줄러 제거 후

**배경**: 한때 외부 plain-wrap 풀(HikariCP/DBCP)이 반납 시 CUBRID 전용 훅을 부르지 않는다는 이유로 드라이버 스케줄러(P3)를 안전망으로 두었다. 그러나 실측(2026-07-28, `temp_test`)에서 **HikariCP·DBCP 모두 HOLD_CURSORS_OVER_COMMIT 커서를 명시적으로 닫지 않아도 반납 시 정상 정리**됨을 확인했다. 풀이 논리 반납 시 자신이 추적한 Statement를 `close()` 하기 때문이다(→ `CLOSE_USTATEMENT` → 서버 결과 해제). 따라서 **드라이버 스케줄러는 불필요**하여 제거했다.

**결론 — 스케줄러 없이 3계층으로 홀더블 커서 자원은 회수된다:**

| 계층 | 시점 | 메커니즘 | 커버리지 |
|---|---|---|---|
| ① 풀 자체 stmt 정리 | 논리 반납 시 | 풀(HikariCP/DBCP)이 추적한 Statement를 `close()` → `CUBRIDStatement.close`→`CLOSE_USTATEMENT`로 서버 결과 즉시 해제 | 앱이 연 statement 대부분 (실측 확인) |
| ② 연결 종료 | 물리 close / CUBRID·SPI풀 논리반납 / maxLifetime eviction | `CUBRIDConnection.close`/`closeConnection`→`clear`→`closeAllStatements`(추적 중 전 statement close) + `CON_CLOSE` → 서버 전량 해제 | 풀이 놓친 것 + 남은 전부 |
| ③ 비정상 종료 | 소켓 drop | 서버 강제 rollback + free_all | 크래시/네트워크 단절 |

추가로 **재실행 supersede**(같은 핸들 재실행 시 서버 `hm_qresult_end`가 이전 결과 해제)가 Case 1의 방치를 흡수한다.

**풀 재사용 안전성**(holdable_work.md: "유저가 다시 다른 작업을 사용할 때 영향받지 않아야"): 스케줄러가 없으므로 데몬이 재사용 중 연결의 커서를 건드릴 여지 자체가 사라졌다. 방치 커서는 위 ①~③로 회수되고, 재사용 시 새 작업은 새 핸들을 쓰므로 이전 작업과 간섭하지 않는다.

> 제거된 코드: `UJCIManager`의 registry·데몬 스캔(tick), `UConnection.registerHoldableCursors`/`closeExpiredHoldableCursors`/`holdableRegistered`, `UStatement.holdableCursorOpenMillis` 및 그에 의존한 `executeInternal` 하드닝. **유지된 코드**: 지연 배치 close(`addDeferredCursorClose`/`flushDeferredCursorClose`/max 5), 재실행 supersede(`removeDeferredCursorClose`), commit 무동작(H1)/rollback 폐기.

---

## 10. 예상 변경 파일

| 파일 | 변경 |
|---|---|
| `jci/UStatement` | cursorState/lastAccess, closeCursor→지연 mark, execute/fetch→lastAccess·supersede, close→항목 제거 |
| `jci/UConnection` | 연결별 열린-holdable tracking, batch flush, registry 등록/해제, PROTOCOL_V13 게이트 |
| `jci/UClientSideConnection` | endTransaction: **commit 무동작(H1)** / rollback 폐기; 연결 종료 flush(H3) |
| `jci/UJCIManager` | 스케줄러 데몬(기존 JdbcCacheWorker 패턴) + 전역 registry |
| `driver/CUBRIDConnection` (+ pooled) | 논리 종료 flush 훅(H3, 풀) |
| 서버 `cas_protocol.h`/`cas_function.c` | V13 multi-id(이미 구현) — 무변경 or 재적용 |

---

## 부록: 이전 문서와의 관계
- 이전 `cursor_close_improvement_plan.md`/`to_do.md`는 초기 검토용. 본 문서가 **최신 요구사항(스케줄러·풀 안전성 포함) 기준의 설계안**으로 이를 대체.
