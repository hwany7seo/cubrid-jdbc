package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOIDImpl;

public class ExecuteQueryShow {
    private static final String URL = "jdbc:CUBRID:192.168.2.32:33000:demodb:::";

    public static void main(String arg[]) throws Exception {
        Connection conn = null;
        ResultSet rs = null;
        Statement stmt = null;
        try {
            Properties prop = new Properties();
            prop.put("user", "dba");
            prop.put("password", "");
            //prop.put("validSql", "select 1|select 2");
            CUBRIDDriver cubridDriver = new CUBRIDDriver();
            conn = cubridDriver.connect(URL, prop);
            runQuery(conn);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void runQuery(Connection conn) {
        ResultSet rs = null;
        Statement stmt = null;
        String sql = "SHOW CREATE VIEW aaaa";
        // String sql = "select target_class from db_trigger";
//        String sql =
//                "SELECT unique_name, "
//                        + "(SELECT class_name FROM db_class WHERE class_name = db_trigger.target_class) as target_class_name "
//                        + "FROM db_trigger";
//        String sql = "SELECT t.*, c.target_class_name"
//                + " FROM db_trigger t, db_trig c"
//                + " WHERE t.name=c.trigger_name";

        System.out.println(sql);
        try {
            stmt = conn.createStatement();
            boolean ret = stmt.execute(sql);
    
            if (ret) {
                rs = stmt.getResultSet();
                if (rs != null) {
                    ResultSetMetaData meta = rs.getMetaData();
                    System.out.println("getLargeUpdateCount " + stmt.getUpdateCount());
                    int count = meta.getColumnCount();
                    while (rs.next()) {
                        for (int j = 1; j < count + 1; j++) {
                            System.out.println("Columns : " + meta.getColumnName(j));
                            System.out.println("Columns isAutoIncrement : " + meta.isAutoIncrement(j));
                            Object obj = rs.getObject(j);
                            if (obj != null) {
                                if (obj instanceof CUBRIDOIDImpl) {
                                    System.out.println("Columns value : " + obj.getClass().getName());
                                    System.out.println("Columns value : " + String.valueOf(obj));
                                } else {
                                    System.out.println("Columns value : " + String.valueOf(obj));
                                }
                            }
                        }
                    }
                }
            }
    
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) rs.close();
                if (stmt != null) stmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
}
