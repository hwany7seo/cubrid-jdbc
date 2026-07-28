package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;


public class BatchTest {
	private final static String URL = "jdbc:cubrid:192.168.2.33:30000:demodb:dba::";
	
	public static void main(String arg[]) throws Exception {
		Connection conn = null;
		ResultSet rs = null;
		PreparedStatement pstmt = null;               
		try {
			Properties prop = new Properties();
        	prop.put("user", "dba");
        	prop.put("password", "");
        	CUBRIDDriver cubridDriver = new CUBRIDDriver();
        	conn = cubridDriver.connect(URL, prop);
			conn.setAutoCommit(false);
			String sql = "INSERT INTO "
					+ "	temp ( "
					+ "		int_col1,"
					+ "		varcher_col2,"
					+ "		enum_col"
					+ "	)"
					+ "VALUES"
					+ "	("
					+ "		?,"
					+ "		?,"
					+ "		?"
					+ "	)";
			pstmt = conn.prepareStatement(sql);
			pstmt.setInt(1, 3);
			pstmt.setString(2, "B");
			pstmt.setString(3, "");
			
			pstmt.addBatch();
			
			int[] ret = pstmt.executeBatch();

			for (int i=0; 1 < ret.length; i++) {
				System.out.println("ret " + i + " : " + ret[i]);
			}
			
			conn.commit();
              
	    } catch ( SQLException e ) {
	            e.printStackTrace();
	    } catch ( Exception e ) {
	            e.printStackTrace();
	    } finally {
	    	if (rs != null) rs.close();
	    	if (pstmt != null) pstmt.close();
	    	if (conn != null) conn.close();
	    }
	}
}
