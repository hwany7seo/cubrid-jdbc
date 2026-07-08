package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOID;


public class OIDTest {
	private final static String URL = "jdbc:cubrid:192.168.2.33:30000:demodb:dba::";
	
	public static void main(String arg[]) throws Exception {
		Connection conn = null;
		CUBRIDResultSet rs = null;
		Statement stmt = null;
		CUBRIDResultSet rsoid = null;
		ResultSetMetaData rsmd = null;
		try {
			Properties prop = new Properties();
        	prop.put("user", "dba");
        	prop.put("password", "");
        	CUBRIDDriver cubridDriver = new CUBRIDDriver();
        	conn = cubridDriver.connect(URL, prop);
			conn.setAutoCommit(true);
			String sql = "SELECT s_name, f_name from public.code";
			stmt = conn.createStatement();
            rs = (CUBRIDResultSet)stmt.executeQuery(sql);
            rsmd = rs.getMetaData();
            
            // Printing columns
            int numOfColumn = rsmd.getColumnCount();
            for (int i = 1; i <= numOfColumn; i++ ) {
               String ColumnName = rsmd.getColumnName(i);
               String JdbcType = rsmd.getColumnTypeName(i);
               System.out.print(ColumnName );
               System.out.print("("+ JdbcType + ")");
               System.out.print(" | ");
            }
            System.out.print("\n");
            
            String[] attr = { "s_name", "f_name"};
            while (rs.next()) {
                CUBRIDOID oid = rs.getOID(); //d
                System.out.print("OID");
                System.out.print(" | ");
                rsoid = (CUBRIDResultSet)oid.getValues(attr);

                while (rsoid.next()) {
                   for( int j=1; j <= attr.length; j++ ) {
                      System.out.print(rsoid.getObject(j));
                      System.out.print(" | ");
                   }
                }
                System.out.print("\n");
             }
            
			conn.commit();
              
	    } catch ( SQLException e ) {
	            e.printStackTrace();
	    } catch ( Exception e ) {
	            e.printStackTrace();
	    } finally {
	    	if (rs != null) rs.close();
	    	if (stmt != null) stmt.close();
	    	if (conn != null) conn.close();
	    }
	}
}
