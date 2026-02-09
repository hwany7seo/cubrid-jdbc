package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOIDImpl;

public class DBeaverUserTest {
    private static final String URL = "jdbc:CUBRID:192.168.3.80:33000:demodb:::";

    public static void main(String arg[]) throws Exception {
        Connection conn = null;
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
        boolean ret = false;
        String sql = "select t.groups.name from db_user join table(groups) as t(groups) where name = 'DBA'";

        System.out.println(sql);
        try {
        	pstmt = conn.prepareStatement("select t.groups.name from db_user join table(groups) as t(groups) where name = ?");
        	pstmt.setString(1, "DBA");
        	ret = pstmt.execute();
        	if (ret) {
        		rs = pstmt.getResultSet();
	            if (rs != null) {
		            ResultSetMetaData meta = rs.getMetaData();
		            int count = meta.getColumnCount();
		            while (rs.next()) {
		            	System.out.println("0: " + rs.getString(0));
		            	System.out.println("1: " + rs.getString(0));
		            	
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
        	} else {
		        int updateCount = pstmt.getUpdateCount();
		        if (updateCount == -1) {
		            System.out.println("nothing");
		        } else {
		            System.out.println("updateCount : " + updateCount);
		        }
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
