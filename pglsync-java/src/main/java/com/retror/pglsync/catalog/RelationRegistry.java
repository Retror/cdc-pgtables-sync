package com.retror.pglsync.catalog;

import com.retror.pglsync.config.ConfigException;
import com.retror.pglsync.config.SyncConfig;
import com.retror.pglsync.config.TableConfig;
import com.retror.pglsync.pgoutput.DecodeException;
import com.retror.pglsync.pgoutput.PgOutput;
import com.retror.pglsync.util.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 根据 pgoutput 的 Relation 消息维护 relid -> RelationInfo，并完成 TimescaleDB chunk -> 超表映射。 */
public class RelationRegistry implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RelationRegistry.class);
    private static final Pattern CHUNK_NAME = Pattern.compile("^_hyper_(\\d+)_\\d+_chunk$");
    private static final String[] NONE = new String[0];

    private final SyncConfig cfg;
    private final Map<Long, RelationInfo> rels = new HashMap<>();
    private final Map<String, String[]> chunkCache = new HashMap<>();
    private Connection conn;

    public RelationRegistry(SyncConfig cfg) {
        this.cfg = cfg;
    }

    /** 源库元数据连接（autocommit，查询失败不会污染其他事务）。 */
    private Connection conn() throws SQLException {
        if (conn == null || conn.isClosed()) {
            conn = Db.connect(cfg.getSource(), true);
        }
        return conn;
    }

    @Override
    public void close() {
        Db.closeQuietly(conn);
        conn = null;
    }

    /**
     * chunk -> {超表 schema, 超表名}；不是 chunk 或已无法找到时返回 null。
     *
     * <p>通过 PostgreSQL 自身的继承关系（pg_inherits）查找：chunk 都是超表的继承子表，
     * 不依赖 _timescaledb_catalog 内部表结构（其列名在不同 TimescaleDB 版本间有变化）。
     * 主查询异常直接抛出（由上层重连重试），不会被缓存成“跳过”。
     */
    protected String[] hypertableOf(String schema, String name) throws SQLException {
        String key = schema + "." + name;
        String[] cached = chunkCache.get(key);
        if (cached != null) {
            return cached.length == 0 ? null : cached;
        }
        String[] result = null;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT pn.nspname, p.relname FROM pg_class c "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "JOIN pg_inherits i ON i.inhrelid = c.oid "
                        + "JOIN pg_class p ON p.oid = i.inhparent "
                        + "JOIN pg_namespace pn ON pn.oid = p.relnamespace "
                        + "WHERE n.nspname = ? AND c.relname = ?")) {
            ps.setString(1, schema);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    result = new String[]{rs.getString(1), rs.getString(2)};
                }
            }
        }
        if (result == null) {
            // chunk 可能已被 drop_chunks 删除，按命名规则 _hyper_<超表id>_<n>_chunk 兜底
            result = hypertableByChunkName(name);
        }
        if (result == null && name.startsWith("_hyper_")) {
            log.warn("无法找到 chunk {}.{} 所属的超表，其变更将被忽略", schema, name);
        }
        chunkCache.put(key, result == null ? NONE : result);
        return result;
    }

    private String[] hypertableByChunkName(String name) {
        Matcher m = CHUNK_NAME.matcher(name);
        if (!m.matches()) {
            return null;
        }
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT schema_name, table_name FROM _timescaledb_catalog.hypertable WHERE id = ?")) {
            ps.setInt(1, Integer.parseInt(m.group(1)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null;
            }
        } catch (Exception e) {
            log.warn("按 chunk 名称查找超表失败: {}", e.getMessage());
            return null;
        }
    }

    public RelationInfo register(PgOutput.Relation rel) throws SQLException {
        List<String> colNames = new ArrayList<>();
        List<String> keyCols = new ArrayList<>();
        for (PgOutput.Column c : rel.columns) {
            colNames.add(c.name);
            if ((c.flags & PgOutput.KEY_FLAG) != 0) {
                keyCols.add(c.name);
            }
        }
        RelationInfo info = new RelationInfo(rel.relid, rel.namespace, rel.name, rel.replident, colNames, keyCols);

        String targetKey = null;
        if (cfg.getChunkSchemas().contains(rel.namespace)) {
            String[] hyper = hypertableOf(rel.namespace, rel.name);
            if (hyper != null) {
                info.chunk = true;
                targetKey = hyper[0] + "." + hyper[1];
            }
        } else {
            targetKey = rel.namespace + "." + rel.name;
        }

        TableConfig table = targetKey == null ? null : cfg.getTables().get(targetKey);
        if (table != null) {
            info.target = table;
            try {
                info.pk = validate(table, info);
                log.debug("relation {} (relid={}) -> {} pk={}", info.sourceName(), rel.relid, table, info.pk);
            } catch (ConfigException e) {
                // 可能是表结构变更前的旧 Relation，其变更会因 resync 位点而被跳过；应用时才报错
                info.invalid = e.getMessage();
                log.debug("relation {} 暂不可用: {}", info.sourceName(), e.getMessage());
            }
        }
        rels.put(rel.relid, info);
        return info;
    }

    static List<String> validate(TableConfig table, RelationInfo info) {
        List<String> pk = table.getPk() != null ? table.getPk() : info.keyCols;
        if (pk.isEmpty()) {
            throw new ConfigException("表 " + table + " 没有主键（复制标识），请在配置中指定 pk 或为源表添加主键");
        }
        List<String> missing = new ArrayList<>();
        List<String> notInIdentity = new ArrayList<>();
        for (String c : pk) {
            if (!info.colNames.contains(c)) {
                missing.add(c);
            }
            if (!info.keyCols.contains(c)) {
                notInIdentity.add(c);
            }
        }
        if (!missing.isEmpty()) {
            throw new ConfigException("表 " + table + " 配置的主键列 " + missing + " 在源表中不存在");
        }
        if (!notInIdentity.isEmpty()) {
            throw new ConfigException("表 " + table + "（源 relation " + info.sourceName() + "）的复制标识不包含主键列 "
                    + notInIdentity + "，DELETE/UPDATE 将无法定位目标行。"
                    + "请为源表设置主键，或执行 ALTER TABLE ... REPLICA IDENTITY FULL");
        }
        return new ArrayList<>(pk);
    }

    public RelationInfo get(long relid) {
        RelationInfo info = rels.get(relid);
        if (info == null) {
            throw new DecodeException("收到未声明的 relid=" + relid + "（缺少 Relation 消息）");
        }
        return info;
    }
}
