package com.retror.pglsync.util;

import org.postgresql.replication.LogSequenceNumber;

import java.util.List;
import java.util.stream.Collectors;

/** 标识符引用、LSN 转换、时间戳转换等小工具。 */
public final class Sql {

    /** 2000-01-01 00:00:00 UTC 的 Unix 毫秒数（pgoutput 时间戳起点）。 */
    private static final long PG_EPOCH_MILLIS = 946684800000L;

    private Sql() {
    }

    /** 以双引号引用 SQL 标识符。拒绝 NUL 等非法字符。 */
    public static String qi(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法标识符: " + name);
        }
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    public static String qname(String schema, String name) {
        return qi(schema) + "." + qi(name);
    }

    public static String qcols(List<String> cols) {
        return cols.stream().map(Sql::qi).collect(Collectors.joining(", "));
    }

    public static long lsn(String text) {
        return LogSequenceNumber.valueOf(text).asLong();
    }

    public static String lsn(long value) {
        return LogSequenceNumber.valueOf(value).asString();
    }

    /** pgoutput 时间戳（2000-01-01 起的微秒数）-> Unix 毫秒。 */
    public static long pgTimestampToMillis(long micros) {
        return PG_EPOCH_MILLIS + micros / 1000;
    }
}
