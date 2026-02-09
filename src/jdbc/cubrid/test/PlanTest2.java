package cubrid.test;

import java.sql.*;
import cubrid.jdbc.driver.*;

public class PlanTest2 {

    private static final String CLASS_NAME = "cubrid.jdbc.driver.CUBRIDDriver";
    private static final String CONNECTION_URL = "jdbc:cubrid:192.168.2.58:30000:demodb:dba::";
    private static final String SHOW_FULL_COLUMNS = "show full columns from temp";

    public static void main(String arg[]) throws Exception {
        Class.forName(CLASS_NAME);
        Statement stmt = null;

//        try (Connection conn = DriverManager.getConnection(CONNECTION_URL)) {
//            String sql = "select host_nation from olympic where host_year = 2004";
//            stmt = conn.createStatement();
//
//            String plan = ((CUBRIDStatement) stmt).getQueryplan(sql);
//            System.out.println("plan : " + plan);
//            if (stmt != null) stmt.close();
//            if (conn != null) conn.close();
//            
//        } catch (SQLException e) {
//            e.printStackTrace();
//        }
        
        getQueryPlan();
    }

    private static void getQueryPlan() {
        Connection conn = null;
        ResultSet rs = null;
        PreparedStatement pstmt = null;
        try {
            Class.forName("cubrid.jdbc.driver.CUBRIDDriver");
            conn = DriverManager.getConnection(CONNECTION_URL);
            conn.setAutoCommit(false);
            String sql = "select host_nation from public.olympic where host_year = ?";
            String sql2 = "SELECT * FROM dba.code";
            pstmt = conn.prepareStatement(sql);
            pstmt.setInt(1, 2004);
            ((CUBRIDStatement) pstmt).setQueryInfo(false);
            rs = pstmt.executeQuery();
            String plan = ((CUBRIDStatement) pstmt).getQueryplan();
            System.out.println("plan111 : " + plan);
            while (rs.next()) System.out.println("host_nation : " + rs.getString(1));
            conn.commit();
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
            if (pstmt != null)
                try {
                    pstmt.close();
                } catch (SQLException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            if (conn != null)
                try {
                    conn.close();
                } catch (SQLException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
        }
    }

    private static void getType() {
        try (Connection conn = DriverManager.getConnection(CONNECTION_URL)) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet res = meta.getTypeInfo();

            while (res.next()) {
                String typeName = res.getString("TYPE_NAME");
                int dataType = res.getInt("DATA_TYPE");
                String createParams = res.getString("CREATE_PARAMS");
                System.out.println("TYPE_NAME: " + typeName);
                System.out.println("DATA_TYPE: " + dataType);
                System.out.println("CREATE_PARAMS: " + createParams);
                System.out.println("-------------------");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void getColumnsInfo() {
        try (Connection conn = DriverManager.getConnection(CONNECTION_URL)) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet rs = meta.getColumns(null, "DBA", "temp", "%");
            PreparedStatement pstmt = null;
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                int position = rs.getInt("ORDINAL_POSITION");
                String type = rs.getString("TYPE_NAME");
                int length = rs.getInt("CHAR_OCTET_LENGTH");
                int precision = rs.getInt("COLUMN_SIZE");
                int scale = rs.getInt("DECIMAL_DIGITS");
                int precision2 = rs.getInt("NUM_PREC_RADIX");
                boolean nullable = rs.getInt("NULLABLE") == 0 ? false : true;
                System.out.println(" COLUMN_NAME : " + name);
                System.out.println(" ORDINAL_POSITION : " + position);
                System.out.println(" TYPE_NAME : " + type);
                System.out.println(" CHAR_OCTET_LENGTH : " + length);
                System.out.println(" precision : " + precision);
                System.out.println(" DECIMAL_DIGITS : " + scale);
                System.out.println(" precision2 : " + precision2);
                System.out.println(" NULLABLE : " + nullable);
            }
            pstmt = conn.prepareStatement(SHOW_FULL_COLUMNS);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                System.out.println("rs string 1 : " + rs.getString(1));
                System.out.println("rs string 1 : " + rs.getString(1));
                System.out.println("rs string 1 : " + rs.getString(2));
                System.out.println("rs string 1 : " + rs.getString(3));
                System.out.println("rs string 1 : " + rs.getString(4));
                System.out.println("rs string 1 : " + rs.getString(5));
                System.out.println("rs string 1 : " + rs.getString(6));
                System.out.println("rs string 1 : " + rs.getString(7));
                System.out.println("rs string 1 : " + rs.getString(8));
            }
            conn.commit();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void getShowColumnsInfo() {
        ResultSet rs = null;
        PreparedStatement pstmt = null;

        try (Connection conn = DriverManager.getConnection(CONNECTION_URL)) {
            conn.setAutoCommit(false);
            pstmt = conn.prepareStatement(SHOW_FULL_COLUMNS);
            rs = pstmt.executeQuery();
            while (rs.next()) {
                System.out.println("rs string 1 : " + rs.getString(1));
                System.out.println("rs string 1 : " + rs.getString(1));
                System.out.println("rs string 1 : " + rs.getString(2));
                System.out.println("rs string 1 : " + rs.getString(3));
                System.out.println("rs string 1 : " + rs.getString(4));
                System.out.println("rs string 1 : " + rs.getString(5));
                System.out.println("rs string 1 : " + rs.getString(6));
                System.out.println("rs string 1 : " + rs.getString(7));
                System.out.println("rs string 1 : " + rs.getString(8));
            }
            conn.commit();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
