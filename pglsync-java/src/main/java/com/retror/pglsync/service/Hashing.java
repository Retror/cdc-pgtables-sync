package com.retror.pglsync.service;

import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.util.Db;
import com.retror.pglsync.util.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 行数 + 整行哈希的分桶比较（reconcile 与 init --no-copy 共用）。 */
public final class Hashing {

    /** 两边使用相同的会话设置，保证 ROW(...)::text 的文本表示一致。 */
    public static final String[] SESSION_SETTINGS = {
            "SET TimeZone = 'UTC'",
            "SET DateStyle = 'ISO, YMD'",
            "SET IntervalStyle = 'postgres'",
            "SET extra_float_digits = 3",
            "SET bytea_output = 'hex'",
    };

    private static final Map<String, String> BUCKET_INTERVAL = new HashMap<>();

    static {
        BUCKET_INTERVAL.put("hour", "1 hour");
        BUCKET_INTERVAL.put("day", "1 day");
        BUCKET_INTERVAL.put("week", "1 week");
        BUCKET_INTERVAL.put("month", "1 month");
    }

    private Hashing() {
    }

    /** 一个桶的行数与哈希。 */
    public static final class Bucket {
        public final long count;
        public final String hash;

        public Bucket(long count, String hash) {
            this.count = count;
            this.hash = hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Bucket)) {
                return false;
            }
            Bucket b = (Bucket) o;
            return count == b.count && Objects.equals(hash, b.hash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(count, hash);
        }
    }

    public static void applySessionSettings(Connection c) throws SQLException {
        for (String s : SESSION_SETTINGS) {
            Db.execute(c, s);
        }
    }

    private static String where(TableConfig t, String since, String bucket, List<String> params) {
        List<String> clauses = new ArrayList<>();
        if (t.getTimeColumn() != null) {
            String col = Sql.qi(t.getTimeColumn());
            if (since != null) {
                clauses.add(col + " >= ?");
                params.add(since);
            }
            if (bucket != null) {
                clauses.add(col + " >= ?::timestamptz AND " + col + " < ?::timestamptz + ?::interval");
                params.add(bucket);
                params.add(bucket);
                params.add(BUCKET_INTERVAL.get(t.getBucket()));
            }
        }
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    /** 返回 桶 -> (行数, 哈希)，按桶排序。 */
    public static TreeMap<String, Bucket> bucketHashes(Connection c, TableConfig t, List<String> cols,
                                                       List<String> pk, String since, String bucket)
            throws SQLException {
        String rowHash = "md5(ROW(" + Sql.qcols(cols) + ")::text)";
        String order = Sql.qcols(pk);
        String bucketExpr = t.getTimeColumn() != null
                ? "date_trunc('" + t.getBucket() + "', " + Sql.qi(t.getTimeColumn()) + ")::text"
                : "'*'";
        List<String> params = new ArrayList<>();
        String w = where(t, since, bucket, params);
        String sql = "SELECT " + bucketExpr + ", count(*), md5(string_agg(" + rowHash + ", '' ORDER BY " + order
                + ")) FROM " + Sql.qname(t.getSchema(), t.getName()) + w + " GROUP BY 1";
        TreeMap<String, Bucket> out = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new Bucket(rs.getLong(2), rs.getString(3)));
                }
            }
        }
        return out;
    }

    /** 不一致的桶列表。 */
    public static List<String> diff(Map<String, Bucket> a, Map<String, Bucket> b) {
        TreeMap<String, Boolean> keys = new TreeMap<>();
        a.keySet().forEach(k -> keys.put(k, true));
        b.keySet().forEach(k -> keys.put(k, true));
        return keys.keySet().stream().filter(k -> !Objects.equals(a.get(k), b.get(k))).collect(Collectors.toList());
    }

    public static long total(Map<String, Bucket> m) {
        return m.values().stream().mapToLong(x -> x.count).sum();
    }

    /** 某个桶内每行的 主键文本 -> 整行哈希。 */
    public static Map<String, String> rowHashes(Connection c, TableConfig t, List<String> cols, List<String> pk,
                                                String bucket) throws SQLException {
        List<String> params = new ArrayList<>();
        String w = where(t, null, "*".equals(bucket) ? null : bucket, params);
        String sql = "SELECT ROW(" + Sql.qcols(pk) + ")::text, md5(ROW(" + Sql.qcols(cols) + ")::text) FROM "
                + Sql.qname(t.getSchema(), t.getName()) + w;
        Map<String, String> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }
}
