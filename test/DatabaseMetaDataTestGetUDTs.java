package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;

public class DatabaseMetaDataTestGetUDTs {
    private final String URL = "jdbc:cubrid:192.168.2.32:33000:demodb:dba::";

    public static void main(String[] args) {
        // make table, input want table count create.
        new DatabaseMetaDataTestGetUDTs().run(1000);
    }

    private void run(int count) throws UnsupportedOperationException {
        String[] getTableColumnLabels = {"TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "TABLE_TYPE", "REMARKS"};
        //String[] tableNames = {"REMARKS"};
        String[] getColumnsColumnLabels = {
                "TABLE_CAT",
                "TABLE_SCHEM",
                "TABLE_NAME",
                "COLUMN_NAME",
                "DATA_TYPE",
                "TYPE_NAME",
                "COLUMN_SIZE",
                "BUFFER_LENGTH",
                "DECIMAL_DIGITS",
                "NUM_PREC_RADIX",
                "NULLABLE",
                "REMARKS",
                "COLUMN_DEF",
                "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB",
                "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION",
                "IS_NULLABLE"
            };

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
            ResultSet tables = metadata.getTables(null, "%", "%", new String[] {"TABLE"});
            ResultSetMetaData rsmd = tables.getMetaData();

            System.out.println("============getTables () ================");
            while (tables.next()) {
                for (int i = 0; i < getTableColumnLabels.length; i++) {
                    System.out.println("getTableColumnLabels " + getTableColumnLabels[i] + " : " + tables.getString(getTableColumnLabels[i]));
                }

                int cols = rsmd.getColumnCount(); 
                for (int j = 1; j <= cols; j++) {
                    System.out.println("getTableColumnLabels count " + j + " : " + tables.getString(j));
                }
            }
            
            ResultSet columns = metadata.getColumns(null, "public", "athlete", "%");
            System.out.println("============getColumns () ================");
            while (columns.next()) {
                System.out.println("============================");
                for (int i = 0; i < getColumnsColumnLabels.length; i++) {
                    System.out.println("columnsNames " + getColumnsColumnLabels[i] + " : " + columns.getString(getColumnsColumnLabels[i]));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
}
