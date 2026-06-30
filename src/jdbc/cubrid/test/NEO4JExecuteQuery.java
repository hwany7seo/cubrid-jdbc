package cubrid.test;

import java.sql.*;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public class NEO4JExecuteQuery {
	private final static String NEO4J_URL = "jdbc:neo4j:bolt://192.168.2.48:7687/?database=tibero";
	
	public static void main(String arg[]) throws Exception {
		Connection conn = null;
		ResultSet rs = null;
		Statement stmt = null;               
		try {
			Properties prop = new Properties();
        	prop.put("user", "dba");
        	prop.put("password", "");
        	// Connecting
        	conn = DriverManager.getConnection(NEO4J_URL, "neo4j", "neo4j4j4j");
        	String sql = "match (n:CUSTOMER)-[r]-(m) return n,r,m";
			stmt = conn.createStatement();
			boolean ret = stmt.execute(sql);
			if (ret) {
				rs = stmt.getResultSet();
        		ResultSetMetaData meta = rs.getMetaData();
        		System.out.println("----");
        		int count = meta.getColumnCount();
        		System.out.println("rowCount : " + count);
        		while (rs.next()) {
        			for (int j = 1; j < count + 1; j++) {
        					System.out.println("Label : " + meta.getTableName(j));
        					System.out.println("Property : " + meta.getColumnName(j));
        					Map<String, Object> mmm = new HashMap<>();
//        					rs.getObject("n", mmm);
        			       	//System.out.println("Values : " + rs.getString(j));
        			       	Object m = rs.getObject(j);
        			       	mmm.putAll((LinkedHashMap)m);
        			       	System.out.println("getName = " + m.getClass().getName());
        			       	System.out.println("getTypeName = " + m.getClass().getTypeName());
        			       	for (String key : mmm.keySet()) {
        			       		System.out.println(key + " : " + key);
        			       		System.out.println(key + " : " + mmm.get(key).getClass().getName());
        			       	}
        			    }
        			break;
        		}
			}

              
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
