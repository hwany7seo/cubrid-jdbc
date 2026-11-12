package cubrid.test;

import java.sql.Connection;
import java.util.Properties;

import cubrid.jdbc.driver.CUBRIDDriver;

public class ConnectWorker {
    
    // JDBC_URL 환경변수(또는 -DJDBC_URL=...)로 덮어쓸 수 있다.
    private static final String CONNECTION_URL = System.getProperty("JDBC_URL",
            System.getenv("JDBC_URL") != null ? System.getenv("JDBC_URL")
                                              : "jdbc:cubrid:localhost:33000:demodb:::");
    // JDBC_USER / JDBC_PASSWORD 환경변수(또는 -D)로 덮어쓸 수 있다.
    private static String USER = System.getProperty("JDBC_USER",
            System.getenv("JDBC_USER") != null ? System.getenv("JDBC_USER") : "dba");
    private static String PASSWORD = System.getProperty("JDBC_PASSWORD",
            System.getenv("JDBC_PASSWORD") != null ? System.getenv("JDBC_PASSWORD") : "");
    
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
