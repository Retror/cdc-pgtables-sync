package com.retror.pglsync;

import com.retror.pglsync.config.PglsyncProperties;
import com.retror.pglsync.config.SyncConfig;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 测试辅助：构造 pgoutput 消息与配置。 */
public final class TestSupport {

    private TestSupport() {
    }

    public static SyncConfig config() {
        return config(5000, 1000.0, "warn", "warn");
    }

    public static SyncConfig config(int batchMaxChanges, double batchMaxSeconds, String onMissingRow, String chunkTruncate) {
        PglsyncProperties p = new PglsyncProperties();
        p.getSource().setHost("src");
        p.getSource().setDbname("db");
        p.getSource().setUser("u");
        p.getTarget().setHost("tgt");
        p.getTarget().setDbname("db");
        p.getTarget().setUser("u");
        p.setSlotName("s");
        PglsyncProperties.Table t = new PglsyncProperties.Table();
        t.setName("public.t");
        PglsyncProperties.Table h = new PglsyncProperties.Table();
        h.setName("public.h");
        h.setPk(Arrays.asList("id", "ts"));
        p.setTables(Arrays.asList(t, h));
        p.getApply().setBatchMaxChanges(batchMaxChanges);
        p.getApply().setBatchMaxSeconds(batchMaxSeconds);
        p.getApply().setOnMissingRow(onMissingRow);
        p.getApply().setChunkTruncate(chunkTruncate);
        p.getStream().setStatsIntervalSeconds(1e9);
        return SyncConfig.from(p);
    }

    /** 简单的消息构造器（大端序）。 */
    public static final class Msg {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Msg ch(char c) {
            out.write((byte) c);
            return this;
        }

        public Msg u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        public Msg i16(int v) {
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
            return this;
        }

        public Msg i32(int v) {
            for (int s = 24; s >= 0; s -= 8) {
                out.write((v >> s) & 0xFF);
            }
            return this;
        }

        public Msg i64(long v) {
            for (int s = 56; s >= 0; s -= 8) {
                out.write((int) (v >> s) & 0xFF);
            }
            return this;
        }

        public Msg cstr(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
            out.write(0);
            return this;
        }

        /** 元组：null -> 'n'，"\u0000U" -> 'u'（未变更 TOAST），其他 -> 't'。 */
        public Msg tuple(String... values) {
            i16(values.length);
            for (String v : values) {
                if (v == null) {
                    ch('n');
                } else if (UNCHANGED.equals(v)) {
                    ch('u');
                } else {
                    byte[] b = v.getBytes(StandardCharsets.UTF_8);
                    ch('t').i32(b.length);
                    out.write(b, 0, b.length);
                }
            }
            return this;
        }

        public ByteBuffer buf() {
            return ByteBuffer.wrap(out.toByteArray());
        }

        public byte[] bytes() {
            return out.toByteArray();
        }
    }

    public static final String UNCHANGED = "\u0000U";

    public static ByteBuffer begin(long finalLsn) {
        return new Msg().ch('B').i64(finalLsn).i64(0).i32(1).buf();
    }

    public static ByteBuffer commit(long commitLsn, long endLsn) {
        return new Msg().ch('C').u8(0).i64(commitLsn).i64(endLsn).i64(0).buf();
    }

    /** cols 形如 "id*"（* 表示属于复制标识）。 */
    public static ByteBuffer relation(int relid, String ns, String name, String... cols) {
        Msg m = new Msg().ch('R').i32(relid).cstr(ns).cstr(name).ch('d').i16(cols.length);
        for (String c : cols) {
            boolean key = c.endsWith("*");
            m.u8(key ? 1 : 0).cstr(key ? c.substring(0, c.length() - 1) : c).i32(25).i32(-1);
        }
        return m.buf();
    }

    public static ByteBuffer insert(int relid, String... values) {
        return new Msg().ch('I').i32(relid).ch('N').tuple(values).buf();
    }

    public static ByteBuffer update(int relid, String[] oldValues, char oldKind, String... newValues) {
        Msg m = new Msg().ch('U').i32(relid);
        if (oldValues != null) {
            m.ch(oldKind).tuple(oldValues);
        }
        return m.ch('N').tuple(newValues).buf();
    }

    public static ByteBuffer delete(int relid, String... oldValues) {
        return new Msg().ch('D').i32(relid).ch('K').tuple(oldValues).buf();
    }

    public static ByteBuffer truncate(int options, int... relids) {
        Msg m = new Msg().ch('T').i32(relids.length).u8(options);
        for (int r : relids) {
            m.i32(r);
        }
        return m.buf();
    }
}
