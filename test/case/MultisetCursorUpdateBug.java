import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Separate bug (NOT APIS-1110) - discovered while adding APIS-1110 regression coverage for
 * ResultSet.updateRow().
 *
 * Updating a MULTISET-typed column via ResultSet.updateObject()+updateRow() always fails
 * server-side with "Invalid data type referenced", while the exact same code path works
 * fine for SET and LIST(=SEQUENCE) columns. It reproduces identically against both the
 * APIS-1110-fixed driver and the pre-fix driver, so it is unrelated to the
 * UOutputBuffer.writeCollection() framing bug - this is a genuine server-side (CAS/engine)
 * defect.
 *
 * Root cause (cubrid engine, src/broker/cas_execute.c, netval_to_dbval(), the
 * CCI_U_TYPE_SET / CCI_U_TYPE_MULTISET / CCI_U_TYPE_SEQUENCE case, around line 4838-4845):
 *
 *     if (type == CCI_U_TYPE_SEQUENCE)
 *       {
 *         err_code = db_make_sequence (&db_val, seq);
 *       }
 *     else
 *       {
 *         err_code = db_make_set (&db_val, set);      // <-- always db_make_set(), even
 *       }                                              //     when type == CCI_U_TYPE_MULTISET
 *
 * The DB_SET itself is built correctly as a multiset (db_set_create_multi() a few lines
 * above, keyed off `type == CCI_U_TYPE_MULTISET`), but the DB_VALUE wrapping it is always
 * stamped DB_TYPE_SET via db_make_set() - db_make_multiset() (a real, widely used engine
 * API - see e.g. object_primitive.c) is never called. ux_cursor_update() then calls
 * db_put(obj_p, attr_name, attr_val) with a DB_VALUE typed SET against an attribute whose
 * real domain is MULTISET; the domain mismatch is what surfaces to the JDBC client as
 * "Invalid data type referenced" (ER_QPROC_INVALID_DATATYPE).
 *
 * This case documents/reproduces the bug rather than asserting a fix - it reports
 * [BUG REPRODUCED] while the defect is present, and [FIXED] if a future engine fix (calling
 * db_make_multiset() for the CCI_U_TYPE_MULTISET case) makes it start working, so it can
 * also serve as the regression check once that's fixed.
 *
 * Run against the jar built at the repository root via test/run_test.sh.
 */
public class MultisetCursorUpdateBug {

    private static final String TABLE = "multiset_cursor_update_bug_tbl";

    public static void main(String[] args) {
        String url = env("CUBRID_JDBC_URL", "jdbc:cubrid:localhost:33000:demodb:dba::");
        String user = env("CUBRID_JDBC_USER", "dba");
        String pass = env("CUBRID_JDBC_PASSWORD", "");

        Connection conn = null;
        Statement ddl = null;
        Statement updStmt = null;
        ResultSet rs = null;
        try {
            Class.forName("cubrid.jdbc.driver.CUBRIDDriver");

            conn = DriverManager.getConnection(url, user, pass);
            conn.setAutoCommit(false);

            ddl = conn.createStatement();
            ddl.executeUpdate("DROP TABLE IF EXISTS " + TABLE);
            // DONT_REUSE_OID: updatable result sets need OID info, which CUBRID refuses on
            // REUSE_OID (the default) classes.
            ddl.executeUpdate("CREATE TABLE " + TABLE
                    + " (id VARCHAR(10), tags MULTISET(VARCHAR(255))) DONT_REUSE_OID");
            ddl.executeUpdate("INSERT INTO " + TABLE + " (id) VALUES ('row0')");
            conn.commit();

            updStmt = conn.createStatement(
                    ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE,
                    ResultSet.CLOSE_CURSORS_AT_COMMIT);
            // "select * from table" is required for CUBRID to include OID info, which is
            // what makes the result set genuinely updatable.
            rs = updStmt.executeQuery("SELECT * FROM " + TABLE + " WHERE id = 'row0'");
            if (!rs.next()) {
                fail("seed row not found");
                return;
            }

            rs.updateObject("tags", new String[] { "a", "b", "c" });
            rs.updateRow();
            conn.commit();

            System.out.println("[FIXED] MultisetCursorUpdateBug: MULTISET column update via "
                    + "ResultSet.updateRow() now succeeds - the netval_to_dbval() "
                    + "db_make_set()/db_make_multiset() defect in cas_execute.c appears to be "
                    + "fixed. This test can be converted into a normal pass/fail regression "
                    + "check.");
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("Invalid data type referenced")) {
                System.out.println("[BUG REPRODUCED] MultisetCursorUpdateBug: "
                        + "MULTISET column update via ResultSet.updateRow() still fails with "
                        + "\"Invalid data type referenced\" - see cas_execute.c netval_to_dbval() "
                        + "(CCI_U_TYPE_MULTISET calls db_make_set() instead of "
                        + "db_make_multiset()). This is expected until the engine is fixed.");
            } else {
                e.printStackTrace();
                fail("unexpected error: " + e.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        } finally {
            closeQuietly(rs);
            closeQuietly(updStmt);
            try {
                if (ddl != null) {
                    ddl.executeUpdate("DROP TABLE IF EXISTS " + TABLE);
                }
            } catch (Exception ignore) {
                // best effort cleanup
            }
            closeQuietly(ddl);
            closeQuietly(conn);
        }
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static void fail(String message) {
        System.out.println("[FAIL] MultisetCursorUpdateBug: " + message);
        System.exit(1);
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
