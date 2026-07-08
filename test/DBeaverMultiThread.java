package cubrid.test;

import java.sql.*;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import cubrid.jdbc.driver.*;

public class DBeaverMultiThread {
    private static final String URL = "jdbc:CUBRID:192.168.3.81:33000:demodb:::";

    public static void main(String arg[]) throws Exception {
        Connection conn = null;
        try {
            Properties prop = new Properties();
            prop.put("user", "dba");
            prop.put("password", "");
            CUBRIDDriver cubridDriver = new CUBRIDDriver();
            conn = cubridDriver.connect(URL, prop);

            ExecutorService executor = Executors.newFixedThreadPool(4);
            final Connection sharedConn = conn;

            executor.submit(() -> {
                String sql = "select * from db_charset";
                System.out.println("[Thread-1] Start: " + sql);
                try (PreparedStatement pstmt = sharedConn.prepareStatement(sql)) {
                    pstmt.execute();
                    try (ResultSet rs = pstmt.getResultSet()) {
                        if (rs != null) {
                            while (rs.next()) {
                                int id = rs.getInt(1);
                                String name = rs.getString(2);
                                System.out.println("[Thread-1] id : " + id + ", name : " + name);
                            }
                            rs.close();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            
            executor.submit(() -> {
                String sql = "select * from db_class";
                System.out.println("[Thread-2] Start: " + sql);
                try (PreparedStatement pstmt = sharedConn.prepareStatement(sql)) {
                    pstmt.execute();
                    try (ResultSet rs = pstmt.getResultSet()) {
                        if (rs != null) {
                            while (rs.next()) {
                                String name = rs.getString(1);
                                String owner = rs.getString(2);
                                System.out.println("[Thread-2] name : " + name + ", owner : " + owner);
                            }
                            rs.close();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

//            executor.submit(() -> {
//                String sql = "select db_user.name, user_group.name from db_user, table(groups) as groups_tb(user_group) where db_user.name = ?";
//                System.out.println("[Thread-2] Start: " + sql);
//                try (PreparedStatement pstmt = sharedConn.prepareStatement(sql)) {
//                    pstmt.setString(1, "DBA");
//                    pstmt.execute();
//                    try (ResultSet rs = pstmt.getResultSet()) {
//                        if (rs != null) {
//                            while (rs.next()) {
//                                String userName = rs.getString(1);
//                                String groupName = rs.getString(2);
//                                System.out.println("[Thread-2] userName : " + userName + ", groupName : " + groupName);
//                            }
//                            rs.close();
//                        }
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            });

            executor.submit(() -> {
                String sql = "select * from db_user";
                System.out.println("[Thread-3] Start: " + sql);
                try (PreparedStatement pstmt = sharedConn.prepareStatement(sql)) {
                    pstmt.execute();
                    try (ResultSet rs = pstmt.getResultSet()) {
                        if (rs != null) {
//                        	ResultSetMetaData meta = rs.getMetaData();
//                        	int count = meta.getColumnCount();
                            while (rs.next()) {
                                String name = rs.getString(1);
                                int id = rs.getInt("id");
                                System.out.println("[Thread-3] name : " + name + ", id : " + id);
                            }
                            rs.close();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

//            executor.submit(() -> {
//                String sql = "select t.groups.name from db_user join table(groups) as t(groups) where name = ?";
//                System.out.println("[Thread-4] Start: " + sql);
//                try (PreparedStatement pstmt = sharedConn.prepareStatement(sql)) {
//                    pstmt.setString(1, "DBA");
//                    pstmt.execute();
//                    try (ResultSet rs = pstmt.getResultSet()) {
//                        if (rs != null) {
//                            while (rs.next()) {
//                                String name = rs.getString(1);
//                                System.out.println("[Thread-4] name : " + name);
//                            }
//                            rs.close();
//                        }
//                    }
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            });

            executor.shutdown();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                    System.out.println("Connection closed.");
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}