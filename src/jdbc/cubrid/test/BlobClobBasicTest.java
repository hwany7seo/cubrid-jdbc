package cubrid.test;

import cubrid.jdbc.driver.CUBRIDBlob;
import cubrid.jdbc.driver.CUBRIDClob;
import cubrid.jdbc.driver.CUBRIDConnection;
import java.io.InputStream;
import java.io.Reader;
import java.sql.*;
import java.util.Arrays;

/**
 * CBRD-26197: internal BLOB / CLOB 기본 round-trip 테스트
 *
 * <p>테이블 생성 → INSERT(byte[] / String) → SELECT → 값 검증 → 정리
 */
public class BlobClobBasicTest {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS test_internal_lob ("
                    + "  id     INTEGER PRIMARY KEY,"
                    + "  b_col  BLOB,"
                    + "  c_col  CLOB"
                    + ")";

    private static final String DROP_TABLE = "DROP TABLE IF EXISTS test_internal_lob";

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
            testInsertAndSelectBlob(conn);
            testInsertAndSelectClob(conn);
            testNullLob(conn);
            testEmptyLob(conn);
            testLargeLob(conn);
            testUpdateBlob(conn);
        } finally {
            teardown(conn);
            conn.close();
        }

        System.out.printf("%nResult: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // -----------------------------------------------------------------------

    private static void testInsertAndSelectBlob(Connection conn) throws Exception {
        String label = "insertAndSelectBlob";
        byte[] original = "Hello BLOB world!".getBytes("UTF-8");

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_internal_lob(id, b_col) VALUES(?,?)");
        ins.setInt(1, 1);
        ins.setBytes(2, original);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col FROM test_internal_lob WHERE id=?");
        sel.setInt(1, 1);
        ResultSet rs = sel.executeQuery();
        rs.next();

        // getBytes 경로
        Blob blob = rs.getBlob(1);
        assertNotNull(label + " blob not null", blob);
        assertEquals(label + " blob length", original.length, (int) blob.length());
        byte[] got = blob.getBytes(1, (int) blob.length());
        assertArrayEquals(label + " blob bytes", original, got);

        // InputStream 경로
        rs.beforeFirst();
        rs.next();
        InputStream is = rs.getBlob(1).getBinaryStream();
        byte[] streamed = is.readAllBytes();
        is.close();
        assertArrayEquals(label + " blob stream", original, streamed);

        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testInsertAndSelectClob(Connection conn) throws Exception {
        String label = "insertAndSelectClob";
        String original = "안녕하세요 CLOB world! 한글 포함.";

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_internal_lob(id, c_col) VALUES(?,?)");
        ins.setInt(1, 2);
        ins.setString(2, original);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT c_col FROM test_internal_lob WHERE id=?");
        sel.setInt(1, 2);
        ResultSet rs = sel.executeQuery();
        rs.next();

        // getString 경로
        String gotStr = rs.getString(1);
        assertEquals(label + " clob getString", original, gotStr);

        // getClob 경로
        rs.beforeFirst();
        rs.next();
        Clob clob = rs.getClob(1);
        assertNotNull(label + " clob not null", clob);
        String sub = clob.getSubString(1, (int) clob.length());
        assertEquals(label + " clob getSubString", original, sub);

        // Reader 경로
        rs.beforeFirst();
        rs.next();
        Reader reader = rs.getClob(1).getCharacterStream();
        char[] cbuf = new char[4096];
        int len = reader.read(cbuf);
        reader.close();
        String fromReader = new String(cbuf, 0, len);
        assertEquals(label + " clob reader", original, fromReader);

        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testNullLob(Connection conn) throws Exception {
        String label = "nullLob";

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_internal_lob(id, b_col, c_col) VALUES(?,?,?)");
        ins.setInt(1, 3);
        ins.setNull(2, Types.BLOB);
        ins.setNull(3, Types.CLOB);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col, c_col FROM test_internal_lob WHERE id=?");
        sel.setInt(1, 3);
        ResultSet rs = sel.executeQuery();
        rs.next();

        assertNull(label + " blob null", rs.getBlob(1));
        assertNull(label + " clob null", rs.getClob(2));

        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testEmptyLob(Connection conn) throws Exception {
        String label = "emptyLob";

        Blob emptyBlob = conn.createBlob();
        Clob emptyClob = conn.createClob();
        assertEquals(label + " createBlob length 0", 0, (int) emptyBlob.length());
        assertEquals(label + " createClob length 0", 0, (int) emptyClob.length());

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_internal_lob(id, b_col, c_col) VALUES(?,?,?)");
        ins.setInt(1, 4);
        ins.setBlob(2, emptyBlob);
        ins.setClob(3, emptyClob);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col, c_col FROM test_internal_lob WHERE id=?");
        sel.setInt(1, 4);
        ResultSet rs = sel.executeQuery();
        rs.next();

        Blob b = rs.getBlob(1);
        Clob c = rs.getClob(2);
        // empty insert → server might store NULL or empty; tolerate null
        if (b != null) assertEquals(label + " blob 0 length", 0, (int) b.length());
        if (c != null) assertEquals(label + " clob 0 length", 0, (int) c.length());

        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testLargeLob(Connection conn) throws Exception {
        String label = "largeLob";
        // 200KB binary + 100KB text
        byte[] bigBytes = new byte[200 * 1024];
        for (int i = 0; i < bigBytes.length; i++) bigBytes[i] = (byte) (i % 127);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50 * 1024; i++) sb.append("AB");
        String bigStr = sb.toString();

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_internal_lob(id, b_col, c_col) VALUES(?,?,?)");
        ins.setInt(1, 5);
        ins.setBytes(2, bigBytes);
        ins.setString(3, bigStr);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col, c_col FROM test_internal_lob WHERE id=?");
        sel.setInt(1, 5);
        ResultSet rs = sel.executeQuery();
        rs.next();

        byte[] gotBytes = rs.getBytes(1);
        assertArrayEquals(label + " large blob", bigBytes, gotBytes);

        String gotStr = rs.getString(2);
        assertEquals(label + " large clob", bigStr, gotStr);

        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testUpdateBlob(Connection conn) throws Exception {
        String label = "updateBlob";
        byte[] v1 = "version1".getBytes("UTF-8");
        byte[] v2 = "version2_updated".getBytes("UTF-8");

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_internal_lob(id, b_col) VALUES(?,?)");
        ins.setInt(1, 6);
        ins.setBytes(2, v1);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement upd = conn.prepareStatement(
                "UPDATE test_internal_lob SET b_col=? WHERE id=?");
        upd.setBytes(1, v2);
        upd.setInt(2, 6);
        upd.executeUpdate();
        conn.commit();
        upd.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col FROM test_internal_lob WHERE id=?");
        sel.setInt(1, 6);
        ResultSet rs = sel.executeQuery();
        rs.next();
        assertArrayEquals(label + " updated bytes", v2, rs.getBytes(1));
        rs.close();
        sel.close();
        cleanup(conn);
    }

    // -----------------------------------------------------------------------
    // Infrastructure

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

    private static void cleanup(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM test_internal_lob");
        }
        conn.commit();
    }

    // -----------------------------------------------------------------------
    // Assertion helpers

    private static void assertNotNull(String label, Object v) {
        if (v == null) {
            System.err.println("[FAIL] " + label + " : expected non-null but was null");
            fail++;
        } else {
            System.out.println("[PASS] " + label);
            pass++;
        }
    }

    private static void assertNull(String label, Object v) {
        if (v != null) {
            System.err.println("[FAIL] " + label + " : expected null but was " + v);
            fail++;
        } else {
            System.out.println("[PASS] " + label);
            pass++;
        }
    }

    private static void assertEquals(String label, int expected, int actual) {
        if (expected != actual) {
            System.err.println("[FAIL] " + label + " : expected=" + expected + " actual=" + actual);
            fail++;
        } else {
            System.out.println("[PASS] " + label);
            pass++;
        }
    }

    private static void assertEquals(String label, String expected, String actual) {
        if (!expected.equals(actual)) {
            System.err.println("[FAIL] " + label + " : expected='" + expected + "' actual='" + actual + "'");
            fail++;
        } else {
            System.out.println("[PASS] " + label);
            pass++;
        }
    }

    private static void assertArrayEquals(String label, byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            System.err.println("[FAIL] " + label + " : byte arrays differ (len expected="
                    + expected.length + " actual=" + (actual == null ? "null" : actual.length) + ")");
            fail++;
        } else {
            System.out.println("[PASS] " + label);
            pass++;
        }
    }
}
