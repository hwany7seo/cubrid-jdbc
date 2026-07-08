package cubrid.test;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Stream;

import cubrid.jdbc.driver.*;
import cubrid.sql.CUBRIDOIDImpl;

public class ExecuteQuery3 {
    private static final String URL = "jdbc:CUBRID:192.168.2.32:33000:demodb2:::?charset=utf8";

    public static void main(String arg[]) throws Exception {
        Connection conn = null;
        ResultSet rs = null;
        Statement stmt = null;
        try {
            Properties prop = new Properties();
            prop.put("user", "dba");
            prop.put("password", "");
            //prop.put("validSql", "select 1|select 2");
            CUBRIDDriver cubridDriver = new CUBRIDDriver();
            conn = cubridDriver.connect(URL, prop);
            runQuery(conn);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void runQuery(Connection conn) {
        ResultSet rs = null;
        Statement stmt = null;
        String sql = "select * from han";
        System.out.println(sql);
        try {
            stmt = conn.createStatement();
            boolean ret = stmt.execute(sql);
    
            if (ret) {
                rs = stmt.getResultSet();
                if (rs != null) {
                    ResultSetMetaData meta = rs.getMetaData();
                    System.out.println("getLargeUpdateCount " + stmt.getUpdateCount());
                    int count = meta.getColumnCount();
                    while (rs.next()) {
                        for (int j = 1; j < count + 1; j++) {
//                            System.out.println("Columns : " + meta.getColumnName(j));
//                            System.out.println("Columns isAutoIncrement : " + meta.isAutoIncrement(j));
                            Object obj = rs.getObject(j);
                            if (obj != null) {
                                if (obj instanceof CUBRIDOIDImpl) {
                                    System.out.println("Columns value : " + obj.getClass().getName());
                                    System.out.println("Columns value : " + String.valueOf(obj));
                                } else {
                                    //String oriString = String.valueOf(obj);
                                    String oriString = "01001000 01100101 01101100 01101100 01101111";
                                    String[] binaryArray = oriString.split(" ");
//                                    for (int i=0 ;i < binaryArray.length; i++) {
//                                        binaryArray[i] = hexToBinary(binaryArray[i]);
//                                    }
                                    
                                    byte[] byteArray = new byte[binaryArray.length];
                                    for (int i = 0; i < binaryArray.length; i++) {
                                        byteArray[i] = (byte) Integer.parseInt(binaryArray[i], 2);
                                    }
                                    
                                    try {
                                        String result = new String(byteArray, "UTF-8");
                                        System.out.println(result);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    
                                    System.out.println("Columns value : " + String.valueOf(oriString));
                                    String iso88591 = new String(String.valueOf(obj).getBytes("iso-8859-1"), "iso-8859-1"); 
                                    System.out.println("Columns value : " + iso88591);
                                    String utf8 = new String(String.valueOf(obj).getBytes("utf-8"), "utf-8");
                                    System.out.println("Columns value : " + utf8);
                                    System.out.println("Columns value : " + String.valueOf(obj).getBytes(StandardCharsets.US_ASCII));
                                    System.out.println("Columns value : " + String.valueOf(obj).getBytes("EUC-KR"));
                                    System.out.println("Columns value : " + String.valueOf(obj).getBytes("unicode"));
                                    System.out.println("Columns value : " + String.valueOf(obj).getBytes("utf-16"));
                                }
                            }
                        }
                    }
                }
            }
    
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (rs != null) rs.close();
                if (stmt != null) stmt.close();
                if (conn != null) conn.close();
            } catch (SQLException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        
        String binaryString = "01001000 01100101 01101100 01101100 01101111";
        System.out.println("binaryString : " + binaryString);
        String[] binaryArray = binaryString.split(" ");
        
        byte[] byteArray = new byte[binaryArray.length];
        for (int i = 0; i < binaryArray.length; i++) {
            byteArray[i] = (byte) Integer.parseInt(binaryArray[i], 2);
        }
        
        try {
            String result = new String(byteArray, "UTF-8");
            System.out.println(result);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
//    select hex(user_nm) from test_user;
//
//    String hex = rs.getString(columnIndex);
//    byte[] b = DatatypeConverter.parseHexBinary(hex);
//    String value = new String(b, "euc-kr");
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
    
    public static String hexToBinary(String hex) {
         long decimal = Long.parseLong(hex, 16);
         return Long.toBinaryString(decimal);
    }
}
