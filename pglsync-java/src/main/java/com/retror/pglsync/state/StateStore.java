package com.retror.pglsync.state;

import com.retror.pglsync.util.Db;
import com.retror.pglsync.util.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目标库中的同步位点表：位点与数据在同一事务中提交，实现精确续传。
 *
 * <ul>
 *   <li>sync_state：每个复制槽一行，记录最后一个已应用事务的 end_lsn；</li>
 *   <li>table_state：单表重新复制（resync）时记录该表快照对应的位点 X，
 *       增量同步遇到该表在 X 之前提交的变更会跳过。</li>
 * </ul>
 * 除 ensure / read 外，写方法都在调用方的事务内执行，不提交。
 */
public final class StateStore {

    private StateStore() {
    }

    public static void ensure(Connection c, String schema) throws SQLException {
        Db.execute(c, "CREATE SCHEMA IF NOT EXISTS " + Sql.qi(schema));
        Db.execute(c, "CREATE TABLE IF NOT EXISTS " + Sql.qi(schema) + ".sync_state ("
                + " slot_name text PRIMARY KEY, lsn pg_lsn NOT NULL,"
                + " updated_at timestamptz NOT NULL DEFAULT now())");
        Db.execute(c, "CREATE TABLE IF NOT EXISTS " + Sql.qi(schema) + ".table_state ("
                + " slot_name text NOT NULL, table_name text NOT NULL, lsn pg_lsn NOT NULL,"
                + " updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY (slot_name, table_name))");
        commitIfNeeded(c);
    }

    public static void writeState(Connection c, String schema, String slot, long lsn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + Sql.qi(schema)
                + ".sync_state (slot_name, lsn, updated_at) VALUES (?, ?::pg_lsn, now()) "
                + "ON CONFLICT (slot_name) DO UPDATE SET lsn = EXCLUDED.lsn, updated_at = now()")) {
            ps.setString(1, slot);
            ps.setString(2, Sql.lsn(lsn));
            ps.executeUpdate();
        }
    }

    public static void writeTableState(Connection c, String schema, String slot, String table, long lsn)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + Sql.qi(schema)
                + ".table_state (slot_name, table_name, lsn, updated_at) VALUES (?, ?, ?::pg_lsn, now()) "
                + "ON CONFLICT (slot_name, table_name) DO UPDATE SET lsn = EXCLUDED.lsn, updated_at = now()")) {
            ps.setString(1, slot);
            ps.setString(2, table);
            ps.setString(3, Sql.lsn(lsn));
            ps.executeUpdate();
        }
    }

    public static void clearTableStates(Connection c, String schema, String slot) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + Sql.qi(schema) + ".table_state WHERE slot_name = ?")) {
            ps.setString(1, slot);
            ps.executeUpdate();
        }
    }

    /** 同步位点。 */
    public static final class State {
        public final long lsn;
        public final Timestamp updatedAt;

        State(long lsn, Timestamp updatedAt) {
            this.lsn = lsn;
            this.updatedAt = updatedAt;
        }
    }

    public static State readState(Connection c, String schema, String slot) throws SQLException {
        State state = null;
        if (exists(c, schema, "sync_state")) {
            try (PreparedStatement ps = c.prepareStatement("SELECT lsn::text, updated_at FROM "
                    + Sql.qi(schema) + ".sync_state WHERE slot_name = ?")) {
                ps.setString(1, slot);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        state = new State(Sql.lsn(rs.getString(1)), rs.getTimestamp(2));
                    }
                }
            }
        }
        commitIfNeeded(c);
        return state;
    }

    public static Map<String, Long> readTableStates(Connection c, String schema, String slot) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        if (exists(c, schema, "table_state")) {
            try (PreparedStatement ps = c.prepareStatement("SELECT table_name, lsn::text FROM "
                    + Sql.qi(schema) + ".table_state WHERE slot_name = ? ORDER BY table_name")) {
                ps.setString(1, slot);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), Sql.lsn(rs.getString(2)));
                    }
                }
            }
        }
        commitIfNeeded(c);
        return result;
    }

    public static void deleteState(Connection c, String schema, String slot) throws SQLException {
        for (String table : new String[]{"sync_state", "table_state"}) {
            if (exists(c, schema, table)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + Sql.qi(schema) + "." + table + " WHERE slot_name = ?")) {
                    ps.setString(1, slot);
                    ps.executeUpdate();
                }
            }
        }
        commitIfNeeded(c);
    }

    private static boolean exists(Connection c, String schema, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?)::text")) {
            ps.setString(1, Sql.qi(schema) + "." + table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null;
            }
        }
    }

    private static void commitIfNeeded(Connection c) throws SQLException {
        if (!c.getAutoCommit()) {
            c.commit();
        }
    }
}
