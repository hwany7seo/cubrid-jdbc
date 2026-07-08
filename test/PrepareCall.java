package cubrid.test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class PrepareCall {
    
    public static void main(String arg[]) throws Exception {
        try {
            Connection conn = ConnectWorker.getConnect();
            
            dbmsEnable(conn);
            runDemoHello(conn);
            dbmsGetLine(conn);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void dbmsEnable (Connection conn) {
        CallableStatement cstmt;
        try {
            cstmt = conn.prepareCall("CALL ENABLE(?)");
            cstmt.setInt(1, 1000000);
            cstmt.execute();
            cstmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        
    }
    
    private static void runDemoHello (Connection conn) throws SQLException {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement("CALL demo_hello()");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                System.out.println("rs : " + rs.getObject(1));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            if (rs != null) rs.close();
            if (pstmt != null) pstmt.close();
        }
    }
    
    private static void dbmsGetLine (Connection conn) {
        CallableStatement cstmt;
        try {
            cstmt = conn.prepareCall("CALL GET_LINE(?,?)");
            cstmt.registerOutParameter(1, java.sql.Types.VARCHAR);
            cstmt.registerOutParameter(2, java.sql.Types.JAVA_OBJECT);
            boolean isRs = cstmt.execute();
            
//            Object temp1 = cstmt.getobject(1);
//            Object temp2 = cstmt.getobject(2);
            
            String line;
            int status = 0;
            while(status == 0) {
                Object obj = cstmt.getObject(2);
                if (obj == null) {
                    return;
                }
                
                status = Integer.parseInt(obj.toString());
                        
                System.out.println(status);
                if (status == 0) {
                    line = cstmt.getString(1);
                    System.out.println(String.valueOf(line));
                } else {
                    System.out.println(String.valueOf("Data is empty"));
                }
            }
            
            cstmt.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
