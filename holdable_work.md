# 문제점
holdable vs non-holdable — holdable 의 오버헤드
두 모드는 (커밋 이후 커서를 재사용하지 않는 워크로드에서) 관찰 가능한 결과는 동일하지만 비용이 다르다.

|항목|holdable (CUBRID 기본)|non-holdable|
|---|---|---|
|커밋 이후 fetch/스크롤| 가능 (커서 유지) |	불가 (커밋 시 닫힘)|
|결과셋 해제 시점|명시적 CURSOR_CLOSE 또는 연결 종료까지 유지|트랜잭션 종료 시 자동 해제|

close 시 서버 왕복	동기 CURSOR_CLOSE 왕복 1회	없음 (클라이언트 게이트가 생략)
차이의 핵심은 결과셋 close 시의 서버 왕복이다. holdable 은 close 마다 별도 CURSOR_CLOSE 왕복을 1회 전송하고, non-holdable 은 트랜잭션 종료 시 서버가 자동 해제하므로 왕복이 없다.

autocommit·point-select 기준 SELECT 당 broker 왕복:

|단계|non-holdable|holdable (기본)|
|---|---|---|
|execute (+fetch, autocommit commit 포함)|왕복 1|왕복 1
|결과셋 close|0|1 (CURSOR_CLOSE)|
|합계|왕복 1|왕복 2|
commit 은 autocommit 시 execute 요청에 piggyback 되어 별도 왕복이 아니다. 따라서 close 가 유일한 추가 왕복이며, holdable 에서는 SELECT 당 왕복이 1 → 2 로 증가한다.

## JDBC 드라이버
결과셋 close 마다 holdable 이면 closeCursor() 를 호출하고, 그 안에서 send_recv_msg() 로 동기 왕복한다.

```
// CUBRIDResultSet.java:274  (ResultSet.close() 내부)
if (stmt.getResultSetHoldability() == ResultSet.HOLD_CURSORS_OVER_COMMIT) {
    u_stmt.closeCursor();
}

// UStatement.java:665
public synchronized void closeCursor() {
    ...
    outBuffer.newRequest(UFunctionCode.CURSOR_CLOSE);  // V2: CURSOR_CLOSE_FOR_PROTOCOL_V2
    outBuffer.addInt(serverHandler);
    relatedConnection.send_recv_msg();   // 전송 + 응답 대기 = 왕복 1회
}
```

## C 드라이버 (CCI)
qe_close_query_result() 가 holdable 일 때만 CAS_FC_CURSOR_CLOSE 를 전송하고 응답을 대기한다.

```
// cci_query_execute.c:1253  qe_close_query_result()
>if (!hm_get_con_handle_holdable (con_handle))   // non-holdable 이면 전송 없이 반환
    return err_code;
...
net_buf_cp_str (&net_buf, &func_code, 1);        // func_code = CAS_FC_CURSOR_CLOSE
err_code = net_send_msg (con_handle, ...);                 // :1288  전송
err_code = net_recv_msg (con_handle, NULL, NULL, NULL);    // :1295  응답 대기 = 왕복 1회
```

CCI·JDBC 모두 기본값이 holdable 이므로, 커밋 이후 결과 조회(fetch)가 전혀 없는 질의에서도 1 절의 close 왕복이 발생한다.
이를 개선하는 것이 목적이다.

PostgreSQL/pgjdbc 는 이 오버헤드를 별도 왕복 없이 제거하고 있다 (close 를 다음 메시지에 결합/지연). CUBRID 도 close 동작은 유지한 채 별도 왕복만 같은 방식으로 없애면, holdable 의미(커밋 이후 fetch 가능)를 보존하면서 SELECT 당 왕복을 2 → 1 로 줄일 가능성이 있다.


HOLD_CURSORS_OVER_COMMIT 에서
	1. Prepare 한번 + executeQuery -> ux_execute에서 cursor 해소, cursor close 불필요
	2. ~~Prepare + execute 반복 하는데 유저 닫지 않음. 스케줄러를 등록해서 24시간(테스트 3분) 경과 시 cursor를 닫는다. 커넥션 풀 사용도 고려.~~
	   → **제거 결정(2026-07-28).** HikariCP/DBCP 등 외부 풀에서 HOLD_CURSORS_OVER_COMMIT 커서를
	   명시적으로 닫지 않아도 반납 시 정상 정리됨을 실측 확인(temp_test). 방치 커서는
	   (a) 재실행 supersede, (b) 연결 종료(물리/논리) 시 CLOSE_USTATEMENT, (c) 외부 풀의 statement 정리,
	   (d) 비정상 종료 시 서버 강제 free_all 로 회수되므로 드라이버 스케줄러는 불필요. → 스케줄러(P3/P4) 코드 제거.
	
	3. 정상적으로 사용자가 resultset을 close 했을 경우에는 해당 목록을 모아서 deferred 처리한다 (max 개수는 5개)
	   (a) deferred된 cursor가 4개인데 계속 사용하지 않고 가지고 있으므로 24시간(테스트는 3분)이후에 스케줄 처리해야한다. 스캔 주기는 60초(테스트:30초)이다.
	   → **구현됨(2026-07-28).** 항목 2(방치 커서 스케줄러)와 달리 이건 **유지**. 유저가 rs.close()해 deferred(cursorClosePending)로
	   쌓였으나 max 5 미만이라 flush 못 한 배치를, 데몬이 TTL 경과 시 flush(방치/live 커서는 미접촉).
	   TTL=`-Dcubrid.deferred.cursor.close.ttl.millis`(기본 86400000=24h), 스캔=`-Dcubrid.deferred.cursor.close.scan.sec`(기본 60).

HOLD_CURSORS_OVER_COMMIT(holdable)의 꼭 지켜야 할 규칙
	1. Commit 이후 fetch/scroll 에서 커서는 꼭 유지해야 한다.
	2. 명시적 CURSOR_CLOSE는 모아서 처리한다.
	3. 연결 종료시에는 꼭 닫는다.

HOLD_CURSORS_AT_COMMIT(non holdable)
Commit, 트랜잭션 종료시 자동으로 close되어야 한다.


## 9. 검토 · 결정 필요 사항

1. **와이어**: V13 배치(서버 준비됨) vs 서버 무변경(개별 전송 버스트). → 배치 권장.
-  V13 적용하여 배치 해제합니다.  구 버전은 전처럼 핸들별 즉시 `CURSOR_CLOSE`합니다.

2. **스케줄러 전송 방식**: 데몬이 직접 전송(진짜 idle 연결도 회수) vs mark-only(다음 앱 op에서 전송, idle 연결 미회수). → 요구사항(24h 후 닫기)상 **직접 전송** 필요. skip-if-busy로 안전.


3. **풀 논리종료 훅**: `CUBRIDPooledConnection`/외부 풀(DBCP/HikariCP) 반납 시 holdable flush 지점 확정 필요(외부 풀은 반납 시 물리 close 안 함 → 데몬이 안전망).
외부 풀(DBCP/HikariCP) 반납 시 holdable flush 지점 확정 필요? 


4. **TTL 기준**: open 시각 vs last-access. → last-access 권장(활성 커서 보호).
- last-access 로 합니다.

5. **테스트 값**: TTL 3min, 스캔 20s로 시작.
- OK