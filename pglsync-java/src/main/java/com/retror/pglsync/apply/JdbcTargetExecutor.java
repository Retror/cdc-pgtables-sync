package com.retror.pglsync.apply;

import com.retror.pglsync.state.StateStore;
import com.retror.pglsync.util.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/** 基于 JDBC 连接的 TargetExecutor（连接需为 autocommit=false、stringtype=unspecified）。 */
public class JdbcTargetExecutor implements TargetExecutor {
    private final Connection conn;
    private final String stateSchema;
    private final String slotName;

    public JdbcTargetExecutor(Connection conn, String stateSchema, String slotName) {
        this.conn = conn;
        this.stateSchema = stateSchema;
        this.slotName = slotName;
    }

    @Override
    public int update(String sql, List<String> params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                String v = params.get(i);
                if (v == null) {
                    ps.setNull(i + 1, Types.OTHER);
                } else {
                    ps.setString(i + 1, v);
                }
            }
            return ps.executeUpdate();
        }
    }

    @Override
    public void execute(String sql) throws SQLException {
        Db.execute(conn, sql);
    }

    @Override
    public void commitWithState(long endLsn) throws SQLException {
        StateStore.writeState(conn, stateSchema, slotName, endLsn);
        conn.commit();
    }

    @Override
    public void rollback() throws SQLException {
        conn.rollback();
    }
}
