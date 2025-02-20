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
        	ResultSet tables = metaData.getTables(TABLE_SCHEMA, TABLE_NAME, TABLE_SCHEMA, TABLE_TYPES);
        	
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
