package com.retror.pglsync.service;

import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.util.Sql;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 源库 COPY TO STDOUT -> 管道 -> 目标库 COPY FROM STDIN，流式传输不落盘。 */
public final class TableCopier {

    private TableCopier() {
    }

    public static long copy(Connection src, Connection tgt, TableConfig t, List<String> cols) throws Exception {
        String colList = Sql.qcols(cols);
        String table = Sql.qname(t.getSchema(), t.getName());
        String outSql = "COPY (SELECT " + colList + " FROM " + table + ") TO STDOUT";
        String inSql = "COPY " + table + " (" + colList + ") FROM STDIN";

        CopyManager srcCopy = src.unwrap(PGConnection.class).getCopyAPI();
        CopyManager tgtCopy = tgt.unwrap(PGConnection.class).getCopyAPI();

        PipedInputStream in = new PipedInputStream(1 << 20);
        PipedOutputStream out = new PipedOutputStream(in);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            try (OutputStream o = out) {
                srcCopy.copyOut(outSql, o);
            } catch (Throwable e) {
                error.set(e);
            }
        }, "copy-" + t.getName());
        producer.setDaemon(true);
        producer.start();

        long rows;
        try {
            rows = tgtCopy.copyIn(inSql, in);
        } catch (Exception e) {
            // 关闭读端，让生产者线程的写入失败退出，避免阻塞
            closeQuietly(in);
            producer.join();
            if (error.get() != null) {
                e.addSuppressed(error.get());
            }
            throw e;
        }
        producer.join();
        closeQuietly(in);
        if (error.get() != null) {
            Throwable e = error.get();
            if (e instanceof Exception) {
                throw (Exception) e;
            }
            throw new RuntimeException(e);
        }
        return rows;
    }

    private static void closeQuietly(PipedInputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
