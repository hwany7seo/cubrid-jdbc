package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOIDImpl;

public class PrepareTestCompare {
    private static final String URL = "jdbc:CUBRID:192.168.2.33:33000:testdb:::";

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
        PreparedStatement pstmt = null;
        String sql = "SELECT MD5(NVL(TO_CHAR(code), '{NULL}') || NVL(TO_CHAR([name]), '{NULL}') || NVL(TO_CHAR(gender), '{NULL}') || NVL(TO_CHAR(nation_code), '{NULL}') || NVL(TO_CHAR(event), '{NULL}')) AS _record_hash_  FROM [PUBLIC.athlete] WHERE code=?";
        System.out.println(sql);
        try {
            pstmt = conn.prepareStatement(sql);
            
            pstmt.setString(1, "10999");
            
            rs = pstmt.executeQuery(sql);
            
            if (!rs.next()) {
                System.out.println("rs is empty");
            } else {
                String hash = rs.getString("_record_hash_");
                System.out.println("hash = " + hash);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) rs.close();
                if (pstmt != null) pstmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
}
