# holdable cursor close 오버헤드 개선 — 검토 결과 및 TODO

> 대상 문서: [holdable_work.md](holdable_work.md)
> 검토 범위: `cubrid-jdbc`(JDBC 드라이버), `cubrid-cci`(C 드라이버), `cubrid/src`(서버 CAS)
> 검토일: 2026-07-03

---

## 0. 요약 (Executive Summary)

**결론:** 문서의 문제 진단은 **정확**하다. holdable 기본값 때문에 SELECT 당 `CURSOR_CLOSE` 왕복이 1회 추가된다. 개선은 **충분히 실현 가능**하며, 놀랍게도 **JDBC·CCI 양쪽 모두 이미 동일한 "mark 후 다음 요청에 piggyback" 메커니즘이 statement 핸들 close(`CLOSE_REQ_HANDLE`)용으로 존재**한다. 이를 커서 close용으로 복제하는 것이 가장 위험이 낮은 경로다.

**단, 문서의 제안을 그대로 채택하기 전에 두 가지 치명적 사실을 반영해야 한다:**

1. ⚠️ **1안(commit/rollback에 piggyback)은 정작 목표 워크로드(autocommit point-select)를 개선하지 못한다.** autocommit 모드에서는 commit이 별도 메시지가 아니라 execute 요청에 접혀 전송되므로(문서 20행의 내용과 동일), piggyback할 commit 메시지 자체가 없다. rollback은 서버가 어차피 모든 핸들을 해제하므로 piggyback이 무의미하다.
2. ⚠️ **핸들 ID 재사용(handle-id reuse) 위험.** 클라이언트·서버 모두 핸들 테이블의 빈 슬롯을 재사용한다. "close 예약"만 해두고 flush하지 못한 채 해당 핸들이 해제·재할당되면, 나중에 stale marker를 flush할 때 **엉뚱한 새 커서를 닫는** 정합성 버그가 발생한다.

**권고:** 2안(mark + batch)을 기본 전략으로 채택하되, "다음 PREPARE/EXECUTE에 piggyback"하는 기존 선례를 복제한다. 자세한 근거는 [§5](#5-두-방안-평가), 권고안은 [§6](#6-권고안), 위험은 [§7](#7-위험--주의사항), 작업 목록은 [§8](#8-구체적-todo)에 정리했다.

---

## 1. 문서 주장 검증

| 문서 주장 | 검증 | 근거 (file:line) |
|---|---|---|
| holdable close 시 `CURSOR_CLOSE` 동기 왕복 1회 발생 | ✅ 정확 | JDBC `driver/CUBRIDResultSet.java:293-294`, `jci/UStatement.java:666-686` |
| non-holdable은 close 시 왕복 없음 (트랜잭션 종료 시 자동 해제) | ✅ 정확 | CCI 게이트 `cci_query_execute.c:1260`; 서버 `cas_common_execute.c:298-325` |
| CCI `qe_close_query_result`가 holdable일 때만 전송 | ✅ 정확 | `cci_query_execute.c:1260`(게이트), `:1288`(send), `:1295`(recv) |
| autocommit 시 commit은 execute에 piggyback (별도 왕복 아님) | ✅ 정확 | JDBC `jci/UStatement.java:773-776` + `jci/UClientSideConnection.java:315-320` |
| CCI·JDBC 기본값 holdable | ✅ 정확 | CCI `cci_handle_mng.c:1331`(`is_holdable=1`); JDBC holdability 기본 HOLD |
| 인용 코드 줄 번호 | ⚠️ 미세 오차 | `closeCursor`는 665→**666**, `ResultSet.close`는 274→**282** (내용은 동일) |

문서에 인용된 코드 스니펫과 흐름은 모두 실제 소스와 일치한다. 줄 번호만 소폭 밀려 있다.

---

## 2. 현재 구조 — JDBC 드라이버

**왕복 발생 지점**
- `driver/CUBRIDResultSet.java:282` `close()` → `:293` holdability 검사 → `:294` `u_stmt.closeCursor()`
- `jci/UStatement.java:666-686` `closeCursor()`: `CURSOR_CLOSE`(42) / V2 alias(41) + `serverHandler`(int 1개)만 전송 → `:678` `send_recv_msg()` (동기 왕복)
- `closeResult()`(`jci/UStatement.java:2151`)는 로컬 전용(튜플 버퍼 해제) — 왕복 없음. 혼동 주의.

**commit/rollback 흐름**
- `driver/CUBRIDConnection.java:193` `commit()` → `:204` `end(true)`; `:213` `rollback()` → `:222` `end(false)`
- `:798` `end()` → `u_con.endTransaction()`
- `jci/UClientSideConnection.java:304-347` `endTransaction()`: `:329` `END_TRANSACTION`(code 1) + **1바이트**(commit/rollback)만 전송. **piggyback 여지 없음(현재)**

**★ 기존 선례 (핵심)**
- `jci/UConnection.java:260` `Vector<Integer> deferred_close_handle` — statement 핸들 지연 close 목록
- `jci/UStatement.java:597-611` `close()`: 조건부로 즉시 close(`CLOSE_USTATEMENT`, `:604-607`) 또는 **지연 등록**(`:610` `deferred_close_handle.add(...)`)
- `jci/UConnection.java:1316-1319` PREPARE 요청 빌드 시 지연 목록을 **flush(piggyback)** ← **커서 close용으로 복제할 정확한 템플릿**

**autocommit**
- `jci/UStatement.java:773-776` execute 요청에 auto_commit 바이트 포함 → 서버가 실행+커밋
- `jci/UClientSideConnection.java:315-320` 이후 `endTransaction`은 CAS 상태가 INACTIVE라 **왕복 생략**
- ⇒ autocommit에선 piggyback 대상 commit 메시지가 없음

**비정상 종료**
- `jci/UConnection.java:1811-1824` `clientSocketClose()`: `:1823` `deferred_close_handle.clear()`로 미flush 목록을 **조용히 폐기** (서버가 자동 회수하므로 안전)

**프로토콜 협상**
- `jci/UClientSideConnection.java:419-436` broker_info로 버전 판별
- capability 비트: `BROKER_INFO_FUNCTION_FLAG`(index 5). 기존 `CAS_SUPPORT_HOLDABLE_RESULT=0x40` 선례 존재 (`UConnection.java:1693-1698`)
- ⚠️ `protocolVersion`이 **static**(`UConnection.java:232`) — 클래스로더 내 연결 간 공유. 버전 게이팅 로직 추가 시 주의

---

## 3. 현재 구조 — CCI (C 드라이버)

**왕복 발생 지점**
- `cci_query_execute.c:1253-1298` `qe_close_query_result()`: `:1260` holdable 게이트 → `:1265` CUBRID 전용 게이트 → `:1279` `server_handle_id`(int 1개) → `:1288` `net_send_msg` → `:1295` blocking `net_recv_msg`(응답 폐기)
- 호출자 2곳: `cas_cci.c:2206` `cci_close_query_result()`, `cas_cci.c:2249` `cci_close_req_handle()`(pooling 경로)
- **커서 close는 req_handle을 해제하지 않음** — statement는 살아있고 재실행 가능 (`req_close_query_result`는 fetch 버퍼만 해제)

**commit/rollback 흐름**
- `cas_cci.c:804` `cci_end_tran` → `:773` `cci_end_tran_internal` → `cci_query_execute.c:261` `qe_end_tran`
- `cci_query_execute.c:286` `END_TRAN`에 **1바이트**(tran type)만 전송. **핸들 목록 선례 없음**

**★ 기존 선례 (핵심)**
- `cci_handle_mng.h:271-272` `int *deferred_close_handle_list; int deferred_close_handle_count;`
- `cci_query_execute.c:1306-1365` `qe_close_req_handle_internal()`: DML은 `:1356`에서 목록에 지연 등록
- flush(piggyback): `cci_query_execute.c:437-440`(PREPARE), `:833,840-842`(EXECUTE) ← **커서 close용으로 복제할 템플릿**

**핸들 구조**
- `cci_handle_mng.h:154` `server_handle_id`, `:177` `is_closed`(로컬 "결과 닫힘" 마커, but "서버 왕복 미납" 추적 안 함), `:175` `valid`
- `cci_handle_mng.h:224-225` `req_handle_table` / `req_handle_count`
- 핸들 ID 재사용: `new_req_handle_id`(`cci_handle_mng.c:1359-1391`)가 NULL 슬롯 재사용 → ⚠️ [§7](#7-위험--주의사항)

**비정상 종료**
- `net_recv_msg_timeout` 실패 시(`cci_network.c:841-846`) 소켓만 정리, req_handle·지연목록 **미정리**
- `reset_connect`(`cas_cci.c:836-865`) / `hm_force_close_connection`(`cci_handle_mng.c:1501-1510`)도 `deferred_close_handle_count`를 **초기화하지 않음** → 재연결 후 stale ID를 새 CAS에 flush (서버가 관용 처리하나 지저분). **새 목록 도입 시 여기서 clear해야 깔끔**

**프로토콜 협상**
- `cci_handle_mng.c:1093` `hm_get_broker_version`; `:1127` `hm_broker_understand_the_protocol`(`>=`, 기능 게이팅용) vs `:1140` `hm_broker_match_the_protocol`(`==`, V2 특수처리용)
- `CURRENT_PROTOCOL = PROTOCOL_V12`(`broker_cas_protocol.h:217`)

---

## 4. 현재 구조 — 서버 CAS

**`CURSOR_CLOSE` 처리**
- 디스패치: `cas.c:117` `server_fn_table` → `cas_function.c:1687-1706` `fn_cursor_close`: `argv[0]` 1개만 읽음, 응답 body 없음
- `cas_execute.c:2735-2758` `ux_cursor_close`: **결과만** 해제, `T_SRV_HANDLE`은 유지, `as_info->num_holdable_results--`
- ★ **현재 `argv[0]` 이후는 전부 무시** → multi-ID 확장 여지 있음

**함수 코드** (`cas_protocol.h:174-228`): `END_TRAN=1`, `CLOSE_REQ_HANDLE=6`, `PREPARE_AND_EXECUTE=41`, `CURSOR_CLOSE=42`, `CAS_FC_MAX=45`(sentinel). V2 alias는 41↔42 뒤바뀜.

**END_TRAN 처리 — piggyback hook**
- `cas_function.c:75-204` `fn_end_tran`: `argv[0]`(tran type) 1개만 읽음 → `:116` `ux_end_tran`
- `cas_execute.c:877` `ux_end_tran` → `:881` `ux_end_tran_cleanup` (커밋/롤백 **전에** 핸들 정리)
- ★ `cas_common_execute.c:298-325` `ux_end_tran_cleanup`: **commit=holdable 유지·non-holdable 해제, rollback=전부 해제**. 1안의 정확한 삽입 지점
  - `hm_srv_handle_qresult_end_all(false)`(`cas_handle.c:255`)는 commit 시 holdable 유지하며 `is_from_current_transaction=false`로 표시

**최종 해제 시점**: ① 명시적 `CURSOR_CLOSE`, ② `CLOSE_REQ_HANDLE`, ③ rollback(전부), ④ statement pooling 전환(`cas.c:670`), ⑤ 세션/연결 종료

**와이어 포맷**: `net_decode_str`(`cas_network.c:391-441`)는 `[func:1][len:4][payload]...` self-describing 가변 길이 → **후행 인자 추가는 하위호환** (구 클라는 argc 작게 보냄)

**비정상 종료 회수**: 소켓 drop 감지 → `FN_CLOSE_CONN` → `cas_cleanup_session`(`cas.c:440-467`)이 강제 `ux_end_tran(ROLLBACK)` → 모든 holdable 핸들 해제 + DB 트랜잭션 abort. **⇒ 클라가 close 못하고 죽어도 서버 누수 없음**

**⚠️ 부작용**: `cas.c:1309-1312` — `num_holdable_results >= 1`이면 CAS reset/rebalancing 거부. **close를 지연하면 이 카운터가 오래 유지되어 CAS 재조정이 지연**되고, 핸들 용량(`max_handle_id`)도 오래 점유

---

## 5. 두 방안 평가

### 공통 전제
- 세 계층 모두 이미 holdable/deferred 인프라를 갖춤. **두 방안 다 서버 변경이 필요**(순수 클라이언트만으로는 batch/piggyback 불가 — 서버가 multi-ID close 또는 추가 목록을 해석해야 함).
- 유일한 예외: **요청 파이프라이닝**(아래 3안) — 서버 무변경.

### 1안 — commit/rollback에 piggyback
| 항목 | 평가 |
|---|---|
| 효과 | ⚠️ **제한적**. autocommit point-select(목표 워크로드)에선 commit 메시지가 없어 **개선 안 됨**. rollback은 서버가 어차피 전부 해제 → 무의미. **명시적 트랜잭션 커밋에만** 유효 |
| 클라 변경 | END_TRAN 버퍼에 핸들 목록 추가 (JDBC `UClientSideConnection.java:329`, CCI `cci_query_execute.c:286`) — **선례 없음** |
| 서버 변경 | `fn_end_tran`(`cas_function.c:75`) 파싱 확장 + `ux_end_tran` 전 close 루프. 난이도 중 |
| 결론 | 단독 채택 부적합 |

### 2안 — mark + batch (다음 요청에 piggyback / 임계치 flush)
| 항목 | 평가 |
|---|---|
| 효과 | ✅ **autocommit·트랜잭션 양쪽 개선**. 목표 워크로드 커버 |
| 클라 변경 | 기존 `deferred_close_handle(_list)` 선례 **복제** — 최저 위험. 단 **별도 목록** 필요(커서 close는 statement 유지, `CLOSE_REQ_HANDLE`은 statement 제거 — 의미 다름) |
| 서버 변경 | (a) `fn_cursor_close`를 multi-ID 수용으로 확장(최소 표면) **또는** (b) execute/prepare에 두 번째 목록 파싱 추가(최대 효과, 표면 넓음) **또는** (c) 신규 func code `CAS_FC_CLOSE_CURSORS_BATCH` |
| 결론 | **권장.** 선례 재사용으로 위험 최소 |

### 3안 (문서 외) — 요청 파이프라이닝 (저위험 1단계)
- 지연된 N개의 `CURSOR_CLOSE`를 **연속 전송 후 응답을 일괄 수신**. RTT 지배 환경에서 N×RTT → 1×RTT.
- **서버·프로토콜 무변경.** 메시지 수는 그대로지만 지연시간만 단축.
- 1차 릴리스로 즉시 이득을 얻고, 2안은 후속으로 진행하는 단계적 접근 가능.

---

## 6. 권고안

1. **2안을 기본 채택** — 기존 `deferred_close_handle(_list)` 메커니즘을 **커서 close 전용 별도 목록**으로 복제하고, **다음 PREPARE/EXECUTE에 flush(piggyback)**.
2. **서버는 "최소 표면" 옵션 우선 검토**: `fn_cursor_close`가 `argc>1`인 multi-ID를 수용하도록 확장(현재 `argv[0]` 이후 무시하므로 확장 자연스러움) + `PROTOCOL_V13` 게이팅. execute/prepare 인라인 piggyback은 효과가 크지만 표면이 넓어 2차로.
3. **1안은 보조**로만 — 명시적(non-autocommit) 커밋 경로에서 추가 이득이 필요할 때 END_TRAN 확장. autocommit 목표엔 무효임을 명심.
4. **1단계로 3안(파이프라이닝)** 을 먼저 내보내 프로토콜 변경 없이 조기 이득 확보 후 2안으로 확장하는 단계적 롤아웃 권장.
5. **flush 시점 정책**: (a) 다음 PREPARE/EXECUTE, (b) 명시적 commit/rollback, (c) 배치 크기 임계치(예: 문서의 "5") — 셋 중 가장 먼저 도달하는 시점. 임계치는 [§7](#7-위험--주의사항)의 핸들 용량/CAS reset 지연을 막는 상한 역할.

---

## 7. 위험 · 주의사항

- ⚠️ **[정합성-치명] 핸들 ID 재사용**: rollback(또는 statement close)로 서버가 핸들을 해제하면 그 ID가 재할당된다. stale한 "close 예약" marker를 나중에 flush하면 **엉뚱한 새 커서를 닫는다**.
  - **대책 A(권장):** marker를 별도 ID 목록이 아니라 **req_handle의 플래그(`needs_server_close`)로 보관** → 핸들이 해제되면 marker도 자연 소멸. flush 시 살아있는 핸들만 순회.
  - **대책 B:** 별도 목록 사용 시, 핸들 해제·rollback·재연결(`reset_connect`, `hm_force_close_connection`, `clientSocketClose`) 지점에서 해당 ID를 **반드시 목록에서 제거**.
- ⚠️ **rollback은 flush가 아니라 discard**: rollback 시 서버가 전부 해제하므로, 예약된 커서 close는 전송하지 말고 목록을 비운다.
- ⚠️ **CAS reset/rebalancing 지연**: `num_holdable_results` 카운터(`cas.c:1311` 가드)가 오래 유지됨 → 배치 상한과 commit 시 flush로 완화.
- ⚠️ **핸들 용량 점유**: 지연된 커서만큼 서버 `srv_handle`/클라 `req_handle` 슬롯이 오래 점유 → `max_handle_id`/`max_req_handle` 압박.
- ⚠️ **프로토콜 하위호환**: `PROTOCOL_V13` 신설 후 `hm_broker_understand_the_protocol`(CCI) / `protoVersionIsAbove`(JDBC)로 게이팅. 구 브로커엔 기존 per-close로 폴백.
- ⚠️ **다중 디스패치 테이블**(2안-c 신규 func code 채택 시): `cas_protocol.h:221-222` 경고대로 CUBRID(`cas.c`) + CGW(`cas_cgw.c`) + shard-proxy(`shard_proxy_handler.c`) 테이블 **모두**에 동일 순서로 등록 필요. `fn_cursor_close` 확장(2안-a)은 이 문제 회피.
- ⚠️ **JDBC `protocolVersion` static**: 연결 간 공유되므로 버전 게이팅 시 주의(`UConnection.java:232`).
- ✅ **크래시 안전**: 미flush marker는 서버 강제 rollback으로 자동 회수 → 누수 없음. 단, 위 정합성(ID 재사용)과 목록 정리는 별개 문제.
- **비정상 종료 시 CAS 수정 필요 여부**(문서 60-61행 우려): 서버는 소켓 drop 시 이미 강제 rollback+전량 해제를 수행하므로 **누수 방지 목적의 CAS 수정은 불필요**. CAS 수정이 필요한 것은 오직 "새 프로토콜(multi-ID close / END_TRAN 확장)을 해석하는 정상 경로"뿐.

---

## 8. 구체적 TODO

### 공통 / 설계
- [ ] 최종 방식 확정: **2안(별도 목록 piggyback)** 기준, 서버는 `fn_cursor_close` multi-ID 확장(2안-a) vs execute piggyback(2안-b) 중 택1
- [ ] `PROTOCOL_V13` 신설 및 capability 협상 방식 결정(버전 bump vs `BROKER_INFO_FUNCTION_FLAG` 비트)
- [ ] flush 정책(다음요청 / commit / 임계치 N) 및 배치 상한 값 확정
- [ ] 3안(파이프라이닝) 1차 릴리스 여부 결정

### 서버 CAS (`cubrid/src`)
- [ ] `PROTOCOL_V13` 추가: `cas_protocol.h:231-247` `t_cas_protocol`, `CURRENT_PROTOCOL` 갱신
- [ ] **[2안-a]** `fn_cursor_close`(`cas_function.c:1687`) `argc>1` multi-ID 수용 + 루프 `ux_cursor_close`
- [ ] **[2안-b/1안]** `fn_end_tran`(`cas_function.c:75`) 또는 execute 핸들러에 커서-close 목록 파싱 추가(`ux_end_tran_cleanup` 전/후 처리, `cas_common_execute.c:298`)
- [ ] **[2안-c 채택 시만]** 신규 func code를 CUBRID/CGW/shard 디스패치 테이블 전체에 등록(`cas.c:119,166`, `cas_cgw.c`, `shard_proxy_handler.c`) + `cas_function.h` 프로토타입
- [ ] 신규/확장 경로를 `DOES_CLIENT_UNDERSTAND_THE_PROTOCOL(..., PROTOCOL_V13)`로 게이팅(`cas.c:1111` 인근 패턴)

### CCI (`cubrid-cci`)
- [ ] `T_CON_HANDLE`(`cci_handle_mng.h:208`)에 커서 close 전용 pending 목록 or `T_REQ_HANDLE`(`:143`)에 `needs_server_close` 플래그 추가 — **[§7 대책 A 권장]**
- [ ] `qe_close_query_result`(`cci_query_execute.c:1253`): holdable + 신프로토콜이면 **즉시 전송 대신 mark**로 분기
- [ ] flush 로직: `qe_prepare`(`:437-440`) / `qe_execute`(`:833,840-842`) 선례 옆에 커서-close 목록 flush 추가(별도 의미로)
- [ ] rollback 경로(`qe_end_tran`, `:261`)에서 pending 목록 **discard**
- [ ] `reset_connect`(`cas_cci.c:836`), `hm_force_close_connection`(`cci_handle_mng.c:1501`)에서 pending 목록 **clear** (현재 미정리 버그 동반 수정)
- [ ] broker 버전 게이팅(`hm_broker_understand_the_protocol`, `cci_handle_mng.c:1127`); 구 브로커 폴백 유지

### JDBC (`cubrid-jdbc`)
- [ ] `UConnection`에 커서 close 전용 pending 목록(`deferred_close_handle` 선례, `UConnection.java:260`) 또는 `UStatement` 플래그 추가 — **[§7 대책 A 권장]**
- [ ] `closeCursor`(`jci/UStatement.java:666`): holdable + 신프로토콜이면 **mark**로 분기
- [ ] flush 로직: PREPARE flush 선례(`UConnection.java:1316-1319`) 옆에 커서-close 목록 flush 추가
- [ ] rollback 시 pending 목록 discard(`CUBRIDConnection.java:213`/`end(false)`)
- [ ] `clientSocketClose`(`UConnection.java:1811`)에서 pending 목록 clear (기존 `deferred_close_handle.clear()`와 동일 처리)
- [ ] 버전 게이팅(`protoVersionIsAbove`); static `protocolVersion` 주의(`UConnection.java:232`)

### 테스트 / 검증
- [ ] ⚠️ **핸들 ID 재사용 회귀 테스트**: close 예약 → 핸들 해제/재할당 → flush 시 엉뚱한 커서 미close 확인
- [ ] autocommit point-select에서 SELECT 당 왕복 2→1 감소 실측
- [ ] 명시적 트랜잭션(commit/rollback) 정합성
- [ ] 신·구 클라이언트 × 신·구 브로커 4조합 상호운용
- [ ] 비정상 종료(연결 강제 종료) 후 서버 핸들/트랜잭션 누수 없음 + `num_holdable_results` 정상 감소
- [ ] CAS reset/rebalancing 지연 영향 측정(배치 상한 효과)

---

## 9. 미해결 질문

- flush 임계치(문서의 "5")의 적정값? CAS reset 지연·핸들 용량과 지연시간 이득의 트레이드오프 정량화 필요.
- 2안-a(multi-ID cursor_close 별도 메시지) vs 2안-b(execute 인라인 piggyback): 후자가 메시지 0추가로 효과 크나 서버 표면 넓음. 성능 이득 차이가 표면 증가를 정당화하는가?
- non-holdable을 기본값으로 바꾸는 대안은 검토 대상인가? (커밋 후 커서 재사용 워크로드 호환성 문제로 별도 논의 필요)
