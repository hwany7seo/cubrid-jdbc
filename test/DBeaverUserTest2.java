package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOIDImpl;

public class DBeaverUserTest2 {
    private static final String URL = "jdbc:CUBRID:192.168.3.80:33000:demodb:::";

    public static void main(String arg[]) throws Exception {
        Connection conn = null;
        try {
            Properties prop = new Properties();
            prop.put("user", "dba");
            prop.put("password", "");
            CUBRIDDriver cubridDriver = new CUBRIDDriver();
            conn = cubridDriver.connect(URL, prop);
            runQuery(conn);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
//        	if (conn != null) conn.close();
		}
    }
    
    private static void runQuery(Connection conn) {
        ResultSet rs = null;
        String sql = "select * from db_charset";
        System.out.println(sql);
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
        	pstmt.execute();
        	rs = pstmt.getResultSet();
        	if (rs != null) {
	        	try {
		            while (rs.next()) {
		            	int id = rs.getInt(1);
		            	String name = rs.getString(2);
		            	System.out.println("id : " + id);
		            	System.out.println("name : " + name);
		            }
	        	} catch (Exception e) {
	        		e.printStackTrace();
				} finally {
//					rs.close();
				} 
        	}
        } catch (Exception e) {
        	e.printStackTrace();
		}
    }
}
