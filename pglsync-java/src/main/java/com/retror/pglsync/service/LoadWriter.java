package com.retror.pglsync.service;

import com.retror.pglsync.catalog.Catalog;
import com.retror.pglsync.config.ConfigException;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.util.Db;
import com.retror.pglsync.util.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 测试工具：定时向源库的指定表写入模拟数据，用来验证同步效果。
 *
 * <p>每一轮对每张表：
 * <ol>
 *   <li>按表结构自动生成约 N 行数据（上下浮动 10%），分成多个事务批量 INSERT；</li>
 *   <li>先开启一个“长事务”写入一小部分数据，等其他批次全部提交后它才提交（旧触发器方案丢数据的场景）；</li>
 *   <li>对刚写入的部分行执行 UPDATE / DELETE。</li>
 * </ol>
 * 列值根据 information_schema 中的列类型自动生成（统一生成 PostgreSQL 文本格式，由数据库按列类型解析）。
 */
public class LoadWriter {
    private static final Logger log = LoggerFactory.getLogger(LoadWriter.class);
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final List<String> INT_TYPES = Arrays.asList("smallint", "integer", "bigint");
    private static final List<String> NUMERIC_TYPES = Arrays.asList("smallint", "integer", "bigint", "numeric",
            "real", "double precision");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    /** 无法生成的类型。 */
    private static final String UNSUPPORTED = "\u0000UNSUPPORTED";

    /** 写入参数。 */
    public static class Options {
        public List<String> tables = Arrays.asList("public.tcrealtime", "public.tcensemblerealtime");
        public int rows = 10000;
        public double interval = 300;
        public boolean once;
        public int txnSize = 1000;
        public int longTxnRows = 100;
        public double updateRatio = 0.05;
        public double deleteRatio = 0.01;
        public String timeColumn = "datatime";
        public double timeSpreadHours = 24;
    }

    private final SyncConfig cfg;
    private final Options opt;
    private volatile boolean stop;

    public LoadWriter(SyncConfig cfg, Options opt) {
        this.cfg = cfg;
        this.opt = opt;
    }

    public void requestStop() {
        if (!stop) {
            log.info("收到停止信号，本轮结束后退出");
        }
        stop = true;
    }

    // ------------------------------------------------------------------ 列与值生成

    static final class Col {
        String name;
        String dataType;
        String udtName;
        boolean nullable;
        boolean hasDefault;
        Integer charLen;
        Integer numPrec;
        Integer numScale;
        List<String> enumValues;
    }

    private static String randStr(int n) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private static String round(double v, int scale) {
        return String.format(Locale.ROOT, "%." + Math.max(0, scale) + "f", v);
    }

    /** 按列类型生成一个随机值（PostgreSQL 文本格式）；无法生成时返回 UNSUPPORTED。 */
    static String genValue(Col c) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String t = c.dataType;
        switch (t) {
            case "smallint":
                return String.valueOf(r.nextInt(0, 32000));
            case "integer":
            case "bigint":
                return String.valueOf(r.nextInt(0, 100001));
            case "numeric": {
                int scale;
                int intDigits;
                if (c.numPrec == null) {
                    scale = 3;
                    intDigits = 6;
                } else {
                    scale = c.numScale == null ? 0 : c.numScale;
                    intDigits = c.numPrec - scale;
                }
                double upper = intDigits > 0 ? Math.min(Math.pow(10, intDigits) - 1, 100000) : 0.9;
                return round(r.nextDouble(0, upper), scale);
            }
            case "real":
            case "double precision":
                return round(r.nextDouble(-1000, 1000), 4);
            case "character":
                return randStr(Math.min(c.charLen == null ? 1 : c.charLen, 32));
            case "character varying":
            case "text":
                return randStr(Math.min(c.charLen == null ? 12 : c.charLen, 12));
            case "boolean":
                return r.nextBoolean() ? "true" : "false";
            case "timestamp with time zone":
                return OffsetDateTime.now(ZoneOffset.UTC).format(TS) + "+00";
            case "timestamp without time zone":
                return LocalDateTime.now().format(TS);
            case "date":
                return LocalDate.now().toString();
            case "time without time zone":
                return LocalTime.now().toString();
            case "json":
            case "jsonb":
                return "{\"test\": true, \"v\": " + r.nextInt(1000) + "}";
            case "uuid":
                return UUID.randomUUID().toString();
            case "interval":
                return r.nextInt(1, 600) + " minutes";
            case "inet":
                return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
            case "ARRAY":
                return genArray(c.udtName);
            case "USER-DEFINED":
                return c.enumValues == null || c.enumValues.isEmpty()
                        ? UNSUPPORTED : c.enumValues.get(r.nextInt(c.enumValues.size()));
            default:
                return UNSUPPORTED;
        }
    }

    private static String genArray(String udt) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            switch (udt) {
                case "_int2":
                case "_int4":
                case "_int8":
                    items.add(String.valueOf(r.nextInt(0, 1000)));
                    break;
                case "_float4":
                case "_float8":
                case "_numeric":
                    items.add(round(r.nextDouble(0, 1000), 2));
                    break;
                case "_text":
                case "_varchar":
                    items.add(randStr(8));
                    break;
                default:
                    return UNSUPPORTED;
            }
        }
        return "{" + String.join(",", items) + "}";
    }

    /** 一张表的写入计划。 */
    final class Plan {
        final TableConfig table;
        final List<String> pk;
        final String timeColumn;
        final List<Col> genCols;
        final List<String> counterCols = new ArrayList<>();
        final Col updateCol;

        Plan(Connection c, TableConfig table, String timeColumn) throws SQLException {
            this.table = table;
            List<Col> cols = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT column_name, data_type, udt_name, "
                    + "is_nullable = 'YES', column_default IS NOT NULL OR is_identity = 'YES', "
                    + "character_maximum_length, numeric_precision, numeric_scale "
                    + "FROM information_schema.columns WHERE table_schema = ? AND table_name = ? "
                    + "AND is_generated = 'NEVER' ORDER BY ordinal_position")) {
                ps.setString(1, table.getSchema());
                ps.setString(2, table.getName());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Col col = new Col();
                        col.name = rs.getString(1);
                        col.dataType = rs.getString(2);
                        col.udtName = rs.getString(3);
                        col.nullable = rs.getBoolean(4);
                        col.hasDefault = rs.getBoolean(5);
                        col.charLen = (Integer) rs.getObject(6);
                        col.numPrec = (Integer) rs.getObject(7);
                        col.numScale = (Integer) rs.getObject(8);
                        cols.add(col);
                    }
                }
            }
            if (cols.isEmpty()) {
                throw new ConfigException("源库中不存在表 " + table);
            }
            for (Col col : cols) {
                if ("USER-DEFINED".equals(col.dataType)) {
                    col.enumValues = Catalog.strings(c, "SELECT e.enumlabel FROM pg_enum e JOIN pg_type t "
                            + "ON t.oid = e.enumtypid WHERE t.typname = ? ORDER BY e.enumsortorder", col.udtName);
                }
            }
            this.pk = Catalog.primaryKey(c, table.getSchema(), table.getName());
            if (pk.isEmpty()) {
                throw new ConfigException("表 " + table + " 没有主键，无法用于同步测试");
            }
            this.timeColumn = cols.stream().anyMatch(x -> x.name.equals(timeColumn)) ? timeColumn : null;
            this.genCols = cols.stream().filter(x -> !x.hasDefault).collect(Collectors.toList());
            if (genCols.isEmpty()) {
                throw new ConfigException("表 " + table + " 所有列都有默认值，暂不支持");
            }
            for (Col col : genCols) {
                if (pk.contains(col.name) && INT_TYPES.contains(col.dataType)) {
                    counterCols.add(col.name);
                }
            }
            for (Col col : genCols) {
                if (counterCols.contains(col.name) || col.name.equals(this.timeColumn)) {
                    continue;
                }
                if (!col.nullable && UNSUPPORTED.equals(genValue(col))) {
                    throw new ConfigException("表 " + table + " 的列 " + col.name + "（类型 " + col.udtName
                            + "）非空且无默认值，脚本无法生成该类型的数据");
                }
            }
            List<Col> candidates = genCols.stream()
                    .filter(x -> !pk.contains(x.name) && !x.name.equals(this.timeColumn) && !UNSUPPORTED.equals(genValue(x)))
                    .sorted((a, b) -> Integer.compare(NUMERIC_TYPES.contains(a.dataType) ? 0 : 1,
                            NUMERIC_TYPES.contains(b.dataType) ? 0 : 1))
                    .collect(Collectors.toList());
            this.updateCol = candidates.isEmpty() ? null : candidates.get(0);
            log.info("{}: 主键 {}，脚本生成列 {} 个，时间列 {}，UPDATE 使用列 {}", table, pk, genCols.size(),
                    this.timeColumn == null ? "无" : this.timeColumn,
                    updateCol == null ? "无（跳过 UPDATE）" : updateCol.name);
        }

        Map<String, Long> nextCounters(Connection c) throws SQLException {
            Map<String, Long> counters = new HashMap<>();
            for (String col : counterCols) {
                String v = Catalog.single(c, "SELECT (COALESCE(max(" + Sql.qi(col) + "), 0) + 1)::text FROM "
                        + Sql.qname(table.getSchema(), table.getName()));
                counters.put(col, Long.parseLong(v));
            }
            c.commit();
            return counters;
        }

        List<String> makeRow(Map<String, Long> counters, double spreadSeconds) {
            long deltaMicros = (long) (ThreadLocalRandom.current().nextDouble(0, Math.max(spreadSeconds, 0.000001)) * 1e6);
            List<String> row = new ArrayList<>(genCols.size());
            for (Col col : genCols) {
                if (counters.containsKey(col.name)) {
                    long v = counters.get(col.name);
                    row.add(String.valueOf(v));
                    counters.put(col.name, v + 1);
                } else if (col.name.equals(timeColumn) && "timestamp with time zone".equals(col.dataType)) {
                    row.add(OffsetDateTime.now(ZoneOffset.UTC).minusNanos(deltaMicros * 1000).format(TS) + "+00");
                } else if (col.name.equals(timeColumn) && "timestamp without time zone".equals(col.dataType)) {
                    row.add(LocalDateTime.now().minusNanos(deltaMicros * 1000).format(TS));
                } else {
                    String v = genValue(col);
                    row.add(UNSUPPORTED.equals(v) ? null : v);
                }
            }
            return row;
        }

        /** 批量插入，返回插入行的主键值（文本）。 */
        List<List<String>> insert(Connection c, List<List<String>> rows) throws SQLException {
            List<List<String>> pks = new ArrayList<>();
            int ncols = genCols.size();
            int perStmt = Math.max(1, Math.min(1000, 32767 / ncols));
            String colList = genCols.stream().map(x -> Sql.qi(x.name)).collect(Collectors.joining(", "));
            String one = "(" + String.join(", ", Collections.nCopies(ncols, "?")) + ")";
            for (int i = 0; i < rows.size(); i += perStmt) {
                List<List<String>> page = rows.subList(i, Math.min(rows.size(), i + perStmt));
                String sql = "INSERT INTO " + Sql.qname(table.getSchema(), table.getName()) + " (" + colList
                        + ") VALUES " + String.join(", ", Collections.nCopies(page.size(), one))
                        + " ON CONFLICT DO NOTHING RETURNING " + pk.stream().map(Sql::qi)
                        .map(x -> x + "::text").collect(Collectors.joining(", "));
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    int idx = 1;
                    for (List<String> row : page) {
                        for (String v : row) {
                            if (v == null) {
                                ps.setNull(idx++, Types.OTHER);
                            } else {
                                ps.setString(idx++, v);
                            }
                        }
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            List<String> key = new ArrayList<>();
                            for (int k = 1; k <= pk.size(); k++) {
                                key.add(rs.getString(k));
                            }
                            pks.add(key);
                        }
                    }
                }
            }
            return pks;
        }

        String wherePk() {
            return pk.stream().map(x -> Sql.qi(x) + " = ?").collect(Collectors.joining(" AND "));
        }
    }

    private void writeRound(Connection conn, Connection longConn, Plan plan) throws SQLException {
        long started = System.currentTimeMillis();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int total = Math.max(1, (int) (opt.rows * r.nextDouble(0.9, 1.1)));
        Map<String, Long> counters = plan.nextCounters(conn);
        List<List<String>> rows = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            rows.add(plan.makeRow(counters, opt.timeSpreadHours * 3600));
        }
        int nLong = longConn != null ? Math.min(opt.longTxnRows, total) : 0;
        List<List<String>> longRows = rows.subList(0, nLong);
        List<List<String>> normal = rows.subList(nLong, rows.size());
        List<List<String>> pks = new ArrayList<>();

        // 长事务：先写入但不提交
        if (!longRows.isEmpty()) {
            pks.addAll(plan.insert(longConn, longRows));
        }
        // 普通批次：每 txnSize 行一个事务
        for (int i = 0; i < normal.size(); i += opt.txnSize) {
            pks.addAll(plan.insert(conn, normal.subList(i, Math.min(normal.size(), i + opt.txnSize))));
            conn.commit();
        }
        // 其他批次都提交后，长事务才提交
        if (!longRows.isEmpty()) {
            longConn.commit();
        }

        int nUpd = plan.updateCol != null ? (int) (pks.size() * opt.updateRatio) : 0;
        int nDel = (int) (pks.size() * opt.deleteRatio);
        Collections.shuffle(pks);
        List<List<String>> upd = pks.subList(0, Math.min(nUpd, pks.size()));
        List<List<String>> del = pks.subList(upd.size(), Math.min(upd.size() + nDel, pks.size()));
        String table = Sql.qname(plan.table.getSchema(), plan.table.getName());
        if (!upd.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + table + " SET "
                    + Sql.qi(plan.updateCol.name) + " = ? WHERE " + plan.wherePk())) {
                for (List<String> key : upd) {
                    ps.setString(1, genValue(plan.updateCol));
                    for (int k = 0; k < key.size(); k++) {
                        ps.setString(k + 2, key.get(k));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (!del.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE " + plan.wherePk())) {
                for (List<String> key : del) {
                    for (int k = 0; k < key.size(); k++) {
                        ps.setString(k + 1, key.get(k));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        conn.commit();
        log.info(String.format("%s: 插入 %d 行（其中长事务 %d 行），更新 %d 行，删除 %d 行，耗时 %.1fs",
                plan.table, pks.size(), longRows.size(), upd.size(), del.size(),
                (System.currentTimeMillis() - started) / 1000.0));
    }

    private List<TableConfig> tables() {
        List<TableConfig> out = new ArrayList<>();
        for (String item : opt.tables) {
            item = item.trim();
            if (item.isEmpty()) {
                continue;
            }
            String key = item.contains(".") ? item : "public." + item;
            TableConfig tc = cfg.getTables().get(key);
            if (tc == null) {
                int dot = key.indexOf('.');
                tc = new TableConfig(key.substring(0, dot), key.substring(dot + 1));
            }
            out.add(tc);
        }
        return out;
    }

    public int run() throws InterruptedException {
        Connection conn = null;
        Connection longConn = null;
        List<Plan> plans = null;
        int round = 0;
        try {
            while (true) {
                round++;
                long started = System.currentTimeMillis();
                try {
                    if (conn == null || conn.isClosed()) {
                        conn = Db.connect(cfg.getSource(), false);
                        longConn = opt.longTxnRows > 0 ? Db.connect(cfg.getSource(), false) : null;
                        plans = new ArrayList<>();
                        for (TableConfig t : tables()) {
                            String tc = t.getTimeColumn() != null ? t.getTimeColumn() : opt.timeColumn;
                            plans.add(new Plan(conn, t, tc));
                        }
                        conn.commit();
                    }
                    log.info("===== 第 {} 轮写入开始 =====", round);
                    for (Plan p : plans) {
                        writeRound(conn, longConn, p);
                    }
                } catch (ConfigException e) {
                    log.error(e.getMessage());
                    return 3;
                } catch (SQLException e) {
                    log.error("本轮写入失败，下一轮将重新连接", e);
                    Db.closeQuietly(conn);
                    Db.closeQuietly(longConn);
                    conn = null;
                    longConn = null;
                }
                if (opt.once || stop) {
                    break;
                }
                long nextAt = started + (long) (opt.interval * 1000);
                log.info("下一轮开始时间: {}", LocalTime.now().plusNanos((nextAt - System.currentTimeMillis()) * 1_000_000L)
                        .withNano(0));
                while (!stop && System.currentTimeMillis() < nextAt) {
                    Thread.sleep(Math.min(1000, Math.max(1, nextAt - System.currentTimeMillis())));
                }
                if (stop) {
                    break;
                }
            }
            return 0;
        } finally {
            Db.closeQuietly(conn);
            Db.closeQuietly(longConn);
        }
    }
}
