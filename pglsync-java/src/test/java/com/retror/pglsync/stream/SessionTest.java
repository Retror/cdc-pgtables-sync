package com.retror.pglsync.stream;

import com.retror.pglsync.TestSupport;
import com.retror.pglsync.apply.Applier;
import com.retror.pglsync.apply.RecordingExecutor;
import com.retror.pglsync.catalog.RelationRegistry;
import com.retror.pglsync.config.ConfigException;
import com.retror.pglsync.config.SyncConfig;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.retror.pglsync.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    static class FakeIo implements ReplicationIo {
        final List<Long> feedback = new ArrayList<>();
        long walEnd;

        @Override
        public ByteBuffer readPending() {
            return null;
        }

        @Override
        public void sendFeedback(long lsn) {
            feedback.add(lsn);
        }

        @Override
        public long lastReceiveLsn() {
            return walEnd;
        }
    }

    /** 不访问数据库的 registry（测试中不出现 chunk）。 */
    static class NoDbRegistry extends RelationRegistry {
        NoDbRegistry(SyncConfig cfg) {
            super(cfg);
        }

        @Override
        protected String[] hypertableOf(String schema, String name) {
            return null;
        }
    }

    static final class Fixture {
        final RecordingExecutor rec = new RecordingExecutor();
        final FakeIo io = new FakeIo();
        final Session session;

        Fixture(long startLsn, SyncConfig cfg, Map<String, Long> tableLsns) {
            session = new Session(cfg, io, new Applier(cfg, rec), new NoDbRegistry(cfg), startLsn, tableLsns);
        }
    }

    @Test
    void replayedTransactionsAreSkipped() throws Exception {
        Fixture f = new Fixture(200, TestSupport.config(), null);
        Session s = f.session;
        s.handle(relation(1, "public", "t", "id*", "name", "big"));
        s.handle(begin(150));
        s.handle(insert(1, "1", "old", null));
        s.handle(commit(150, 160));
        assertEquals(1, s.statSkippedTxns);
        assertNull(s.pendingEnd);
        s.handle(begin(300));
        s.handle(insert(1, "2", "new", null));
        s.handle(commit(300, 310));
        s.flush();
        assertEquals(1, f.rec.sqls.size());
        assertEquals(java.util.Arrays.asList("2", "new", null), f.rec.params.get(0));
        assertEquals(Long.valueOf(310), f.rec.committedLsn);
        assertEquals(Collections.singletonList(310L), f.io.feedback);
    }

    @Test
    void batchFlushesOnChangeCount() throws Exception {
        Fixture f = new Fixture(0, TestSupport.config(2, 1000, "warn", "warn"), null);
        Session s = f.session;
        s.handle(relation(1, "public", "t", "id*", "name", "big"));
        s.handle(begin(10));
        s.handle(insert(1, "1", "a", null));
        s.handle(commit(10, 20));
        assertEquals(0, f.rec.commits);
        s.handle(begin(30));
        s.handle(insert(1, "2", "b", null));
        s.handle(commit(30, 40));
        assertEquals(1, f.rec.commits);
        assertEquals(Collections.singletonList(40L), f.io.feedback);
    }

    @Test
    void irrelevantTransactionsDoNotWriteTarget() throws Exception {
        Fixture f = new Fixture(0, TestSupport.config(), null);
        Session s = f.session;
        s.handle(relation(2, "public", "other", "id*"));
        s.handle(begin(10));
        s.handle(insert(2, "1"));
        s.handle(commit(10, 20));
        s.flush();
        assertEquals(0, f.rec.commits);
        assertEquals(Collections.singletonList(20L), f.io.feedback);
    }

    @Test
    void idleFeedbackAdvancesToWalEnd() throws Exception {
        Fixture f = new Fixture(100, TestSupport.config(), null);
        f.io.walEnd = 500;
        f.session.idleFeedback(System.currentTimeMillis() + 3_600_000L);
        assertEquals(Collections.singletonList(500L), f.io.feedback);
    }

    @Test
    void resyncTableSkipsChangesBeforeItsLsn() throws Exception {
        Fixture f = new Fixture(100, TestSupport.config(), Collections.singletonMap("public.t", 300L));
        Session s = f.session;
        s.handle(relation(1, "public", "t", "id*", "name", "big"));
        s.handle(relation(3, "public", "h", "id*", "ts*", "v"));
        s.handle(begin(200));
        s.handle(insert(1, "1", "old", null));
        s.handle(insert(3, "7", "2026-11-01 00:00:00+00", "x"));
        s.handle(commit(200, 210));
        s.handle(begin(400));
        s.handle(insert(1, "2", "new", null));
        s.handle(commit(400, 410));
        s.flush();
        assertEquals(2, f.rec.sqls.size());
        assertTrue(f.rec.sqls.get(0).startsWith("INSERT INTO \"public\".\"h\""));
        assertTrue(f.rec.sqls.get(1).startsWith("INSERT INTO \"public\".\"t\""));
        assertEquals(java.util.Arrays.asList("2", "new", null), f.rec.params.get(1));
        assertEquals(1, s.statSkippedTableChanges);
    }

    @Test
    void invalidRelationSkippedBeforeResyncLsnButFailsAfter() throws Exception {
        Fixture f = new Fixture(0, TestSupport.config(), Collections.singletonMap("public.h", 300L));
        Session s = f.session;
        // 旧 relation：主键列 ts 不在复制标识中，校验失败
        s.handle(relation(3, "public", "h", "id*", "ts", "v"));
        s.handle(begin(100));
        s.handle(insert(3, "1", "2026-11-01 00:00:00+00", "x"));
        s.handle(commit(100, 110));
        s.handle(begin(500));
        assertThrows(ConfigException.class, () -> s.handle(insert(3, "2", "2026-11-01 00:00:00+00", "y")));
    }
}
