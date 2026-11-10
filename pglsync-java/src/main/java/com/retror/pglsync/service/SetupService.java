package com.retror.pglsync.service;

import com.retror.pglsync.catalog.Catalog;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.util.Console;
import com.retror.pglsync.util.Db;
import com.retror.pglsync.util.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * check / setup：检查源库逻辑复制前置条件，并创建发布（publication）。
 *
 * <p>TimescaleDB：超表数据存放在 chunk 中且会自动创建新 chunk，发布必须覆盖 chunk 所在的 schema：
 * PG15+ 用 FOR TABLE ..., TABLES IN SCHEMA _timescaledb_internal；PG10~14 只能 FOR ALL TABLES。
 * 被发布的表如果没有复制标识（主键），源库对它的 UPDATE/DELETE 会直接报错，因此创建前必须检查。
 */
public class SetupService {
    private final SyncConfig cfg;

    public SetupService(SyncConfig cfg) {
        this.cfg = cfg;
    }

    private static String setting(Connection c, String name) throws SQLException {
        return Catalog.single(c, "SELECT setting FROM pg_settings WHERE name = ?", name);
    }

    private static final class BadTable {
        final String schema;
        final String name;
        final String ident;
        /** 可用作复制标识的唯一索引名，没有则为 null。 */
        final String uniqueIndex;

        BadTable(String schema, String name, String ident, String uniqueIndex) {
            this.schema = schema;
            this.name = name;
            this.ident = ident;
            this.uniqueIndex = uniqueIndex;
        }
    }

    /** 修复语句：有合适的唯一索引时用它作为复制标识（WAL 开销与主键相同），否则 FULL。 */
    static String riFixSql(String schema, String name, String uniqueIndex) {
        if (uniqueIndex != null) {
            return "ALTER TABLE " + Sql.qname(schema, name) + " REPLICA IDENTITY USING INDEX " + Sql.qi(uniqueIndex);
        }
        return "ALTER TABLE " + Sql.qname(schema, name) + " REPLICA IDENTITY FULL";
    }

    private List<BadTable> findBadReplicaIdentity(Connection c, String mode, List<String> chunkSchemas)
            throws SQLException {
        List<String> explicit = cfg.getTables().values().stream().map(TableConfig::qualified).collect(Collectors.toList());
        String where;
        if ("all".equals(mode)) {
            where = "n.nspname NOT IN ('pg_catalog', 'information_schema') "
                    + "AND left(n.nspname, 8) <> 'pg_toast' AND left(n.nspname, 7) <> 'pg_temp'";
        } else if ("schema".equals(mode)) {
            where = "((n.nspname || '.' || c.relname) = ANY(?) OR n.nspname = ANY(?))";
        } else {
            where = "(n.nspname || '.' || c.relname) = ANY(?)";
        }
        // compress_hyper_* 是 TimescaleDB 内部压缩表，只被扩展内部写入，不经过复制标识检查。
        // 可用作复制标识的索引：唯一、非部分、非表达式、立即检查、有效，且所有列 NOT NULL。
        String sql = "SELECT n.nspname, c.relname, c.relreplident::text, "
                + "EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisprimary), "
                + "EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisreplident), "
                + "(SELECT ic.relname::text FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid "
                + "  WHERE i.indrelid = c.oid AND i.indisunique AND i.indpred IS NULL "
                + "    AND i.indexprs IS NULL AND i.indimmediate AND i.indisvalid "
                + "    AND NOT EXISTS (SELECT 1 FROM pg_attribute a "
                + "                     WHERE a.attrelid = c.oid AND a.attnum = ANY (i.indkey) AND NOT a.attnotnull) "
                + "  ORDER BY i.indnatts, ic.relname LIMIT 1) "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE c.relkind IN ('r', 'p') AND c.relpersistence = 'p' "
                + "AND left(c.relname, 15) <> 'compress_hyper_' AND " + where + " ORDER BY 1, 2";
        List<BadTable> bad = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (!"all".equals(mode)) {
                ps.setArray(1, c.createArrayOf("text", explicit.toArray()));
                if ("schema".equals(mode)) {
                    ps.setArray(2, c.createArrayOf("text", chunkSchemas.toArray()));
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ident = rs.getString(3);
                    boolean hasPk = rs.getBoolean(4);
                    boolean hasRiIdx = rs.getBoolean(5);
                    if ("n".equals(ident) || ("d".equals(ident) && !hasPk) || ("i".equals(ident) && !hasRiIdx)) {
                        bad.add(new BadTable(rs.getString(1), rs.getString(2), ident, rs.getString(6)));
                    }
                }
            }
        }
        return bad;
    }

    private static String[] hypertableRoot(Connection c, String schema, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pn.nspname, p.relname FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_inherits i ON i.inhrelid = c.oid "
                + "JOIN pg_class p ON p.oid = i.inhparent JOIN pg_namespace pn ON pn.oid = p.relnamespace "
                + "WHERE n.nspname = ? AND c.relname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null;
            }
        }
    }

    private void fixReplicaIdentity(Connection c, List<BadTable> bad, List<String> chunkSchemas) throws SQLException {
        Set<String> roots = new HashSet<>();
        for (BadTable b : bad) {
            String[] root = chunkSchemas.contains(b.schema) ? hypertableRoot(c, b.schema, b.name) : null;
            if (root != null) {
                if (roots.add(Sql.qname(root[0], root[1]))) {
                    Console.println("  ALTER TABLE " + Sql.qname(root[0], root[1]) + " REPLICA IDENTITY FULL  (超表)");
                    Db.execute(c, "ALTER TABLE " + Sql.qname(root[0], root[1]) + " REPLICA IDENTITY FULL");
                }
                // chunk 统一用 FULL，与超表保持一致
                String stmt = riFixSql(b.schema, b.name, null);
                Console.println("  " + stmt);
                Db.execute(c, stmt);
            } else {
                // 普通表 / TimescaleDB 内部目录表：有合适唯一索引时用索引
                String stmt = riFixSql(b.schema, b.name, b.uniqueIndex);
                Console.println("  " + stmt);
                Db.execute(c, stmt);
            }
        }
    }

    private void timescaleWarnings(Connection c, List<String> warnings) {
        Set<String> synced = cfg.getTables().keySet();
        try {
            List<String> compressed = Catalog.strings(c, "SELECT hypertable_schema || '.' || hypertable_name "
                    + "FROM timescaledb_information.hypertables WHERE compression_enabled ORDER BY 1");
            compressed.retainAll(synced);
            if (!compressed.isEmpty()) {
                warnings.add("超表 " + String.join(", ", compressed) + " 启用了压缩。压缩/解压过程会在 WAL 中产生 chunk 行的"
                        + "删除和重新插入，请在测试环境验证后再上线，并定期执行 reconcile 核对已压缩时间段。");
            }
            List<String> retention = Catalog.strings(c, "SELECT DISTINCT hypertable_schema || '.' || hypertable_name "
                    + "FROM timescaledb_information.jobs WHERE proc_name = 'policy_retention' ORDER BY 1");
            retention.retainAll(synced);
            if (!retention.isEmpty()) {
                warnings.add("超表 " + String.join(", ", retention) + " 配置了数据保留策略。drop_chunks 删除整个 chunk "
                        + "不会产生逐行变更，目标库不会同步删除，请在目标库配置相同的保留策略。");
            }
        } catch (SQLException e) {
            warnings.add("无法读取 TimescaleDB 策略信息: " + e.getMessage());
        }
    }

    /** apply=false 时只检查（check 命令）。返回进程退出码。 */
    public int run(boolean apply, boolean fixRi, boolean force) throws Exception {
        boolean ok = true;
        try (Connection c = Db.connect(cfg.getSource(), true)) {
            List<String> problems = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int ver = Integer.parseInt(setting(c, "server_version_num"));
            Console.println("源库 PostgreSQL 版本号: " + ver);
            if (ver < 100000) {
                problems.add("PostgreSQL 版本过低（需要 10+ 才有 pgoutput）");
            }
            String walLevel = setting(c, "wal_level");
            if (!"logical".equals(walLevel)) {
                problems.add("wal_level = " + walLevel + "，需要改为 logical：ALTER SYSTEM SET wal_level = 'logical'; 然后重启 PostgreSQL");
            }
            int usedSlots = Integer.parseInt(Catalog.single(c, "SELECT count(*)::text FROM pg_replication_slots"));
            boolean ownSlot = Catalog.single(c, "SELECT slot_name FROM pg_replication_slots WHERE slot_name = ?",
                    cfg.getSlotName()) != null;
            int maxSlots = Integer.parseInt(setting(c, "max_replication_slots"));
            if (!ownSlot && usedSlots >= maxSlots) {
                problems.add("max_replication_slots = " + maxSlots + " 已用完（已有 " + usedSlots + " 个槽），请调大并重启");
            }
            int maxSenders = Integer.parseInt(setting(c, "max_wal_senders"));
            if (maxSenders < 1) {
                problems.add("max_wal_senders = " + maxSenders + "，至少需要 1（建议 10），修改后重启");
            }
            if (ver >= 130000) {
                if ("-1".equals(setting(c, "max_slot_wal_keep_size"))) {
                    warnings.add("max_slot_wal_keep_size = -1（不限制）。同步长时间中断时复制槽会无限保留 WAL，可能撑满磁盘。"
                            + "建议按磁盘容量设置上限，例如: ALTER SYSTEM SET max_slot_wal_keep_size = '50GB'; SELECT pg_reload_conf();");
                }
            } else {
                warnings.add("PG < 13 没有 max_slot_wal_keep_size，必须监控复制槽积压（status 命令）");
            }
            String role = Catalog.single(c, "SELECT (rolsuper OR rolreplication)::text FROM pg_roles WHERE rolname = current_user");
            if (!"true".equals(role)) {
                problems.add("当前用户既不是超级用户也没有 REPLICATION 权限：ALTER ROLE <user> REPLICATION;");
            }

            List<String> missing = new ArrayList<>();
            for (TableConfig t : cfg.getTables().values()) {
                if (!Catalog.tableExists(c, t.getSchema(), t.getName())) {
                    missing.add(t.qualified());
                }
            }
            if (!missing.isEmpty()) {
                problems.add("源库中不存在表: " + String.join(", ", missing));
            }

            boolean timescale = Catalog.hasTimescaledb(c);
            List<String> chunkSchemas = new ArrayList<>();
            if (timescale) {
                for (String s : cfg.getChunkSchemas()) {
                    if (Catalog.single(c, "SELECT nspname FROM pg_namespace WHERE nspname = ?", s) != null) {
                        chunkSchemas.add(s);
                    }
                }
            }
            String mode;
            if (chunkSchemas.isEmpty()) {
                mode = "tables";
            } else {
                mode = cfg.getPublicationMode();
                if ("auto".equals(mode)) {
                    mode = ver >= 150000 ? "schema" : "all";
                }
            }
            if ("schema".equals(mode) && ver < 150000) {
                problems.add("publication-mode=schema 需要 PostgreSQL 15+，当前版本请使用 all");
            }
            if (timescale) {
                timescaleWarnings(c, warnings);
            }
            Console.println("TimescaleDB: " + (timescale ? "已安装" : "未安装") + "，发布模式: " + mode);
            if ("all".equals(mode)) {
                warnings.add("FOR ALL TABLES 会发布库内所有表的变更（客户端过滤），解码开销较大，且库内所有表都必须有复制标识。");
            }

            List<BadTable> bad = findBadReplicaIdentity(c, mode, chunkSchemas);
            if (!bad.isEmpty()) {
                Console.println("\n以下表没有可用的复制标识（加入发布后，源库对它们的 UPDATE/DELETE 会报错！）:");
                for (BadTable b : bad) {
                    String note = chunkSchemas.contains(b.schema) && !b.name.startsWith("_hyper_")
                            ? "  <- TimescaleDB 内部目录表（因 TABLES IN SCHEMA " + b.schema + " 被纳入发布）" : "";
                    Console.println("  - " + Sql.qname(b.schema, b.name) + " (replident=" + b.ident + ")" + note);
                    Console.println("      建议: " + riFixSql(b.schema, b.name, b.uniqueIndex) + ";");
                }
                if (apply && fixRi) {
                    Console.println("\n修复复制标识（有合适唯一索引的表使用该索引，否则 REPLICA IDENTITY FULL）:");
                    fixReplicaIdentity(c, bad, chunkSchemas);
                    bad = findBadReplicaIdentity(c, mode, chunkSchemas);
                    if (!bad.isEmpty()) {
                        problems.add("仍有表缺少复制标识: " + bad.stream().map(b -> b.schema + "." + b.name)
                                .collect(Collectors.joining(", ")));
                    }
                } else if (!force) {
                    problems.add("存在缺少复制标识的表。请先为其添加主键，或执行 setup --fix-replica-identity"
                            + "（设置 REPLICA IDENTITY FULL），或确认这些表从不 UPDATE/DELETE 后使用 --force");
                }
            }

            List<String> actions = publicationActions(c, ver, mode, chunkSchemas, problems);

            if (!warnings.isEmpty()) {
                Console.println("\n警告:");
                warnings.forEach(w -> Console.println("  ! " + w));
            }
            if (!problems.isEmpty()) {
                ok = false;
                Console.println("\n问题（必须解决）:");
                problems.forEach(p -> Console.println("  x " + p));
            }
            if (!actions.isEmpty()) {
                Console.println("\n需要执行的发布变更:");
                actions.forEach(a -> Console.println("  " + a + ";"));
                if (apply && ok) {
                    for (String a : actions) {
                        Db.execute(c, a);
                    }
                    Console.println("已执行。");
                } else if (apply) {
                    Console.println("存在未解决的问题，未执行任何发布变更。");
                }
            } else {
                Console.println("\n发布 " + cfg.getPublication() + " 已就绪。");
            }
            if (ok) {
                Console.println("\n检查通过。" + (apply ? "" : "执行 setup 创建发布，然后执行 init。"));
            }
        }
        return ok ? 0 : 1;
    }

    private List<String> publicationActions(Connection c, int ver, String mode, List<String> chunkSchemas,
                                            List<String> problems) throws SQLException {
        List<String> actions = new ArrayList<>();
        String pub = Sql.qi(cfg.getPublication());
        String allTables = Catalog.single(c, "SELECT puballtables::text FROM pg_publication WHERE pubname = ?",
                cfg.getPublication());
        String tableList = cfg.getTables().values().stream()
                .map(t -> Sql.qname(t.getSchema(), t.getName())).collect(Collectors.joining(", "));
        if (allTables == null) {
            if ("all".equals(mode)) {
                actions.add("CREATE PUBLICATION " + pub + " FOR ALL TABLES");
            } else {
                String target = "TABLE " + tableList;
                if ("schema".equals(mode)) {
                    target += ", TABLES IN SCHEMA " + chunkSchemas.stream().map(Sql::qi).collect(Collectors.joining(", "));
                }
                actions.add("CREATE PUBLICATION " + pub + " FOR " + target);
            }
            return actions;
        }
        if ("true".equals(allTables)) {
            return actions;
        }
        if ("all".equals(mode)) {
            problems.add("已存在的发布 " + cfg.getPublication() + " 不是 FOR ALL TABLES，请手动删除后重新 setup");
            return actions;
        }
        Set<String> published = new HashSet<>(Catalog.strings(c, "SELECT schemaname || '.' || tablename "
                + "FROM pg_publication_tables WHERE pubname = ?", cfg.getPublication()));
        List<String> lacking = cfg.getTables().values().stream().filter(t -> !published.contains(t.qualified()))
                .map(t -> Sql.qname(t.getSchema(), t.getName())).collect(Collectors.toList());
        if (!lacking.isEmpty()) {
            actions.add("ALTER PUBLICATION " + pub + " ADD TABLE " + String.join(", ", lacking));
        }
        if ("schema".equals(mode) && ver >= 150000) {
            Set<String> schemas = new HashSet<>(Catalog.strings(c, "SELECT n.nspname FROM pg_publication_namespace pn "
                    + "JOIN pg_namespace n ON n.oid = pn.pnnspid JOIN pg_publication p ON p.oid = pn.pnpubid "
                    + "WHERE p.pubname = ?", cfg.getPublication()));
            List<String> lackingS = chunkSchemas.stream().filter(s -> !schemas.contains(s)).map(Sql::qi)
                    .collect(Collectors.toList());
            if (!lackingS.isEmpty()) {
                actions.add("ALTER PUBLICATION " + pub + " ADD TABLES IN SCHEMA " + String.join(", ", lackingS));
            }
        }
        return actions;
    }
}
