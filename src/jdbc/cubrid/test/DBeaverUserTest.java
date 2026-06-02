package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOIDImpl;

public class DBeaverUserTest {
    private static final String URL = "jdbc:CUBRID:192.168.3.81:33000:demodb:::";

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
        	if (conn != null) conn.close();
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
					rs.close();
				} 
        	}
        } catch (Exception e) {
        	e.printStackTrace();
		}
        
        sql = "select db_user.name, user_group.name from db_user, table(groups) as groups_tb(user_group) where db_user.name = ?";
        System.out.println(sql);
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
        	pstmt.setString(1, "DBA");
        	pstmt.execute();
        	rs = pstmt.getResultSet();
        	if (rs != null) {
	        	try {
		            while (rs.next()) {
		            	String userName = rs.getString(1);
		            	String groupName = rs.getString(2);
		            	System.out.println("userName : " + userName);
		            	System.out.println("groupName : " + groupName);
		            }
	        	} catch (Exception e) {
	        		e.printStackTrace();
				} finally {
					rs.close();
				} 
        	}
        } catch (Exception e) {
        	e.printStackTrace();
		}
        
        sql = "select * from db_user";
        System.out.println(sql);
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
        	pstmt.execute();
        	rs = pstmt.getResultSet();
        	if (rs != null) {
	        	try {
		            while (rs.next()) {
		            	String name = rs.getString(1);
		            	int id = rs.getInt("id");
		            	
		            	System.out.println("name : " + name);
		            	System.out.println("id : " + id);
		            }
	        	} catch (Exception e) {
	        		e.printStackTrace();
				} finally {
					rs.close();
				}
        	}
        } catch (Exception e) {
        	e.printStackTrace();
		}
        
        sql = "select t.groups.name from db_user join table(groups) as t(groups) where name = ?";
        System.out.println(sql);
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
        	pstmt.setString(1, "DBA");
        	pstmt.execute();
        	rs = pstmt.getResultSet();
        	if (rs != null) {
	        	try {
		            while (rs.next()) {
		            	String name = rs.getString(1);
		            	System.out.println("name : " + name);
		            }
	        	} catch (Exception e) {
	        		e.printStackTrace();
				} finally {
					rs.close();
				} 
        	}
        } catch (Exception e) {
        	e.printStackTrace();
		}
    }
}
