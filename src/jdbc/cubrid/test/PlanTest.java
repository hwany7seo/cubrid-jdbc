package cubrid.test;

import java.sql.*;
import cubrid.jdbc.driver.*;

public class PlanTest {

    private static final String CLASS_NAME = "cubrid.jdbc.driver.CUBRIDDriver";
    private static final String CONNECTION_URL = "jdbc:cubrid:192.168.2.53:36000:shard1:shard:1234:";
    private static final String SHOW_FULL_COLUMNS = "show full columns from temp";

    public static void main(String arg[]) throws Exception {
        Class.forName(CLASS_NAME); 
        getQueryPlan();
        //getType();
        //getColumnsInfo();
        //getShowColumnsInfo();
    }

    private static void getQueryPlan() {
        
        try (Connection conn = DriverManager.getConnection(CONNECTION_URL)) {
            DatabaseMetaData meta = conn.getMetaData();
            ResultSet rs = meta.getTypeInfo();
            PreparedStatement pstmt;
            conn.setAutoCommit(false);
            String sql = "SELECT /*+ RECOMPILE */ * FROM public.code /*+ shard_id(1) */";
            String sql2 = "SELECT /*+ RECOMPILE */ * FROM public.game /*+ shard_id(1) */";
            String sql3 = "SELECT /*+ RECOMPILE */ * FROM public.nation /*+ shard_id(1) */";

            pstmt = conn.prepareStatement(sql);
            pstmt = conn.prepareStatement(sql2);
            pstmt = conn.prepareStatement(sql3);
            // pstmt.setInt(1, 2004);
            ((CUBRIDStatement) pstmt).setQueryInfo(true);
            boolean ret = pstmt.execute();
            System.out.println("ret : " + ret);
            rs = pstmt.executeQuery();
            String plan = ((CUBRIDStatement) pstmt).getQueryplan();
            System.out.println("plan : " + plan);
            while (rs.next()) {
                System.out.println("rs string 1 : " + rs.getString(1));
            }
            conn.commit();
        } catch (SQLException e) {
            e.printStackTrace();
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
