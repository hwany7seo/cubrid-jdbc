package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOIDImpl;

public class DBeaverSQLEditorIssue {
    private static final String URL = "jdbc:CUBRID:192.168.3.81:33000:demodb:::";

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
        } finally {
        	if (conn != null) conn.close();
		}
    }
    
    private static void runQuery(Connection conn) {
        ResultSet rs = null;
        PreparedStatement pstmt = null;
        boolean ret = false;
        String sql = "SELECT a.*, a.def_order + 1 AS ref_order, i.is_foreign_key "
        		+ "FROM db_attribute a "
        		+ "LEFT JOIN "
        		+ "(SELECT k.key_attr_name AS attr_name, i.class_name, i.is_foreign_key , i.owner_name FROM db_index i "
        		+ "JOIN db_index_key k ON i.class_name = k.class_name AND i.index_name = k.index_name "
        		+ "WHERE i.is_foreign_key = 'YES') i "
        		+ "ON a.class_name = i.class_name AND a.attr_name = i.attr_name AND a.owner_name = i.owner_name "
        		+ "WHERE a.class_name = ? AND a.owner_name = ? ORDER BY def_order";
        System.out.println(sql);
        try {
        	pstmt = conn.prepareStatement(sql);
		} catch (Exception e) {
			e.printStackTrace();
		}
        
        for (int i = 0 ; i < 2 ; i++) {
	        try {
	    		if (i == 0) {
	    			System.out.println("Test1");
		        	pstmt.setString(1, "db_user");
		        	pstmt.setString(2, "DBA");
	    		} else if (i == 1){
	    			System.out.println("Test2");
	    			pstmt.setString(1, "db_user");
		        	pstmt.setString(2, "DBA");
	    		} 
	        	ret = pstmt.execute();
	        	System.out.println("ret : " + ret);
	        	if (ret) {
	        		rs = pstmt.getResultSet();
	        		if (rs != null) {
			            while (rs.next()) {
			            	int prec = rs.getInt("prec");
			            	if (rs.wasNull()) {
			            		System.out.println("retPrec rs wasNull");
			            	}
			            	String is_nullable = rs.getString("is_nullable");
			            	if (rs.wasNull()) {
			            		System.out.println("is_nullable rs wasNull");
			            	}
			            	System.out.println("prec : " + prec);
			            	System.out.println("is_nullable : " + is_nullable);
			            }
	        		}
	        	}
	        } catch (SQLException e) {
	            e.printStackTrace();
	        } catch (Exception e) {
	            e.printStackTrace();
	        } finally {
                if (rs != null)
					try {
						rs.close();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	        }
        }
    }
}
