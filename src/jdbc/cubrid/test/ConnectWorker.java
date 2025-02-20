package cubrid.test;

import java.sql.Connection;
import java.util.Properties;

import cubrid.jdbc.driver.CUBRIDDriver;

public class ConnectWorker {
    
    private static final String CONNECTION_URL = "jdbc:cubrid:192.168.2.33:33000:demodb:dba::";
    private static String user = "dba";
    private static String password = "";
    
    public static Connection getConnect() {
        Connection conn = null;
        Properties prop = new Properties();
        try {
            prop.put("user", user);
            prop.put("password", password);
            CUBRIDDriver cubridDriver = new CUBRIDDriver();
            conn = cubridDriver.connect(CONNECTION_URL, prop);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            return conn;
        }
    }
}
