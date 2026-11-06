package com.retror.pglsync.stream;

import com.retror.pglsync.apply.Applier;
import com.retror.pglsync.apply.JdbcTargetExecutor;
import com.retror.pglsync.catalog.RelationRegistry;
import com.retror.pglsync.config.ConfigException;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.state.StateStore;
import com.retror.pglsync.util.Db;
import com.retror.pglsync.util.Sql;
import org.postgresql.PGConnection;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 增量同步主循环：断线自动重连（指数退避），收到停止信号后处理完当前事务再退出。 */
public class Streamer {
    private static final Logger log = LoggerFactory.getLogger(Streamer.class);

    private final SyncConfig cfg;
    private volatile boolean stop;
    private final CountDownLatch stopped = new CountDownLatch(1);

    public Streamer(SyncConfig cfg) {
        this.cfg = cfg;
    }

    public void requestStop() {
        if (!stop) {
            log.info("收到停止信号，处理完当前事务后退出...");
        }
        stop = true;
    }

    public boolean awaitStopped(long seconds) throws InterruptedException {
        return stopped.await(seconds, TimeUnit.SECONDS);
    }

    public void runForever() throws InterruptedException {
        try {
            double delay = cfg.getRetryInitialSeconds();
            while (!stop) {
                long started = System.currentTimeMillis();
                try {
                    runOnce();
                } catch (ConfigException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("同步中断", e);
                }
                if (stop) {
                    break;
                }
                if (System.currentTimeMillis() - started > 300_000) {
                    delay = cfg.getRetryInitialSeconds();
                }
                log.info("{} 秒后重新连接...", (long) delay);
                sleep(delay);
                delay = Math.min(delay * 2, cfg.getRetryMaxSeconds());
            }
            log.info("同步进程已退出");
        } finally {
            stopped.countDown();
        }
    }

    private void sleep(double seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + (long) (seconds * 1000);
        while (!stop && System.currentTimeMillis() < end) {
            Thread.sleep(Math.min(500, Math.max(1, end - System.currentTimeMillis())));
        }
    }

    private void checkSource() throws Exception {
        try (Connection c = Db.connect(cfg.getSource(), true);
             PreparedStatement ps = c.prepareStatement("SELECT plugin, active, active_pid FROM pg_replication_slots "
                     + "WHERE slot_name = ? AND database = current_database()")) {
            ps.setString(1, cfg.getSlotName());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ConfigException("源库中不存在复制槽 " + cfg.getSlotName() + "，请先执行 init");
                }
                if (!"pgoutput".equals(rs.getString(1))) {
                    throw new ConfigException("复制槽 " + cfg.getSlotName() + " 的插件是 " + rs.getString(1) + "，需要 pgoutput");
                }
                if (rs.getBoolean(2)) {
                    log.warn("复制槽 {} 正被进程 {} 占用（是否有另一个同步进程在运行？）", cfg.getSlotName(), rs.getString(3));
                }
            }
            try (PreparedStatement p2 = c.prepareStatement("SELECT 1 FROM pg_publication WHERE pubname = ?")) {
                p2.setString(1, cfg.getPublication());
                try (ResultSet rs = p2.executeQuery()) {
                    if (!rs.next()) {
                        throw new ConfigException("源库中不存在发布 " + cfg.getPublication() + "，请先执行 setup");
                    }
                }
            }
        }
    }

    private void runOnce() throws Exception {
        Connection tgt = null;
        Connection repl = null;
        PGReplicationStream stream = null;
        RelationRegistry registry = new RelationRegistry(cfg);
        try {
            tgt = Db.connect(cfg.getTarget(), false);
            StateStore.ensure(tgt, cfg.getStateSchema());
            StateStore.State state = StateStore.readState(tgt, cfg.getStateSchema(), cfg.getSlotName());
            if (state == null) {
                throw new ConfigException("目标库 " + cfg.getStateSchema() + ".sync_state 中没有复制槽 "
                        + cfg.getSlotName() + " 的同步位点，请先执行 init");
            }
            long startLsn = state.lsn;
            Map<String, Long> tableLsns = StateStore.readTableStates(tgt, cfg.getStateSchema(), cfg.getSlotName());
            for (Map.Entry<String, Long> e : tableLsns.entrySet()) {
                if (e.getValue() > startLsn) {
                    log.info("表 {} 做过 resync，位点 {} 之前的变更将被跳过", e.getKey(), Sql.lsn(e.getValue()));
                }
            }
            checkSource();

            Applier applier = new Applier(cfg, new JdbcTargetExecutor(tgt, cfg.getStateSchema(), cfg.getSlotName()));
            repl = Db.connectReplication(cfg.getSource());
            stream = repl.unwrap(PGConnection.class).getReplicationAPI()
                    .replicationStream()
                    .logical()
                    .withSlotName(cfg.getSlotName())
                    .withStartPosition(LogSequenceNumber.valueOf(startLsn))
                    .withSlotOption("proto_version", "1")
                    .withSlotOption("publication_names", cfg.getPublication())
                    .withStatusInterval((int) Math.max(1, cfg.getStatusIntervalSeconds()), TimeUnit.SECONDS)
                    .start();
            log.info("开始增量同步: slot={} publication={} 起始位点={}",
                    cfg.getSlotName(), cfg.getPublication(), Sql.lsn(startLsn));
            new Session(cfg, new PgReplicationIo(stream), applier, registry, startLsn, tableLsns)
                    .loop(() -> stop);
        } finally {
            Db.closeQuietly(stream);
            Db.closeQuietly(repl);
            Db.rollbackQuietly(tgt);
            Db.closeQuietly(tgt);
            registry.close();
        }
    }
}
