import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import cubrid.jdbc.driver.CUBRIDPreparedStatement;

/**
 * APIS-1110 regression check.
 *
 * Binding a SET/MULTISET/LIST(SEQUENCE) parameter used to corrupt the request frame sent to
 * CAS: UOutputBuffer.writeCollection() built the collection payload in a throw-away
 * ByteArrayBuffer whose constructor always reserved 8 header bytes meant only for the
 * connection's top-level request buffer. Those 8 stray bytes were spliced into the real
 * request without being counted in the declared length field, so CAS could not decode the
 * desynced stream and replied CAS_ER_COMMUNICATION, closing the connection - surfacing as
 * "Communication error" even though CAS and the network were fine. Fixed by giving
 * ByteArrayBuffer a reserveHeader flag (ByteArrayBuffer(boolean)) so only the top-level
 * buffer reserves those 8 bytes.
 *
 * writeCollection() is reachable from several JDBC bind paths, all covered here in one file:
 *   1. addBatch()/executeBatch()          - the originally reported failure (CUBRID
 *                                            Migration Toolkit, 19-row batch import)
 *   2. a single (non-batch) execute()     - riskier variant: when the collection is the
 *                                            LAST argument in the request, the framing bug
 *                                            might not trip CAS's error guard and could
 *                                            silently insert corrupted content instead of
 *                                            throwing, so this checks exact content via
 *                                            CUBRID's SETEQ containment operator, not just
 *                                            "no exception"
 *   3. ResultSet.updateObject()+updateRow() (SET/LIST only - see note in
 *      testUpdatableResultSet() about a separate, unrelated MULTISET limitation)
 *
 * CUBRIDOID.setValues() (the direct OID/glo-API bind path) also reaches writeCollection()
 * but is not a common application pattern, so it is intentionally left out of this suite.
 *
 * Run against the jar built at the repository root via test/run_test.sh.
 */
public class Apis1110CollectionBind {

    private static String url;
    private static String user;
    private static String pass;
    private static boolean failed = false;

    public static void main(String[] args) throws Exception {
        url = env("CUBRID_JDBC_URL", "jdbc:cubrid:localhost:33000:demodb:dba::");
        user = env("CUBRID_JDBC_USER", "dba");
        pass = env("CUBRID_JDBC_PASSWORD", "");

        Class.forName("cubrid.jdbc.driver.CUBRIDDriver");

        testBatchInsert();
        testSingleExecute();
        testUpdatableResultSet();

        if (failed) {
            System.exit(1);
        }
    }

    /** addBatch()/executeBatch() across 5 rows - the originally reported failure. */
    private static void testBatchInsert() {
        String table = "apis_1110_batch_tbl";
        int rowCount = 5;

        Connection conn = null;
        Statement ddl = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = DriverManager.getConnection(url, user, pass);
            conn.setAutoCommit(false);

            ddl = conn.createStatement();
            ddl.executeUpdate("DROP TABLE IF EXISTS " + table);
            ddl.executeUpdate("CREATE TABLE " + table
                    + " (id VARCHAR(10),"
                    + " tags_set SET(VARCHAR(255)),"
                    + " tags_multiset MULTISET(VARCHAR(255)),"
                    + " tags_list LIST(VARCHAR(255)))");

            stmt = conn.prepareStatement("INSERT INTO " + table
                    + " (id, tags_set, tags_multiset, tags_list) VALUES (?, ?, ?, ?)");
            CUBRIDPreparedStatement cstmt = (CUBRIDPreparedStatement) stmt;

            for (int i = 0; i < rowCount; i++) {
                stmt.setString(1, "id" + i);
                cstmt.setCollection(2, new String[] { "set_a_" + i, "set_b_" + i, "set_c_" + i });
                cstmt.setCollection(3, new String[] { "mset_a_" + i, "mset_b_" + i, "mset_c_" + i });
                cstmt.setCollection(4, new String[] { "list_a_" + i, "list_b_" + i, "list_c_" + i });
                stmt.addBatch();
            }

            int[] results = stmt.executeBatch();
            conn.commit();

            if (results.length != rowCount) {
                fail("testBatchInsert", "expected " + rowCount + " batch results, got "
                        + results.length);
                return;
            }

            rs = conn.createStatement().executeQuery("SELECT count(*) FROM " + table);
            rs.next();
            int count = rs.getInt(1);
            if (count != rowCount) {
                fail("testBatchInsert", "expected " + rowCount + " rows in " + table
                        + ", found " + count);
                return;
            }

            pass("testBatchInsert", rowCount
                    + " rows inserted via SET/MULTISET/LIST-bound batch without a "
                    + "communication error");
        } catch (Exception e) {
            e.printStackTrace();
            fail("testBatchInsert", e.getMessage());
        } finally {
            closeQuietly(rs);
            closeQuietly(stmt);
            dropQuietly(ddl, table);
            closeQuietly(ddl);
            closeQuietly(conn);
        }
    }

    /**
     * Single (non-batch) execute() - the collection parameter bound last is the very last
     * argument written into the request, so a framing bug here could silently corrupt data
     * instead of throwing. Content is verified with SETEQ, not just "no exception".
     */
    private static void testSingleExecute() {
        String table = "apis_1110_single_exec_tbl";

        Connection conn = null;
        Statement ddl = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = DriverManager.getConnection(url, user, pass);
            conn.setAutoCommit(false);

            ddl = conn.createStatement();
            ddl.executeUpdate("DROP TABLE IF EXISTS " + table);
            ddl.executeUpdate("CREATE TABLE " + table
                    + " (id VARCHAR(10),"
                    + " tags_set SET(VARCHAR(255)),"
                    + " tags_multiset MULTISET(VARCHAR(255)),"
                    + " tags_list LIST(VARCHAR(255)))");

            stmt = conn.prepareStatement("INSERT INTO " + table
                    + " (id, tags_set, tags_multiset, tags_list) VALUES (?, ?, ?, ?)");
            CUBRIDPreparedStatement cstmt = (CUBRIDPreparedStatement) stmt;

            stmt.setString(1, "row0");
            cstmt.setCollection(2, new String[] { "set_a", "set_b", "set_c" });
            cstmt.setCollection(3, new String[] { "mset_a", "mset_b", "mset_c" });
            cstmt.setCollection(4, new String[] { "list_a", "list_b", "list_c" });

            int updated = stmt.executeUpdate();
            conn.commit();

            if (updated != 1) {
                fail("testSingleExecute", "expected 1 row updated, got " + updated);
                return;
            }

            rs = conn.createStatement().executeQuery(
                    "SELECT count(*) FROM " + table + " WHERE id = 'row0'"
                    + " AND tags_set SETEQ {'set_a','set_b','set_c'}"
                    + " AND tags_multiset SETEQ {'mset_a','mset_b','mset_c'}"
                    + " AND tags_list SETEQ {'list_a','list_b','list_c'}");
            rs.next();
            int count = rs.getInt(1);
            if (count != 1) {
                fail("testSingleExecute", "row content mismatch after single-execute "
                        + "collection bind (found " + count + " matching rows, expected 1) "
                        + "- possible silent data corruption");
                return;
            }

            pass("testSingleExecute", "single-execute SET/MULTISET/LIST bind round-tripped "
                    + "correctly, no communication error");
        } catch (Exception e) {
            e.printStackTrace();
            fail("testSingleExecute", e.getMessage());
        } finally {
            closeQuietly(rs);
            closeQuietly(stmt);
            dropQuietly(ddl, table);
            closeQuietly(ddl);
            closeQuietly(conn);
        }
    }

    /**
     * ResultSet.updateObject()+updateRow(). Covers SET and LIST only - updating the
     * MULTISET column via this path fails with a separate, pre-existing "Invalid data type
     * referenced" server error unrelated to the APIS-1110 framing bug (reproduces the same
     * way against both the buggy and the fixed driver; see cas_execute.c netval_to_dbval(),
     * which used to call db_make_set() instead of db_make_multiset() for MULTISET values).
     */
    private static void testUpdatableResultSet() {
        String table = "apis_1110_update_row_tbl";

        Connection conn = null;
        Statement ddl = null;
        Statement updStmt = null;
        ResultSet rs = null;
        ResultSet verifyRs = null;
        try {
            conn = DriverManager.getConnection(url, user, pass);
            conn.setAutoCommit(false);

            ddl = conn.createStatement();
            ddl.executeUpdate("DROP TABLE IF EXISTS " + table);
            // DONT_REUSE_OID: updatable result sets need OID info, which CUBRID refuses on
            // REUSE_OID (the default) classes.
            ddl.executeUpdate("CREATE TABLE " + table
                    + " (id VARCHAR(10),"
                    + " tags_set SET(VARCHAR(255)),"
                    + " tags_multiset MULTISET(VARCHAR(255)),"
                    + " tags_list LIST(VARCHAR(255))) DONT_REUSE_OID");
            ddl.executeUpdate("INSERT INTO " + table + " (id) VALUES ('row0')");
            conn.commit();

            updStmt = conn.createStatement(
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE,
                    ResultSet.CLOSE_CURSORS_AT_COMMIT);
            // "select * from table" is required for CUBRID to include OID info, which is
            // what makes the result set genuinely updatable (CUBRIDStatement silently
            // downgrades CONCUR_UPDATABLE to CONCUR_READ_ONLY otherwise).
            rs = updStmt.executeQuery("SELECT * FROM " + table + " WHERE id = 'row0'");
            if (!rs.next()) {
                fail("testUpdatableResultSet", "seed row not found");
                return;
            }

            rs.updateObject("tags_set", new String[] { "set_a", "set_b", "set_c" });
            rs.updateRow();

            rs.updateObject("tags_list", new String[] { "list_a", "list_b", "list_c" });
            rs.updateRow();

            conn.commit();

            verifyRs = conn.createStatement().executeQuery(
                    "SELECT count(*) FROM " + table + " WHERE id = 'row0'"
                    + " AND tags_set SETEQ {'set_a','set_b','set_c'}"
                    + " AND tags_list SETEQ {'list_a','list_b','list_c'}");
            verifyRs.next();
            int count = verifyRs.getInt(1);
            if (count != 1) {
                fail("testUpdatableResultSet", "row content mismatch after updateRow() "
                        + "collection bind (found " + count + " matching rows, expected 1) "
                        + "- possible silent data corruption");
                return;
            }

            pass("testUpdatableResultSet", "updateRow() SET/LIST bind round-tripped "
                    + "correctly, no communication error (MULTISET-via-updateRow is a "
                    + "separate, tracked issue)");
        } catch (Exception e) {
            e.printStackTrace();
            fail("testUpdatableResultSet", e.getMessage());
        } finally {
            closeQuietly(verifyRs);
            closeQuietly(rs);
            dropQuietly(ddl, table);
            closeQuietly(updStmt);
            closeQuietly(ddl);
            closeQuietly(conn);
        }
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static void pass(String scenario, String message) {
        System.out.println("[PASS] Apis1110CollectionBind." + scenario + ": " + message);
    }

    private static void fail(String scenario, String message) {
        System.out.println("[FAIL] Apis1110CollectionBind." + scenario + ": " + message);
        failed = true;
    }

    private static void dropQuietly(Statement ddl, String table) {
        try {
            if (ddl != null) {
                ddl.executeUpdate("DROP TABLE IF EXISTS " + table);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
