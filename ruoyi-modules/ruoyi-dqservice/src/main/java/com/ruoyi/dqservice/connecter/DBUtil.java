package com.ruoyi.dqservice.connecter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class DBUtil {
    private static final String DBDriver = "com.mysql.cj.jdbc.Driver";
//    private static final String url="jdbc:mysql:///dadb?serverTimezone=GMT%2B8&useUnicode=true&characterEncoding=utf8&rewriteBatchedStatements=true&autoReconnect=true";
    private static final String url= "jdbc:hive2://localhost:10000";
    private static final String user = "root";
    private static final String password = "root";

    public static Connection getConn() {
        try {
            Class.forName(DBDriver);
            return DriverManager.getConnection(url, user, password);
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void close(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}
