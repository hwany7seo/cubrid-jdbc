package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;

public class DatabaseMetaDataGet {
    private final String URL = "jdbc:cubrid:192.168.2.32:33000:demodb:dba::";

    public static void main(String[] args) {
        // make table, input want table count create.
        new DatabaseMetaDataGet().run(1000);
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
        	CUBRIDDatabaseMetaData cubMetaData = (CUBRIDDatabaseMetaData) metaData;
            ResultSet columns = metaData.getColumns(null, null, "public.athlete", null);
            cubMetaData.getColumns(null, null, "public.athlete", null);
            while (columns.next()){
               System.out.print("Column name and size: "+columns.getString("COLUMN_NAME"));
               System.out.print("("+columns.getInt("COLUMN_SIZE")+")");
               System.out.println(" ");
               System.out.println("Ordinal position: "+columns.getInt("ORDINAL_POSITION"));
               System.out.println("Catalog: "+columns.getString("TABLE_CAT"));
               System.out.println("Data type (integer value): "+columns.getInt("DATA_TYPE"));
               System.out.println("Data type name: "+columns.getString("TYPE_NAME"));
               System.out.println(" ");
            }
            String[] names = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"};
            ResultSet tables = conn.getMetaData().getTables(null, null, null, new String[]{"TABLE"});
            while(tables.next()) {
                System.out.println("==============getTables==============");
                for (int i=0; i < names.length; i++) {
                    String data = tables.getString(names[i]);
                    System.out.println(names[i] + " : " + data);
                }
            }
            
            String[] pkNames = {
                    "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"
                };
            ResultSet pk = conn.getMetaData().getPrimaryKeys(null, null, "PUBLIC.game");
            while(pk.next()) {
                System.out.println("==============getPrimaryKeys==============");
                for (int i=0; i < pkNames.length; i++) {
                    String data = pk.getString(pkNames[i]);
                    System.out.println(pkNames[i] + " : " + data);
                }
            }
            
            String[] importNames = {
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
            
            ResultSet importkey = conn.getMetaData().getImportedKeys(null, null, "PUBLIC.game");
            while(importkey.next()) {
                System.out.println("==============getImportedKeys==============");
                for (int i=0; i < pkNames.length; i++) {
                    String data = importkey.getString(importNames[i]);
                    System.out.println(importNames[i] + " : " + data);
                }
            }
            
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
