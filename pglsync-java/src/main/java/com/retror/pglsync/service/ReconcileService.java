package com.retror.pglsync.service;

import com.retror.pglsync.catalog.Catalog;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.util.Console;
import com.retror.pglsync.util.Db;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * reconcile：源库与目标库数据核对（行数 + 整行哈希）。
 * 配置了 time-column 的表按时间分桶比较；不一致的桶在延迟后复查，只报告持续不一致的部分。
 */
public class ReconcileService {
    private final SyncConfig cfg;

    public ReconcileService(SyncConfig cfg) {
        this.cfg = cfg;
    }

    private static Connection connect(com.retror.pglsync.config.PglsyncProperties.Db p) throws Exception {
        Connection c = Db.connect(p, true);
        Hashing.applySessionSettings(c);
        return c;
    }

    /** 返回 0 一致 / 2 不一致 / 1 没有匹配的表。 */
    public int run(List<String> only, String since, double recheckDelay, boolean detail, int limit) throws Exception {
        List<TableConfig> tables = new ArrayList<>();
        for (TableConfig t : cfg.getTables().values()) {
            if (only == null || only.isEmpty() || only.contains(t.qualified()) || only.contains(t.getName())) {
                tables.add(t);
            }
        }
        if (tables.isEmpty()) {
            Console.println("没有匹配的表");
            return 1;
        }
        int badTables = 0;
        try (Connection src = connect(cfg.getSource()); Connection tgt = connect(cfg.getTarget())) {
            Console.printf("%-45s %14s %14s  %s%n", "表", "源库行数", "目标库行数", "结果");
            Console.println(SnapshotService.repeat('-', 95));
            for (TableConfig t : tables) {
                List<String> cols = Catalog.tableColumns(src, t.getSchema(), t.getName());
                List<String> pk = Catalog.resolvePk(src, t);
                TreeMap<String, Hashing.Bucket> s = Hashing.bucketHashes(src, t, cols, pk, since, null);
                TreeMap<String, Hashing.Bucket> g = Hashing.bucketHashes(tgt, t, cols, pk, since, null);
                List<String> bad = Hashing.diff(s, g);
                if (!bad.isEmpty() && recheckDelay > 0) {
                    Thread.sleep((long) (recheckDelay * 1000));
                    List<String> still = new ArrayList<>();
                    for (String b : bad) {
                        String bucket = "*".equals(b) ? null : b;
                        Map<String, Hashing.Bucket> s2 = Hashing.bucketHashes(src, t, cols, pk, since, bucket);
                        Map<String, Hashing.Bucket> g2 = Hashing.bucketHashes(tgt, t, cols, pk, since, bucket);
                        s.remove(b);
                        g.remove(b);
                        s.putAll(s2);
                        g.putAll(g2);
                        if (!java.util.Objects.equals(s2.get(b), g2.get(b))) {
                            still.add(b);
                        }
                    }
                    bad = still;
                }
                String status = bad.isEmpty() ? "一致"
                        : "不一致: " + bad.size() + " 个" + (t.getTimeColumn() != null ? "时间桶" : "表");
                Console.printf("%-45s %14s %14s  %s%n", t, Console.n(Hashing.total(s)), Console.n(Hashing.total(g)), status);
                if (bad.isEmpty()) {
                    continue;
                }
                badTables++;
                List<String> shown = bad.subList(0, Math.min(limit, bad.size()));
                if (t.getTimeColumn() != null) {
                    for (String b : shown) {
                        Console.printf("    %-28s 源 %10s  目标 %10s%n", b, cnt(s, b), cnt(g, b));
                    }
                }
                if (bad.size() > limit) {
                    Console.println("    ... 另有 " + (bad.size() - limit) + " 个桶不一致");
                }
                if (detail) {
                    for (String b : shown) {
                        Map<String, String> rs = Hashing.rowHashes(src, t, cols, pk, b);
                        Map<String, String> rt = Hashing.rowHashes(tgt, t, cols, pk, b);
                        TreeSet<String> missing = new TreeSet<>();
                        TreeSet<String> changed = new TreeSet<>();
                        TreeSet<String> extra = new TreeSet<>();
                        for (Map.Entry<String, String> e : rs.entrySet()) {
                            String other = rt.get(e.getKey());
                            if (other == null) {
                                missing.add(e.getKey());
                            } else if (!other.equals(e.getValue())) {
                                changed.add(e.getKey());
                            }
                        }
                        for (String k : rt.keySet()) {
                            if (!rs.containsKey(k)) {
                                extra.add(k);
                            }
                        }
                        Console.printf("    [%s] 目标缺少 %d 行, 目标多出 %d 行, 内容不同 %d 行%n",
                                b, missing.size(), extra.size(), changed.size());
                        print("缺少", missing, limit);
                        print("多出", extra, limit);
                        print("不同", changed, limit);
                    }
                }
            }
            Console.println(SnapshotService.repeat('-', 95));
            Console.println("不一致的表: " + badTables + " / " + tables.size());
        }
        return badTables == 0 ? 0 : 2;
    }

    private static String cnt(Map<String, Hashing.Bucket> m, String b) {
        Hashing.Bucket x = m.get(b);
        return Console.n(x == null ? 0 : x.count);
    }

    private static void print(String label, TreeSet<String> keys, int limit) {
        int i = 0;
        for (String k : keys) {
            if (i++ >= limit) {
                break;
            }
            Console.println("      " + label + " pk=" + k);
        }
    }
}
