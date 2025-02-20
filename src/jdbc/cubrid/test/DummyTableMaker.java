package cubrid.test;

import java.sql.*;
import java.util.Properties;

import cubrid.jdbc.driver.CUBRIDDriver;

public class DummyTableMaker {
	private final String URL = "jdbc:cubrid:192.168.2.32:33000:demodb:dba::";

	public static void main(String[] args) {
		// make table, input want table count create.
		new DummyTableMaker().run(1000);
	}

	private void run(int count) {

		try {
			Properties prop = new Properties();
			prop.put("user", "dba");
			prop.put("password", "");
			CUBRIDDriver cubridDriver = new CUBRIDDriver();
			Connection conn = cubridDriver.connect(URL, prop);
			Statement stmt = conn.createStatement();
			StringBuilder sb = new StringBuilder();
			String sql = "insert into aaaa Values (1,'seo')";
			stmt.addBatch(sql);
			sql = "insert into aaaa Values (2,'seo')";
			stmt.addBatch(sql);
			sql = "insert into aaaa Values (3,'seo')";
			stmt.addBatch(sql);
			sql = "insert into aaaa Values (4, NULL)";
			stmt.addBatch(sql);
			sql = "insert into aaaa Values (5, NULL)";
			stmt.addBatch(sql);
			sql = "insert into aaaa Values (6,'seo')";
			stmt.addBatch(sql);

			int[] countArr = stmt.executeBatch();
			System.out.printf("countArr : " + countArr.toString());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
