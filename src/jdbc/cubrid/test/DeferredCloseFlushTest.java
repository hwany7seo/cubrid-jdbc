package cubrid.test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * item 3(a) — deferred cursor-close 배치의 TTL flush 관찰용 하니스.
 *
 * <p><b>대상 동작</b>: Case 3에서 유저가 {@code rs.close()} 하면 즉시 전송하지 않고 연결별로
 * 모아 <b>5개째(max)</b>에 배치 flush 한다(H2). 5개 미만으로만 쌓이면 연결 종료/풀 반납 전까지
 * 서버에 남는데, 데몬(JdbcCacheWorker)이 <b>TTL 경과 시 flush</b> 한다(item 3(a)).
 * (방치=안 닫은 커서를 닫던 옛 스케줄러와 다름 — 여기 대상은 오직 유저가 rs.close 한 커서다.)
 *
 * <p><b>왜 pass/fail 이 제한적인가</b>: flush 대상 커서는 이미 {@code rs.close()} 된 상태라
 * client 에서 다시 읽어 확인할 수 없다({@code next()} 는 client-side {@code is_closed} 로 즉시 예외).
 * 따라서 본 하니스는
 * <ul>
 *   <li>client 에서 단언 가능한 것 = <b>데몬 동작 전/중/후에도 연결이 계속 정상 사용됨</b>
 *       (= flush 가 앱 요청과 같은 모니터로 직렬화되어 소켓/프로토콜을 깨지 않음, 풀 안전성)만
 *       PASS/FAIL 로 검사하고,</li>
 *   <li>실제 flush(서버 {@code CURSOR_CLOSE} {@value #DEFER}개 묶음)는 <b>CAS SQL 로그 /
 *       JdbcDriverLog 라운드트립</b>으로 관찰하도록 정확한 시각 마커를 출력한다.</li>
 * </ul>
 *
 * <p><b>전제</b>: 브로커가 PROTOCOL_V13 이상이어야 rs.close 가 지연(배치)된다. 구 브로커는
 * closeCursor 가 즉시 전송하므로 배치가 쌓이지 않아 이 시나리오가 성립하지 않는다.
 *
 * <p><b>실행</b>(짧은 TTL 권장, 대기 &gt; TTL+scan 으로):
 * <pre>
 *   java -Dcubrid.deferred.cursor.close.ttl.millis=10000 \
 *        -Dcubrid.deferred.cursor.close.scan.sec=2 \
 *        cubrid.test.DeferredCloseFlushTest 20
 * </pre>
 * 인자[0] = 대기 초(기본 20). 위 -D 값과 맞춰서 준다. 연결은 ConnectWorker.getConnect() 사용.
 */
public class DeferredCloseFlushTest {

    private static final String TABLE = "deferred_close_flush_test";
    private static final int ROWS = 100;
    private static final int DEFER = 4; // < 5(max): at-5 flush 는 걸리지 않고 TTL flush 로만 닫힘

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        int waitSec = 20;
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
        System.out.println("=== DeferredCloseFlushTest : defer=" + DEFER + " (<5), wait=" + waitSec + "s ===");
        System.out.println("  TTL=-Dcubrid.deferred.cursor.close.ttl.millis, scan=-Dcubrid.deferred.cursor.close.scan.sec");

        long t0 = System.currentTimeMillis();
        try {
            setup(conn);
            scenario(conn, waitSec, t0);
        } finally {
            teardown(conn);
            try {
                conn.close();
            } catch (SQLException ignore) {
            }
        }

        System.out.printf("%n=== Result: %d passed, %d failed ===%n", pass, fail);
        System.out.println("실제 flush(서버 CURSOR_CLOSE " + DEFER + "개 묶음)는 위 [3a] 마커 시각 이후 "
                + "CAS SQL 로그 / JdbcDriverLog 라운드트립으로 확인하세요.");
        System.out.println("  기대: 5번째 close 없이도, '첫 rs.close + TTL' 시점에 cursor_close 가 " + DEFER + "개 핸들로 한 번 발생.");
        if (fail > 0) System.exit(1);
    }

    private static void scenario(Connection conn, int waitSec, long t0) {
        boolean prevAuto = true;
        // 강한 참조 유지 → statement 가 닫히지 않아 deferred 커서가 배치에 남아 있게 한다.
        PreparedStatement[] ps = new PreparedStatement[DEFER];
        try {
            prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(true);

            // 1) DEFER개 holdable 커서: open → 1행 읽기 → rs.close()
            //    rs.close() 는 holdable 이므로 즉시 전송하지 않고 deferred(cursorClosePending)로 쌓인다.
            //    DEFER(<5)라서 at-5 배치 flush 는 발생하지 않는다.
            for (int i = 0; i < DEFER; i++) {
                ps[i] = holdable(conn, "SELECT id, val, " + i + " AS m FROM " + TABLE + " ORDER BY id");
                ResultSet rs = ps[i].executeQuery();
                if (rs.next()) {
                    rs.getInt(1);
                }
                rs.close(); // deferred (statement 는 열린 채 유지 → 커서가 배치에 남음)
                mark(t0, "rs.close #" + (i + 1) + " → deferred batch=" + (i + 1) + "/5 (아직 미전송)");
            }
            mark(t0, DEFER + "개 deferred(<5). at-5 flush 미발생 — 이제 TTL 대기 시작(첫 close 기준).");

            // 2) 대기 전: 연결 정상
            check("데몬 flush 전 연결 정상 사용 가능", pingConnection(conn), "ping 실패");

            // 3) TTL 대기 (중간에 한 번 ping → 데몬 스캔/flush 창과 겹쳐도 앱 요청이 안전한지 확인)
            int half = Math.max(1, waitSec / 2);
            sleepSeconds(half);
            check("대기 중(데몬 스캔 진행) 연결 정상 — 소켓/프로토콜 무손상", pingConnection(conn), "ping 실패");
            mark(t0, "이 부근이 예상 flush 시점(첫 close + TTL). CAS 로그 cursor_close(" + DEFER + " ids) 확인 지점.");
            sleepSeconds(waitSec - half);

            // 4) 대기 후: 연결 정상
            check("데몬 flush 후에도 연결 정상 사용 가능", pingConnection(conn), "ping 실패");
            mark(t0, "완료. (닫힌 rs 는 client 재확인 불가 — flush 성사는 로그로 확인)");
        } catch (SQLException e) {
            check("시나리오 수행 중 예외 없음", false, "SQLException: " + e.getMessage());
        } finally {
            for (int i = 0; i < ps.length; i++) closeQuietly(ps[i]); // 남은 statement 핸들 정리
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

    /** deferred 배치를 오염시키지 않도록 <b>non-holdable</b> statement 로 확인(=커서 close 지연 안 됨). */
    private static boolean pingConnection(Connection conn) {
        Statement st = null;
        ResultSet rs = null;
        try {
            st = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                    ResultSet.CLOSE_CURSORS_AT_COMMIT);
            rs = st.executeQuery("SELECT count(*) FROM " + TABLE);
            return rs.next();
        } catch (SQLException e) {
            return false;
        } finally {
            closeQuietly(rs);
            closeQuietly(st);
        }
    }

    private static void mark(long t0, String msg) {
        long el = (System.currentTimeMillis() - t0) / 1000;
        System.out.println("  [3a t+" + el + "s] " + msg);
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
