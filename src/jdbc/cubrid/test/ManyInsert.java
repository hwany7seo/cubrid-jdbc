package cubrid.test;

import java.sql.Connection;
import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.*;
import cubrid.test.ConnectWorker;

public class ManyInsert {
    
    private static final String URL = "jdbc:CUBRID:192.168.2.32:33000:demodb:::";
    private static final String USER = "dba";
    private static final String PASSWORD = "";
    private static final int insertCount = 200000;
    
    private static void createTable(Connection conn) {
        try {
            Statement stmt = conn.createStatement();
            stmt.execute("DROP TABLE IF EXISTS many_insert");
            stmt.execute("CREATE TABLE many_insert (id INT, name VARCHAR(255))");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private static void insertMany(Connection conn) {
        try {
            PreparedStatement pstmt = conn.prepareStatement("INSERT INTO many_insert (id, name) VALUES (?, ?)");
            for (int i = 0; i < insertCount; i++) {
                pstmt.setInt(1, i);
                pstmt.setString(2, "John" + i);
                pstmt.executeUpdate();
                if (i % 10000 == 0) {
                    System.out.println("Inserted " + i + " records");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void selectCount(Connection conn) {
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM many_insert");
            rs.next();
            System.out.println("Count: " + rs.getInt(1));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void selectAll(Connection conn) {
        try {
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM many_insert");
            while (rs.next()) {
                System.out.println("ID: " + rs.getInt("id"));
                System.out.println("Name: " + rs.getString("name"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String arg[]) throws Exception {
        try {
            Connection conn = ConnectWorker.getConnection(URL, USER, PASSWORD);

            if (conn == null) {
                System.out.println("Connection is null");
                return;
            }
            
            createTable(conn);
            insertMany(conn);
            // selectCount(conn);
            // selectAll(conn);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}