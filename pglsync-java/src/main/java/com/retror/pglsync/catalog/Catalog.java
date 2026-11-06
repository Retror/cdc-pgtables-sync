package com.retror.pglsync.catalog;

import com.retror.pglsync.config.ConfigException;
import com.retror.pglsync.config.TableConfig;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 供 init / reconcile / setup 使用的元数据查询。 */
public final class Catalog {

    private Catalog() {
    }

    /** 普通列（排除生成列，与 pgoutput 发送的列一致），按定义顺序。 */
    public static List<String> tableColumns(Connection c, String schema, String name) throws SQLException {
        return strings(c, "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? AND is_generated = 'NEVER' "
                + "ORDER BY ordinal_position", schema, name);
    }

    public static boolean tableExists(Connection c, String schema, String name) throws SQLException {
        return !strings(c, "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')", schema, name).isEmpty();
    }

    public static List<String> primaryKey(Connection c, String schema, String name) throws SQLException {
        return strings(c, "SELECT a.attname FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true "
                + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum "
                + "WHERE n.nspname = ? AND c.relname = ? AND i.indisprimary ORDER BY k.ord", schema, name);
    }

    /** 表上所有唯一索引 / 约束的列集合，用于校验 ON CONFLICT 目标。 */
    public static List<Set<String>> uniqueKeySets(Connection c, String schema, String name) throws SQLException {
        List<Set<String>> result = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT array_agg(a.attname::text) FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (i.indkey) "
                + "WHERE n.nspname = ? AND c.relname = ? AND i.indisunique AND i.indpred IS NULL "
                + "GROUP BY i.indexrelid")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Array arr = rs.getArray(1);
                    result.add(new HashSet<>(Arrays.asList((String[]) arr.getArray())));
                }
            }
        }
        return result;
    }

    /** 配置的 pk 优先，否则取源库主键。 */
    public static List<String> resolvePk(Connection c, TableConfig t) throws SQLException {
        if (t.getPk() != null) {
            return new ArrayList<>(t.getPk());
        }
        List<String> pk = primaryKey(c, t.getSchema(), t.getName());
        if (pk.isEmpty()) {
            throw new ConfigException("表 " + t + " 没有主键，请在配置 tables 中为它指定 pk");
        }
        return pk;
    }

    public static boolean hasTimescaledb(Connection c) throws SQLException {
        return !strings(c, "SELECT extname FROM pg_extension WHERE extname = 'timescaledb'").isEmpty();
    }

    public static List<String> strings(Connection c, String sql, Object... params) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    public static String single(Connection c, String sql, Object... params) throws SQLException {
        List<String> rows = strings(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
