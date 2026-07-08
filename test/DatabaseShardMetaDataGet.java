package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;

public class DatabaseShardMetaDataGet {
    private final String URL = "jdbc:cubrid:192.168.2.53:36000:shard1:::";

    public static void main(String[] args) {
        new DatabaseShardMetaDataGet().run(0);
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
        	prop.put("user", "shard");
        	prop.put("password", "1234");
        	CUBRIDDriver cubridDriver = new CUBRIDDriver();
        	Connection conn = cubridDriver.connect(URL, prop);
        	CUBRIDConnection shardConn = (CUBRIDConnection) conn;
        	CUBRIDDatabaseMetaData meta = (CUBRIDDatabaseMetaData) conn.getMetaData();
        	meta.setShardId(1);
        	
        	CUBRIDShardMetaData shardMetaData = shardConn.getShardMetaData();
        	int shardCount = shardMetaData.getShardCount();
        	System.out.println("shardCount : " + shardCount);
        	for (int i=0; i < shardCount; i++) {
        	    CUBRIDDatabaseMetaData metadata = shardMetaData.getMetaData(i);
        	    String[] names = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"};
                ResultSet tables = metadata.getTables(null, null, null, new String[]{"TABLE"});
                while(tables.next()) {
                    System.out.println("==============getTables============== : " + names.length);
                    for (int i2=0; i2 < names.length; i2++) {
                        System.out.println("i2 : " + i2);
                        String data = tables.getString(names[i2]);
                        System.out.println(names[i2] + " : " + data);
                    }
                }
        	}
        	
//            ResultSet pk = metadata.getPrimaryKeys(null, null, "public.game");
//            String[] names = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"};
//            
//            ResultSet rs = metadata.getSchemas();
//            while(rs.next()) {
//                String catalog = rs.getString("TABLE_CATALOG");
//                String schema = rs.getString("TABLE_SCHEM");
//                System.out.println("Catalog : " + catalog + ", Schema : " + schema);
//            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
