# Holdable Cursor Close 최적화 — 설계서

> 근거: [holdable_work.md](holdable_work.md). 목적은 holdable(HOLD_CURSORS_OVER_COMMIT)에서
> SELECT 당 broker 왕복을 **2 → 1** 로 줄이되(즉 per-close `CURSOR_CLOSE` 별도 왕복 제거),
> holdable 의미(커밋 이후 fetch 가능)를 보존하는 것.
> 상태: **설계안(검토용).** 구현 전. 작성일 2026-07-09.

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
- 전략: **스케줄러(데몬)**가 커서의 **마지막 접근 후 TTL**(운영 24h, 테스트 3min) 경과 시 닫는다.
- 풀 안전성 필수([§6](#6-스케줄러-데몬-설계-핵심)).

### Case 3 — 유저가 정상적으로 rs.close()
- 전략: 즉시 전송하지 않고 **연결별 목록에 모아** batch 처리(**max 5**개 도달 시 flush). (H2)

세 경우 모두 "열린 holdable 커서"를 통일된 tracking으로 관리하고, 아래 이벤트에서만 실제 `CURSOR_CLOSE`가 나간다:
**재실행(supersede, 무전송) · 명시적 close 배치(5개) · 스케줄러 TTL · 연결 종료 · (rollback은 무전송 폐기).**

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
2. ⏸ **(확정 보류 — 최후 단계)** **스케줄러 전송 방식**: 데몬 직접 전송(idle 연결도 회수) vs mark-only(다음 앱 op에서 전송) vs 하이브리드. P3에서 확정.
3. ⏸ **(확정 보류 — 최후 단계)** **외부 풀 반납 훅**: HikariCP식 plain-wrap 외부 풀은 반납 시 드라이버 훅 없음 → 스케줄러 안전망. P4에서 확정.
4. **TTL 기준**: open 시각 vs last-access. → last-access 권장(활성 커서 보호).
5. **테스트 값**: TTL 3min, 스캔 20s로 시작.

## 9-1. 구현 순서 (확정: §9의 2·3번은 최후에 하나씩)

§9의 **2번(스케줄러 전송 방식)·3번(외부 풀 반납 훅)은 지금 확정하지 않고, 가장 마지막 단계에서 하나씩 순차 구현·적용**한다. 그 앞의 확정된 부분을 먼저 단계적으로 진행한다.

| 단계 | 범위 | 핵심 |
|---|---|---|
| **P1** ✅ (2026-07-09 구현) | Case 3 + Case 1 + H1 | per-statement 지연 close, 배치 max 5(V13), 재실행 supersede, **commit 무동작(H1)**, rollback 폐기, V13 게이트/폴백 |
| **P2** | H3 (연결 종료 flush) | `clear()`/`closeAllStatements` 경로에 지연분 flush — 물리 close + CUBRID풀·SPI 외부풀 논리반납 |
| **P3 (최후·개별)** | §9-2 Case 2 스케줄러 | direct-send vs mark-only(하이브리드) 확정 후 구현 |
| **P4 (최후·개별)** | §9-3 외부 풀(plain-wrap) 안전망 | HikariCP식 반납 무-훅 케이스 처리 확정 |

- **P1~P2만으로도** 정상 사용 패턴(재사용·정상 close·연결 종료)은 완전 동작하며, 서버 V13(구현됨)만으로 holdable 왕복 2→1 + 불변식(H1/H2/H3)을 충족한다.
- P3~P4는 "방치(Case 2)" 안전망(자원 회수 강화). **P1~P2 안정화·검증 후** 하나씩 진행.

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
