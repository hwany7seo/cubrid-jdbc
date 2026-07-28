package cubrid.test;

import java.io.File;
import java.io.FileNotFoundException;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Pattern;

import cubrid.jdbc.driver.*;
import cubrid.jdbc.log.BasicLogger;
import cubrid.jdbc.log.Log;

public class ExecuteQuery2 {
    private static final String URL = "jdbc:CUBRID:192.168.2.32:33000:demodb:::";
    private static BasicLogger log;
    protected static ConnectionProperties connectionProperties = new ConnectionProperties();
    
    public static void main(String arg[]) throws Exception {
        Connection conn = null;
        try {
            Properties prop = new Properties();
            prop.put("user", "dba");
            prop.put("password", "");
            //prop.put("validSql", "select 1|select 2");
            CUBRIDDriver cubridDriver = new CUBRIDDriver();
            conn = cubridDriver.connect(URL, prop);
            conn.setAutoCommit(false);
            runQuery(conn);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (conn != null) conn.close();
        }
    }
    
    private static void runQuery(Connection conn) {
        ResultSet rs = null;
        PreparedStatement stmt = null;
        List<String> queryList = new ArrayList<String>();
        LinkedHashMap<String, List<String>> sqlList = new LinkedHashMap<>();
        
        processLimitTest(sqlList);
//        for (String sqlQuery : queryList) {
//            System.out.println(sqlQuery);
//        }
        
        for (String file : sqlList.keySet()) {
            getLogger().logInfo("file : " + file);
            System.out.println("file : " + file);
            for (String sqlQuery : sqlList.get(file)) {
                //System.out.println("sqlQuery : " + sqlQuery);
                try {
                    getLogger().logInfo("sqlQuery : " + sqlQuery);
                    if (sqlQuery.contains("rollback")) {
                        conn.rollback();
                    } else if (sqlQuery.contains("autocommit off")) {
                        conn.setAutoCommit(false);
                    } else if (sqlQuery.contains("autocommit on")) {
                        conn.setAutoCommit(true);
                    } else {
                        stmt = conn.prepareStatement(sqlQuery);
                        boolean ret = stmt.execute();
                
                        if (ret) {
                            rs = stmt.getResultSet();
                            if (rs != null) {
                                //System.out.println("rs is not null");
                            }
                        }
                    }
                } catch (Exception e) {
                    getLogger().logError(e.getMessage());
    //                System.out.println(sqlQuery);
    //                e.printStackTrace();
                } finally {
                    try {
                        if (rs != null) rs.close();
                        if (stmt != null) stmt.close();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
    
    public static void processLimitTest(LinkedHashMap<String, List<String>> sqlList) {
        Pattern pattern = Pattern.compile("(--+|/\\*|\\*/)");
        StringBuilder strBuilder = new StringBuilder();
        File path = new File("d:\\cubrid-testcases\\medium\\_01_fixed");
        final List<File> sqlFiles = new ArrayList<File>();
        search(sqlFiles, path, 0);
        Scanner scanner;
        boolean endQuery = false;
        for (File f : sqlFiles) {
            List<String> queries = new ArrayList<String>();
            System.out.println("file : " + f.getPath());
            try {
                scanner = new Scanner(f);
                while (scanner.hasNextLine()) {
                    String str = scanner.nextLine();
                    boolean ret = pattern.matcher(str).find(); 
                    if (!ret) {
                        endQuery = str.endsWith(";");
                        strBuilder.append(str.trim());
                        if (!endQuery) {
                            strBuilder.append(" ");
                        }
                        
                        if (endQuery) {
                            //strBuilder.append(System.lineSeparator());
                            strBuilder.deleteCharAt(strBuilder.lastIndexOf(";"));
//                          SQLQuery query = new SQLQuery(
//                                  context.getDataSource(),
//                                  strBuilder.toString(),
//                                    0,
//                                    strBuilder.length());
//                            query.setEndsWithDelimiter(false);
                            queries.add(strBuilder.toString());
                            strBuilder = new StringBuilder();
                        }
                    }
                }
                sqlList.put(f.getPath(), queries);
            } catch (FileNotFoundException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            //System.out.println(strBuilder.toString());
//          Display.getDefault().syncExec(new Runnable(){
//                @Override
//                public void run() {
//                  try {
//                        document.replace(0, document.getLength(), strBuilder.toString());
//                    } catch (BadLocationException e) {
//                        e.printStackTrace();
//                    }
//                }
//            });
//          try {
//                Thread.sleep(1000);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
        }
    }
    
    public static boolean search(List<File> sqlFiles, File f, int depth) {
        boolean isExist = (f.getName().endsWith(".sql") && (!f.isDirectory()));
//      if (isExist) {
//          if (!f.isDirectory()) {
//              System.out.println("aaaaa : " + f.getName());
//              System.out.println("aaaaa : " + f.getPath());
//              sqlFiles.add(f);
//              return true;
//          }
//      }

        if (f.isDirectory()) {
            for(File file : f.listFiles()) {
                isExist = search(sqlFiles, file, depth+1);
            }
        }
        if (isExist) {
            if (!f.isDirectory()) {
                //System.out.println(f.getName());
                sqlFiles.add(f);
            }
        }
        return isExist;
    }
    
    protected static Log getLogger() {
        if (log == null) {
            System.out.println("connectionProperties.getLogFile() : " + connectionProperties.getLogFile());
            log = new BasicLogger(connectionProperties.getLogFile());
        }
        return log;
    }
}
