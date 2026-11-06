package com.retror.pglsync.apply;

import java.util.ArrayList;
import java.util.List;

/** 测试用：记录所有 SQL 的假执行器。 */
public class RecordingExecutor implements TargetExecutor {
    public final List<String> sqls = new ArrayList<>();
    public final List<List<String>> params = new ArrayList<>();
    public int rowcount = 1;
    public int commits;
    public Long committedLsn;

    @Override
    public int update(String sql, List<String> p) {
        sqls.add(sql);
        params.add(new ArrayList<>(p));
        return rowcount;
    }

    @Override
    public void execute(String sql) {
        sqls.add(sql);
        params.add(new ArrayList<>());
    }

    @Override
    public void commitWithState(long endLsn) {
        commits++;
        committedLsn = endLsn;
    }

    @Override
    public void rollback() {
    }
}
