package com.retror.pglsync.stream;

import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;

import java.nio.ByteBuffer;
import java.sql.SQLException;

/** 基于 pgjdbc PGReplicationStream 的 ReplicationIo。 */
public class PgReplicationIo implements ReplicationIo {
    private final PGReplicationStream stream;

    public PgReplicationIo(PGReplicationStream stream) {
        this.stream = stream;
    }

    @Override
    public ByteBuffer readPending() throws SQLException {
        return stream.readPending();
    }

    @Override
    public void sendFeedback(long lsn) throws SQLException {
        LogSequenceNumber l = LogSequenceNumber.valueOf(lsn);
        stream.setFlushedLSN(l);
        stream.setAppliedLSN(l);
        stream.forceUpdateStatus();
    }

    @Override
    public long lastReceiveLsn() {
        LogSequenceNumber l = stream.getLastReceiveLSN();
        return l == null ? 0 : l.asLong();
    }
}
