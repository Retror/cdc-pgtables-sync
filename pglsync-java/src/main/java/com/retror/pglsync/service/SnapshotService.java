package com.retror.pglsync.service;

import com.retror.pglsync.catalog.Catalog;
import com.retror.pglsync.config.ConfigException;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.state.StateStore;
import com.retror.pglsync.util.Console;
import com.retror.pglsync.util.Db;
import com.retror.pglsync.util.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * init / resync：基于复制槽导出的一致性快照，做全量复制或数据核对。
 *
 * <p>init（默认）：
 * <ol>
 *   <li>CREATE_REPLICATION_SLOT ... LOGICAL pgoutput EXPORT_SNAPSHOT -> consistent_point + 快照名；</li>
 *   <li>另开源库连接 SET TRANSACTION SNAPSHOT，读到的数据恰好是 consistent_point 时刻的状态；</li>
 *   <li>目标库一个事务内 TRUNCATE + COPY 全部表，写入位点 = consistent_point。</li>
 * </ol>
 *
 * <p>init --no-copy：目标库已有数据时，用同一快照逐表核对，一致则直接写入位点；
 * 不一致按 on-mismatch 处理（abort 放弃 / copy 只重新复制不一致的表 / ignore 保留差异）。
 *
 * <p>resync：主复制槽不变，用临时复制槽的快照重新复制指定表，并记录表级位点。
 */
public class SnapshotService {
    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("^[0-9A-Fa-f-]+$");

    private final SyncConfig cfg;

    public SnapshotService(SyncConfig cfg) {
        this.cfg = cfg;
    }

    /** 预检结果：表、源库列、主键。 */
    static final class Item {
        final TableConfig table;
        final List<String> cols;
        final List<String> pk;

        Item(TableConfig table, List<String> cols, List<String> pk) {
            this.table = table;
            this.cols = cols;
            this.pk = pk;
        }
    }

    /** 创建好的快照：复制连接必须保持打开且不执行其他命令，快照才有效。 */
    private static final class Snapshot implements AutoCloseable {
        final Connection repl;
        final Connection snap;
        final long lsn;

        Snapshot(Connection repl, Connection snap, long lsn) {
            this.repl = repl;
            this.snap = snap;
            this.lsn = lsn;
        }

        @Override
        public void close() {
            Db.rollbackQuietly(snap);
            Db.closeQuietly(snap);
            Db.closeQuietly(repl);
        }
    }

    private Snapshot createSnapshot(String slotName, boolean temporary) throws Exception {
        Connection repl = Db.connectReplication(cfg.getSource());
        Connection snap = null;
        try {
            String consistentPoint;
            String snapshotName;
            try (Statement st = repl.createStatement();
                 ResultSet rs = st.executeQuery("CREATE_REPLICATION_SLOT " + slotName
                         + (temporary ? " TEMPORARY" : "") + " LOGICAL pgoutput EXPORT_SNAPSHOT")) {
                rs.next();
                consistentPoint = rs.getString("consistent_point");
                snapshotName = rs.getString("snapshot_name");
            }
            if (snapshotName == null || !SNAPSHOT_NAME.matcher(snapshotName).matches()) {
                throw new IllegalStateException("快照名非法: " + snapshotName);
            }
            log.info("已创建{}复制槽 {}，一致性位点 {}，快照 {}", temporary ? "临时" : "", slotName,
                    consistentPoint, snapshotName);
            snap = Db.connect(cfg.getSource());
            snap.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            snap.setAutoCommit(false);
            Db.execute(snap, "SET TRANSACTION SNAPSHOT '" + snapshotName + "'");
            return new Snapshot(repl, snap, Sql.lsn(consistentPoint));
        } catch (Exception e) {
            Db.closeQuietly(snap);
            Db.closeQuietly(repl);
            throw e;
        }
    }

    /** 检查表结构。 */
    List<Item> preflight(Connection src, Connection tgt, Collection<TableConfig> tables) throws Exception {
        List<Item> plan = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (TableConfig t : tables) {
            if (!Catalog.tableExists(src, t.getSchema(), t.getName())) {
                problems.add("源库不存在表 " + t);
                continue;
            }
            if (!Catalog.tableExists(tgt, t.getSchema(), t.getName())) {
                problems.add("目标库不存在表 " + t + "（请先用 pg_dump --schema-only 建表）");
                continue;
            }
            List<String> srcCols = Catalog.tableColumns(src, t.getSchema(), t.getName());
            List<String> tgtCols = Catalog.tableColumns(tgt, t.getSchema(), t.getName());
            List<String> missing = new ArrayList<>(srcCols);
            missing.removeAll(tgtCols);
            if (!missing.isEmpty()) {
                problems.add("目标表 " + t + " 缺少列: " + String.join(", ", missing));
            }
            List<String> extra = new ArrayList<>(tgtCols);
            extra.removeAll(srcCols);
            if (!extra.isEmpty()) {
                log.warn("目标表 {} 多出列 {}（将保持默认值）", t, String.join(", ", extra));
            }
            List<String> pk;
            try {
                pk = Catalog.resolvePk(src, t);
            } catch (ConfigException e) {
                problems.add(e.getMessage());
                continue;
            }
            if (!Catalog.uniqueKeySets(tgt, t.getSchema(), t.getName()).contains(new HashSet<>(pk))) {
                problems.add("目标表 " + t + " 上没有与主键 " + pk + " 完全一致的唯一约束（ON CONFLICT 需要）");
            }
            plan.add(new Item(t, srcCols, pk));
        }
        if (!problems.isEmpty()) {
            throw new ConfigException("前置检查失败:\n  - " + String.join("\n  - ", problems));
        }
        if (!tgt.getAutoCommit()) {
            tgt.commit();
        }
        return plan;
    }

    private static Boolean slotActive(Connection src, String slot) throws Exception {
        try (PreparedStatement ps = src.prepareStatement(
                "SELECT active FROM pg_replication_slots WHERE slot_name = ?")) {
            ps.setString(1, slot);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }

    private void dropSlot(String slot) throws Exception {
        try (Connection c = Db.connect(cfg.getSource(), true);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots WHERE slot_name = ?")) {
            ps.setString(1, slot);
            ps.executeQuery().close();
        }
    }

    private static long copyTables(Snapshot s, Connection tgt, List<Item> items) throws Exception {
        long total = 0;
        for (Item it : items) {
            long started = System.currentTimeMillis();
            Db.execute(tgt, "TRUNCATE TABLE " + Sql.qname(it.table.getSchema(), it.table.getName()));
            long rows = TableCopier.copy(s.snap, tgt, it.table, it.cols);
            total += rows;
            log.info(String.format("已复制 %-45s %12s 行  %.1fs", it.table, Console.n(rows),
                    (System.currentTimeMillis() - started) / 1000.0));
        }
        return total;
    }

    private static void analyze(Connection tgt, List<TableConfig> tables) throws Exception {
        tgt.setAutoCommit(true);
        for (TableConfig t : tables) {
            Db.execute(tgt, "ANALYZE " + Sql.qname(t.getSchema(), t.getName()));
        }
    }

    // ------------------------------------------------------------------ init

    public int init(boolean assumeYes, boolean recreateSlot, boolean copy, boolean verify, String onMismatch)
            throws Exception {
        boolean slotCreated = false;
        try (Connection src = Db.connect(cfg.getSource(), true);
             Connection tgt = Db.connect(cfg.getTarget(), false)) {
            if (Catalog.single(src, "SELECT pubname FROM pg_publication WHERE pubname = ?", cfg.getPublication()) == null) {
                throw new ConfigException("源库中不存在发布 " + cfg.getPublication() + "，请先执行 setup");
            }
            Boolean active = slotActive(src, cfg.getSlotName());
            if (active != null) {
                if (!recreateSlot) {
                    throw new ConfigException("复制槽 " + cfg.getSlotName() + " 已存在。如需重新初始化，请加 --recreate-slot");
                }
                if (active) {
                    throw new ConfigException("复制槽 " + cfg.getSlotName() + " 正在被使用，请先停止同步进程");
                }
            }
            StateStore.ensure(tgt, cfg.getStateSchema());
            List<Item> plan = preflight(src, tgt, cfg.getTables().values());

            if (copy) {
                Console.println("\n即将对目标库 " + cfg.getTarget().getDbname() + "@" + cfg.getTarget().getHost()
                        + " 执行全量初始化，以下表会被 TRUNCATE 后重新复制:");
            } else {
                Console.println("\n即将以目标库 " + cfg.getTarget().getDbname() + "@" + cfg.getTarget().getHost()
                        + " 的现有数据为起点初始化（不重新全量复制）。");
                if (verify) {
                    String how = "abort".equals(onMismatch) ? "放弃初始化，不做任何修改"
                            : "copy".equals(onMismatch) ? "仅对不一致的表 TRUNCATE 后重新复制" : "保留差异，直接开始同步（不推荐）";
                    Console.println("会先用一致性快照逐表核对源库与目标库，不一致时的处理方式: " + how);
                } else {
                    Console.println("已指定 --skip-verify：不核对数据，请自行确保两边此刻完全一致！");
                }
                Console.println("涉及的表:");
            }
            for (Item it : plan) {
                Console.println("  - " + it.table);
            }
            if (!Console.confirm(assumeYes)) {
                return 1;
            }
            if (recreateSlot && active != null) {
                log.info("删除已有复制槽 {}", cfg.getSlotName());
                dropSlot(cfg.getSlotName());
            }

            try (Snapshot s = createSnapshot(cfg.getSlotName(), false)) {
                slotCreated = true;
                long started = System.currentTimeMillis();
                List<Item> toCopy;
                if (copy) {
                    toCopy = plan;
                } else if (verify) {
                    toCopy = verifyAgainstSnapshot(s.snap, tgt, plan, onMismatch);
                } else {
                    toCopy = new ArrayList<>();
                }
                copyTables(s, tgt, toCopy);
                StateStore.writeState(tgt, cfg.getStateSchema(), cfg.getSlotName(), s.lsn);
                StateStore.clearTableStates(tgt, cfg.getStateSchema(), cfg.getSlotName());
                tgt.commit();
                s.snap.commit();
                log.info(String.format("初始化完成，耗时 %.1fs，重新复制 %d 张表，同步位点已设为 %s",
                        (System.currentTimeMillis() - started) / 1000.0, toCopy.size(), Sql.lsn(s.lsn)));
                List<TableConfig> copied = new ArrayList<>();
                toCopy.forEach(it -> copied.add(it.table));
                analyze(tgt, copied);
            }
            Console.println("\n初始化完成。接下来请启动增量同步: java -jar pglsync.jar run");
            return 0;
        } catch (Exception e) {
            if (slotCreated) {
                log.error("初始化失败，删除刚创建的复制槽 {}（避免其无限保留 WAL）", cfg.getSlotName());
                try {
                    dropSlot(cfg.getSlotName());
                } catch (Exception ex) {
                    log.error("删除复制槽失败，请手动执行: SELECT pg_drop_replication_slot('{}');", cfg.getSlotName(), ex);
                }
            }
            throw e;
        }
    }

    /** 用快照读取源库与目标库逐表比较，返回需要重新复制的表。 */
    List<Item> verifyAgainstSnapshot(Connection snap, Connection tgt, List<Item> plan, String onMismatch)
            throws Exception {
        Hashing.applySessionSettings(snap);
        Hashing.applySessionSettings(tgt);
        Console.printf("%n%-45s %14s %14s  %s%n", "表", "源库(快照)行数", "目标库行数", "结果");
        Console.println(repeat('-', 95));
        List<Item> mismatched = new ArrayList<>();
        for (Item it : plan) {
            long started = System.currentTimeMillis();
            Map<String, Hashing.Bucket> s = Hashing.bucketHashes(snap, it.table, it.cols, it.pk, null, null);
            Map<String, Hashing.Bucket> t = Hashing.bucketHashes(tgt, it.table, it.cols, it.pk, null, null);
            List<String> bad = Hashing.diff(s, t);
            String status = bad.isEmpty() ? "一致"
                    : "不一致: " + bad.size() + " 个" + (it.table.getTimeColumn() != null ? "时间桶" : "表");
            Console.printf("%-45s %14s %14s  %s  (%.1fs)%n", it.table, Console.n(Hashing.total(s)),
                    Console.n(Hashing.total(t)), status, (System.currentTimeMillis() - started) / 1000.0);
            if (!bad.isEmpty()) {
                mismatched.add(it);
                if (it.table.getTimeColumn() != null) {
                    for (String b : bad.subList(0, Math.min(10, bad.size()))) {
                        Console.printf("    %-28s 源 %10s  目标 %10s%n", b, count(s, b), count(t, b));
                    }
                    if (bad.size() > 10) {
                        Console.println("    ... 另有 " + (bad.size() - 10) + " 个桶不一致");
                    }
                }
            }
        }
        Console.println(repeat('-', 95));
        if (mismatched.isEmpty()) {
            log.info("所有表与源库快照一致，直接从一致性位点开始同步");
            return new ArrayList<>();
        }
        List<String> names = new ArrayList<>();
        mismatched.forEach(it -> names.add(it.table.qualified()));
        if ("abort".equals(onMismatch)) {
            throw new ConfigException("以下表与源库不一致: " + String.join(", ", names) + "\n"
                    + "  未做任何修改。可选择：\n"
                    + "  - init --no-copy --on-mismatch=copy   只重新复制这些表\n"
                    + "  - init（不带 --no-copy）               全部重新复制");
        }
        if ("copy".equals(onMismatch)) {
            log.warn("以下表与源库不一致，将用快照重新复制: {}", String.join(", ", names));
            return mismatched;
        }
        log.warn("以下表与源库不一致，按 --on-mismatch=ignore 保留差异: {}", String.join(", ", names));
        return new ArrayList<>();
    }

    private static String count(Map<String, Hashing.Bucket> m, String b) {
        Hashing.Bucket x = m.get(b);
        return Console.n(x == null ? 0 : x.count);
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ resync

    List<TableConfig> resolveTables(List<String> names) {
        List<TableConfig> tables = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String n : names) {
            String key = n.contains(".") ? n : "public." + n;
            TableConfig t = cfg.getTables().get(key);
            if (t == null) {
                unknown.add(n);
            } else if (!tables.contains(t)) {
                tables.add(t);
            }
        }
        if (!unknown.isEmpty()) {
            throw new ConfigException("表 " + String.join(", ", unknown) + " 不在配置的 pglsync.tables 中，请先把它加入配置");
        }
        return tables;
    }

    /**
     * 重新全量复制指定的表（新增同步表、表结构变更、单表数据不一致时使用）。
     * 要求 run 已停止；主复制槽不变；这些表在快照位点之前的变更会在 run 中被跳过。
     */
    public int resync(List<String> names, boolean assumeYes) throws Exception {
        if (names == null || names.isEmpty()) {
            throw new ConfigException("请用 --table=表名 指定要重新复制的表（可多次指定）");
        }
        List<TableConfig> tables = resolveTables(names);
        try (Connection src = Db.connect(cfg.getSource(), true);
             Connection tgt = Db.connect(cfg.getTarget(), false)) {
            try (PreparedStatement ps = src.prepareStatement(
                    "SELECT active, active_pid FROM pg_replication_slots WHERE slot_name = ?")) {
                ps.setString(1, cfg.getSlotName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new ConfigException("复制槽 " + cfg.getSlotName() + " 不存在。首次部署请使用 init");
                    }
                    if (rs.getBoolean(1)) {
                        throw new ConfigException("复制槽 " + cfg.getSlotName() + " 正被进程 " + rs.getString(2)
                                + " 使用，请先停止增量同步（run）再执行 resync");
                    }
                }
            }
            List<String> notPublished = new ArrayList<>();
            for (TableConfig t : tables) {
                if (Catalog.single(src, "SELECT tablename FROM pg_publication_tables WHERE pubname = ? "
                        + "AND schemaname = ? AND tablename = ?", cfg.getPublication(), t.getSchema(), t.getName()) == null) {
                    notPublished.add(t.qualified());
                }
            }
            if (!notPublished.isEmpty()) {
                throw new ConfigException("表 " + String.join(", ", notPublished) + " 不在发布 "
                        + cfg.getPublication() + " 中，请先执行 setup");
            }
            StateStore.ensure(tgt, cfg.getStateSchema());
            if (StateStore.readState(tgt, cfg.getStateSchema(), cfg.getSlotName()) == null) {
                throw new ConfigException("目标库没有复制槽 " + cfg.getSlotName() + " 的同步位点，请先执行 init");
            }
            List<Item> plan = preflight(src, tgt, tables);

            Console.println("\n即将重新全量复制以下表（目标库中这些表会被 TRUNCATE 后用源库快照重新复制，其他表不受影响）:");
            for (Item it : plan) {
                Console.println("  - " + it.table);
            }
            if (!Console.confirm(assumeYes)) {
                return 1;
            }
            String base = cfg.getSlotName().length() > 40 ? cfg.getSlotName().substring(0, 40) : cfg.getSlotName();
            String tempSlot = base + "_rs_" + (System.currentTimeMillis() % 100000000L);
            // 关闭复制连接后，临时复制槽由 PostgreSQL 自动删除
            try (Snapshot s = createSnapshot(tempSlot, true)) {
                long started = System.currentTimeMillis();
                copyTables(s, tgt, plan);
                for (Item it : plan) {
                    StateStore.writeTableState(tgt, cfg.getStateSchema(), cfg.getSlotName(), it.table.qualified(), s.lsn);
                }
                tgt.commit();
                s.snap.commit();
                log.info(String.format("resync 完成，耗时 %.1fs，这些表的起始位点为 %s",
                        (System.currentTimeMillis() - started) / 1000.0, Sql.lsn(s.lsn)));
                analyze(tgt, tables);
            } catch (Exception e) {
                Db.rollbackQuietly(tgt);
                throw e;
            }
            Console.println("\nresync 完成。请重新启动增量同步: java -jar pglsync.jar run");
            return 0;
        }
    }
}
