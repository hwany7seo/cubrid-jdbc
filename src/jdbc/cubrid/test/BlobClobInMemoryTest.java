package cubrid.test;

import cubrid.jdbc.driver.CUBRIDInternalBlob;
import cubrid.jdbc.driver.CUBRIDInternalClob;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.sql.*;
import java.util.Arrays;

/**
 * CBRD-26197: CUBRIDInternalBlob / CUBRIDInternalClob 의 in-memory 동작 (서버 없이 로컬 객체 단위) 검증.
 *
 * <p>Connection 없이 직접 객체를 생성하여 기본 API 계약을 확인한다.
 * UUType 상수 값이 올바르게 매핑되었는지도 함께 검증한다.
 */
public class BlobClobInMemoryTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        testUUTypeConstants();
        testCUBRIDInternalBlobCreation();
        testCUBRIDInternalBlobSetAndGet();
        testCUBRIDInternalBlobStream();
        testCUBRIDInternalBlobTruncate();
        testCUBRIDInternalBlobFree();
        testCUBRIDInternalBlobPartialGet();
        testCUBRIDInternalClobCreation();
        testCUBRIDInternalClobSetAndGet();
        testCUBRIDInternalClobSubString();
        testCUBRIDInternalClobReader();
        testCUBRIDInternalClobTruncate();
        testCUBRIDInternalClobFree();
        testCUBRIDInternalClobAppend();
        testCUBRIDInternalBlobEquality();
        testCUBRIDInternalClobEquality();

        System.out.printf("%nResult: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    // -----------------------------------------------------------------------

    private static void testUUTypeConstants() {
        String label = "UUTypeConstants";
        assertEquals(label + " BFILE=23",  23, cubrid.jdbc.jci.UUType.U_TYPE_BFILE);
        assertEquals(label + " CFILE=24",  24, cubrid.jdbc.jci.UUType.U_TYPE_CFILE);
        assertEquals(label + " BLOB=25",   25, cubrid.jdbc.jci.UUType.U_TYPE_BLOB);
        assertEquals(label + " CLOB=26",   26, cubrid.jdbc.jci.UUType.U_TYPE_CLOB);
        assertEquals(label + " ENUM=27",   27, cubrid.jdbc.jci.UUType.U_TYPE_ENUM);
        assertEquals(label + " USHORT=28", 28, cubrid.jdbc.jci.UUType.U_TYPE_USHORT);
        assertEquals(label + " UINT=29",   29, cubrid.jdbc.jci.UUType.U_TYPE_UINT);
        assertEquals(label + " UBIGINT=30",30, cubrid.jdbc.jci.UUType.U_TYPE_UBIGINT);
        assertEquals(label + " TSTZ=31",   31, cubrid.jdbc.jci.UUType.U_TYPE_TIMESTAMPTZ);
        assertEquals(label + " TSLTZ=32",  32, cubrid.jdbc.jci.UUType.U_TYPE_TIMESTAMPLTZ);
        assertEquals(label + " DTTZ=33",   33, cubrid.jdbc.jci.UUType.U_TYPE_DATETIMETZ);
        assertEquals(label + " DTLTZ=34",  34, cubrid.jdbc.jci.UUType.U_TYPE_DATETIMELTZ);
        assertEquals(label + " TIMETZ=35", 35, cubrid.jdbc.jci.UUType.U_TYPE_TIMETZ);
        assertEquals(label + " JSON=36",   36, cubrid.jdbc.jci.UUType.U_TYPE_JSON);
        assertEquals(label + " U_TYPE_MAX=36", 36, cubrid.jdbc.jci.UUType.U_TYPE_MAX);
    }

    private static void testCUBRIDInternalBlobCreation() throws Exception {
        String label = "CUBRIDInternalBlob creation";

        CUBRIDInternalBlob empty = new CUBRIDInternalBlob();
        assertEquals(label + " empty length", 0, (int) empty.length());
        assertNotNull(label + " getData", empty.getData());
        assertEquals(label + " getData length", 0, empty.getData().length);

        byte[] data = {1, 2, 3};
        CUBRIDInternalBlob fromData = new CUBRIDInternalBlob(data);
        assertEquals(label + " from data length", 3, (int) fromData.length());
        assertArrayEq(label + " getData matches", data, fromData.getData());

        CUBRIDInternalBlob fromNull = new CUBRIDInternalBlob(null);
        assertEquals(label + " from null length", 0, (int) fromNull.length());
    }

    private static void testCUBRIDInternalBlobSetAndGet() throws Exception {
        String label = "CUBRIDInternalBlob setBytes/getBytes";

        CUBRIDInternalBlob blob = new CUBRIDInternalBlob();
        byte[] data = "Hello World".getBytes("UTF-8");

        int written = blob.setBytes(1, data);
        assertEquals(label + " written count", data.length, written);
        assertEquals(label + " length after set", data.length, (int) blob.length());

        byte[] got = blob.getBytes(1, data.length);
        assertArrayEq(label + " full getBytes", data, got);

        // pos > 1
        byte[] partial = blob.getBytes(7, 5);
        assertArrayEq(label + " partial getBytes", "World".getBytes("UTF-8"), partial);

        // length exceeds: clamp
        byte[] clamped = blob.getBytes(9, 100);
        assertEquals(label + " clamped length", 3, clamped.length);

        // overwrite part
        blob.setBytes(1, "Bye!!".getBytes("UTF-8"));
        byte[] overwritten = blob.getBytes(1, 5);
        assertArrayEq(label + " overwrite", "Bye!!".getBytes("UTF-8"), overwritten);
        // rest is unchanged ("Hello World" positions 6..11 = " World")
        byte[] rest = blob.getBytes(6, 6);
        assertArrayEq(label + " rest unchanged", " World".getBytes("UTF-8"), rest);
    }

    private static void testCUBRIDInternalBlobStream() throws Exception {
        String label = "CUBRIDInternalBlob stream";

        CUBRIDInternalBlob blob = new CUBRIDInternalBlob();
        byte[] data = {10, 20, 30, 40, 50};
        try (OutputStream os = blob.setBinaryStream(1)) {
            os.write(data);
        }
        assertEquals(label + " length after stream write", data.length, (int) blob.length());
        assertArrayEq(label + " bytes after stream write", data, blob.getData());

        // getBinaryStream full
        try (InputStream is = blob.getBinaryStream()) {
            byte[] read = is.readAllBytes();
            assertArrayEq(label + " getBinaryStream full", data, read);
        }

        // getBinaryStream partial
        try (InputStream is = blob.getBinaryStream(2, 3)) {
            byte[] read = is.readAllBytes();
            assertArrayEq(label + " getBinaryStream partial", new byte[]{20, 30, 40}, read);
        }
    }

    private static void testCUBRIDInternalBlobTruncate() throws Exception {
        String label = "CUBRIDInternalBlob truncate";

        CUBRIDInternalBlob blob = new CUBRIDInternalBlob(new byte[]{1, 2, 3, 4, 5});
        blob.truncate(3);
        assertEquals(label + " length after truncate", 3, (int) blob.length());
        assertArrayEq(label + " data after truncate", new byte[]{1, 2, 3}, blob.getData());

        // truncate to 0
        blob.truncate(0);
        assertEquals(label + " length after truncate 0", 0, (int) blob.length());
    }

    private static void testCUBRIDInternalBlobFree() throws Exception {
        String label = "CUBRIDInternalBlob free";

        CUBRIDInternalBlob blob = new CUBRIDInternalBlob(new byte[]{9, 8, 7});
        blob.free();

        try {
            blob.length();
            fail_test(label + " should throw after free");
        } catch (SQLException e) {
            pass_test(label + " throws after free");
        }
    }

    private static void testCUBRIDInternalBlobPartialGet() throws Exception {
        String label = "CUBRIDInternalBlob partial boundary";

        byte[] data = new byte[100];
        for (int i = 0; i < 100; i++) data[i] = (byte) i;
        CUBRIDInternalBlob blob = new CUBRIDInternalBlob(data);

        // getBytes at boundary
        byte[] last5 = blob.getBytes(96, 10); // clamp to 5
        assertEquals(label + " clamped to 5", 5, last5.length);
        assertEquals(label + " last byte", (byte) 99, last5[4]);

        // getBytes past end returns empty
        byte[] empty = blob.getBytes(101, 5);
        assertEquals(label + " past end length", 0, empty.length);
    }

    // -----------------------------------------------------------------------
    // CUBRIDInternalClob tests

    private static void testCUBRIDInternalClobCreation() throws Exception {
        String label = "CUBRIDInternalClob creation";

        CUBRIDInternalClob empty = new CUBRIDInternalClob("UTF-8");
        assertEquals(label + " empty length", 0, (int) empty.length());
        assertEquals(label + " getData empty", "", empty.getData());

        CUBRIDInternalClob fromStr = new CUBRIDInternalClob("Hello CLOB", "UTF-8");
        assertEquals(label + " from str length", 10, (int) fromStr.length());
        assertEquals(label + " getData matches", "Hello CLOB", fromStr.getData());

        CUBRIDInternalClob fromNull = new CUBRIDInternalClob(null, "UTF-8");
        assertEquals(label + " from null length", 0, (int) fromNull.length());
    }

    private static void testCUBRIDInternalClobSetAndGet() throws Exception {
        String label = "CUBRIDInternalClob setString/getSubString";

        CUBRIDInternalClob clob = new CUBRIDInternalClob("UTF-8");
        String text = "Hello World 한글";

        int written = clob.setString(1, text);
        assertEquals(label + " written count", text.length(), written);
        assertEquals(label + " length after set", text.length(), (int) clob.length());

        String got = clob.getSubString(1, text.length());
        assertEquals(label + " full getSubString", text, got);

        // partial
        String partial = clob.getSubString(7, 5);
        assertEquals(label + " partial", "World", partial);

        // length exceeds: clamp
        String clamped = clob.getSubString(12, 100);
        assertTrue(label + " clamped not null", clamped != null);
    }

    private static void testCUBRIDInternalClobSubString(String... ignored) throws Exception {
        String label = "CUBRIDInternalClob getSubString boundary";

        CUBRIDInternalClob clob = new CUBRIDInternalClob("ABCDE", "UTF-8");

        assertEquals(label + " sub from 1", "ABCDE", clob.getSubString(1, 5));
        assertEquals(label + " sub from 3", "CDE",   clob.getSubString(3, 3));
        assertEquals(label + " sub empty len", "",    clob.getSubString(1, 0));
        assertEquals(label + " sub past end", "",     clob.getSubString(6, 5));
    }

    private static void testCUBRIDInternalClobSubString() throws Exception {
        testCUBRIDInternalClobSubString(new String[0]);
    }

    private static void testCUBRIDInternalClobReader() throws Exception {
        String label = "CUBRIDInternalClob Reader";

        CUBRIDInternalClob clob = new CUBRIDInternalClob("ReaderTest", "UTF-8");

        try (Reader r = clob.getCharacterStream()) {
            char[] cbuf = new char[20];
            int n = r.read(cbuf);
            assertEquals(label + " reader chars", "ReaderTest", new String(cbuf, 0, n));
        }

        // partial reader
        try (Reader r = clob.getCharacterStream(7, 4)) {
            char[] cbuf = new char[10];
            int n = r.read(cbuf);
            assertEquals(label + " partial reader", "Test", new String(cbuf, 0, n));
        }
    }

    private static void testCUBRIDInternalClobTruncate() throws Exception {
        String label = "CUBRIDInternalClob truncate";

        CUBRIDInternalClob clob = new CUBRIDInternalClob("Hello World", "UTF-8");
        clob.truncate(5);
        assertEquals(label + " length", 5, (int) clob.length());
        assertEquals(label + " content", "Hello", clob.getSubString(1, 5));

        clob.truncate(0);
        assertEquals(label + " truncate 0", 0, (int) clob.length());
    }

    private static void testCUBRIDInternalClobFree() throws Exception {
        String label = "CUBRIDInternalClob free";

        CUBRIDInternalClob clob = new CUBRIDInternalClob("test", "UTF-8");
        clob.free();

        try {
            clob.length();
            fail_test(label + " should throw after free");
        } catch (SQLException e) {
            pass_test(label + " throws after free");
        }
    }

    private static void testCUBRIDInternalClobAppend() throws Exception {
        String label = "CUBRIDInternalClob append";

        CUBRIDInternalClob clob = new CUBRIDInternalClob("Hello", "UTF-8");
        clob.setString(6, " World");
        assertEquals(label + " appended length", 11, (int) clob.length());
        assertEquals(label + " appended content", "Hello World", clob.getData());
    }

    private static void testCUBRIDInternalBlobEquality() throws Exception {
        String label = "CUBRIDInternalBlob equals";

        byte[] data = {1, 2, 3};
        CUBRIDInternalBlob b1 = new CUBRIDInternalBlob(data);
        CUBRIDInternalBlob b2 = new CUBRIDInternalBlob(data.clone());
        CUBRIDInternalBlob b3 = new CUBRIDInternalBlob(new byte[]{1, 2, 4});

        assertTrue(label + " equal", b1.equals(b2));
        assertTrue(label + " not equal", !b1.equals(b3));
        assertTrue(label + " not equal null", !b1.equals(null));
    }

    private static void testCUBRIDInternalClobEquality() throws Exception {
        String label = "CUBRIDInternalClob equals";

        CUBRIDInternalClob c1 = new CUBRIDInternalClob("Hello", "UTF-8");
        CUBRIDInternalClob c2 = new CUBRIDInternalClob("Hello", "UTF-8");
        CUBRIDInternalClob c3 = new CUBRIDInternalClob("World", "UTF-8");

        assertTrue(label + " equal", c1.equals(c2));
        assertTrue(label + " not equal", !c1.equals(c3));
        assertTrue(label + " not equal null", !c1.equals(null));
    }

    // -----------------------------------------------------------------------

    private static void assertEquals(String label, int e, int a) {
        if (e != a) { System.err.println("[FAIL] " + label + ": exp=" + e + " act=" + a); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertEquals(String label, String e, String a) {
        boolean ok = (e == null && a == null) || (e != null && e.equals(a));
        if (!ok) { System.err.println("[FAIL] " + label + ": exp='" + e + "' act='" + a + "'"); fail++; }
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

    private static void assertTrue(String label, boolean cond) {
        if (!cond) { System.err.println("[FAIL] " + label); fail++; }
        else { System.out.println("[PASS] " + label); pass++; }
    }

    private static void assertArrayEq(String label, byte[] e, byte[] a) {
        if (!Arrays.equals(e, a)) {
            System.err.println("[FAIL] " + label + ": differ exp=" + Arrays.toString(e)
                    + " act=" + (a == null ? "null" : Arrays.toString(a)));
            fail++;
        } else {
            System.out.println("[PASS] " + label);
            pass++;
        }
    }

    private static void fail_test(String label) {
        System.err.println("[FAIL] " + label);
        fail++;
    }

    private static void pass_test(String label) {
        System.out.println("[PASS] " + label);
        pass++;
    }
}
