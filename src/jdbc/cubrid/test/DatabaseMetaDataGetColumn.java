package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;

public class DatabaseMetaDataGetColumn {
    private final String URL = "jdbc:cubrid:192.168.2.33:33000:demodb:dba::";

    public static void main(String[] args) {
        // make table, input want table count create.
        new DatabaseMetaDataGetColumn().run(1000);
    }

    private void run(int count) {
    	String TABLE_NAME = "TABLE_NAME";
        String TABLE_SCHEMA = "TABLE_SCHEM";
        String[] TABLE_TYPES = {"TABLE"};
        
//        try {
//            Class.forName("cubrid.jdbc.driver.CUBRIDDriver");
//        } catch (ClassNotFoundException e) {
//            e.printStackTrace();
//        }
        try {
        	Properties prop = new Properties();
        	prop.put("user", "dba");
        	prop.put("password", "");
        	CUBRIDDriver cubridDriver = new CUBRIDDriver();
        	Connection conn = cubridDriver.connect(URL, prop);
        	DatabaseMetaData metaData = conn.getMetaData();
        	ResultSet rs = metaData.getImportedKeys(null, null, "PUBLIC.game");
        	
        	while(rs.next()) {
            	String pkTableCatalog = rs.getString("PKTABLE_CAT");
            	System.out.println("pkTableCatalog = " + pkTableCatalog);
                String pkTableSchema = rs.getString("PKTABLE_SCHEM");
                System.out.println("pkTableSchema = " + pkTableSchema);
        	}
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
