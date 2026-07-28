package cubrid.test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class HoldableCloseTestThread2 {

    private static final String TABLE = "holdable_close_test";
    private static final int ROWS = 20000; // full-scan 기대 행 수
    private static final String POINT_SQL = "SELECT id, val FROM " + TABLE + " WHERE id = ?";
    private static final String SCAN_SQL = "SELECT id, val FROM " + TABLE;
    private static final String SELECT_SQL = "SELECT attr_name FROM db_attribute";
    private static final String SELECT_SQL_1 = "SELECT 1";

    private static int pass = 0;
    private static int fail = 0;
    private static boolean verbose = false;

    public static void main(String[] args) throws Exception {
        int iters = intProp("holdable.iters", 200000);
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
        System.out.println("=== HoldableCloseTest : iters=" + iters + ", rows=" + ROWS
                + ", verbose=" + verbose + " ===");

        try {
        	
//        	setup(conn);
        	
        } finally {
//            teardown(conn);
            try {
                conn.close();
            } catch (SQLException ignore) {
            }
        }
        
//        System.out.printf("%n=== Result: %d passed, %d failed ===%n", pass, fail);
//        if (fail > 0) System.exit(1);
    }

    private static long SelectThroughput(Connection conn, boolean holdable, int iters)
            throws SQLException {
        String tag = holdable ? "holdable" : "non-holdable";
        int scenario = holdable ? 1 : 2;
        System.out.println();
        System.out.println("[RT-SCENARIO:" + scenario + " " + tag + "-autocommit point-select N="
                + iters + "]  (" + (holdable ? "close 왕복 1회/건" : "close 왕복 0회 기준선") + ")");

        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        long t0 = System.nanoTime();
        try {
        	PreparedStatement ps;
        	ps = prepare(conn, SELECT_SQL, holdable);
            for (int i = 0; i < iters; i++) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    rs.getString(1);
                }
                if (verbose) System.out.println("  [RT-OP close id=" + " (" + tag + ")]");
//                rs.close(); // <-- holdable 이면 여기서 CURSOR_CLOSE 왕복 (드라이버 로그 지점)
            }
        } finally {
//            ps.close();
//            conn.setAutoCommit(prevAuto);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("  -> " + tag + " elapsed " + ms + " ms ("
                + usPerOp(ms, iters) + " us/op)");
        return ms;
    }
    
    private static long SelectThroughputPESCSingle(Connection conn, boolean holdable)
            throws SQLException {
        String tag = holdable ? "holdable" : "non-holdable";
        int scenario = holdable ? 1 : 2;
        System.out.println();
        System.out.println("[RT-SCENARIO:" + scenario + " " + tag + "-autocommit point-select "
                + "]  (" + (holdable ? "close 왕복 1회/건" : "close 왕복 0회 기준선") + ")");

        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        long t0 = System.nanoTime();
        try {
        	PreparedStatement ps;
            	ps = prepare(conn, SELECT_SQL, holdable);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    rs.getString(1);
                }
                if (verbose) System.out.println("  [RT-OP close id=" + " (" + tag + ")]");
                ps.close();
        } finally {
            conn.setAutoCommit(prevAuto);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("  -> " + tag + " elapsed " + ms + " ms ");
        return ms;
    }
    
    private static long SelectThroughputPERCSingle(Connection conn, boolean holdable)
            throws SQLException {
        String tag = holdable ? "holdable" : "non-holdable";
        int scenario = holdable ? 1 : 2;
        System.out.println();
        System.out.println("[RT-SCENARIO:" + scenario + " " + tag + "-autocommit point-select "
                + "]  (" + (holdable ? "close 왕복 1회/건" : "close 왕복 0회 기준선") + ")");

        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        long t0 = System.nanoTime();
        try {
        	PreparedStatement ps;
            	ps = prepare(conn, SELECT_SQL, holdable);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    rs.getString(1);
                }
                if (verbose) System.out.println("  [RT-OP close id=" + " (" + tag + ")]");
                rs.close(); // <-- holdable 이면 여기서 CURSOR_CLOSE 왕복 (드라이버 로그 지점)
        } finally {
            conn.setAutoCommit(prevAuto);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("  -> " + tag + " elapsed " + ms + " ms ");
        return ms;
    }
    
    
    // ------------------------------------------------------------------
    // (1) 포인트-셀렉트 처리량 : close 왕복 비용 격리
    //     prepare 는 1회, 루프에서 execute + 1행 read + rs.close() 만 반복하여
    //     holdable 일 때만 발생하는 CURSOR_CLOSE 왕복 비용을 격리한다.
    // ------------------------------------------------------------------
    private static long pointSelectThroughput(Connection conn, boolean holdable, int iters)
            throws SQLException {
        String tag = holdable ? "holdable" : "non-holdable";
        int scenario = holdable ? 1 : 2;
        System.out.println();
        System.out.println("[RT-SCENARIO:" + scenario + " " + tag + "-autocommit point-select N="
                + iters + "]  (" + (holdable ? "close 왕복 1회/건" : "close 왕복 0회 기준선") + ")");

        boolean prevAuto = conn.getAutoCommit();
        conn.setAutoCommit(true);
        PreparedStatement ps = prepare(conn, POINT_SQL, holdable);
        long t0 = System.nanoTime();
        try {
            for (int i = 0; i < iters; i++) {
                int id = (i % ROWS) + 1;
                ps.setInt(1, id);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    rs.getInt(1);
                    rs.getString(2);
                }
                if (verbose) System.out.println("  [RT-OP close id=" + id + " (" + tag + ")]");
//                rs.close(); // <-- holdable 이면 여기서 CURSOR_CLOSE 왕복 (드라이버 로그 지점)
            }
        } finally {
            ps.close();
            conn.setAutoCommit(prevAuto);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("  -> " + tag + " elapsed " + ms + " ms ("
                + usPerOp(ms, iters) + " us/op)");
        return ms;
    }

    private static void reportThroughput(int iters, long holdMs, long noholdMs) {
        System.out.println();
        System.out.println("[SUMMARY point-select throughput]");
        System.out.println("  holdable     : " + holdMs + " ms (" + usPerOp(holdMs, iters) + " us/op)");
        System.out.println("  non-holdable : " + noholdMs + " ms (" + usPerOp(noholdMs, iters) + " us/op)");
        long delta = holdMs - noholdMs;
        System.out.println("  delta        : " + delta + " ms  <= close 왕복(CURSOR_CLOSE) 추정 총비용");
        if (iters > 0) {
            System.out.printf("  per-op close cost ~= %.1f us/op%n", (delta * 1000.0) / iters);
        }
        // 정보용 신호일 뿐 pass/fail 로 판정하지 않음(네트워크/부하 변동 큼).
    }

    // ------------------------------------------------------------------
    // (3) holdable : 커밋 이후에도 열린 커서에서 계속 fetch 가능해야 한다.
    // ------------------------------------------------------------------
    private static void testHoldableSurvivesCommit(Connection conn) {
        System.out.println();
        System.out.println("[RT-SCENARIO:3 holdable survives commit]");
        boolean prevAuto = true;
        Statement st = null;
        ResultSet rs = null;
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                    ResultSet.HOLD_CURSORS_OVER_COMMIT);
            rs = st.executeQuery(SELECT_SQL_1);

            int before = 0;
            if (rs.next()) before++; // 커밋 전 1행만 읽기
            conn.commit(); // <-- 여기서 holdable 이면 커서 유지
//            rs.close();

            boolean stillOpen = true;
            try {
            	int after = before;
                while (rs.next()) {
                	stillOpen = true;
                	after++;
                }
                check("holdable: 커밋 이후 fetch 로 전체 " + ROWS + "행 도달", after == ROWS,
                        "before=" + before + ", total=" + after);
            } catch (SQLException e) {
                stillOpen = false;
                System.out.println("  info: 커밋 후 next() 가 예외 -> 커서 닫힘 확인 (" + e.getMessage() + ")");
            }
            System.out.println("  info: holdable 커밋 후 커서 열림 상태 = " + stillOpen
                    + " (true=기대동작)");
        } catch (SQLException e) {
            check("holdable: 커밋 이후 fetch 예외 없이 성공", false, "SQLException: " + e.getMessage());
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
            restoreAuto(conn, prevAuto);
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
            rs = st.executeQuery(SCAN_SQL);
            
            int before = 0;
            if (rs.next()) { before++; }
            conn.commit();

            boolean stillOpen = true;
            try {
            	int after = before;
                while (rs.next()) {
                	stillOpen = true;
                	after++;
                }
                check("holdable: 커밋 이후 fetch 로 전체 " + ROWS + "행 도달", after == ROWS,
                        "before=" + before + ", total=" + after);
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
    // (2) 정합성 회귀 : 핸들 ID 재사용 위험(to_do.md §7).
    //     holdable RS 를 close(개선 후 "close 예약") -> statement close(서버 핸들 해제,
    //     ID 재사용 가능) -> 새 statement 가 그 ID 를 재사용 -> 새 RS 는 반드시
    //     전체 행을 완전하게 반환해야 한다. stale 한 close 가 엉뚱한 커서를 닫으면
    //     이 스캔이 잘려서 fail 한다. 현재 드라이버에서는 통과해야 하고, 개선 후에도
    //     계속 통과해야 하는 불변식이다.
    // ------------------------------------------------------------------
    private static void testHandleReuseCompleteness(Connection conn, int loops) {
        System.out.println();
        System.out.println("[RT-SCENARIO:5 handle-id reuse completeness loops=" + loops + "]");
        boolean prevAuto = true;
        int bad = 0;
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            for (int k = 0; k < loops; k++) {
                PreparedStatement psA = prepare(conn, SCAN_SQL, true);
                ResultSet rsA = psA.executeQuery();
                int cA = drainAll(rsA);
                rsA.close();  // (개선 후) 커서 close 예약
                psA.close();  // 서버 핸들 해제 -> ID 재사용 가능

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
    }

    // ------------------------------------------------------------------
    // (2') 배치 close 스트레스 : 여러 holdable RS 를 동시에 열어두었다가 한꺼번에 close.
    //      개선안(batch/piggyback)이 다수의 예약된 close 를 몰아 처리할 때의 정합성 확인.
    // ------------------------------------------------------------------
    private static void testManyOpenThenClose(Connection conn) {
        System.out.println();
        final int N = 10;
        System.out.println("[RT-SCENARIO:6 many holdable open-then-close N=" + N + "]");
        boolean prevAuto = true;
        PreparedStatement[] ps = new PreparedStatement[N];
        ResultSet[] rs = new ResultSet[N];
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            int opened = 0;
            for (int i = 0; i < N; i++) {
                ps[i] = prepare(conn, POINT_SQL, true);
                ps[i].setInt(1, (i % ROWS) + 1);
                rs[i] = ps[i].executeQuery();
                if (rs[i].next()) opened++;
            }
            // 역순 close : 예약된 close 들이 몰려 flush 되는 상황을 흉내
            for (int i = N - 1; i >= 0; i--) {
                if (verbose) System.out.println("  [RT-OP batch-close #" + i + "]");
                closeQuietly(rs[i]);
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
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------
    private static PreparedStatement prepare(Connection conn, String sql, boolean holdable)
            throws SQLException {
        int hold = holdable ? ResultSet.HOLD_CURSORS_OVER_COMMIT : ResultSet.CLOSE_CURSORS_AT_COMMIT;
        return conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
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
                st.executeUpdate("DROP TABLE " + TABLE);
            } catch (SQLException ignore) {
                // 없으면 무시
            }
            st.executeUpdate("CREATE TABLE " + TABLE
                    + " (id INT PRIMARY KEY, val VARCHAR(64))");
        } finally {
            st.close();
        }
        final int BATCH_SIZE = 1000;
        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO " + TABLE + " (id, val) VALUES (?, ?)");
        try {
            for (int i = 1; i <= ROWS; i++) {
                ins.setInt(1, i);
                ins.setString(2, "row-" + i);
                ins.addBatch();
                if (i % BATCH_SIZE == 0) {
                    ins.executeBatch();
                }
            }
            if (ROWS % BATCH_SIZE != 0) {
                ins.executeBatch();
            }
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
 
    static class myRunnable implements Runnable {
        private int threadName;
        private Connection conn;
        private boolean holdable;
        private int iters;

        public myRunnable(int name, Connection conn, boolean holdable, int iters) {
            this.threadName = name;
            this.conn = conn;
            this.holdable = holdable;
            this.iters = iters;
        }

        public void run() {
        	//        		System.out.println("threadName : " + threadName);
			try {
				SelectThroughputPESCSingle(conn, true);
			} catch (SQLException e) {
				e.printStackTrace();
			}
//			testHoldableSurvivesCommit(conn);
//			testNonHoldableClosedAtCommit(conn);
        }
    }
    
    static class myRunnablePESC implements Runnable {
        private int threadName;
        private Connection conn;
        private boolean holdable;
        private int iters;

        public myRunnablePESC(int name, Connection conn, boolean holdable, int iters) {
            this.threadName = name;
            this.conn = conn;
            this.holdable = holdable;
            this.iters = iters;
        }

        public void run() {
        	//        		System.out.println("threadName : " + threadName);
			try {
				SelectThroughputPESCSingle(conn, true);
			} catch (SQLException e) {
				e.printStackTrace();
			}
//			testHoldableSurvivesCommit(conn);
//			testNonHoldableClosedAtCommit(conn);
        }
    }
    
    static class myRunnablePERC implements Runnable {
        private int threadName;
        private Connection conn;
        private boolean holdable;

        public myRunnablePERC(int name, Connection conn, boolean holdable) {
            this.threadName = name;
            this.conn = conn;
            this.holdable = holdable;
        }

        public void run() {
        	//        		System.out.println("threadName : " + threadName);
			try {
				SelectThroughputPERCSingle(conn, true);
			} catch (SQLException e) {
				e.printStackTrace();
			}
//			testHoldableSurvivesCommit(conn);
//			testNonHoldableClosedAtCommit(conn);
        }
    }
}
