package cubrid.test;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOIDImpl;

public class ExecuteUpdate {
    private static final String URL = "jdbc:CUBRID:192.168.2.32:33000:demodb:::?charset=utf8";

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
        PreparedStatement ps;
        try {
            ps = conn.prepareStatement("INSERT INTO auto_ti (c1, c2) VALUES (NULL, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "CUBRID");
            ps.executeUpdate();

            ResultSet rs = ps.getGeneratedKeys();
            while (rs.next()) {
              System.out.println(rs.getLong(1));
            }   
        } catch (SQLException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

            }
}
