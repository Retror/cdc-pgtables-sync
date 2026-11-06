package com.retror.pglsync.apply;

import com.retror.pglsync.catalog.RelationInfo;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.pgoutput.PgOutput;
import com.retror.pglsync.util.Sql;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把解码后的行变更应用到目标库。
 *
 * <ul>
 *   <li>所有写入都是幂等的：INSERT / 整行 UPDATE 统一用 INSERT ... ON CONFLICT (pk) DO UPDATE；</li>
 *   <li>UPDATE 修改了主键时，先按旧主键删除再写入新行；</li>
 *   <li>连续的同表 upsert 合并成一条多行 INSERT，减少跨网络往返；</li>
 *   <li>列值保持 PostgreSQL 文本格式，以未知类型参数由目标库按列类型解析。</li>
 * </ul>
 */
public class Applier {
    private static final Logger log = LoggerFactory.getLogger(Applier.class);
    /** PostgreSQL 单条语句的绑定参数上限。 */
    private static final int MAX_PARAMS = 32767;

    private final SyncConfig cfg;
    private final TargetExecutor exec;
    private Group group;
    private long missingRows;

    public Applier(SyncConfig cfg, TargetExecutor exec) {
        this.cfg = cfg;
        this.exec = exec;
    }

    public long getMissingRows() {
        return missingRows;
    }

    /** 待合并写入的 upsert 组：同一张表、同一组列。 */
    private static final class Group {
        final RelationInfo info;
        final List<String> cols;
        final String key;
        /** 主键值 -> 整行值（同一批内同主键只保留最后一次）。 */
        final LinkedHashMap<List<String>, List<String>> rows = new LinkedHashMap<>();

        Group(RelationInfo info, List<String> cols, String key) {
            this.info = info;
            this.cols = cols;
            this.key = key;
        }
    }

    /** 解析后的一行：列名 -> 文本值（可为 null），以及未变更的 TOAST 列。 */
    static final class Row {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        final Set<String> unchanged = new HashSet<>();
    }

    static Row toRow(RelationInfo info, List<PgOutput.Value> tuple) {
        if (tuple.size() != info.colNames.size()) {
            throw new ApplyException("表 " + info.sourceName() + " 列数不匹配: relation 声明 "
                    + info.colNames.size() + " 列, 收到 " + tuple.size() + " 列");
        }
        Row row = new Row();
        for (int i = 0; i < tuple.size(); i++) {
            String name = info.colNames.get(i);
            PgOutput.Value v = tuple.get(i);
            switch (v.kind) {
                case PgOutput.TEXT:
                    row.values.put(name, v.text);
                    break;
                case PgOutput.NULL:
                    row.values.put(name, null);
                    break;
                case PgOutput.UNCHANGED:
                    row.unchanged.add(name);
                    break;
                default:
                    throw new ApplyException("不支持的列格式 " + v.kind + "（仅支持文本格式）");
            }
        }
        return row;
    }

    private static String table(RelationInfo info) {
        TableConfig t = info.target;
        return Sql.qname(t.getSchema(), t.getName());
    }

    private static String wherePk(List<String> pk) {
        return pk.stream().map(c -> Sql.qi(c) + " = ?").collect(Collectors.joining(" AND "));
    }

    // ------------------------------------------------------------ upsert 合并

    private void upsert(RelationInfo info, LinkedHashMap<String, String> values) throws SQLException {
        List<String> cols = new ArrayList<>(values.keySet());
        String key = info.target.qualified() + "|" + String.join(",", cols);
        if (group != null && !group.key.equals(key)) {
            flushPending();
        }
        if (group == null) {
            group = new Group(info, cols, key);
        }
        List<String> pkValue = new ArrayList<>();
        for (String c : info.pk) {
            pkValue.add(values.get(c));
        }
        group.rows.remove(pkValue);
        group.rows.put(pkValue, new ArrayList<>(values.values()));
        if (group.rows.size() >= cfg.getUpsertGroupSize()) {
            flushPending();
        }
    }

    public void flushPending() throws SQLException {
        Group g = group;
        if (g == null) {
            return;
        }
        group = null;
        int perStmt = Math.max(1, Math.min(cfg.getUpsertGroupSize(), MAX_PARAMS / g.cols.size()));
        List<List<String>> rows = new ArrayList<>(g.rows.values());
        for (int i = 0; i < rows.size(); i += perStmt) {
            List<List<String>> page = rows.subList(i, Math.min(rows.size(), i + perStmt));
            exec.update(upsertSql(g.info, g.cols, page.size()), flatten(page));
        }
    }

    static String upsertSql(RelationInfo info, List<String> cols, int nrows) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table(info))
                .append(" (").append(Sql.qcols(cols)).append(") VALUES ");
        String one = "(" + cols.stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";
        for (int i = 0; i < nrows; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(one);
        }
        sb.append(" ON CONFLICT (").append(Sql.qcols(info.pk)).append(") ");
        List<String> nonPk = cols.stream().filter(c -> !info.pk.contains(c)).collect(Collectors.toList());
        if (nonPk.isEmpty()) {
            sb.append("DO NOTHING");
        } else {
            sb.append("DO UPDATE SET ").append(nonPk.stream()
                    .map(c -> Sql.qi(c) + " = EXCLUDED." + Sql.qi(c)).collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    private static List<String> flatten(List<List<String>> rows) {
        List<String> out = new ArrayList<>();
        for (List<String> r : rows) {
            out.addAll(r);
        }
        return out;
    }

    // ------------------------------------------------------------ 变更类型

    public void insert(RelationInfo info, List<PgOutput.Value> tuple) throws SQLException {
        Row row = toRow(info, tuple);
        if (!row.unchanged.isEmpty()) {
            throw new ApplyException("INSERT " + info.target + " 中出现未变更 TOAST 列 " + row.unchanged);
        }
        upsert(info, row.values);
    }

    public void update(RelationInfo info, Character oldKind, List<PgOutput.Value> oldTuple,
                       List<PgOutput.Value> newTuple) throws SQLException {
        Row nw = toRow(info, newTuple);
        Row old = oldTuple == null ? null : toRow(info, oldTuple);
        if (old != null && !nw.unchanged.isEmpty() && oldKind != null && oldKind == 'O') {
            // REPLICA IDENTITY FULL 时可从旧整行补齐未变更的 TOAST 列
            for (Iterator<String> it = nw.unchanged.iterator(); it.hasNext(); ) {
                String col = it.next();
                if (old.values.containsKey(col) && !old.unchanged.contains(col)) {
                    nw.values.put(col, old.values.get(col));
                    it.remove();
                }
            }
            LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
            for (String c : info.colNames) {
                if (nw.values.containsKey(c)) {
                    ordered.put(c, nw.values.get(c));
                }
            }
            nw.values.clear();
            nw.values.putAll(ordered);
        }

        List<String> pk = info.pk;
        for (String c : pk) {
            if (nw.unchanged.contains(c)) {
                throw new ApplyException("UPDATE " + info.target + " 的主键列为未变更 TOAST 值，无法定位目标行");
            }
        }
        List<String> newPk = new ArrayList<>();
        for (String c : pk) {
            newPk.add(nw.values.get(c));
        }
        List<String> oldPk = newPk;
        if (old != null) {
            List<String> candidate = new ArrayList<>();
            boolean complete = true;
            for (String c : pk) {
                String v = old.values.get(c);
                if (v == null) {
                    complete = false;
                }
                candidate.add(v);
            }
            if (complete) {
                oldPk = candidate;
            }
        }

        if (!Objects.equals(oldPk, newPk)) {
            // 主键发生变化：删除旧行 + 写入新行
            flushPending();
            int n = exec.update("DELETE FROM " + table(info) + " WHERE " + wherePk(pk), oldPk);
            if (n == 0) {
                missing("UPDATE(主键变更)", info, oldPk);
            }
            if (!nw.unchanged.isEmpty()) {
                throw new ApplyException("UPDATE " + info.target + " 修改了主键且存在未变更 TOAST 列 " + nw.unchanged
                        + "，无法重建整行；请对源表设置 REPLICA IDENTITY FULL");
            }
            upsert(info, nw.values);
            return;
        }

        if (nw.unchanged.isEmpty()) {
            // 整行可用：幂等 upsert（目标缺行时会自动补上）
            upsert(info, nw.values);
            return;
        }

        // 存在未变更 TOAST 列：只更新收到的列
        flushPending();
        List<String> setCols = new ArrayList<>();
        List<String> params = new ArrayList<>();
        for (Map.Entry<String, String> e : nw.values.entrySet()) {
            if (!pk.contains(e.getKey())) {
                setCols.add(e.getKey());
                params.add(e.getValue());
            }
        }
        if (setCols.isEmpty()) {
            return;
        }
        params.addAll(newPk);
        String sql = "UPDATE " + table(info) + " SET "
                + setCols.stream().map(c -> Sql.qi(c) + " = ?").collect(Collectors.joining(", "))
                + " WHERE " + wherePk(pk);
        if (exec.update(sql, params) == 0) {
            missing("UPDATE", info, newPk);
        }
    }

    public void delete(RelationInfo info, char oldKind, List<PgOutput.Value> oldTuple) throws SQLException {
        flushPending();
        Row old = toRow(info, oldTuple);
        List<String> pkValues = new ArrayList<>();
        for (String c : info.pk) {
            String v = old.values.get(c);
            if (v == null) {
                throw new ApplyException("DELETE " + info.target + " 缺少主键值（复制标识不包含主键列 " + info.pk + "）");
            }
            pkValues.add(v);
        }
        int n = exec.update("DELETE FROM " + table(info) + " WHERE " + wherePk(info.pk), pkValues);
        if (n == 0) {
            // 删除一条本就不存在的行不影响最终一致性，仅记录
            missingRows++;
            log.debug("DELETE {} 未命中目标行 pk={}", info.target, pkValues);
        }
    }

    public void truncate(List<RelationInfo> infos, int options) throws SQLException {
        flushPending();
        Map<String, TableConfig> tables = new LinkedHashMap<>();
        for (RelationInfo i : infos) {
            if (!i.chunk) {
                tables.put(i.target.qualified(), i.target);
            }
        }
        for (RelationInfo i : infos) {
            if (i.chunk && !tables.containsKey(i.target.qualified())) {
                String msg = "源库 TRUNCATE 了 chunk " + i.sourceName() + "（属于超表 " + i.target
                        + "），无法安全映射为目标库操作，已忽略；请用 reconcile 核对该时间段数据";
                if (cfg.chunkTruncateIsError()) {
                    throw new ApplyException(msg);
                }
                log.warn(msg);
            }
        }
        if (!tables.isEmpty()) {
            log.warn("源库执行了 TRUNCATE，同步到目标库: {}", String.join(", ", tables.keySet()));
            exec.execute("TRUNCATE TABLE " + tables.values().stream()
                    .map(t -> Sql.qname(t.getSchema(), t.getName())).collect(Collectors.joining(", ")));
        }
    }

    private void missing(String op, RelationInfo info, List<String> pk) {
        missingRows++;
        String msg = op + " " + info.target + " 未命中目标行 pk=" + pk + "（目标库与源库已不一致）";
        if (cfg.missingRowIsError()) {
            throw new ApplyException(msg);
        }
        log.warn(msg);
    }

    /** 写入位点并提交：数据与位点原子落库。 */
    public void commit(long endLsn) throws SQLException {
        flushPending();
        exec.commitWithState(endLsn);
    }

    public void rollback() throws SQLException {
        group = null;
        exec.rollback();
    }
}
