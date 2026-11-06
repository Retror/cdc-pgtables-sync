package com.retror.pglsync.stream;

import java.nio.ByteBuffer;
import java.sql.SQLException;

/** 复制流的最小接口（便于单元测试替换）。 */
public interface ReplicationIo {

    /** 非阻塞读取下一条消息，没有数据时返回 null。 */
    ByteBuffer readPending() throws SQLException;

    /** 向源库确认已安全落库的位点（write/flush/apply 同值）。 */
    void sendFeedback(long lsn) throws SQLException;

    /** 已从服务端收到的最大位点（含 keepalive 中的 wal_end）。 */
    long lastReceiveLsn();
}
