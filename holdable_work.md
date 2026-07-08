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

1안) marking만하고 commit/rollback시 함께 전달해서 처리.
2안) marking 후 모아두었다가 5번에 한번 정도 서버로 보내는 처리.

주의사항 
비정상 종료인 경우 서버에 전달하지 못한 holdable cursor는 어떻게 처리할지에 대해서도 함께 고민이 필요함.
비정상 종료일 경우 Server의 CAS 부분도 수정이 필요할 것으로 예측됨.