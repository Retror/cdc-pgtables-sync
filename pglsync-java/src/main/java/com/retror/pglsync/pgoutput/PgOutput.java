package com.retror.pglsync.pgoutput;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * pgoutput 逻辑复制协议解码（proto_version = 1，文本格式列值）。
 *
 * <p>协议文档：https://www.postgresql.org/docs/current/protocol-logicalrep-message-formats.html
 *
 * <p>列值保持为 PostgreSQL 的文本输出格式（String），写入目标库时以未知类型参数交给 PostgreSQL
 * 按目标列类型解析，不存在类型转换导致的精度丢失。
 */
public final class PgOutput {

    public static final char NULL = 'n';
    /** 未变化的 TOAST 值（UPDATE 时可能出现，值未随消息发送）。 */
    public static final char UNCHANGED = 'u';
    public static final char TEXT = 't';
    public static final char BINARY = 'b';

    /** Relation 中列 flags 的“属于复制标识（主键）”位。 */
    public static final int KEY_FLAG = 1;

    private PgOutput() {
    }

    public interface Message {
    }

    public static final class Begin implements Message {
        public final long finalLsn;
        public final long commitTs;
        public final long xid;

        public Begin(long finalLsn, long commitTs, long xid) {
            this.finalLsn = finalLsn;
            this.commitTs = commitTs;
            this.xid = xid;
        }
    }

    public static final class Commit implements Message {
        public final int flags;
        public final long commitLsn;
        public final long endLsn;
        public final long commitTs;

        public Commit(int flags, long commitLsn, long endLsn, long commitTs) {
            this.flags = flags;
            this.commitLsn = commitLsn;
            this.endLsn = endLsn;
            this.commitTs = commitTs;
        }
    }

    public static final class Column {
        public final int flags;
        public final String name;
        public final long typeOid;
        public final int typmod;

        public Column(int flags, String name, long typeOid, int typmod) {
            this.flags = flags;
            this.name = name;
            this.typeOid = typeOid;
            this.typmod = typmod;
        }
    }

    public static final class Relation implements Message {
        public final long relid;
        public final String namespace;
        public final String name;
        public final char replident;
        public final List<Column> columns;

        public Relation(long relid, String namespace, String name, char replident, List<Column> columns) {
            this.relid = relid;
            this.namespace = namespace;
            this.name = name;
            this.replident = replident;
            this.columns = Collections.unmodifiableList(columns);
        }
    }

    /** 元组中的一列。kind 为 NULL / UNCHANGED / TEXT / BINARY。 */
    public static final class Value {
        public final char kind;
        public final String text;

        public Value(char kind, String text) {
            this.kind = kind;
            this.text = text;
        }

        @Override
        public String toString() {
            return kind + ":" + text;
        }
    }

    public static final class Insert implements Message {
        public final long relid;
        public final List<Value> newTuple;

        public Insert(long relid, List<Value> newTuple) {
            this.relid = relid;
            this.newTuple = newTuple;
        }
    }

    public static final class Update implements Message {
        public final long relid;
        /** null（无旧值）/ 'K'（仅键列）/ 'O'（整行）。 */
        public final Character oldKind;
        public final List<Value> oldTuple;
        public final List<Value> newTuple;

        public Update(long relid, Character oldKind, List<Value> oldTuple, List<Value> newTuple) {
            this.relid = relid;
            this.oldKind = oldKind;
            this.oldTuple = oldTuple;
            this.newTuple = newTuple;
        }
    }

    public static final class Delete implements Message {
        public final long relid;
        public final char oldKind;
        public final List<Value> oldTuple;

        public Delete(long relid, char oldKind, List<Value> oldTuple) {
            this.relid = relid;
            this.oldKind = oldKind;
            this.oldTuple = oldTuple;
        }
    }

    public static final class Truncate implements Message {
        public static final int CASCADE = 1;
        public static final int RESTART_IDENTITY = 2;
        public final int options;
        public final List<Long> relids;

        public Truncate(int options, List<Long> relids) {
            this.options = options;
            this.relids = relids;
        }
    }

    /** Origin / Type / LogicalMessage 等无需处理的消息。 */
    public static final class Ignored implements Message {
        public final char tag;

        public Ignored(char tag) {
            this.tag = tag;
        }
    }

    // ------------------------------------------------------------------ 解码

    public static Message decode(ByteBuffer payload) {
        ByteBuffer b = payload.slice();
        try {
            char tag = (char) (b.get() & 0xFF);
            switch (tag) {
                case 'B':
                    return new Begin(b.getLong(), b.getLong(), u32(b));
                case 'C':
                    return new Commit(b.get() & 0xFF, b.getLong(), b.getLong(), b.getLong());
                case 'R': {
                    long relid = u32(b);
                    String ns = cstr(b);
                    String name = cstr(b);
                    char ident = (char) (b.get() & 0xFF);
                    int n = b.getShort();
                    List<Column> cols = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        int flags = b.get() & 0xFF;
                        String col = cstr(b);
                        long oid = u32(b);
                        int typmod = b.getInt();
                        cols.add(new Column(flags, col, oid, typmod));
                    }
                    return new Relation(relid, ns, name, ident, cols);
                }
                case 'I': {
                    long relid = u32(b);
                    expect(b, 'N', "INSERT");
                    return new Insert(relid, tuple(b));
                }
                case 'U': {
                    long relid = u32(b);
                    char marker = (char) (b.get() & 0xFF);
                    Character oldKind = null;
                    List<Value> old = null;
                    if (marker == 'K' || marker == 'O') {
                        oldKind = marker;
                        old = tuple(b);
                        marker = (char) (b.get() & 0xFF);
                    }
                    if (marker != 'N') {
                        throw new DecodeException("UPDATE 消息格式错误: " + marker);
                    }
                    return new Update(relid, oldKind, old, tuple(b));
                }
                case 'D': {
                    long relid = u32(b);
                    char marker = (char) (b.get() & 0xFF);
                    if (marker != 'K' && marker != 'O') {
                        throw new DecodeException("DELETE 消息格式错误: " + marker);
                    }
                    return new Delete(relid, marker, tuple(b));
                }
                case 'T': {
                    long count = u32(b);
                    int options = b.get() & 0xFF;
                    List<Long> relids = new ArrayList<>();
                    for (long i = 0; i < count; i++) {
                        relids.add(u32(b));
                    }
                    return new Truncate(options, relids);
                }
                case 'O':
                case 'Y':
                case 'M':
                    return new Ignored(tag);
                default:
                    throw new DecodeException("未知的 pgoutput 消息类型: " + tag);
            }
        } catch (BufferUnderflowException e) {
            throw new DecodeException("消息被截断");
        }
    }

    private static long u32(ByteBuffer b) {
        return b.getInt() & 0xFFFFFFFFL;
    }

    private static void expect(ByteBuffer b, char c, String what) {
        char marker = (char) (b.get() & 0xFF);
        if (marker != c) {
            throw new DecodeException(what + " 消息格式错误: " + marker);
        }
    }

    private static String cstr(ByteBuffer b) {
        int start = b.position();
        int end = start;
        while (end < b.limit() && b.get(end) != 0) {
            end++;
        }
        if (end >= b.limit()) {
            throw new DecodeException("字符串缺少结束符");
        }
        byte[] bytes = new byte[end - start];
        b.get(bytes);
        b.get(); // NUL
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<Value> tuple(ByteBuffer b) {
        int n = b.getShort();
        List<Value> values = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            char kind = (char) (b.get() & 0xFF);
            switch (kind) {
                case NULL:
                case UNCHANGED:
                    values.add(new Value(kind, null));
                    break;
                case TEXT:
                case BINARY: {
                    int len = b.getInt();
                    if (len < 0 || len > b.remaining()) {
                        throw new DecodeException("列数据长度非法: " + len);
                    }
                    byte[] bytes = new byte[len];
                    b.get(bytes);
                    values.add(new Value(kind, new String(bytes, StandardCharsets.UTF_8)));
                    break;
                }
                default:
                    throw new DecodeException("未知的列数据类型标记: " + kind);
            }
        }
        return values;
    }
}
