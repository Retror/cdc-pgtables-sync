package com.retror.pglsync.pgoutput;

import com.retror.pglsync.util.Sql;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static com.retror.pglsync.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class PgOutputTest {

    @Test
    void lsnRoundTrip() {
        for (String s : Arrays.asList("0/0", "16/B374D848", "4/3213BD08")) {
            assertEquals(s, Sql.lsn(Sql.lsn(s)));
        }
        assertEquals(1L << 32, Sql.lsn("1/0"));
    }

    @Test
    void quoteIdentifier() {
        assertEquals("\"a\"\"b\"", Sql.qi("a\"b"));
        assertEquals("\"public\".\"t\"", Sql.qname("public", "t"));
    }

    @Test
    void beginCommit() {
        PgOutput.Begin b = (PgOutput.Begin) PgOutput.decode(begin(100));
        assertEquals(100, b.finalLsn);
        PgOutput.Commit c = (PgOutput.Commit) PgOutput.decode(commit(100, 108));
        assertEquals(100, c.commitLsn);
        assertEquals(108, c.endLsn);
    }

    @Test
    void relationWithKeyFlags() {
        PgOutput.Relation r = (PgOutput.Relation) PgOutput.decode(
                relation(5, "_timescaledb_internal", "_hyper_1_2_chunk", "id*", "ts*", "v"));
        assertEquals(5, r.relid);
        assertEquals("_hyper_1_2_chunk", r.name);
        assertEquals(3, r.columns.size());
        assertEquals(1, r.columns.get(0).flags & PgOutput.KEY_FLAG);
        assertEquals(0, r.columns.get(2).flags & PgOutput.KEY_FLAG);
    }

    @Test
    void insertWithNullAndUnicode() {
        PgOutput.Insert m = (PgOutput.Insert) PgOutput.decode(insert(5, "1", null, "台风"));
        assertEquals(PgOutput.TEXT, m.newTuple.get(0).kind);
        assertEquals(PgOutput.NULL, m.newTuple.get(1).kind);
        assertEquals("台风", m.newTuple.get(2).text);
    }

    @Test
    void updateVariants() {
        PgOutput.Update u1 = (PgOutput.Update) PgOutput.decode(update(5, null, 'K', "1", "x"));
        assertNull(u1.oldKind);
        PgOutput.Update u2 = (PgOutput.Update) PgOutput.decode(update(5, new String[]{"1", null}, 'K', "2", "x"));
        assertEquals(Character.valueOf('K'), u2.oldKind);
        assertEquals("1", u2.oldTuple.get(0).text);
        PgOutput.Update u3 = (PgOutput.Update) PgOutput.decode(update(5, null, 'K', "1", UNCHANGED));
        assertEquals(PgOutput.UNCHANGED, u3.newTuple.get(1).kind);
    }

    @Test
    void deleteAndTruncate() {
        PgOutput.Delete d = (PgOutput.Delete) PgOutput.decode(delete(5, "1", null));
        assertEquals('K', d.oldKind);
        PgOutput.Truncate t = (PgOutput.Truncate) PgOutput.decode(truncate(1, 5, 6));
        assertEquals(1, t.options);
        assertEquals(Arrays.asList(5L, 6L), t.relids);
    }

    @Test
    void truncatedMessageFails() {
        byte[] b = insert(5, "abc").array();
        assertThrows(DecodeException.class, () -> PgOutput.decode(ByteBuffer.wrap(b, 0, b.length - 1)));
        assertThrows(DecodeException.class, () -> PgOutput.decode(ByteBuffer.wrap(new byte[]{'Z'})));
    }
}
