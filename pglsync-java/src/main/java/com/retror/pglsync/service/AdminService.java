package com.retror.pglsync.service;

import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.state.StateStore;
import com.retror.pglsync.util.Console;
import com.retror.pglsync.util.Db;
import com.retror.pglsync.util.Sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

/** status / drop 运维命令。 */
public class AdminService {
    private final SyncConfig cfg;

    public AdminService(SyncConfig cfg) {
        this.cfg = cfg;
    }

    public int status() throws Exception {
        int code = 0;
        try (Connection src = Db.connect(cfg.getSource(), true)) {
            int ver = Integer.parseInt(com.retror.pglsync.catalog.Catalog.single(src,
                    "SELECT current_setting('server_version_num')"));
            String walStatus = ver >= 130000 ? "wal_status" : "NULL";
            try (PreparedStatement ps = src.prepareStatement("SELECT plugin, active, active_pid, restart_lsn::text, "
                    + "confirmed_flush_lsn::text, "
                    + "pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)), "
                    + "pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)), "
                    + "pg_current_wal_lsn()::text, " + walStatus + " FROM pg_replication_slots WHERE slot_name = ?")) {
                ps.setString(1, cfg.getSlotName());
                try (ResultSet rs = ps.executeQuery()) {
                    Console.println("== 源库复制槽 " + cfg.getSlotName() + " ==");
                    if (!rs.next()) {
                        Console.println("  不存在（尚未 init？）");
                        code = 1;
                    } else {
                        boolean active = rs.getBoolean(2);
                        Console.println("  插件            : " + rs.getString(1));
                        Console.println("  是否活跃        : " + (active ? "是 (pid " + rs.getString(3) + ")" : "否"));
                        Console.println("  当前 WAL 位置   : " + rs.getString(8));
                        Console.println("  已确认位点      : " + rs.getString(5) + "  (落后 " + rs.getString(7) + ")");
                        Console.println("  保留 WAL        : " + rs.getString(6) + "  (restart_lsn " + rs.getString(4) + ")");
                        String ws = rs.getString(9);
                        if (ws != null) {
                            Console.println("  wal_status      : " + ws);
                            if ("unreserved".equals(ws) || "lost".equals(ws)) {
                                Console.println("  !! WAL 已超出 max_slot_wal_keep_size，复制槽可能已失效，需要重新 init");
                                code = 1;
                            }
                        }
                        if (!active) {
                            Console.println("  !! 没有同步进程连接到该复制槽，WAL 会持续堆积");
                            code = 1;
                        }
                    }
                }
            }
            String pub = com.retror.pglsync.catalog.Catalog.single(src,
                    "SELECT puballtables::text FROM pg_publication WHERE pubname = ?", cfg.getPublication());
            Console.println("== 源库发布 " + cfg.getPublication() + " ==");
            Console.println("  " + (pub == null ? "不存在" : ("true".equals(pub) ? "FOR ALL TABLES" : "存在")));
        }
        try (Connection tgt = Db.connect(cfg.getTarget(), true)) {
            StateStore.State state = StateStore.readState(tgt, cfg.getStateSchema(), cfg.getSlotName());
            Console.println("== 目标库同步位点 ==");
            if (state == null) {
                Console.println("  无");
                code = 1;
            } else {
                Console.println("  位点 " + Sql.lsn(state.lsn) + "，更新于 " + state.updatedAt);
            }
            Map<String, Long> tables = StateStore.readTableStates(tgt, cfg.getStateSchema(), cfg.getSlotName());
            for (Map.Entry<String, Long> e : tables.entrySet()) {
                Console.println("  resync 表 " + e.getKey() + " 起始位点 " + Sql.lsn(e.getValue()));
            }
        }
        return code;
    }

    public int drop(boolean assumeYes, boolean keepPublication) throws Exception {
        Console.println("将删除源库复制槽 " + cfg.getSlotName()
                + (keepPublication ? "" : " 和发布 " + cfg.getPublication()) + "，并清除目标库中的同步位点。");
        if (!Console.confirm(assumeYes)) {
            return 1;
        }
        try (Connection src = Db.connect(cfg.getSource(), true)) {
            try (PreparedStatement ps = src.prepareStatement(
                    "SELECT active, active_pid FROM pg_replication_slots WHERE slot_name = ?")) {
                ps.setString(1, cfg.getSlotName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (rs.getBoolean(1)) {
                            Console.println("复制槽正在被进程 " + rs.getString(2) + " 使用，请先停止同步服务");
                            return 1;
                        }
                        try (PreparedStatement d = src.prepareStatement("SELECT pg_drop_replication_slot(?)")) {
                            d.setString(1, cfg.getSlotName());
                            d.executeQuery().close();
                        }
                        Console.println("已删除复制槽 " + cfg.getSlotName());
                    }
                }
            }
            if (!keepPublication) {
                Db.execute(src, "DROP PUBLICATION IF EXISTS " + Sql.qi(cfg.getPublication()));
                Console.println("已删除发布 " + cfg.getPublication());
            }
        }
        try (Connection tgt = Db.connect(cfg.getTarget(), true)) {
            StateStore.deleteState(tgt, cfg.getStateSchema(), cfg.getSlotName());
        }
        return 0;
    }
}
