package cubrid.test;

import java.sql.Connection;
import java.util.Properties;

import cubrid.jdbc.driver.CUBRIDDriver;

public class ConnectWorker {
    
    private static final String CONNECTION_URL = "jdbc:cubrid:192.168.3.32:33000:demodb:::";
    private static String USER = "dba";
    private static String PASSWORD = "";
    
    public static Connection getConnect() {
        Connection conn = null;
        Properties prop = new Properties();
        try {
            prop.put("user", USER);
            prop.put("password", PASSWORD);
            CUBRIDDriver cubridDriver = new CUBRIDDriver();
            conn = cubridDriver.connect(CONNECTION_URL, prop);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return conn;
    }

    public static Connection getConnection(String url, String user, String password) {
        Connection conn = null;
        Properties prop = new Properties();
        try {
            prop.put("user", user);
            prop.put("password", password);
            CUBRIDDriver cubridDriver = new CUBRIDDriver();
            conn = cubridDriver.connect(url, prop);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return conn;
    }
}
