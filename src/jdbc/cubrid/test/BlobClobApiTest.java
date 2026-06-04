package cubrid.test;

import cubrid.jdbc.driver.CUBRIDBlob;
import cubrid.jdbc.driver.CUBRIDClob;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.*;

/**
 * CBRD-26197: JDBC 표준 API (createBlob/createClob, setBlob/setClob, getBlob/getClob)
 * 및 stream API 검증
 */
public class BlobClobApiTest {

    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS test_lob_api ("
                    + "  id    INTEGER PRIMARY KEY,"
                    + "  b_col BLOB,"
                    + "  c_col CLOB"
                    + ")";

    private static final String DROP_TABLE = "DROP TABLE IF EXISTS test_lob_api";

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
            testCreateBlobApi(conn);
            testCreateClobApi(conn);
            testBlobOutputStream(conn);
            testClobWriter(conn);
            testBlobGetBytes(conn);
            testClobGetSubString(conn);
            testBlobTruncate(conn);
            testClobTruncate(conn);
            testSetBlobFromBlob(conn);
            testSetClobFromClob(conn);
        } finally {
            teardown(conn);
            conn.close();
        }

        System.out.printf("%nResult: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // -----------------------------------------------------------------------

    private static void testCreateBlobApi(Connection conn) throws Exception {
        String label = "createBlobApi";
        CUBRIDBlob blob = (CUBRIDBlob) conn.createBlob();

        assertEquals(label + " initial length", 0, (int) blob.length());

        byte[] data = new byte[]{1, 2, 3, 4, 5};
        int written = blob.setBytes(1, data);
        assertEquals(label + " setBytes written", data.length, written);
        assertEquals(label + " after setBytes length", data.length, (int) blob.length());

        byte[] got = blob.getBytes(1, data.length);
        assertArrayEq(label + " getBytes", data, got);

        // partial getBytes
        byte[] partial = blob.getBytes(2, 3);
        assertArrayEq(label + " partial getBytes", new byte[]{2, 3, 4}, partial);

        blob.free();
        pass++;
        System.out.println("[PASS] " + label + " free");
    }

    private static void testCreateClobApi(Connection conn) throws Exception {
        String label = "createClobApi";
        CUBRIDClob clob = (CUBRIDClob) conn.createClob();

        assertEquals(label + " initial length", 0, (int) clob.length());

        String text = "Hello, CLOB!";
        int written = clob.setString(1, text);
        assertEquals(label + " setString written", text.length(), written);
        assertEquals(label + " after setString length", text.length(), (int) clob.length());

        String got = clob.getSubString(1, text.length());
        assertEquals(label + " getSubString", text, got);

        // partial getSubString
        String partial = clob.getSubString(8, 4);
        assertEquals(label + " partial getSubString", "CLOB", partial);

        clob.free();
        pass++;
        System.out.println("[PASS] " + label + " free");
    }

    private static void testBlobOutputStream(Connection conn) throws Exception {
        String label = "blobOutputStream";
        byte[] data = "StreamWrite".getBytes("UTF-8");

        Blob blob = conn.createBlob();
        OutputStream os = blob.setBinaryStream(1);
        os.write(data);
        os.close();

        assertEquals(label + " length via stream", data.length, (int) blob.length());
        assertArrayEq(label + " bytes via stream", data, blob.getBytes(1, (int) blob.length()));

        // persist and verify
        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_lob_api(id, b_col) VALUES(?,?)");
        ins.setInt(1, 100);
        ins.setBlob(2, blob);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col FROM test_lob_api WHERE id=?");
        sel.setInt(1, 100);
        ResultSet rs = sel.executeQuery();
        rs.next();
        assertArrayEq(label + " persisted bytes", data, rs.getBytes(1));
        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testClobWriter(Connection conn) throws Exception {
        String label = "clobWriter";
        String text = "Writer test 테스트";

        Clob clob = conn.createClob();
        Writer w = clob.setCharacterStream(1);
        w.write(text);
        w.close();

        assertEquals(label + " length via writer", text.length(), (int) clob.length());
        assertEquals(label + " subString via writer", text,
                clob.getSubString(1, (int) clob.length()));

        // persist and verify
        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_lob_api(id, c_col) VALUES(?,?)");
        ins.setInt(1, 101);
        ins.setClob(2, clob);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT c_col FROM test_lob_api WHERE id=?");
        sel.setInt(1, 101);
        ResultSet rs = sel.executeQuery();
        rs.next();
        assertEquals(label + " persisted string", text, rs.getString(1));
        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testBlobGetBytes(Connection conn) throws Exception {
        String label = "blobGetBytes";
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) data[i] = (byte) i;

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_lob_api(id, b_col) VALUES(?,?)");
        ins.setInt(1, 200);
        ins.setBytes(2, data);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col FROM test_lob_api WHERE id=?");
        sel.setInt(1, 200);
        ResultSet rs = sel.executeQuery();
        rs.next();

        Blob blob = rs.getBlob(1);
        assertNotNull(label + " blob", blob);
        assertEquals(label + " length", 256, (int) blob.length());

        // getBytes from middle
        byte[] mid = blob.getBytes(101, 50);
        assertEquals(label + " mid length", 50, mid.length);
        assertEquals(label + " mid[0]", (byte) 100, mid[0]);

        // getBinaryStream
        InputStream is = blob.getBinaryStream(1, 10);
        byte[] first10 = is.readAllBytes();
        is.close();
        assertEquals(label + " stream length", 10, first10.length);
        assertArrayEq(label + " stream first10", java.util.Arrays.copyOf(data, 10), first10);

        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testClobGetSubString(Connection conn) throws Exception {
        String label = "clobGetSubString";
        String text = "ABCDEFGHIJ";

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_lob_api(id, c_col) VALUES(?,?)");
        ins.setInt(1, 201);
        ins.setString(2, text);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT c_col FROM test_lob_api WHERE id=?");
        sel.setInt(1, 201);
        ResultSet rs = sel.executeQuery();
        rs.next();

        Clob clob = rs.getClob(1);
        assertNotNull(label + " clob", clob);
        assertEquals(label + " length", text.length(), (int) clob.length());
        assertEquals(label + " full", text, clob.getSubString(1, text.length()));
        assertEquals(label + " sub 3-5", "CDE", clob.getSubString(3, 3));

        // Reader
        Reader reader = clob.getCharacterStream(1, 5);
        char[] cbuf = new char[10];
        int n = reader.read(cbuf);
        reader.close();
        assertEquals(label + " reader chars", "ABCDE", new String(cbuf, 0, n));

        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testBlobTruncate(Connection conn) throws Exception {
        String label = "blobTruncate";
        Blob blob = conn.createBlob();
        blob.setBytes(1, new byte[]{10, 20, 30, 40, 50});
        blob.truncate(3);
        assertEquals(label + " truncated length", 3, (int) blob.length());
        assertArrayEq(label + " truncated bytes", new byte[]{10, 20, 30},
                blob.getBytes(1, (int) blob.length()));
    }

    private static void testClobTruncate(Connection conn) throws Exception {
        String label = "clobTruncate";
        Clob clob = conn.createClob();
        clob.setString(1, "HelloWorld");
        clob.truncate(5);
        assertEquals(label + " truncated length", 5, (int) clob.length());
        assertEquals(label + " truncated content", "Hello",
                clob.getSubString(1, (int) clob.length()));
    }

    private static void testSetBlobFromBlob(Connection conn) throws Exception {
        String label = "setBlobFromBlob";
        byte[] data = "BlobObject".getBytes("UTF-8");
        CUBRIDBlob blob = (CUBRIDBlob) conn.createBlob();
        blob.setBytes(1, data);

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_lob_api(id, b_col) VALUES(?,?)");
        ins.setInt(1, 300);
        ins.setBlob(2, (Blob) blob);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT b_col FROM test_lob_api WHERE id=?");
        sel.setInt(1, 300);
        ResultSet rs = sel.executeQuery();
        rs.next();
        assertArrayEq(label + " setBlob", data, rs.getBytes(1));
        rs.close();
        sel.close();
        cleanup(conn);
    }

    private static void testSetClobFromClob(Connection conn) throws Exception {
        String label = "setClobFromClob";
        String text = "ClobObject!";
        CUBRIDClob clob = (CUBRIDClob) conn.createClob();
        clob.setString(1, text);

        PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO test_lob_api(id, c_col) VALUES(?,?)");
        ins.setInt(1, 301);
        ins.setClob(2, (Clob) clob);
        ins.executeUpdate();
        conn.commit();
        ins.close();

        PreparedStatement sel = conn.prepareStatement(
                "SELECT c_col FROM test_lob_api WHERE id=?");
        sel.setInt(1, 301);
        ResultSet rs = sel.executeQuery();
        rs.next();
        assertEquals(label + " setClob", text, rs.getString(1));
        rs.close();
        sel.close();
        cleanup(conn);
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

    private static void cleanup(Connection conn) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute("DELETE FROM test_lob_api");
        }
        conn.commit();
    }

    private static void assertEquals(String label, int e, int a) {
        if (e != a) { System.err.println("[FAIL] " + label + ": exp=" + e + " act=" + a); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertEquals(String label, String e, String a) {
        if (!e.equals(a)) { System.err.println("[FAIL] " + label + ": exp='" + e + "' act='" + a + "'"); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertEquals(String label, byte e, byte a) {
        if (e != a) { System.err.println("[FAIL] " + label + ": exp=" + e + " act=" + a); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertNotNull(String label, Object v) {
        if (v == null) { System.err.println("[FAIL] " + label + ": null"); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertArrayEq(String label, byte[] e, byte[] a) {
        if (!java.util.Arrays.equals(e, a)) {
            System.err.println("[FAIL] " + label + ": byte arrays differ");
            fail++;
        } else {
            System.out.println("[PASS] " + label);
            pass++;
        }
    }
}
