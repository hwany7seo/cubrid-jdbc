package cubrid.test;

import cubrid.jdbc.driver.CUBRIDBlob;
import cubrid.jdbc.driver.CUBRIDClob;
import java.sql.*;

/**
 * CBRD-26197: ResultSetMetaData / DatabaseMetaData 에서 BLOB/CLOB 타입 구분 검증
 *
 * <p>- ResultSetMetaData.getColumnTypeName() 이 "BLOB" / "CLOB" 반환 확인
 * - ResultSetMetaData.getColumnType() 이 Types.BLOB / Types.CLOB 반환 확인
 * - ResultSet.getObject() 가 CUBRIDBlob / CUBRIDClob 인스턴스 반환 확인
 * - DatabaseMetaData.getColumns() TYPE_NAME 확인
 */
public class LobTypeMetaDataTest {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS test_lob_meta ("
                    + "  id     INTEGER PRIMARY KEY,"
                    + "  b_col  BLOB,"
                    + "  c_col  CLOB,"
                    + "  v_col  VARCHAR(100)"
                    + ")";

    private static final String DROP_TABLE = "DROP TABLE IF EXISTS test_lob_meta";
    private static final String TABLE_NAME = "test_lob_meta";

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
            insertSampleRow(conn);
            testResultSetMetaData(conn);
            testColumnTypeName(conn);
            testGetObjectType(conn);
            testDatabaseMetaDataColumns(conn);
        } finally {
            teardown(conn);
            conn.close();
        }

        System.out.printf("%nResult: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // -----------------------------------------------------------------------

    private static void testResultSetMetaData(Connection conn) throws Exception {
        PreparedStatement sel = conn.prepareStatement(
                "SELECT id, b_col, c_col, v_col FROM test_lob_meta");
        ResultSet rs = sel.executeQuery();
        ResultSetMetaData meta = rs.getMetaData();

        // col 2 → BLOB
        assertEquals("rsmd blob typeName", "BLOB", meta.getColumnTypeName(2));
        assertEquals("rsmd blob sqlType", Types.BLOB, meta.getColumnType(2));

        // col 3 → CLOB
        assertEquals("rsmd clob typeName", "CLOB", meta.getColumnTypeName(3));
        assertEquals("rsmd clob sqlType", Types.CLOB, meta.getColumnType(3));

        // col 4 → VARCHAR (regression: not shifted)
        String vType = meta.getColumnTypeName(4);
        assertTrue("rsmd varchar typeName is not BLOB/CLOB",
                !vType.equals("BLOB") && !vType.equals("CLOB"));

        rs.close();
        sel.close();
    }

    private static void testColumnTypeName(Connection conn) throws Exception {
        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col, c_col FROM test_lob_meta WHERE id=1");
        ResultSet rs = sel.executeQuery();
        rs.next();
        ResultSetMetaData meta = rs.getMetaData();

        assertEquals("col typeName blob", "BLOB", meta.getColumnTypeName(1));
        assertEquals("col typeName clob", "CLOB", meta.getColumnTypeName(2));
        assertEquals("col className blob", "java.sql.Blob", meta.getColumnClassName(1));
        assertEquals("col className clob", "java.sql.Clob", meta.getColumnClassName(2));

        rs.close();
        sel.close();
    }

    private static void testGetObjectType(Connection conn) throws Exception {
        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col, c_col FROM test_lob_meta WHERE id=1");
        ResultSet rs = sel.executeQuery();
        rs.next();

        Object bObj = rs.getObject(1);
        Object cObj = rs.getObject(2);

        assertNotNull("getObject blob", bObj);
        assertNotNull("getObject clob", cObj);
        assertTrue("getObject blob instanceof Blob", bObj instanceof Blob);
        assertTrue("getObject clob instanceof Clob", cObj instanceof Clob);
        assertTrue("getObject blob instanceof CUBRIDBlob", bObj instanceof CUBRIDBlob);
        assertTrue("getObject clob instanceof CUBRIDClob", cObj instanceof CUBRIDClob);

        rs.close();
        sel.close();
    }

    private static void testDatabaseMetaDataColumns(Connection conn) throws Exception {
        DatabaseMetaData dbMeta = conn.getMetaData();
        // schema is "public" in CUBRID
        ResultSet cols = dbMeta.getColumns(null, null, TABLE_NAME, null);

        boolean foundBlob = false;
        boolean foundClob = false;

        while (cols.next()) {
            String colName = cols.getString("COLUMN_NAME");
            String typeName = cols.getString("TYPE_NAME");
            int dataType = cols.getInt("DATA_TYPE");

            if ("b_col".equalsIgnoreCase(colName)) {
                assertEquals("dbmeta blob TYPE_NAME", "BLOB", typeName);
                assertEquals("dbmeta blob DATA_TYPE", Types.BLOB, dataType);
                foundBlob = true;
            }
            if ("c_col".equalsIgnoreCase(colName)) {
                assertEquals("dbmeta clob TYPE_NAME", "CLOB", typeName);
                assertEquals("dbmeta clob DATA_TYPE", Types.CLOB, dataType);
                foundClob = true;
            }
        }
        cols.close();

        assertTrue("dbmeta found blob column", foundBlob);
        assertTrue("dbmeta found clob column", foundClob);
    }

    // -----------------------------------------------------------------------

    private static void setup(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute(DROP_TABLE);
            s.execute(CREATE_TABLE);
        }
        conn.commit();
    }

    private static void insertSampleRow(Connection conn) throws Exception {
        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_lob_meta(id, b_col, c_col, v_col) VALUES(?,?,?,?)");
        ins.setInt(1, 1);
        ins.setBytes(2, "sample blob".getBytes("UTF-8"));
        ins.setString(3, "sample clob");
        ins.setString(4, "plain varchar");
        ins.executeUpdate();
        conn.commit();
        ins.close();
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

    private static void assertNotNull(String label, Object v) {
        if (v == null) { System.err.println("[FAIL] " + label + ": null"); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertTrue(String label, boolean cond) {
        if (!cond) { System.err.println("[FAIL] " + label); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }
}
