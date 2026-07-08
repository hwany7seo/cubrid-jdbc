package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;

public class DatabaseMetaDataGetSchema {
    private final String URL = "jdbc:cubrid:192.168.2.32:33000:demodb:dba::";

    public static void main(String[] args) {
        // make table, input want table count create.
        new DatabaseMetaDataGetSchema().run(1000);
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
            DatabaseMetaData metadata = conn.getMetaData();
            ResultSet pk = metadata.getPrimaryKeys(null, null, "public.game");
            String[] names = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"};
            
            ResultSet rs = metadata.getSchemas();
            while(rs.next()) {
                String catalog = rs.getString("TABLE_CATALOG");
                String schema = rs.getString("TABLE_SCHEM");
                System.out.println("Catalog : " + catalog + ", Schema : " + schema);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
