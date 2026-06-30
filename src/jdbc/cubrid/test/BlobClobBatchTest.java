package cubrid.test;

import java.sql.*;
import java.util.Arrays;

/**
 * CBRD-26197: BLOB/CLOB 배치 INSERT / 다건 SELECT 테스트
 *
 * <p>여러 행을 배치로 삽입하고 순서대로 값이 일치하는지 검증한다.
 */
public class BlobClobBatchTest {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS test_lob_batch ("
                    + "  id    INTEGER PRIMARY KEY,"
                    + "  b_col BLOB,"
                    + "  c_col CLOB"
                    + ")";

    private static final String DROP_TABLE = "DROP TABLE IF EXISTS test_lob_batch";

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        Connection conn = ConnectWorker.getConnect();
        if (conn == null) {
            System.err.println("[FATAL] Connection failed");
            return;
        }
        conn.setAutoCommit(false);

        try {
            setup(conn);
            testBatchInsert(conn);
            testMultiRowSelect(conn);
            testScrollableResultSet(conn);
        } finally {
            teardown(conn);
            conn.close();
        }

        System.out.printf("%nResult: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // -----------------------------------------------------------------------

    private static final int ROW_COUNT = 50;

    private static void testBatchInsert(Connection conn) throws Exception {
        String label = "batchInsert";
        PreparedStatement pstmt = conn.prepareStatement(
                "INSERT INTO test_lob_batch(id, b_col, c_col) VALUES(?,?,?)");

        for (int i = 1; i <= ROW_COUNT; i++) {
            byte[] bData = ("blob_row_" + i).getBytes("UTF-8");
            String cData = "clob_row_" + i;
            pstmt.setInt(1, i);
            pstmt.setBytes(2, bData);
            pstmt.setString(3, cData);
            pstmt.addBatch();
        }

        int[] results = pstmt.executeBatch();
        conn.commit();
        pstmt.close();

        assertEquals(label + " batch length", ROW_COUNT, results.length);
        for (int i = 0; i < results.length; i++) {
            assertTrue(label + " row " + i + " success", results[i] >= 0);
        }
    }

    private static void testMultiRowSelect(Connection conn) throws Exception {
        String label = "multiRowSelect";
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
                "SELECT id, b_col, c_col FROM test_lob_batch ORDER BY id");

        int count = 0;
        while (rs.next()) {
            int id = rs.getInt(1);
            byte[] gotBytes = rs.getBytes(2);
            String gotStr = rs.getString(3);

            byte[] expectedBytes = ("blob_row_" + id).getBytes("UTF-8");
            String expectedStr = "clob_row_" + id;

            assertArrayEq(label + " row " + id + " blob", expectedBytes, gotBytes);
            assertEquals(label + " row " + id + " clob", expectedStr, gotStr);
            count++;
        }
        assertEquals(label + " total rows", ROW_COUNT, count);

        rs.close();
        stmt.close();
    }

    private static void testScrollableResultSet(Connection conn) throws Exception {
        String label = "scrollableRS";
        Statement stmt = conn.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
        ResultSet rs = stmt.executeQuery(
                "SELECT id, b_col, c_col FROM test_lob_batch ORDER BY id");

        // forward to row 25
        rs.absolute(25);
        assertEquals(label + " row25 id", 25, rs.getInt(1));
        assertEquals(label + " row25 clob", "clob_row_25", rs.getString(3));
        assertArrayEq(label + " row25 blob",
                "blob_row_25".getBytes("UTF-8"), rs.getBytes(2));

        // move to last
        rs.last();
        assertEquals(label + " last id", ROW_COUNT, rs.getInt(1));

        // move to first
        rs.first();
        assertEquals(label + " first id", 1, rs.getInt(1));

        rs.close();
        stmt.close();
    }

    // -----------------------------------------------------------------------

    private static void setup(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute(DROP_TABLE);
            s.execute(CREATE_TABLE);
        }
        conn.commit();
    }

    private static void teardown(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute(DROP_TABLE);
        }
        conn.commit();
    }

    private static void assertEquals(String label, int e, int a) {
        if (e != a) { System.err.println("[FAIL] " + label + ": exp=" + e + " act=" + a); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertEquals(String label, String e, String a) {
        boolean ok = (e == null && a == null) || (e != null && e.equals(a));
        if (!ok) { System.err.println("[FAIL] " + label + ": exp='" + e + "' act='" + a + "'"); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertTrue(String label, boolean cond) {
        if (!cond) { System.err.println("[FAIL] " + label); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertArrayEq(String label, byte[] e, byte[] a) {
        if (!Arrays.equals(e, a)) {
            System.err.println("[FAIL] " + label + ": byte arrays differ (exp len=" + e.length
                    + " act len=" + (a == null ? "null" : a.length) + ")");
            fail++;
        } else {
            System.out.println("[PASS] " + label);
            pass++;
        }
    }
}
