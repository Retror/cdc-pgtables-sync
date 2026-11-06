package com.retror.pglsync.apply;

import java.sql.SQLException;
import java.util.List;

/** Applier 写目标库所用的最小接口（便于单元测试替换）。 */
public interface TargetExecutor {

    /** 执行带参数的写语句（参数为 PostgreSQL 文本格式或 null），返回影响行数。 */
    int update(String sql, List<String> params) throws SQLException;

    /** 执行无参数语句（TRUNCATE 等）。 */
    void execute(String sql) throws SQLException;

    /** 在当前事务内写入同步位点并提交。 */
    void commitWithState(long endLsn) throws SQLException;

    void rollback() throws SQLException;
}
