package com.retror.pglsync.apply;

import com.retror.pglsync.TestSupport;
import com.retror.pglsync.catalog.RelationInfo;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.pgoutput.PgOutput;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplierTest {

    /** 记录所有 SQL 的假执行器。 */
    static class Recorder extends RecordingExecutor {
    }

    static PgOutput.Value t(String s) {
        return new PgOutput.Value(PgOutput.TEXT, s);
    }

    static PgOutput.Value n() {
        return new PgOutput.Value(PgOutput.NULL, null);
    }

    static PgOutput.Value u() {
        return new PgOutput.Value(PgOutput.UNCHANGED, null);
    }

    static RelationInfo info(TableConfig target, List<String> cols, List<String> pk, boolean chunk) {
        RelationInfo i = new RelationInfo(1, "public", target.getName(), 'd', cols, pk);
        i.target = target;
        i.pk = pk;
        i.chunk = chunk;
        return i;
    }

    static RelationInfo info() {
        return info(new TableConfig("public", "t"), Arrays.asList("id", "name", "big"),
                Arrays.asList("id"), false);
    }

    @Test
    void insertIsIdempotentUpsertAndCommitWritesState() throws Exception {
        Recorder r = new Recorder();
        Applier a = new Applier(TestSupport.config(), r);
        a.insert(info(), Arrays.asList(t("1"), t("a"), n()));
        a.commit(16);
        assertEquals("INSERT INTO \"public\".\"t\" (\"id\", \"name\", \"big\") VALUES (?, ?, ?) "
                + "ON CONFLICT (\"id\") DO UPDATE SET \"name\" = EXCLUDED.\"name\", \"big\" = EXCLUDED.\"big\"", r.sqls.get(0));
        assertEquals(Arrays.asList("1", "a", null), r.params.get(0));
        assertEquals(1, r.commits);
        assertEquals(Long.valueOf(16), r.committedLsn);
    }

    @Test
    void groupDedupKeepsLast() throws Exception {
        Recorder r = new Recorder();
        Applier a = new Applier(TestSupport.config(), r);
        RelationInfo i = info();
        a.insert(i, Arrays.asList(t("1"), t("a"), t("x")));
        a.update(i, null, null, Arrays.asList(t("1"), t("b"), t("x")));
        a.insert(i, Arrays.asList(t("2"), t("c"), t("x")));
        a.flushPending();
        assertEquals(1, r.sqls.size());
        assertEquals(Arrays.asList("1", "b", "x", "2", "c", "x"), r.params.get(0));
    }

    @Test
    void orderPreservedAroundDelete() throws Exception {
        Recorder r = new Recorder();
        Applier a = new Applier(TestSupport.config(), r);
        RelationInfo i = info();
        a.insert(i, Arrays.asList(t("1"), t("a"), t("x")));
        a.delete(i, 'K', Arrays.asList(t("1"), n(), n()));
        assertTrue(r.sqls.get(0).startsWith("INSERT"));
        assertEquals("DELETE FROM \"public\".\"t\" WHERE \"id\" = ?", r.sqls.get(1));
        assertEquals(Arrays.asList("1"), r.params.get(1));
    }

    @Test
    void updatePrimaryKeyChange() throws Exception {
        Recorder r = new Recorder();
        Applier a = new Applier(TestSupport.config(), r);
        a.update(info(), 'K', Arrays.asList(t("1"), n(), n()), Arrays.asList(t("9"), t("a"), t("x")));
        a.flushPending();
        assertTrue(r.sqls.get(0).startsWith("DELETE"));
        assertEquals(Arrays.asList("1"), r.params.get(0));
        assertEquals(Arrays.asList("9", "a", "x"), r.params.get(1));
    }

    @Test
    void unchangedToastUsesPartialUpdate() throws Exception {
        Recorder r = new Recorder();
        Applier a = new Applier(TestSupport.config(), r);
        a.update(info(), null, null, Arrays.asList(t("1"), t("b"), u()));
        assertEquals("UPDATE \"public\".\"t\" SET \"name\" = ? WHERE \"id\" = ?", r.sqls.get(0));
        assertEquals(Arrays.asList("b", "1"), r.params.get(0));
    }

    @Test
    void unchangedToastFilledFromFullOldRow() throws Exception {
        Recorder r = new Recorder();
        Applier a = new Applier(TestSupport.config(), r);
        a.update(info(), 'O', Arrays.asList(t("1"), t("a"), t("BIG")), Arrays.asList(t("1"), t("b"), u()));
        a.flushPending();
        assertEquals(Arrays.asList("1", "b", "BIG"), r.params.get(0));
    }

    @Test
    void missingRowPolicy() throws Exception {
        Recorder r = new Recorder();
        r.rowcount = 0;
        Applier a = new Applier(TestSupport.config(5000, 1000, "error", "warn"), r);
        assertThrows(ApplyException.class,
                () -> a.update(info(), null, null, Arrays.asList(t("1"), t("b"), u())));
        // DELETE 未命中不算错误
        a.delete(info(), 'K', Arrays.asList(t("1"), n(), n()));
    }

    @Test
    void deleteWithoutPkFails() {
        Applier a = new Applier(TestSupport.config(), new Recorder());
        assertThrows(ApplyException.class, () -> a.delete(info(), 'K', Arrays.asList(n(), n(), n())));
    }

    @Test
    void compositePk() throws Exception {
        Recorder r = new Recorder();
        Applier a = new Applier(TestSupport.config(), r);
        RelationInfo h = info(new TableConfig("public", "h"), Arrays.asList("id", "ts", "v"),
                Arrays.asList("id", "ts"), false);
        a.delete(h, 'K', Arrays.asList(t("1"), t("2026-01-01 00:00:00"), n()));
        assertEquals("DELETE FROM \"public\".\"h\" WHERE \"id\" = ? AND \"ts\" = ?", r.sqls.get(0));
    }

    @Test
    void truncateNormalAndChunk() throws Exception {
        Recorder r = new Recorder();
        Applier a = new Applier(TestSupport.config(), r);
        TableConfig h = new TableConfig("public", "h");
        a.truncate(Arrays.asList(info(), info(h, Arrays.asList("id"), Arrays.asList("id"), true)), 0);
        assertEquals(Arrays.asList("TRUNCATE TABLE \"public\".\"t\""), r.sqls);

        SyncConfig strict = TestSupport.config(5000, 1000, "warn", "error");
        Recorder r2 = new Recorder();
        Applier a2 = new Applier(strict, r2);
        RelationInfo chunk = info(h, Arrays.asList("id"), Arrays.asList("id"), true);
        assertThrows(ApplyException.class, () -> a2.truncate(Arrays.asList(chunk), 0));
        // 超表本身被 TRUNCATE 时，同一消息中的 chunk 不再报错
        a2.truncate(Arrays.asList(info(h, Arrays.asList("id"), Arrays.asList("id"), false), chunk), 0);
        assertEquals(Arrays.asList("TRUNCATE TABLE \"public\".\"h\""), r2.sqls);
    }
}
