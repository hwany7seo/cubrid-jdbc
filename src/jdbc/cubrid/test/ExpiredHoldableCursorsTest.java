package cubrid.test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * holdable cursor close 최적화 통합 테스트.
 *
 * <p>두 케이스를 한 파일에서 검증한다.
 * <ul>
 *   <li><b>Case 3 (정상 close 배치)</b>: 유저가 holdable ResultSet 을 정상적으로
 *       rs.close() 하면 즉시 전송하지 않고 모아 두었다가 **5개**째에 한 번의
 *       배치 CAS_FC_CURSOR_CLOSE 로 전송한다. (H2)</li>
 *   <li><b>Case 2 (스케줄러 TTL close, P3)</b>: 유저가 닫지 않고 방치한 holdable
 *       커서를 백그라운드 데몬이 open 후 TTL 경과 시 닫는다.</li>
 * </ul>
 *
 * <p>서버측 실제 close 는 CAS 로그의 "cursor_close srv_h_id ..." 로 확인할 수 있고,
 * 본 테스트는 클라이언트에서 관측 가능한 것(예외 없음/연결 정상/방치 커서의
 * fetch 실패)을 단언한다.
 *
 * <p><b>실행</b> (스케줄러 대기시간이 TTL 에 좌우됨):
 * <pre>
 *   # 빠른 실행 (TTL 10s, 스캔 2s → 대기 ~15s)
 *   java -Dcubrid.holdable.cursor.ttl.millis=10000 \
 *        -Dcubrid.holdable.cursor.scan.sec=2 \
 *        cubrid.test.ExpiredHoldableCursorsTest 15
 *
 *   # 기본(TTL 3min) : 인자 없이 → 대기 220s
 *   java cubrid.test.ExpiredHoldableCursorsTest
 * </pre>
 * 인자[0] = 스케줄러 대기 초(기본 220). 반드시 위 -D 값과 맞춰서 준다.
 * 연결은 ConnectWorker.getConnect() 사용.
 */
public class ExpiredHoldableCursorsTest {

    private static final String TABLE = "expired_cursor_test";
    private static final int ROWS = 2000; // 초기 fetch로 전부 안 오도록 충분히 크게(이후 fetch가 서버로 감)
    private static final int BATCH_STMTS = 6; // Case 3: 5개 배치 + 1개 잔여
    private static final int ABANDON_CURSORS = 3; // Case 2: 방치할 커서 수

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        int waitSec = 220;
        if (args.length >= 1) {
            try {
                waitSec = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignore) {
            }
        }

        Connection conn = ConnectWorker.getConnect();
        if (conn == null) {
            System.err.println("[FATAL] Connection failed (ConnectWorker.getConnect() == null)");
            System.exit(2);
        }
        System.out.println("=== ExpiredHoldableCursorsTest : rows=" + ROWS
                + ", batchStmts=" + BATCH_STMTS + ", abandon=" + ABANDON_CURSORS
                + ", schedulerWait=" + waitSec + "s ===");

        try {
            setup(conn);

            // 빠른 검증 먼저
            scenarioBatchOfFive(conn);        // Case 3
            scenarioHoldableSurvivesCommit(conn); // H1 sanity

            // 스케줄러(대기 포함) 마지막
            scenarioSchedulerClosesAbandoned(conn, waitSec); // Case 2 / P3
        } finally {
            teardown(conn);
            try {
                conn.close();
            } catch (SQLException ignore) {
            }
        }

        System.out.printf("%n=== Result: %d passed, %d failed ===%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // ------------------------------------------------------------------
    // Case 3 — 정상 rs.close() 는 모아서 5개째에 배치 flush
    // ------------------------------------------------------------------
    private static void scenarioBatchOfFive(Connection conn) {
        System.out.println();
        System.out.println("[SCENARIO Case3] normal rs.close() batched (flush at 5)");
        boolean prevAuto = true;
        PreparedStatement[] ps = new PreparedStatement[BATCH_STMTS];
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);

            for (int i = 0; i < BATCH_STMTS; i++) {
                int id = i + 1;
                ps[i] = holdable(conn, "SELECT id, val FROM " + TABLE + " WHERE id = " + id);
                ResultSet rs = ps[i].executeQuery();
                if (rs.next()) {
                    rs.getInt(1);
                    rs.getString(2);
                }
                rs.close();
                System.out.println("  [RT-OP] rs.close #" + (i + 1)
                        + (i == 4 ? "  <= 5개째: 배치 flush 기대 (CAS 로그 cursor_close 5 ids)" : ""));
            }

            boolean ok = pingConnection(conn);
            check("Case3: 6개 holdable rs 정상 close, 예외 없음 & 연결 정상", ok, "ping 실패");
            System.out.println("  참고: 배치 여부는 CAS 로그의 'cursor_close srv_h_id ...'(5개 묶음)로 확인");
        } catch (SQLException e) {
            check("Case3: 예외 없이 완료", false, "SQLException: " + e.getMessage());
        } finally {
            for (int i = 0; i < ps.length; i++) closeQuietly(ps[i]); // 잔여 1개 포함 정리
            restoreAuto(conn, prevAuto);
        }
    }

    // ------------------------------------------------------------------
    // Case 2 / P3 — 방치된 holdable 커서를 스케줄러가 TTL 후 close
    // ------------------------------------------------------------------
    private static void scenarioSchedulerClosesAbandoned(Connection conn, int waitSec) {
        System.out.println();
        System.out.println("[SCENARIO Case2/P3] scheduler closes abandoned holdable cursors after TTL");
        boolean prevAuto = true;
        // 강한 참조 유지 → GC 가 아니라 "스케줄러"가 닫도록 한다.
        PreparedStatement[] ps = new PreparedStatement[ABANDON_CURSORS];
        ResultSet[] rs = new ResultSet[ABANDON_CURSORS];
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);

            for (int i = 0; i < ABANDON_CURSORS; i++) {
                /*
                 * 서로 다른 핸들이 필요하지만 "행 수는 동일"해야 rowsRead<ROWS 판정이 정확하다.
                 * (이전 버전은 WHERE id >= i+1 로 행 수가 500/499/498 로 달라져, 1번은 항상
                 *  open, 2번+는 항상 closed 로 오판되어 스케줄러 동작과 무관하게 fail 했다.)
                 * → SELECT 리스트에 상수 컬럼을 넣어 SQL만 다르게(핸들 구분) 하고, 모든 커서가
                 *   정확히 ROWS 행을 반환하게 한다.
                 */
                ps[i] = holdable(conn,
                        "SELECT id, val, " + i + " AS m FROM " + TABLE + " ORDER BY id");
                ps[i].setFetchSize(10); // 초기 배치 소진 후 이후 fetch 가 서버로 가게
                rs[i] = ps[i].executeQuery();
                rs[i].next(); // 첫 행만 읽고 방치(닫지 않음)
                rs[i].getInt(1);
            }
            System.out.println("  방치한 holdable 커서 " + ABANDON_CURSORS + "개. 스케줄러(TTL) 대기 "
                    + waitSec + "s ...");
            System.out.println("  (TTL 은 -Dcubrid.holdable.cursor.ttl.millis, 스캔은 -Dcubrid.holdable.cursor.scan.sec)");

            sleepSeconds(waitSec);

            // (1) 연결은 여전히 정상 동작해야 한다(데몬이 앱을 방해하지 않음).
            boolean usable = pingConnection(conn);
            check("Case2: 스케줄러 동작 후에도 연결 정상 사용 가능", usable, "ping 실패");

            // (2) 방치 커서에서 첫 배치 이후를 fetch 하면, 서버 커서가 이미 닫혀
            //     더 읽지 못한다(예외 또는 조기 종료). 이를 스케줄러 close 의 관측 신호로 본다.
            int closedObserved = 0;
            for (int i = 0; i < ABANDON_CURSORS; i++) {
                int rowsRead = 1; // 이미 1행 읽음
                boolean cut = false;
                try {
                    while (rs[i].next()) {
                        rs[i].getInt(1);
                        rowsRead++;
                        if (rowsRead >= ROWS) break;
                    }
                    // 끝까지 못 읽었으면(조기 false) 커서가 잘린 것
                    if (rowsRead < ROWS) cut = true;
                } catch (SQLException e) {
                    cut = true; // 서버 커서 닫힘 → fetch 실패
                }
                if (cut) closedObserved++;
                System.out.println("  abandoned #" + (i + 1) + ": rowsRead=" + rowsRead
                        + (cut ? " -> 서버 커서 닫힘 관측" : " -> 전부 읽힘(닫히지 않음)"));
            }
            check("Case2: 방치 커서가 스케줄러에 의해 닫힘 (" + closedObserved + "/" + ABANDON_CURSORS + ")",
                    closedObserved == ABANDON_CURSORS,
                    "일부 커서가 안 닫힘 — TTL/스캔 설정과 대기시간을 확인(-D 값과 인자[0])");
            System.out.println("  참고: CAS 로그의 'cursor_close srv_h_id ...' 로도 확인 가능");
        } catch (SQLException e) {
            check("Case2: 시나리오 수행 중 예외 없음", false, "SQLException: " + e.getMessage());
        } finally {
            for (int i = 0; i < rs.length; i++) closeQuietly(rs[i]);
            for (int i = 0; i < ps.length; i++) closeQuietly(ps[i]);
            restoreAuto(conn, prevAuto);
        }
    }

    // ------------------------------------------------------------------
    // H1 sanity — commit 이후에도 holdable 커서 유지(스케줄러/배치와 무관)
    // ------------------------------------------------------------------
    private static void scenarioHoldableSurvivesCommit(Connection conn) {
        System.out.println();
        System.out.println("[SCENARIO H1] holdable cursor survives commit");
        boolean prevAuto = true;
        Statement st = null;
        ResultSet rs = null;
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                    ResultSet.HOLD_CURSORS_OVER_COMMIT);
            rs = st.executeQuery("SELECT id, val FROM " + TABLE + " ORDER BY id");

            int before = 0;
            if (rs.next()) before++;
            conn.commit(); // holdable → 커서 유지되어야 함

            int total = before;
            while (rs.next()) total++;
            check("H1: commit 이후 fetch 로 전체 " + ROWS + "행 도달", total == ROWS,
                    "before=" + before + ", total=" + total);
        } catch (SQLException e) {
            check("H1: commit 이후 fetch 성공", false, "SQLException: " + e.getMessage());
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
            restoreAuto(conn, prevAuto);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private static PreparedStatement holdable(Connection conn, String sql) throws SQLException {
        return conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                ResultSet.HOLD_CURSORS_OVER_COMMIT);
    }

    private static boolean pingConnection(Connection conn) {
        Statement st = null;
        ResultSet rs = null;
        try {
            st = conn.createStatement();
            rs = st.executeQuery("SELECT count(*) FROM " + TABLE);
            return rs.next();
        } catch (SQLException e) {
            return false;
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    private static void sleepSeconds(int sec) {
        long end = System.currentTimeMillis() + sec * 1000L;
        while (System.currentTimeMillis() < end) {
            long remain = (end - System.currentTimeMillis() + 999) / 1000;
            System.out.print("\r  대기 " + remain + "s 남음   ");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                break;
            }
        }
        System.out.println("\r  대기 완료           ");
    }

    private static void setup(Connection conn) throws SQLException {
        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        Statement st = conn.createStatement();
        try {
            try {
                st.executeUpdate("DROP TABLE " + TABLE);
            } catch (SQLException ignore) {
            }
            st.executeUpdate("CREATE TABLE " + TABLE + " (id INT PRIMARY KEY, val VARCHAR(64))");
        } finally {
            st.close();
        }
        PreparedStatement ins =
                conn.prepareStatement("INSERT INTO " + TABLE + " (id, val) VALUES (?, ?)");
        try {
            for (int i = 1; i <= ROWS; i++) {
                ins.setInt(1, i);
                ins.setString(2, "row-" + i);
                ins.addBatch();
                if (i % 100 == 0) ins.executeBatch();
            }
            ins.executeBatch();
        } finally {
            ins.close();
        }
        conn.setAutoCommit(prevAuto);
        System.out.println("  setup: " + TABLE + " created, " + ROWS + " rows");
    }

    private static void teardown(Connection conn) {
        try {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            Statement st = conn.createStatement();
            try {
                st.executeUpdate("DROP TABLE " + TABLE);
            } finally {
                st.close();
            }
            conn.setAutoCommit(prevAuto);
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
}
