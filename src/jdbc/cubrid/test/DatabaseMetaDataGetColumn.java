package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;

public class DatabaseMetaDataGetColumn {
    private final String URL = "jdbc:cubrid:192.168.2.32:33000:demodb:dba::";

    String[] names = {
            "PKTABLE_CAT",
            "PKTABLE_SCHEM",
            "PKTABLE_NAME",
            "PKCOLUMN_NAME",
            "FKTABLE_CAT",
            "FKTABLE_SCHEM",
            "FKTABLE_NAME",
            "FKCOLUMN_NAME",
            "KEY_SEQ",
            "UPDATE_RULE",
            "DELETE_RULE",
            "FK_NAME",
            "PK_NAME",
            "DEFERRABILITY"
        };
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
        	    System.out.println("read data");
        	    for (int i=0; i < names.length; i++) {
        	        System.out.println("names.length : " + names.length);
        	        System.out.println("i : " + i);
        	        String data = rs.getString(names[i]);
        	        System.out.println(names[i] + " : " + data);
        	    }
        	}
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
