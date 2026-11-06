package com.retror.pglsync.stream;

import com.retror.pglsync.apply.Applier;
import com.retror.pglsync.catalog.RelationInfo;
import com.retror.pglsync.catalog.RelationRegistry;
import com.retror.pglsync.config.ConfigException;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.pgoutput.DecodeException;
import com.retror.pglsync.pgoutput.PgOutput;
import com.retror.pglsync.util.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 一次复制连接的生命周期：按源库提交顺序处理消息、批量提交、确认位点。
 *
 * <p>位点语义（保证不丢、不重复生效）：
 * <ul>
 *   <li>目标库 sync_state.lsn 保存“最后一个已应用事务的 end_lsn”，与数据同事务提交；</li>
 *   <li>目标库提交成功后才向源库确认（flush_lsn），源库才会回收这之前的 WAL；</li>
 *   <li>重启后源库可能重发少量已应用的事务，通过 Begin.finalLsn &lt; 已应用位点 识别并跳过；</li>
 *   <li>resync 过的表，在其快照位点之前提交的变更被跳过。</li>
 * </ul>
 */
public class Session {
    private static final Logger log = LoggerFactory.getLogger(Session.class);

    private final SyncConfig cfg;
    private final ReplicationIo io;
    private final Applier applier;
    private final RelationRegistry registry;
    private final Map<String, Long> tableLsns;

    long lastEnd;
    long flushed;
    private long txnLsn;
    boolean inTxn;
    private boolean skipTxn;
    Long pendingEnd;
    private long batchStartedMs;
    int batchChanges;
    private Long lastCommitTs;
    private long lastIdleFeedbackMs;
    private long lastStatsMs;

    long statTxns;
    long statChanges;
    long statSkippedTxns;
    long statSkippedTableChanges;

    public Session(SyncConfig cfg, ReplicationIo io, Applier applier, RelationRegistry registry,
                   long startLsn, Map<String, Long> tableLsns) {
        this.cfg = cfg;
        this.io = io;
        this.applier = applier;
        this.registry = registry;
        this.tableLsns = tableLsns == null ? new HashMap<>() : new HashMap<>(tableLsns);
        this.lastEnd = startLsn;
        this.flushed = startLsn;
        long now = System.currentTimeMillis();
        this.lastIdleFeedbackMs = now;
        this.lastStatsMs = now;
    }

    // ------------------------------------------------------------ 主循环

    public void loop(BooleanSupplier shouldStop) throws Exception {
        long batchMaxMs = (long) (cfg.getBatchMaxSeconds() * 1000);
        while (true) {
            if (shouldStop.getAsBoolean() && !inTxn) {
                break;
            }
            ByteBuffer msg = io.readPending();
            if (msg != null) {
                handle(msg);
                continue;
            }
            long now = System.currentTimeMillis();
            if (pendingEnd != null && !inTxn && now - batchStartedMs >= batchMaxMs) {
                flush();
            }
            if (pendingEnd == null && !inTxn) {
                idleFeedback(now);
            }
            maybeLogStats(now);
            Thread.sleep(10);
        }
        if (pendingEnd != null) {
            flush();
        }
    }

    public void handle(ByteBuffer payload) throws Exception {
        PgOutput.Message m = PgOutput.decode(payload);
        if (m instanceof PgOutput.Relation) {
            registry.register((PgOutput.Relation) m);
        } else if (m instanceof PgOutput.Begin) {
            PgOutput.Begin b = (PgOutput.Begin) m;
            if (inTxn) {
                throw new DecodeException("收到嵌套的 BEGIN");
            }
            inTxn = true;
            txnLsn = b.finalLsn;
            skipTxn = b.finalLsn < lastEnd;
            if (skipTxn) {
                statSkippedTxns++;
                log.debug("跳过已应用的事务 xid={} commit_lsn={}", b.xid, Sql.lsn(b.finalLsn));
            } else if (batchStartedMs == 0) {
                batchStartedMs = System.currentTimeMillis();
            }
        } else if (m instanceof PgOutput.Commit) {
            PgOutput.Commit c = (PgOutput.Commit) m;
            if (!inTxn) {
                throw new DecodeException("收到没有 BEGIN 的 COMMIT");
            }
            inTxn = false;
            if (skipTxn) {
                skipTxn = false;
                return;
            }
            lastEnd = c.endLsn;
            pendingEnd = c.endLsn;
            lastCommitTs = c.commitTs;
            statTxns++;
            if (batchChanges >= cfg.getBatchMaxChanges()
                    || System.currentTimeMillis() - batchStartedMs >= (long) (cfg.getBatchMaxSeconds() * 1000)) {
                flush();
            }
        } else if (m instanceof PgOutput.Insert || m instanceof PgOutput.Update
                || m instanceof PgOutput.Delete || m instanceof PgOutput.Truncate) {
            if (!inTxn) {
                throw new DecodeException("收到事务之外的数据变更消息");
            }
            if (skipTxn) {
                return;
            }
            int n = apply(m);
            batchChanges += n;
            statChanges += n;
        }
        // Origin / Type / LogicalMessage：忽略
    }

    /** 该 relation 的变更是否需要应用。 */
    private boolean wanted(RelationInfo info) {
        if (info.target == null) {
            return false;
        }
        Long tableLsn = tableLsns.get(info.target.qualified());
        if (tableLsn != null && txnLsn < tableLsn) {
            // 该表做过 resync，快照位点之前提交的变更已包含在复制的数据中
            statSkippedTableChanges++;
            return false;
        }
        if (info.invalid != null) {
            throw new ConfigException(info.invalid);
        }
        return true;
    }

    private int apply(PgOutput.Message m) throws Exception {
        if (m instanceof PgOutput.Truncate) {
            PgOutput.Truncate t = (PgOutput.Truncate) m;
            List<RelationInfo> infos = new ArrayList<>();
            for (Long relid : t.relids) {
                RelationInfo i = registry.get(relid);
                if (wanted(i)) {
                    infos.add(i);
                }
            }
            if (!infos.isEmpty()) {
                applier.truncate(infos, t.options);
            }
            return infos.size();
        }
        if (m instanceof PgOutput.Insert) {
            PgOutput.Insert i = (PgOutput.Insert) m;
            RelationInfo info = registry.get(i.relid);
            if (!wanted(info)) {
                return 0;
            }
            applier.insert(info, i.newTuple);
        } else if (m instanceof PgOutput.Update) {
            PgOutput.Update u = (PgOutput.Update) m;
            RelationInfo info = registry.get(u.relid);
            if (!wanted(info)) {
                return 0;
            }
            applier.update(info, u.oldKind, u.oldTuple, u.newTuple);
        } else {
            PgOutput.Delete d = (PgOutput.Delete) m;
            RelationInfo info = registry.get(d.relid);
            if (!wanted(info)) {
                return 0;
            }
            applier.delete(info, d.oldKind, d.oldTuple);
        }
        return 1;
    }

    // ------------------------------------------------------------ 提交与确认

    public void flush() throws Exception {
        long end = pendingEnd;
        if (batchChanges > 0) {
            applier.commit(end);
        }
        // 目标库已提交，才向源库确认，源库此后可以回收这之前的 WAL
        io.sendFeedback(end);
        flushed = end;
        log.debug("已提交批次: {} 条变更, 位点 {}", batchChanges, Sql.lsn(end));
        pendingEnd = null;
        batchStartedMs = 0;
        batchChanges = 0;
        lastIdleFeedbackMs = System.currentTimeMillis();
    }

    /**
     * 空闲时把确认位点推进到服务端 WAL 末尾。PG15+ 会跳过与发布无关的空事务，
     * 如果只在收到相关事务时确认，同步表长时间无变更时复制槽会一直保留 WAL。
     * 此时所有已收到的数据都已处理完，推进到 wal_end 是安全的。
     */
    void idleFeedback(long now) throws Exception {
        if (now - lastIdleFeedbackMs < (long) (cfg.getStatusIntervalSeconds() * 1000)) {
            return;
        }
        lastIdleFeedbackMs = now;
        long walEnd = io.lastReceiveLsn();
        if (walEnd > flushed) {
            io.sendFeedback(walEnd);
            flushed = walEnd;
        }
    }

    private void maybeLogStats(long now) {
        if (now - lastStatsMs < (long) (cfg.getStatsIntervalSeconds() * 1000)) {
            return;
        }
        lastStatsMs = now;
        String lag = "";
        if (lastCommitTs != null && statTxns > 0) {
            double delay = (System.currentTimeMillis() - Sql.pgTimestampToMillis(lastCommitTs)) / 1000.0;
            lag = String.format(", 最近事务延迟 %.1fs", Math.max(0.0, delay));
        }
        String extra = statSkippedTableChanges > 0 ? ", 跳过 resync 前变更 " + statSkippedTableChanges : "";
        log.info("统计: 事务 {}, 变更 {}, 跳过重放事务 {}, 目标缺行 {}, 确认位点 {}{}{}",
                statTxns, statChanges, statSkippedTxns, applier.getMissingRows(), Sql.lsn(flushed), lag, extra);
        statTxns = 0;
        statChanges = 0;
        statSkippedTxns = 0;
        statSkippedTableChanges = 0;
    }
}
