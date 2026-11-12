package cubrid.test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cubrid.jdbc.driver.CUBRIDConnection;
import cubrid.jdbc.jci.UConnection;

/**
 * holdable cursor close 개선(deferred batch close + TTL 스케줄러) 검증 테스트.
 * 배경/설계: holdable_work.md, holdable_cursor_design.md.
 *
 * <p>검증 방법: broker SQL 로그(CAS 별 {@code <broker>_<casId>.sql.log})를 읽는다.
 * 서버 {@code fn_cursor_close} 는 실제로 닫은 핸들마다
 * {@code "cursor_close srv_h_id N"} 한 줄을 남긴다. 테스트는 같은 연결로
 * non-holdable 마커 쿼리({@code SELECT 'HCT:<token>:<label>' FROM db_root})를 실행해
 * 로그에 경계를 찍고, 경계 사이의 cursor_close 라인 수/묶음/시각을 판정한다.
 *
 * <p>시나리오
 * <ol>
 *   <li>holdable point-select (같은 핸들 재실행 = supersede, cursor_close 0)</li>
 *   <li>non-holdable point-select (기준선, cursor_close 0)</li>
 *   <li>holdable 커밋 이후 fetch 유지 (H1, commit 이 cursor_close 를 보내지 않음)</li>
 *   <li>non-holdable 커밋 시 닫힘 (정보용)</li>
 *   <li>핸들 ID 재사용 정합성 (stmt.close 가 pending 을 무전송 폐기)</li>
 *   <li>다수 open 후 일괄 close (10개 → 5개씩 2묶음)</li>
 *   <li>deferred batch 타임라인: close 1~4 는 전송 없음, 5번째에 5개 한꺼번에</li>
 *   <li>스케줄러 TTL 정리: 방치 커서 + max 미만 pending 이 TTL 후 한 묶음으로 정리</li>
 *   <li>다중 결과: getMoreResults(NEXT_RESULT) 가 pending 을 지워 살아있는 다음 결과를 닫지 않음
 *       (shard proxy 는 NEXT_RESULT 미지원 -> shard 에서는 수행 안 함)</li>
 *   <li>(shard) 여러 shard 를 오가며 close: CAS 가 바뀌는 연결에서 batch 를 proxy 가 응답, 결과 정상</li>
 *   <li>(shard) 다른 shard statement id 4개 + 자기 id 1개 batch 가 살아있는 큰 결과의 CAS 로 전달:
 *       proxy 는 다른 shard id 를 건너뛰고, 큰 결과는 끝까지 읽힘</li>
 *   <li>(shard) 다른 shard id 5개뿐인 batch: proxy 가 CAS 없이 응답(구 proxy: assert), 큰 결과 정상</li>
 *   <li>(shard) 큰 holdable 결과를 rs.close() + stmt.close() 한 뒤 다른 shard 로 이동: proxy 는 prepare 에 실린
 *       close handle 을 무시하므로 드라이버가 pending close 를 바로 보내야 CAS 가 풀린다</li>
 * </ol>
 *
 * <p>shard broker: 모든 SQL 에 {@code /*+ shard_id(N) *}{@code /} 힌트를 붙인다. proxy 는 트랜잭션 사이에
 * 연결을 다른 CAS 로 옮길 수 있으므로, 시나리오 1~9 는 큰 holdable 결과(pin)를 열어 둔 채 수행한다.
 * holdable 결과가 남은 CAS 에 proxy 가 연결을 붙잡아 두므로 요청이 한 CAS 로그에 모인다. pin 은
 * 드라이버 TTL 스케줄러에 닫히지 않도록 시나리오마다 다시 연다.
 * shard 에서는 pending 이 있는 statement 를 close 하면 드라이버가 pending close 를 바로 보낸다(proxy 가
 * prepare 에 실린 close handle 을 무시하기 때문). 그래서 "stmt.close 는 전송 없음" 기대값이 shard 에서는
 * 전송 수로 바뀐다(구 드라이버의 rs.close 와 같은 비용).</p>
 *
 * <p>실행: java [-D...] cubrid.test.HoldableCloseTest [iterations] [verbose]
 * <ul>
 *   <li>-Dcubrid.holdable.cursor.ttl.millis / -Dcubrid.holdable.cursor.scan.sec :
 *       드라이버 스케줄러 설정(드라이버와 같은 프로퍼티를 읽어 기대 시각을 계산)</li>
 *   <li>-Dholdable.brokerlog.dir : broker SQL 로그 디렉터리
 *       (기본 $CUBRID/log/broker/sql_log). 없으면 로그 판정은 SKIP.</li>
 *   <li>-Dholdable.shard.id : shard broker 에서 테스트 테이블을 둘 shard (기본 0)</li>
 * </ul>
 */
public class HoldableCloseTest {

    private static final String TABLE = "holdable_close_test";
    private static final int ROWS = 200; // full-scan 기대 행 수
    private static final String POINT_SQL = "SELECT id, val FROM " + TABLE + " WHERE id = ?";
    private static final String SCAN_SQL = "SELECT id, val FROM " + TABLE;

    /** 드라이버(UConnection.DEFERRED_CURSOR_CLOSE_MAX)와 같아야 한다. */
    private static final int BATCH_MAX = 5;
    /** 드라이버(UJCIManager)와 같은 프로퍼티/기본값. */
    private static final long TTL_MS = Long.getLong("cubrid.holdable.cursor.ttl.millis", 40000L);
    private static final long SCAN_SEC = Long.getLong("cubrid.holdable.cursor.scan.sec", 5L);

    private static int pass = 0;
    private static int fail = 0;
    private static int skip = 0;
    private static boolean verbose = false;
    private static boolean batched = false;
    private static BrokerLog blog;

    /** shard 연결 여부 / shard 수 / 테스트 테이블이 있는 shard. */
    private static boolean shard = false;
    private static int numShards = 0;
    private static int tableShard = intProp("holdable.shard.id", 0);
    /** shard CAS 의 fetch 버퍼(512KB)보다 큰 결과: execute 후에도 CAS 에 결과가 남는다. */
    private static final String BIG_SQL =
            "SELECT a.class_name AS c1, b.class_name AS c2 FROM db_class a, db_class b, db_class c";
    private static PreparedStatement pinStmt;
    private static ResultSet pinRs;
    private static int ddlSeq = 0;

    public static void main(String[] args) throws Exception {
        int iters = intProp("holdable.iters", 2000);
        if (args.length >= 1) {
            try {
                iters = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignore) {
                // args[0] 가 숫자가 아니면 verbose 플래그일 수 있음
            }
        }
        for (String a : args) {
            if ("verbose".equalsIgnoreCase(a) || "-v".equals(a)) verbose = true;
        }

        Connection conn = ConnectWorker.getConnect();
        if (conn == null) {
            System.err.println("[FATAL] Connection failed (ConnectWorker.getConnect() returned null)");
            System.exit(2);
        }
        conn.setAutoCommit(true);

        int casId = -1;
        try {
            UConnection u = ((CUBRIDConnection) conn).getUConnection();
            batched = u.supportsBatchedCursorClose();
            casId = u.casId;
            shard = u.isConnectedToProxy();
            numShards = shard ? u.getShardCount() : 0;
        } catch (Exception e) {
            System.out.println("  warn: UConnection 조회 실패: " + e);
        }

        System.out.println("=== HoldableCloseTest : iters=" + iters + ", rows=" + ROWS
                + ", verbose=" + verbose + " ===");
        System.out.println("  driver : deferred batch close = " + batched
                + " (broker protocol >= V13), batch max = " + BATCH_MAX);
        System.out.println("  sched  : ttl = " + TTL_MS + " ms, scan = " + SCAN_SEC + " s");
        System.out.println("  cas id : " + casId);
        System.out.println("  broker : " + (shard ? "shard (" + numShards + " shards, test table on shard "
                + tableShard + ")" : "non-shard"));

        blog = new BrokerLog(brokerLogDir());
        // DDL 먼저: pin 이 읽는 catalog 때문에 DDL 이 막힌다
        setup(conn);
        pin(conn);
        blog.mark(conn, "begin");
        if (blog.locate()) {
            System.out.println("  broker log : " + blog.file + "  (token " + blog.token + ")");
        } else {
            System.out.println("  broker log : 사용 불가 -> 로그 판정 SKIP (" + blog.reason + ")");
        }
        if (!batched) {
            System.out.println("  warn: deferred batch close 비활성(구 broker). 시나리오 6~8 의 로그 판정이"
                    + " 실패할 수 있습니다.");
        }

        try {
            // (1) 왕복 비용 : holdable vs non-holdable 포인트-셀렉트
            long holdMs = pointSelectThroughput(conn, true, iters);
            pin(conn);
            long noholdMs = pointSelectThroughput(conn, false, iters);
            reportThroughput(iters, holdMs, noholdMs);

            // (3) holdable 의미(커밋 이후 fetch) 보존 (H1)
            pin(conn);
            testHoldableSurvivesCommit(conn);
            // 참고용 : non-holdable 은 커밋 시 닫혀야 함
            pin(conn);
            testNonHoldableClosedAtCommit(conn);

            // (2) 정합성 회귀 : 핸들 ID 재사용 / 결과 완전성
            pin(conn);
            testHandleReuseCompleteness(conn, Math.max(200, iters / 4));

            // (2') 다수 holdable RS 동시 open 후 일괄 close
            pin(conn);
            testManyOpenThenClose(conn);

            // (H2) deferred batch : close 5회마다 한 번에 전송되는지 broker 로그로 확인
            pin(conn);
            testDeferredBatchTimeline(conn, 12);

            // (Case 2) 스케줄러 : TTL 경과 후 방치/미전송 커서가 정리되는지 확인
            testSchedulerTtlCleanup(conn, 4, 3);

            // (2b 회귀) 다중 결과 : getMoreResults(NEXT_RESULT) 가 pending 을 지우는지
            if (shard) {
                System.out.println();
                System.out.println("[RT-SCENARIO:9 multi-result getMoreResults]  수행 안 함:"
                        + " shard proxy 는 NEXT_RESULT 를 지원하지 않음 (fn_proxy_client_not_supported)");
            } else {
                testNextResultDropsPending(conn);
            }

            unpin();
            if (shard && numShards >= 2) {
                testShardMovingCloses(conn, numShards * BATCH_MAX * 4);
                testShardForeignIds(conn, false);
                testShardForeignIds(conn, true);
                testShardStmtCloseReleasesCas(conn);
            } else if (shard) {
                System.out.println("  shard 시나리오 10~12 는 shard 2개 이상 필요 (현재 " + numShards + ")");
            }

        } finally {
            unpin();
            teardown(conn);
            try {
                conn.close();
            } catch (SQLException ignore) {
            }
        }

        System.out.printf("%n=== Result: %d passed, %d failed, %d skipped ===%n", pass, fail, skip);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // (1) 포인트-셀렉트 처리량
    //     prepare 1회, 루프에서 execute + 1행 read + rs.close() 반복.
    //     holdable: rs.close() 는 pending 으로만 표시되고, 다음 재실행이 이를
    //     supersede(서버가 이전 결과 해제) 하므로 cursor_close 는 0 이어야 한다.
    // ------------------------------------------------------------------
    private static long pointSelectThroughput(Connection conn, boolean holdable, int iters)
            throws SQLException {
        String tag = holdable ? "holdable" : "non-holdable";
        int scenario = holdable ? 1 : 2;
        String scn = "s" + scenario;
        System.out.println();
        System.out.println("[RT-SCENARIO:" + scenario + " " + tag + "-autocommit point-select N="
                + iters + "]  (기대: execute " + iters + "회, cursor_close 0)");

        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        blog.mark(conn, scn + ":begin");
        PreparedStatement ps = prepare(conn, POINT_SQL, holdable);
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < iters; i++) {
                int id = (i % ROWS) + 1;
                ps.setInt(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    rs.getInt(1);
                    rs.getString(2);
                }
                if (verbose) System.out.println("  [RT-OP close id=" + id + " (" + tag + ")]");
                rs.close(); // holdable: deferred(pending) -> 다음 execute 가 supersede
            }
        } finally {
            ps.close();
            conn.setAutoCommit(prevAuto);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        blog.mark(conn, scn + ":end");
        System.out.println("  -> " + tag + " elapsed " + ms + " ms ("
                + usPerOp(ms, iters) + " us/op)");

        if (blog.refresh(scn + ":end")) {
            List<String> seg = blog.segment(scn + ":begin", scn + ":end");
            // 실패한 execute 는 드라이버/proxy 가 재시도한다(예: shard proxy 가 테이블이 재생성된
            // pooled statement 를 다시 prepare). 성공한 것만 센다.
            int execs = countContains(seg, "execute srv_h_id", "FROM " + TABLE + " WHERE id")
                    - countContains(seg, "execute error:", "");
            int cc = BrokerLog.cursorCloses(seg).size();
            System.out.println("  broker: execute=" + execs + ", cursor_close=" + cc);
            check(tag + ": broker execute 수 == " + iters, execs == iters, "execute=" + execs);
            // shard: 마지막 rs.close(pending) 는 ps.close 에서 바로 전송된다(클래스 주석 참조)
            int expectCc = (shard && holdable) ? 1 : 0;
            check(tag + ": broker cursor_close " + expectCc + " (재실행 supersede)", cc == expectCc,
                    "cursor_close=" + cc);
        } else {
            skipCheck(tag + ": broker 로그 판정");
        }
        return ms;
    }

    private static void reportThroughput(int iters, long holdMs, long noholdMs) {
        System.out.println();
        System.out.println("[SUMMARY point-select throughput]");
        System.out.println("  holdable     : " + holdMs + " ms (" + usPerOp(holdMs, iters) + " us/op)");
        System.out.println("  non-holdable : " + noholdMs + " ms (" + usPerOp(noholdMs, iters) + " us/op)");
        long delta = holdMs - noholdMs;
        System.out.println("  delta        : " + delta + " ms  (개선 후 0 근처가 기대값, 정보용)");
        // 정보용 신호일 뿐 pass/fail 로 판정하지 않음(네트워크/부하 변동 큼).
    }

    // ------------------------------------------------------------------
    // (3) holdable : 커밋 이후에도 열린 커서에서 계속 fetch 가능해야 한다 (H1).
    //     commit 은 cursor_close 를 보내면 안 된다.
    // ------------------------------------------------------------------
    private static void testHoldableSurvivesCommit(Connection conn) {
        System.out.println();
        System.out.println("[RT-SCENARIO:3 holdable survives commit]");
        boolean prevAuto = true;
        Statement st = null;
        ResultSet rs = null;
        try {
            blog.mark(conn, "s3:begin");
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                    ResultSet.HOLD_CURSORS_OVER_COMMIT);
            st.setFetchSize(1); // 커밋 이후 실제로 서버 fetch 가 일어나도록
            rs = st.executeQuery(q(SCAN_SQL));

            int before = 0;
            if (rs.next()) before++; // 커밋 전 1행만 읽기
            conn.commit(); // <-- 여기서 holdable 이면 커서 유지

            int after = before;
            while (rs.next()) after++; // 커밋 이후 계속 fetch
            check("holdable: 커밋 이후 fetch 로 전체 " + ROWS + "행 도달", after == ROWS,
                    "before=" + before + ", total=" + after);
        } catch (SQLException e) {
            check("holdable: 커밋 이후 fetch 예외 없이 성공", false, "SQLException: " + e.getMessage());
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
            restoreAuto(conn, prevAuto);
        }
        if (shard) {
            expectCursorClose(conn, "s3", "commit 은 cursor_close 를 보내지 않음 (H1), stmt.close 가 pending 1개 전송"
                    + " (shard)", 1);
        } else {
            expectNoCursorClose(conn, "s3", "commit/stmt.close 가 cursor_close 를 보내지 않음 (H1)");
        }
    }

    // ------------------------------------------------------------------
    // 참고 : non-holdable 은 커밋 시 커서가 닫혀야 한다(정보용, 하드 판정 아님).
    // ------------------------------------------------------------------
    private static void testNonHoldableClosedAtCommit(Connection conn) {
        System.out.println();
        System.out.println("[RT-SCENARIO:4 non-holdable closed at commit (info)]");
        boolean prevAuto = true;
        Statement st = null;
        ResultSet rs = null;
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                    ResultSet.CLOSE_CURSORS_AT_COMMIT);
            rs = st.executeQuery(q(SCAN_SQL));
            if (rs.next()) { /* 1행 */ }
            conn.commit();

            boolean stillOpen;
            try {
                stillOpen = rs.next(); // 닫혔다면 예외 또는 false
            } catch (SQLException e) {
                stillOpen = false;
                System.out.println("  info: 커밋 후 next() 가 예외 -> 커서 닫힘 확인 (" + e.getMessage() + ")");
            }
            System.out.println("  info: non-holdable 커밋 후 커서 열림 상태 = " + stillOpen
                    + " (닫힘=기대동작)");
        } catch (SQLException e) {
            System.out.println("  info: 시나리오 수행 중 SQLException: " + e.getMessage());
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
            restoreAuto(conn, prevAuto);
        }
    }

    // ------------------------------------------------------------------
    // (2) 정합성 회귀 : 핸들 ID 재사용.
    //     holdable RS close(pending) -> stmt close(서버 핸들 해제, pending 무전송 폐기)
    //     -> 새 stmt 가 같은 ID 재사용 -> 새 RS 는 반드시 전체 행을 반환해야 한다.
    // ------------------------------------------------------------------
    private static void testHandleReuseCompleteness(Connection conn, int loops) {
        System.out.println();
        System.out.println("[RT-SCENARIO:5 handle-id reuse completeness loops=" + loops + "]");
        boolean prevAuto = true;
        int bad = 0;
        try {
            blog.mark(conn, "s5:begin");
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            for (int k = 0; k < loops; k++) {
                PreparedStatement psA = prepare(conn, SCAN_SQL, true);
                ResultSet rsA = psA.executeQuery();
                int cA = drainAll(rsA);
                rsA.close();  // close pending
                psA.close();  // 서버 핸들 해제 -> pending 폐기, ID 재사용 가능

                PreparedStatement psB = prepare(conn, SCAN_SQL, true); // 해제된 ID 재사용 가능
                ResultSet rsB = psB.executeQuery();
                int cB = drainAll(rsB);
                rsB.close();
                psB.close();

                if (cA != ROWS || cB != ROWS) {
                    bad++;
                    if (verbose || bad <= 3) {
                        System.out.println("  [MISMATCH k=" + k + " cA=" + cA + " cB=" + cB
                                + " expected=" + ROWS + "]");
                    }
                }
            }
            check("handle-id reuse: 모든 스캔이 완전한 " + ROWS + "행 반환", bad == 0,
                    bad + "/" + loops + " loops 에서 불완전 결과");
        } catch (SQLException e) {
            check("handle-id reuse: 예외 없이 완료", false, "SQLException: " + e.getMessage());
        } finally {
            restoreAuto(conn, prevAuto);
        }
        if (shard) {
            expectCursorClose(conn, "s5", "stmt.close 가 pending close 를 바로 전송 (shard)", 2 * loops);
        } else {
            expectNoCursorClose(conn, "s5", "stmt.close 가 pending close 를 무전송 폐기");
        }
    }

    // ------------------------------------------------------------------
    // (2') 다수 holdable RS 를 동시에 열어두었다가 일괄 close.
    //      N=10 이면 5개째, 10개째 close 에서 각각 5개씩 한 묶음으로 전송되어야 한다.
    // ------------------------------------------------------------------
    private static void testManyOpenThenClose(Connection conn) {
        System.out.println();
        final int N = 10;
        System.out.println("[RT-SCENARIO:6 many holdable open-then-close N=" + N + "]"
                + "  (기대: cursor_close " + BATCH_MAX + "개씩 " + (N / BATCH_MAX) + "묶음)");
        boolean prevAuto = true;
        PreparedStatement[] ps = new PreparedStatement[N];
        ResultSet[] rs = new ResultSet[N];
        try {
            blog.mark(conn, "s6:begin");
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            int opened = 0;
            for (int i = 0; i < N; i++) {
                ps[i] = prepare(conn, POINT_SQL, true);
                ps[i].setInt(1, (i % ROWS) + 1);
                rs[i] = ps[i].executeQuery();
                if (rs[i].next()) opened++;
            }
            // 역순 close : pending 이 쌓여 batch max 에서 flush.
            // 연속된 두 batch 요청은 broker 로그에 경계가 없으므로 close 마다 마커를 찍는다.
            blog.mark(conn, "s6:close:0");
            for (int k = 1; k <= N; k++) {
                int i = N - k;
                if (verbose) System.out.println("  [RT-OP batch-close #" + i + "]");
                closeQuietly(rs[i]);
                blog.mark(conn, "s6:close:" + k);
            }
            for (int i = 0; i < N; i++) closeQuietly(ps[i]);

            // close 이후 연결이 여전히 정상 동작하는지 확인
            PreparedStatement chk = prepare(conn, POINT_SQL, true);
            chk.setInt(1, 1);
            ResultSet crs = chk.executeQuery();
            boolean ok = crs.next();
            crs.close();
            chk.close();
            check("many open-then-close: " + N + "개 open 후 일괄 close, 연결 정상",
                    opened == N && ok, "opened=" + opened + ", postCheck=" + ok);
        } catch (SQLException e) {
            check("many open-then-close: 예외 없이 완료", false, "SQLException: " + e.getMessage());
        } finally {
            restoreAuto(conn, prevAuto);
        }

        blog.mark(conn, "s6:end");
        if (blog.refresh("s6:end")) {
            boolean ok = BrokerLog.cursorCloses(blog.segment("s6:begin", "s6:close:0")).isEmpty();
            List<String> batches = new ArrayList<String>();
            for (int k = 1; k <= N; k++) {
                List<String> seg = blog.segment("s6:close:" + (k - 1), "s6:close:" + k);
                List<Integer> h = BrokerLog.cursorCloses(seg);
                int expect = (k % BATCH_MAX == 0) ? BATCH_MAX : 0;
                ok &= h.size() == expect && distinct(h) == expect
                        && (expect == 0 || BrokerLog.groups(seg).size() == 1);
                if (!h.isEmpty()) batches.add("close#" + k + "->" + h);
            }
            int tail = BrokerLog.cursorCloses(blog.segment("s6:close:" + N, "s6:end")).size();
            ok &= tail == (shard ? 1 : 0); // shard: 확인용 chk 의 rs.close + close 가 1개 전송
            System.out.println("  broker: cursor_close 묶음 " + batches + ", 이후 구간 " + tail);
            check("many open-then-close: broker 에서 " + BATCH_MAX + "개씩 " + (N / BATCH_MAX)
                    + "묶음으로 cursor_close", ok, "batches=" + batches + ", tail=" + tail);
        } else {
            skipCheck("many open-then-close: broker 로그 판정");
        }
    }

    // ------------------------------------------------------------------
    // (H2) deferred batch 타임라인
    //   서로 다른 holdable 핸들 n 개를 열고, rs 를 하나씩 close 하면서 매 close 직후
    //   마커를 찍는다. close k 와 마커 사이에 broker 가 받은 cursor_close 수는
    //     k % 5 != 0 -> 0      (드라이버가 pending 으로만 보관, 왕복 없음)
    //     k % 5 == 0 -> 5      (5개가 한 요청으로 한꺼번에 전송)
    //   이어야 한다. 남은 pending(n % 5)은 stmt.close 로 무전송 폐기된다.
    // ------------------------------------------------------------------
    private static void testDeferredBatchTimeline(Connection conn, int n) {
        System.out.println();
        System.out.println("[RT-SCENARIO:7 deferred batch close timeline n=" + n + "]"
                + "  (기대: close 1~4 전송 없음, 5번째에 " + BATCH_MAX + "개 한꺼번에)");
        PreparedStatement[] ps = new PreparedStatement[n];
        ResultSet[] rs = new ResultSet[n];
        try {
            for (int i = 0; i < n; i++) {
                ps[i] = prepare(conn, POINT_SQL, true);
                ps[i].setInt(1, i + 1);
                rs[i] = ps[i].executeQuery();
                rs[i].next();
            }
            blog.mark(conn, "s7:opened");
            for (int i = 0; i < n; i++) {
                rs[i].close();
                blog.mark(conn, "s7:close:" + (i + 1));
            }
            for (int i = 0; i < n; i++) closeQuietly(ps[i]);
            blog.mark(conn, "s7:end");
        } catch (SQLException e) {
            check("deferred batch: 예외 없이 완료", false, "SQLException: " + e.getMessage());
            for (int i = 0; i < n; i++) {
                closeQuietly(rs[i]);
                closeQuietly(ps[i]);
            }
            return;
        }

        if (!blog.refresh("s7:end")) {
            skipCheck("deferred batch: broker 로그 판정");
            return;
        }
        boolean timelineOk = true;
        String prev = "s7:opened";
        int total = 0;
        for (int k = 1; k <= n; k++) {
            String cur = "s7:close:" + k;
            List<String> seg = blog.segment(prev, cur);
            List<List<String>> groups = BrokerLog.groups(seg);
            List<Integer> handles = BrokerLog.cursorCloses(seg);
            int expect = (k % BATCH_MAX == 0) ? BATCH_MAX : 0;
            boolean ok = handles.size() == expect
                    && (expect == 0 || (groups.size() == 1 && distinctHandles(groups.get(0)) == expect));
            timelineOk &= ok;
            total += handles.size();
            System.out.printf("  close #%-2d -> broker cursor_close %d %s%s%n", k, handles.size(),
                    handles.isEmpty() ? "" : "srv_h_id=" + handles + " ",
                    ok ? "" : "  <-- 기대 " + expect);
            prev = cur;
        }
        List<String> tail = blog.segment("s7:close:" + n, "s7:end");
        int tailCc = BrokerLog.cursorCloses(tail).size();
        int expectTotal = (n / BATCH_MAX) * BATCH_MAX;
        System.out.println("  stmt.close 구간 -> cursor_close " + tailCc
                + " (남은 pending " + (n % BATCH_MAX) + "개는 무전송 폐기 기대)");
        check("deferred batch: close " + BATCH_MAX + "회마다 " + BATCH_MAX
                + "개가 한 요청으로 전송, 그 외 close 는 왕복 없음", timelineOk,
                "타임라인 불일치(위 표 참조)");
        // shard: 남은 pending 은 첫 stmt.close 에서 한 번에 전송된다
        int expectTail = shard ? n % BATCH_MAX : 0;
        check("deferred batch: 총 cursor_close " + expectTotal + ", stmt.close 구간 " + expectTail,
                total == expectTotal && tailCc == expectTail, "total=" + total + ", tail=" + tailCc);
    }

    // ------------------------------------------------------------------
    // (Case 2) 스케줄러 TTL 정리
    //   abandoned 개: executeQuery 후 rs/stmt 를 닫지 않고 방치
    //   pending   개: rs.close() 했으나 batch max 미만이라 아직 미전송
    //   앱은 TTL + scan + 여유 만큼 아무 요청도 하지 않는다. 그 사이 broker 로그에
    //     - (abandoned + pending) 개의 cursor_close 가 한 묶음으로
    //     - opened 마커 기준 [TTL, TTL + scan + 여유] 시점에
    //   나타나야 한다(스케줄러 데몬이 직접 전송). 이후 앱이 rs/stmt 를 닫아도
    //   중복 cursor_close 는 없어야 하고 연결은 정상이어야 한다.
    // ------------------------------------------------------------------
    private static void testSchedulerTtlCleanup(Connection conn, int abandoned, int pending) {
        System.out.println();
        int n = abandoned + pending;
        long slackMs = 3000;
        long waitMs = TTL_MS + SCAN_SEC * 1000 + slackMs;
        System.out.println("[RT-SCENARIO:8 scheduler TTL cleanup abandoned=" + abandoned
                + ", pending=" + pending + "]  (ttl=" + TTL_MS + "ms, scan=" + SCAN_SEC + "s)");
        if (pending >= BATCH_MAX) {
            throw new IllegalArgumentException("pending must be < " + BATCH_MAX);
        }
        PreparedStatement[] ps = new PreparedStatement[n];
        ResultSet[] rs = new ResultSet[n];
        try {
            if (shard) {
                // 스케줄러는 TTL 이 지난 holdable 결과를 모두 닫으므로 pin 도 닫힌다. 대신 열린
                // 트랜잭션으로 연결을 CAS 에 붙잡아 둔다(autocommit off, 대기 후 commit).
                conn.setAutoCommit(false);
            }
            for (int i = 0; i < n; i++) {
                ps[i] = prepare(conn, SCAN_SQL, true);
                ps[i].setFetchSize(1);
                rs[i] = ps[i].executeQuery();
                rs[i].next();
            }
            unpin(); // 트랜잭션 안: CAS 는 그대로
            for (int i = abandoned; i < n; i++) {
                rs[i].close(); // pending (< batch max -> 전송 안 됨)
            }
            blog.mark(conn, "s8:opened");
            System.out.println("  " + abandoned + "개 방치 + " + pending + "개 pending, "
                    + waitMs + " ms 동안 앱 요청 없음 (스케줄러 대기)...");
            sleepQuietly(waitMs);
            blog.mark(conn, "s8:waited");

            // 스케줄러가 이미 닫은 커서를 앱이 뒤늦게 close: 중복 전송/오류 없어야 함
            for (int i = 0; i < abandoned; i++) closeQuietly(rs[i]);
            for (int i = 0; i < n; i++) closeQuietly(ps[i]);
            PreparedStatement chk = prepare(conn, POINT_SQL, true);
            chk.setInt(1, 1);
            ResultSet crs = chk.executeQuery();
            boolean connOk = crs.next();
            crs.close();
            chk.close();
            blog.mark(conn, "s8:end");
            if (shard) {
                conn.commit();
                conn.setAutoCommit(true);
            }
            check("scheduler: 정리 이후 연결 정상", connOk, "post-check select 실패");
        } catch (SQLException e) {
            check("scheduler: 예외 없이 완료", false, "SQLException: " + e.getMessage());
            for (int i = 0; i < n; i++) {
                closeQuietly(rs[i]);
                closeQuietly(ps[i]);
            }
            restoreAuto(conn, true);
            return;
        }

        if (!blog.refresh("s8:end")) {
            skipCheck("scheduler: broker 로그 판정");
            return;
        }
        long t0 = blog.markTime("s8:opened");
        List<String> seg = blog.segment("s8:opened", "s8:waited");
        List<List<String>> groups = BrokerLog.groups(seg);
        List<Integer> handles = BrokerLog.cursorCloses(seg);
        System.out.println("  broker: 대기 구간 cursor_close " + handles.size() + "개, 묶음 "
                + groupSizes(groups) + ", srv_h_id=" + handles);
        long minOff = Long.MAX_VALUE;
        long maxOff = Long.MIN_VALUE;
        for (String l : seg) {
            if (BrokerLog.CC.matcher(l).find()) {
                long off = BrokerLog.ts(l) - t0;
                minOff = Math.min(minOff, off);
                maxOff = Math.max(maxOff, off);
                if (verbose) System.out.println("    +" + off + "ms  " + l.trim());
            }
        }
        long lo = TTL_MS - 1000; // open 은 opened 마커보다 약간 앞이므로 여유
        long hi = TTL_MS + SCAN_SEC * 1000 + 2500;
        if (!handles.isEmpty()) {
            System.out.println("  broker: opened 마커 기준 정리 시점 +" + minOff + " ~ +" + maxOff
                    + " ms (허용 " + lo + " ~ " + hi + ")");
        }
        check("scheduler: TTL 경과 후 " + n + "개(방치 " + abandoned + " + pending " + pending
                + ") cursor_close", handles.size() == n && distinct(handles) == n,
                "cursor_close=" + handles.size() + " " + handles);
        check("scheduler: 한 요청(한 묶음)으로 정리", groups.size() == 1,
                "groups=" + groupSizes(groups));
        check("scheduler: 정리 시점이 TTL~TTL+scan 범위", !handles.isEmpty()
                && minOff >= lo && maxOff <= hi,
                "offset=" + minOff + "~" + maxOff + "ms, 허용 " + lo + "~" + hi);
        int after = BrokerLog.cursorCloses(blog.segment("s8:waited", "s8:end")).size();
        if (shard) {
            // shard: 방치했던 rs 의 뒤늦은 close(pending) 와 확인용 chk 가 stmt.close 에서 전송된다.
            // 스케줄러가 이미 닫은 결과를 다시 닫는 것은 서버에서 아무 일도 하지 않는다.
            check("scheduler: 정리 후 앱 close -> stmt.close 가 " + (abandoned + 1) + "개 전송 (shard)",
                    after == abandoned + 1, "cursor_close=" + after);
        } else {
            check("scheduler: 정리 후 앱 close 시 중복 cursor_close 없음", after == 0,
                    "cursor_close=" + after);
        }
    }

    // ------------------------------------------------------------------
    // (2b 회귀) 다중 결과 + getMoreResults
    //   holdable statement 로 "q1; q2" 를 실행하고 rs1 을 일부만 읽은 뒤 getMoreResults().
    //   getMoreResults 는 rs1.close()(pending) 후 NEXT_RESULT 를 보낸다. 서버는 NEXT_RESULT 에서
    //   rs1 을 스스로 해제하고 현재 결과를 rs2 로 옮기는데, CURSOR_CLOSE 는 항상 "현재 결과"를
    //   닫으므로 pending 이 남아 있으면 다음 flush 가 살아있는 rs2 를 닫아버린다.
    //   다른 holdable 커서 BATCH_MAX 개를 close 해 flush 를 유발한 뒤 rs2 를 fetchSize=1 로
    //   끝까지 읽어 전체 행이 나오는지 확인한다.
    //   기대: rs2 전체 ROWS 행, flush 묶음은 다른 커서 BATCH_MAX 개뿐.
    // ------------------------------------------------------------------
    private static void testNextResultDropsPending(Connection conn) {
        System.out.println();
        System.out.println("[RT-SCENARIO:9 multi-result getMoreResults drops pending close]"
                + "  (기대: rs2 전체 " + ROWS + "행, flush " + BATCH_MAX + "개)");
        boolean prevAuto = true;
        Statement st = null;
        ResultSet rs2 = null;
        PreparedStatement[] ps = new PreparedStatement[BATCH_MAX];
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                    ResultSet.HOLD_CURSORS_OVER_COMMIT);
            st.setFetchSize(1);
            blog.mark(conn, "s9:begin");

            boolean isRs = st.execute(q(POINT_SQL.replace("?", "1") + "; " + SCAN_SQL));
            if (!isRs) {
                check("multi-result: 첫 결과가 ResultSet", false, "execute() returned false");
                return;
            }
            ResultSet rs1 = st.getResultSet();
            rs1.next();
            if (!st.getMoreResults()) { // rs1 close(pending) -> NEXT_RESULT
                check("multi-result: 두 번째 결과 존재", false, "getMoreResults() returned false");
                return;
            }
            rs2 = st.getResultSet();
            int read = rs2.next() ? 1 : 0; // 첫 fetch 만 수행

            // 다른 holdable 커서 BATCH_MAX 개 close -> flush 유발
            blog.mark(conn, "s9:flush:begin");
            for (int i = 0; i < BATCH_MAX; i++) {
                ps[i] = prepare(conn, POINT_SQL, true);
                ps[i].setInt(1, i + 1);
                ResultSet r = ps[i].executeQuery();
                r.next();
                r.close();
                blog.mark(conn, "s9:close:" + (i + 1));
            }
            blog.mark(conn, "s9:flush:end");

            // flush 이후 rs2 를 끝까지 fetch (fetchSize=1 -> 서버 fetch 반복)
            while (rs2.next()) read++;
            check("multi-result: flush 이후에도 rs2 전체 " + ROWS + "행 fetch", read == ROWS,
                    "read=" + read);
        } catch (SQLException e) {
            check("multi-result: flush 이후 rs2 fetch 예외 없음", false, "SQLException: " + e.getMessage());
        } finally {
            closeQuietly(rs2);
            closeQuietly(st);
            for (int i = 0; i < BATCH_MAX; i++) closeQuietly(ps[i]);
            restoreAuto(conn, prevAuto);
        }

        blog.mark(conn, "s9:end");
        if (blog.refresh("s9:end")) {
            List<Integer> before = BrokerLog.cursorCloses(blog.segment("s9:begin", "s9:flush:begin"));
            /* pending 이 남아 있으면(버그) 다른 커서 BATCH_MAX-1 번째 close 에서 다중결과 핸들을
             * 포함한 flush 가 먼저 일어난다. 정상이면 BATCH_MAX 번째 close 에서만 flush. */
            List<Integer> early = BrokerLog.cursorCloses(
                    blog.segment("s9:flush:begin", "s9:close:" + (BATCH_MAX - 1)));
            List<Integer> h = BrokerLog.cursorCloses(
                    blog.segment("s9:close:" + (BATCH_MAX - 1), "s9:close:" + BATCH_MAX));
            System.out.println("  broker: getMoreResults 구간 cursor_close " + before.size()
                    + ", close 1~" + (BATCH_MAX - 1) + " 구간 " + early
                    + ", close " + BATCH_MAX + " 구간 srv_h_id=" + h);
            check("multi-result: getMoreResults 가 cursor_close 를 보내지 않음", before.isEmpty(),
                    "cursor_close=" + before);
            check("multi-result: flush 는 다른 커서 " + BATCH_MAX + "개만 닫음(다중결과 핸들 제외)",
                    early.isEmpty() && h.size() == BATCH_MAX && distinct(h) == BATCH_MAX,
                    "early=" + early + ", srv_h_id=" + h);
        } else {
            skipCheck("multi-result: broker 로그 판정");
        }
    }

    // ------------------------------------------------------------------
    // broker SQL 로그 리더
    // ------------------------------------------------------------------
    static final class BrokerLog {
        static final Pattern CC = Pattern.compile("cursor_close srv_h_id (\\d+)");
        private static final SimpleDateFormat TS = new SimpleDateFormat("yy-MM-dd HH:mm:ss.SSS");

        final File dir;
        final String token = Long.toString(System.currentTimeMillis(), 36);
        File file;
        String reason = "";
        List<String> lines = new ArrayList<String>();

        BrokerLog(File dir) {
            this.dir = dir;
        }

        String markerText(String label) {
            return "HCT:" + token + ":" + label + "'";
        }

        /** 같은 연결로 non-holdable 마커 쿼리를 실행해 broker 로그에 경계를 남긴다. */
        void mark(Connection conn, String label) {
            Statement st = null;
            try {
                st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                        ResultSet.CLOSE_CURSORS_AT_COMMIT);
                ResultSet rs = st.executeQuery(q("SELECT 'HCT:" + token + ":" + label + "' FROM db_root"));
                rs.next();
                rs.close();
            } catch (SQLException e) {
                System.out.println("  warn: marker '" + label + "' 실패: " + e.getMessage());
            } finally {
                closeQuietly(st);
            }
        }

        /** 마커 "begin" 이 들어있는 CAS 로그 파일을 찾는다. */
        boolean locate() {
            if (dir == null || !dir.isDirectory()) {
                reason = "디렉터리 없음: " + dir;
                return false;
            }
            String needle = markerText("begin");
            for (int attempt = 0; attempt < 10 && file == null; attempt++) {
                File[] fs = dir.listFiles();
                if (fs != null) {
                    for (File f : fs) {
                        if (!f.getName().endsWith(".sql.log")) continue;
                        if (readAll(f).toString().contains(needle)) {
                            file = f;
                            break;
                        }
                    }
                }
                if (file == null) sleepQuietly(300);
            }
            if (file == null) reason = dir + " 의 *.sql.log 에서 마커를 찾지 못함 (SQL_LOG=ON 확인)";
            return file != null;
        }

        /** 로그를 다시 읽는다. label 마커가 보일 때까지 최대 ~3초 대기. */
        boolean refresh(String label) {
            if (file == null) return false;
            String needle = markerText(label);
            for (int attempt = 0; attempt < 10; attempt++) {
                lines = readAll(file);
                if (indexOf(needle) >= 0) return true;
                sleepQuietly(300);
            }
            System.out.println("  warn: broker 로그에서 마커 '" + label + "' 를 찾지 못함 (" + file + ")");
            return false;
        }

        private int indexOf(String needle) {
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) return i;
            }
            return -1;
        }

        /** 두 마커 사이의 로그 라인(경계 제외). */
        List<String> segment(String fromLabel, String toLabel) {
            int a = indexOf(markerText(fromLabel));
            int b = indexOf(markerText(toLabel));
            if (a < 0 || b < 0 || b <= a) return new ArrayList<String>();
            return new ArrayList<String>(lines.subList(a + 1, b));
        }

        long markTime(String label) {
            int i = indexOf(markerText(label));
            return i < 0 ? 0 : ts(lines.get(i));
        }

        static long ts(String line) {
            if (line.length() < 21) return 0;
            try {
                synchronized (TS) {
                    return TS.parse(line.substring(0, 21)).getTime();
                }
            } catch (ParseException e) {
                return 0;
            }
        }

        static List<Integer> cursorCloses(List<String> seg) {
            List<Integer> r = new ArrayList<Integer>();
            for (String l : seg) {
                Matcher m = CC.matcher(l);
                if (m.find()) r.add(Integer.valueOf(m.group(1)));
            }
            return r;
        }

        /** 연속된 cursor_close 라인 묶음(= 한 요청). 다른 라인이 끼면 묶음이 끊긴다. */
        static List<List<String>> groups(List<String> seg) {
            List<List<String>> r = new ArrayList<List<String>>();
            List<String> cur = null;
            for (String l : seg) {
                if (CC.matcher(l).find()) {
                    if (cur == null) {
                        cur = new ArrayList<String>();
                        r.add(cur);
                    }
                    cur.add(l);
                } else {
                    cur = null;
                }
            }
            return r;
        }

        /** 회전된 .bak + 현재 파일을 순서대로 읽는다. */
        private static List<String> readAll(File f) {
            List<String> r = new ArrayList<String>();
            File bak = new File(f.getPath() + ".bak");
            try {
                if (bak.isFile()) r.addAll(Files.readAllLines(bak.toPath(), StandardCharsets.ISO_8859_1));
                if (f.isFile()) r.addAll(Files.readAllLines(f.toPath(), StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                // 회전 중일 수 있음 -> 다음 시도
            }
            return r;
        }
    }

    private static File brokerLogDir() {
        String d = System.getProperty("holdable.brokerlog.dir");
        if (d == null || d.isEmpty()) {
            String home = System.getenv("CUBRID");
            if (home == null) return null;
            d = home + File.separator + "log" + File.separator + "broker" + File.separator + "sql_log";
        }
        return new File(d);
    }

    private static void expectNoCursorClose(Connection conn, String scn, String what) {
        expectCursorClose(conn, scn, what, 0);
    }

    private static void expectCursorClose(Connection conn, String scn, String what, int expected) {
        blog.mark(conn, scn + ":end");
        if (blog.refresh(scn + ":end")) {
            int cc = BrokerLog.cursorCloses(blog.segment(scn + ":begin", scn + ":end")).size();
            System.out.println("  broker: cursor_close=" + cc + " (기대 " + expected + ")");
            check(what, cc == expected, "cursor_close=" + cc);
        } else {
            skipCheck(what);
        }
    }

    private static int countContains(List<String> seg, String a, String b) {
        int n = 0;
        for (String l : seg) {
            if (l.contains(a) && l.contains(b)) n++;
        }
        return n;
    }

    private static int distinctHandles(List<String> group) {
        return distinct(BrokerLog.cursorCloses(group));
    }

    private static int distinct(List<Integer> l) {
        Set<Integer> s = new HashSet<Integer>(l);
        return s.size();
    }

    private static String groupSizes(List<List<String>> groups) {
        List<Integer> r = new ArrayList<Integer>();
        for (List<String> g : groups) r.add(g.size());
        return r.toString();
    }

    // ------------------------------------------------------------------
    // (shard) 10: autocommit 으로 여러 shard 를 오가며 close.
    //   연결이 shard/CAS 사이를 옮겨 다니는 동안 5번째 close 마다 다른 CAS 에서 실행한
    //   statement id 가 flush 된다. 트랜잭션 밖이므로 proxy 가 직접 응답한다.
    //   기대: 모든 쿼리가 올바른 값, 오류 없음, 이후 연결 정상.
    // ------------------------------------------------------------------
    private static void testShardMovingCloses(Connection conn, int loops) {
        System.out.println();
        System.out.println("[RT-SCENARIO:10 shard: closes while moving across " + numShards
                + " shards, loops=" + loops + "]");
        List<PreparedStatement> stmts = new ArrayList<PreparedStatement>();
        int bad = 0;
        try {
            conn.setAutoCommit(true);
            for (int k = 0; k < loops; k++) {
                PreparedStatement p = prepareOn(conn, k % numShards, "SELECT " + k + " FROM db_root", true);
                stmts.add(p);
                ResultSet r = p.executeQuery();
                if (!r.next() || r.getInt(1) != k) bad++;
                r.close(); // pending, 5번째마다 flush
            }
            for (PreparedStatement p : stmts) p.close();
            check("shard move: 모든 쿼리/close 가 올바른 값으로 성공", bad == 0, "wrong values=" + bad);
            check("shard move: 이후 연결 정상", selectValue(conn, tableShard, 4242) == 4242, "post-check 실패");
        } catch (SQLException e) {
            check("shard move: 예외 없이 완료", false, "SQLException: " + e.getMessage());
            for (PreparedStatement p : stmts) closeQuietly(p);
        }
    }

    // ------------------------------------------------------------------
    // (shard) 11, 12: 살아있는 큰 결과(shard B)의 CAS 로 shard A statement id 가 섞인 batch 가 간다.
    //   11: shard A id 4개 + shard B id 1개 -> proxy 는 shard B id 만 CAS 로 전달
    //   12: shard A id 5개 -> proxy 가 CAS 없이 응답 (구 proxy: assert)
    //   기대: 큰 결과를 끝까지 읽음, 오류 없음, 이후 다른 shard 로 이동 가능.
    // ------------------------------------------------------------------
    private static void testShardForeignIds(Connection conn, boolean onlyForeign) {
        int a = tableShard;
        int b = (tableShard + 1) % numShards;
        int nForeign = onlyForeign ? BATCH_MAX : BATCH_MAX - 1;
        System.out.println();
        System.out.println("[RT-SCENARIO:" + (onlyForeign ? 12 : 11) + " shard: flush with " + nForeign
                + " ids of shard " + a + " while a big result of shard " + b + " is live]");
        PreparedStatement[] pa = new PreparedStatement[nForeign];
        ResultSet[] ra = new ResultSet[nForeign];
        PreparedStatement big = null;
        PreparedStatement own = null;
        int bad = 0;
        try {
            conn.setAutoCommit(true);
            // shard A 의 작은 결과: 다 읽혀 CAS 에서 이미 닫힘 (autocommit, 연결은 CAS 를 떠남)
            for (int i = 0; i < nForeign; i++) {
                pa[i] = prepareOn(conn, a, "SELECT " + (100 + i) + " FROM db_root", true);
                ra[i] = pa[i].executeQuery();
                if (!ra[i].next() || ra[i].getInt(1) != 100 + i) bad++;
                if (!onlyForeign) ra[i].close(); // pending 4
            }
            // shard B 의 큰 결과: 연결이 그 CAS 에 붙잡힌다
            big = prepareOn(conn, b, BIG_SQL, true);
            ResultSet rb = big.executeQuery();
            int total = rb.next() ? 1 : 0;
            if (onlyForeign) {
                for (int i = 0; i < nForeign; i++) ra[i].close(); // 5번째에 flush (모두 shard A)
            } else {
                own = prepareOn(conn, b, "SELECT 7 FROM db_root", true);
                ResultSet ro = own.executeQuery();
                if (!ro.next() || ro.getInt(1) != 7) bad++;
                ro.close(); // 5번째: shard A 4개 + 자기 1개
            }
            while (rb.next()) total++;
            int rows = countRows(conn, b, BIG_SQL);
            check("shard foreign ids: flush 이후에도 큰 결과 전체 행 fetch", total == rows && rows > 0,
                    "read=" + total + " of " + rows);
            rb.close();
            closeQuietly(big);
            big = null;
            closeQuietly(own);
            own = null;
            for (int i = 0; i < nForeign; i++) closeQuietly(pa[i]);
            check("shard foreign ids: 오류 없음, 이후 다른 shard 로 이동 가능",
                    bad == 0 && selectValue(conn, a, 4343) == 4343, "wrong values=" + bad);
        } catch (SQLException e) {
            check("shard foreign ids: 예외 없이 완료", false, "SQLException: " + e.getMessage());
            closeQuietly(big);
            closeQuietly(own);
            for (int i = 0; i < nForeign; i++) closeQuietly(pa[i]);
        }
    }

    // ------------------------------------------------------------------
    // (shard) 13: 큰 holdable 결과를 일부만 읽고 rs.close() (pending) + stmt.close().
    //   stmt.close 의 close handle 은 다음 prepare 에 실려 가는데 shard proxy 는 이를 무시한다
    //   ("SHARD TODO : what to do for deferred close handle?"). pending 을 그냥 버리면 결과가 CAS 에
    //   남아 연결이 그 CAS 에 붙잡히고, 다른 shard 쿼리는 "Shard id couldn't be changed in a
    //   transaction" 으로 실패(연결 끊김)한다. 기대: 이후 다른 shard 쿼리 모두 성공.
    // ------------------------------------------------------------------
    private static void testShardStmtCloseReleasesCas(Connection conn) {
        System.out.println();
        System.out.println("[RT-SCENARIO:13 shard: rs.close + stmt.close of a big holdable result, then"
                + " other shards]");
        try {
            conn.setAutoCommit(true);
            PreparedStatement p = prepareOn(conn, tableShard, BIG_SQL, true);
            ResultSet r = p.executeQuery();
            r.next();
            r.close(); // pending
            p.close(); // shard: pending close 를 바로 전송
            int ok = 0;
            String err = "";
            for (int k = 1; k < numShards; k++) {
                int sid = (tableShard + k) % numShards;
                try {
                    if (selectValue(conn, sid, 500 + sid) == 500 + sid) ok++;
                } catch (SQLException e) {
                    err += " shard" + sid + ": " + e.getMessage();
                }
            }
            check("shard stmt close: 큰 결과를 닫은 뒤 다른 shard 쿼리 모두 성공", ok == numShards - 1,
                    "ok=" + ok + "/" + (numShards - 1) + err);
        } catch (SQLException e) {
            check("shard stmt close: 예외 없이 완료", false, "SQLException: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // shard helpers
    // ------------------------------------------------------------------

    /** shard 연결이면 shardId 힌트를 붙인다. */
    private static String sqlOn(int shardId, String sql) {
        return shard ? sql + " /*+ shard_id(" + shardId + ") */" : sql;
    }

    /** 테스트 테이블이 있는 shard 의 SQL. */
    private static String q(String sql) {
        return sqlOn(tableShard, sql);
    }

    /**
     * DDL: 실행마다 다른 주석을 붙여 shard proxy 가 이전 실행에서 prepare 한 statement 를 재사용하지
     * 않게 한다(테이블이 없을 때 prepare 된 "DROP TABLE" 을 재사용하면 테이블이 지워지지 않는다).
     */
    private static String ddl(String sql) {
        return q(sql + " /* HCT:" + (blog != null ? blog.token : "") + ":" + (++ddlSeq) + " */");
    }

    private static PreparedStatement prepareOn(Connection conn, int shardId, String sql, boolean holdable)
            throws SQLException {
        int hold = holdable ? ResultSet.HOLD_CURSORS_OVER_COMMIT : ResultSet.CLOSE_CURSORS_AT_COMMIT;
        return conn.prepareStatement(sqlOn(shardId, sql), ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, hold);
    }

    private static int selectValue(Connection conn, int shardId, int v) throws SQLException {
        PreparedStatement p = prepareOn(conn, shardId, "SELECT " + v + " FROM db_root", false);
        try {
            ResultSet r = p.executeQuery();
            int got = r.next() ? r.getInt(1) : -1;
            r.close();
            return got;
        } finally {
            p.close();
        }
    }

    private static int countRows(Connection conn, int shardId, String sql) throws SQLException {
        PreparedStatement p = prepareOn(conn, shardId, "SELECT COUNT(*) FROM (" + sql + ") t", false);
        try {
            ResultSet r = p.executeQuery();
            int n = r.next() ? r.getInt(1) : -1;
            r.close();
            return n;
        } finally {
            p.close();
        }
    }

    /**
     * shard: 큰 holdable 결과를 (다시) 열어 연결을 한 CAS 에 붙잡아 둔다. 드라이버 TTL 스케줄러가
     * 오래된 holdable 결과를 닫으므로 시나리오마다 다시 연다. 큰 결과가 autocommit 을 막으므로
     * 직접 commit 한다(holdable 이라 결과는 남는다).
     */
    private static void pin(Connection conn) throws SQLException {
        if (!shard) return;
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        if (pinStmt == null) {
            pinStmt = prepareOn(conn, tableShard, BIG_SQL, true);
        }
        pinRs = pinStmt.executeQuery();
        pinRs.next();
        conn.commit();
        conn.setAutoCommit(prevAuto);
    }

    private static void unpin() {
        closeQuietly(pinRs); // holdable: pending
        closeQuietly(pinStmt); // shard: 드라이버가 pending close 를 바로 보낸다
        pinStmt = null;
        pinRs = null;
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private static PreparedStatement prepare(Connection conn, String sql, boolean holdable)
            throws SQLException {
        int hold = holdable ? ResultSet.HOLD_CURSORS_OVER_COMMIT : ResultSet.CLOSE_CURSORS_AT_COMMIT;
        return conn.prepareStatement(q(sql), ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY, hold);
    }

    private static int drainAll(ResultSet rs) throws SQLException {
        int n = 0;
        while (rs.next()) {
            rs.getInt(1);
            rs.getString(2);
            n++;
        }
        return n;
    }

    private static void setup(Connection conn) throws SQLException {
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        Statement st = conn.createStatement();
        try {
            try {
                st.executeUpdate(ddl("DROP TABLE " + TABLE));
            } catch (SQLException ignore) {
                // 없으면 무시
            }
            st.executeUpdate(ddl("CREATE TABLE " + TABLE
                    + " (id INT PRIMARY KEY, val VARCHAR(64))"));
        } finally {
            st.close();
        }
        PreparedStatement ins = conn.prepareStatement(
                q("INSERT INTO " + TABLE + " (id, val) VALUES (?, ?)"));
        try {
            for (int i = 1; i <= ROWS; i++) {
                ins.setInt(1, i);
                ins.setString(2, "row-" + i);
                ins.addBatch();
            }
            ins.executeBatch();
        } finally {
            ins.close();
        }
        conn.setAutoCommit(prevAuto);
        System.out.println("  setup: " + TABLE + " created, " + ROWS + " rows inserted");
    }

    private static void teardown(Connection conn) {
        try {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            Statement st = conn.createStatement();
            try {
                st.executeUpdate(ddl("DROP TABLE " + TABLE));
            } finally {
                st.close();
            }
            conn.setAutoCommit(prevAuto);
            System.out.println();
            System.out.println("  teardown: " + TABLE + " dropped");
        } catch (SQLException e) {
            System.out.println("  teardown: (무시) " + e.getMessage());
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name + " -- " + detail);
        }
    }

    private static void skipCheck(String name) {
        skip++;
        System.out.println("  [SKIP] " + name + " (broker 로그 사용 불가)");
    }

    private static void closeQuietly(ResultSet rs) {
        if (rs != null) {
            try {
                rs.close();
            } catch (SQLException ignore) {
            }
        }
    }

    private static void closeQuietly(Statement st) {
        if (st != null) {
            try {
                st.close();
            } catch (SQLException ignore) {
            }
        }
    }

    private static void restoreAuto(Connection conn, boolean auto) {
        try {
            conn.setAutoCommit(auto);
        } catch (SQLException ignore) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String usPerOp(long ms, int iters) {
        if (iters <= 0) return "n/a";
        return String.format("%.1f", (ms * 1000.0) / iters);
    }

    private static int intProp(String key, int def) {
        try {
            String v = System.getProperty(key);
            return v == null ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
