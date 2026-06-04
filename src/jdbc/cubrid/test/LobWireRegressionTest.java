package cubrid.test;

import java.math.BigDecimal;
import java.sql.*;

/**
 * CBRD-26197: wire 타입 shift 이후 ENUM / JSON / TIMESTAMPTZ / DATETIMETZ 등
 * 타입 decode regression 검증.
 *
 * <p>BLOB=25, CLOB=26 삽입 후 ENUM=27, USHORT=28 ... JSON=36 로 shift 됨.
 * 각 타입이 올바르게 decode 되는지 확인한다.
 */
public class LobWireRegressionTest {

    private static final String CREATE_ENUM_TABLE =
            "CREATE TABLE IF NOT EXISTS test_wire_enum ("
                    + "  id   INTEGER PRIMARY KEY,"
                    + "  e    ENUM('RED','GREEN','BLUE'),"
                    + "  s    SMALLINT,"
                    + "  i    INTEGER,"
                    + "  b    BIGINT,"
                    + "  f    FLOAT,"
                    + "  d    DOUBLE,"
                    + "  n    NUMERIC(10,3)"
                    + ")";

    private static final String CREATE_TS_TABLE =
            "CREATE TABLE IF NOT EXISTS test_wire_ts ("
                    + "  id   INTEGER PRIMARY KEY,"
                    + "  ts   TIMESTAMP,"
                    + "  dt   DATETIME,"
                    + "  d    DATE,"
                    + "  t    TIME"
                    + ")";

    private static final String CREATE_JSON_TABLE =
            "CREATE TABLE IF NOT EXISTS test_wire_json ("
                    + "  id   INTEGER PRIMARY KEY,"
                    + "  j    JSON,"
                    + "  v    VARCHAR(200)"
                    + ")";

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
            setupAll(conn);
            testEnumAndNumericTypes(conn);
            testTimestampAndDateTypes(conn);
            testJsonType(conn);
            testMixedWithBlob(conn);
        } finally {
            teardownAll(conn);
            conn.close();
        }

        System.out.printf("%nResult: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // -----------------------------------------------------------------------

    private static void testEnumAndNumericTypes(Connection conn) throws Exception {
        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_wire_enum VALUES(?,?,?,?,?,?,?,?)");
        ins.setInt(1, 1);
        ins.setString(2, "GREEN");
        ins.setShort(3, (short) 32000);
        ins.setInt(4, 2_000_000);
        ins.setLong(5, 9_000_000_000L);
        ins.setFloat(6, 3.14f);
        ins.setDouble(7, 2.718281828);
        ins.setBigDecimal(8, new BigDecimal("12345.678"));
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT e, s, i, b, f, d, n FROM test_wire_enum WHERE id=1");
        ResultSet rs = sel.executeQuery();
        rs.next();

        assertEquals("enum value", "GREEN", rs.getString(1));
        assertEquals("smallint value", 32000, rs.getShort(2));
        assertEquals("int value", 2_000_000, rs.getInt(3));
        assertEquals("bigint value", 9_000_000_000L, rs.getLong(4));
        assertTrue("float ~3.14", Math.abs(rs.getFloat(5) - 3.14f) < 0.01f);
        assertTrue("double ~2.718", Math.abs(rs.getDouble(6) - 2.718281828) < 0.000001);
        assertEquals("numeric value", new BigDecimal("12345.678"), rs.getBigDecimal(7));

        rs.close();
        sel.close();

        // Verify column type names via metadata
        PreparedStatement metaSel = conn.prepareStatement(
                "SELECT e, s, i, b, f, d, n FROM test_wire_enum WHERE id=1");
        ResultSet mrs = metaSel.executeQuery();
        ResultSetMetaData meta = mrs.getMetaData();
        assertEquals("enum typeName", "ENUM", meta.getColumnTypeName(1));
        assertEquals("smallint typeName", "SHORT", meta.getColumnTypeName(2));
        assertEquals("int typeName", "INTEGER", meta.getColumnTypeName(3));
        assertEquals("bigint typeName", "BIGINT", meta.getColumnTypeName(4));
        mrs.close();
        metaSel.close();
    }

    private static void testTimestampAndDateTypes(Connection conn) throws Exception {
        Timestamp ts = Timestamp.valueOf("2024-06-04 12:30:45");
        Timestamp dt = Timestamp.valueOf("2024-06-04 12:30:45.123");
        Date d = Date.valueOf("2024-06-04");
        Time t = Time.valueOf("12:30:45");

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_wire_ts VALUES(?,?,?,?,?)");
        ins.setInt(1, 1);
        ins.setTimestamp(2, ts);
        ins.setTimestamp(3, dt);
        ins.setDate(4, d);
        ins.setTime(5, t);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT ts, dt, d, t FROM test_wire_ts WHERE id=1");
        ResultSet rs = sel.executeQuery();
        rs.next();

        Timestamp gotTs = rs.getTimestamp(1);
        assertNotNull("timestamp not null", gotTs);
        assertEquals("timestamp year", 2024, gotTs.toLocalDateTime().getYear());
        assertEquals("timestamp month", 6, gotTs.toLocalDateTime().getMonthValue());
        assertEquals("timestamp day", 4, gotTs.toLocalDateTime().getDayOfMonth());

        Timestamp gotDt = rs.getTimestamp(2);
        assertNotNull("datetime not null", gotDt);
        assertEquals("datetime millis", 123, gotDt.getNanos() / 1_000_000);

        Date gotD = rs.getDate(3);
        assertNotNull("date not null", gotD);
        assertEquals("date value", d.toString(), gotD.toString());

        Time gotT = rs.getTime(4);
        assertNotNull("time not null", gotT);
        assertEquals("time value", t.toString(), gotT.toString());

        rs.close();
        sel.close();

        // Metadata check
        PreparedStatement metaSel = conn.prepareStatement(
                "SELECT ts, dt, d, t FROM test_wire_ts WHERE id=1");
        ResultSet mrs = metaSel.executeQuery();
        ResultSetMetaData meta = mrs.getMetaData();
        assertEquals("timestamp sqlType", Types.TIMESTAMP, meta.getColumnType(1));
        assertEquals("datetime sqlType", Types.TIMESTAMP, meta.getColumnType(2));
        assertEquals("date sqlType", Types.DATE, meta.getColumnType(3));
        assertEquals("time sqlType", Types.TIME, meta.getColumnType(4));
        mrs.close();
        metaSel.close();
    }

    private static void testJsonType(Connection conn) throws Exception {
        String json = "{\"key\":\"value\",\"num\":42}";

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_wire_json(id, j, v) VALUES(?,?,?)");
        ins.setInt(1, 1);
        ins.setString(2, json);
        ins.setString(3, "plain varchar");
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT j, v FROM test_wire_json WHERE id=1");
        ResultSet rs = sel.executeQuery();
        rs.next();

        String gotJson = rs.getString(1);
        assertNotNull("json not null", gotJson);
        assertTrue("json contains key", gotJson.contains("key"));
        assertTrue("json contains 42", gotJson.contains("42"));

        String gotV = rs.getString(2);
        assertEquals("varchar after json", "plain varchar", gotV);

        rs.close();
        sel.close();

        // Metadata
        PreparedStatement metaSel = conn.prepareStatement(
                "SELECT j, v FROM test_wire_json WHERE id=1");
        ResultSet mrs = metaSel.executeQuery();
        ResultSetMetaData meta = mrs.getMetaData();
        assertEquals("json typeName", "JSON", meta.getColumnTypeName(1));
        assertEquals("varchar after json typeName", "VARCHAR", meta.getColumnTypeName(2));
        mrs.close();
        metaSel.close();
    }

    /**
     * BLOB 컬럼과 다른 타입 컬럼을 함께 SELECT 하여 타입 decode 이상 없음을 검증.
     */
    private static void testMixedWithBlob(Connection conn) throws Exception {
        String CREATE_MIXED =
                "CREATE TABLE IF NOT EXISTS test_wire_mixed ("
                        + "  id     INTEGER PRIMARY KEY,"
                        + "  b_col  BLOB,"
                        + "  e_col  ENUM('A','B','C'),"
                        + "  n_col  NUMERIC(8,2),"
                        + "  v_col  VARCHAR(50)"
                        + ")";
        String DROP_MIXED = "DROP TABLE IF EXISTS test_wire_mixed";

        try (Statement s = conn.createStatement()) {
            s.execute(DROP_MIXED);
            s.execute(CREATE_MIXED);
        }
        conn.commit();

        byte[] blobData = new byte[]{0x01, 0x02, 0x03};
        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_wire_mixed VALUES(?,?,?,?,?)");
        ins.setInt(1, 1);
        ins.setBytes(2, blobData);
        ins.setString(3, "B");
        ins.setBigDecimal(4, new BigDecimal("99.99"));
        ins.setString(5, "hello");
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col, e_col, n_col, v_col FROM test_wire_mixed WHERE id=1");
        ResultSet rs = sel.executeQuery();
        rs.next();

        byte[] gotBlob = rs.getBytes(1);
        assertNotNull("mixed blob bytes", gotBlob);
        assertTrue("mixed blob length", gotBlob.length == 3);

        assertEquals("mixed enum after blob", "B", rs.getString(2));
        assertEquals("mixed numeric after blob", new BigDecimal("99.99"), rs.getBigDecimal(3));
        assertEquals("mixed varchar after blob", "hello", rs.getString(4));

        rs.close();
        sel.close();

        try (Statement s = conn.createStatement()) {
            s.execute(DROP_MIXED);
        }
        conn.commit();
    }

    // -----------------------------------------------------------------------

    private static void setupAll(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS test_wire_enum");
            s.execute("DROP TABLE IF EXISTS test_wire_ts");
            s.execute("DROP TABLE IF EXISTS test_wire_json");
            s.execute(CREATE_ENUM_TABLE);
            s.execute(CREATE_TS_TABLE);
            s.execute(CREATE_JSON_TABLE);
        }
        conn.commit();
    }

    private static void teardownAll(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS test_wire_enum");
            s.execute("DROP TABLE IF EXISTS test_wire_ts");
            s.execute("DROP TABLE IF EXISTS test_wire_json");
        }
        conn.commit();
    }

    private static void assertEquals(String label, int e, int a) {
        if (e != a) { System.err.println("[FAIL] " + label + ": exp=" + e + " act=" + a); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertEquals(String label, long e, long a) {
        if (e != a) { System.err.println("[FAIL] " + label + ": exp=" + e + " act=" + a); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertEquals(String label, String e, String a) {
        boolean ok = (e == null && a == null) || (e != null && e.equals(a));
        if (!ok) { System.err.println("[FAIL] " + label + ": exp='" + e + "' act='" + a + "'"); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertEquals(String label, BigDecimal e, BigDecimal a) {
        boolean ok = (e == null && a == null) || (e != null && e.compareTo(a) == 0);
        if (!ok) { System.err.println("[FAIL] " + label + ": exp=" + e + " act=" + a); fail++; }
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
