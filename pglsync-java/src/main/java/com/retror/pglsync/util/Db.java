package com.retror.pglsync.util;

import com.retror.pglsync.config.PglsyncProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

/** 数据库连接工具。 */
public final class Db {

    private Db() {
    }

    private static String url(PglsyncProperties.Db p) {
        return "jdbc:postgresql://" + p.getHost() + ":" + p.getPort() + "/" + p.getDbname();
    }

    private static Properties base(PglsyncProperties.Db p) {
        Properties props = new Properties();
        props.setProperty("user", p.getUser());
        if (p.getPassword() != null) {
            props.setProperty("password", p.getPassword());
        }
        props.setProperty("ApplicationName", "pglsync");
        props.setProperty("connectTimeout", "10");
        for (Map.Entry<String, String> e : p.getParams().entrySet()) {
            props.setProperty(e.getKey(), e.getValue());
        }
        return props;
    }

    /**
     * 普通连接。stringtype=unspecified：setString 绑定的参数以“未知类型”发送，
     * 由 PostgreSQL 按目标列类型解析（列值统一使用 PostgreSQL 文本格式传递）。
     */
    public static Connection connect(PglsyncProperties.Db p) throws SQLException {
        Properties props = base(p);
        props.setProperty("stringtype", "unspecified");
        return DriverManager.getConnection(url(p), props);
    }

    public static Connection connect(PglsyncProperties.Db p, boolean autoCommit) throws SQLException {
        Connection c = connect(p);
        c.setAutoCommit(autoCommit);
        return c;
    }

    /** 逻辑复制连接（replication=database），只能执行复制协议命令。 */
    public static Connection connectReplication(PglsyncProperties.Db p) throws SQLException {
        Properties props = base(p);
        props.setProperty("replication", "database");
        props.setProperty("assumeMinServerVersion", "10");
        props.setProperty("preferQueryMode", "simple");
        return DriverManager.getConnection(url(p), props);
    }

    public static void closeQuietly(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    public static void rollbackQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            if (!c.isClosed() && !c.getAutoCommit()) {
                c.rollback();
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    public static void execute(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
