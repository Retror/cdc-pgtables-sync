# -*- coding: utf-8 -*-
"""源库元数据：relation 缓存、TimescaleDB chunk -> 超表映射、列与主键查询。"""
import logging
import re

from . import pgoutput
from .config import ConfigError

log = logging.getLogger('pglsync.catalog')

_CHUNK_NAME_RE = re.compile(r'^_hyper_(\d+)_\d+_chunk$')


class RelationInfo(object):
    """一个源库 relation（pgoutput 的 relid）及其映射到的目标表。"""
    __slots__ = ('relid', 'namespace', 'name', 'replident', 'col_names',
                 'key_cols', 'target', 'pk', 'is_chunk')

    def __init__(self, relid, namespace, name, replident, col_names, key_cols,
                 target=None, pk=None, is_chunk=False):
        self.relid = relid
        self.namespace = namespace
        self.name = name
        self.replident = replident
        self.col_names = col_names
        self.key_cols = key_cols
        self.target = target      # TableConfig；None 表示该表不需要同步
        self.pk = pk              # 目标表主键列（用于 ON CONFLICT / WHERE）
        self.is_chunk = is_chunk

    @property
    def source_name(self):
        return '%s.%s' % (self.namespace, self.name)


class RelationRegistry(object):
    """根据 pgoutput 的 Relation 消息维护 relid -> RelationInfo。"""

    def __init__(self, cfg, connect=None):
        self.cfg = cfg
        self._connect = connect
        self._rels = {}
        self._chunk_cache = {}
        self._conn = None

    # ---------- 源库元数据连接（autocommit，查询失败不会污染其他事务） ----------
    def _cursor(self):
        if self._conn is None or self._conn.closed:
            if self._connect is not None:
                self._conn = self._connect()
            else:
                import psycopg2
                self._conn = psycopg2.connect(**self.cfg.source)
            self._conn.autocommit = True
        return self._conn.cursor()

    def close(self):
        if self._conn is not None and not self._conn.closed:
            self._conn.close()
        self._conn = None

    def hypertable_of(self, schema, name):
        """chunk -> (超表 schema, 超表名)；不是 chunk 或已无法找到时返回 None。

        查询异常会直接抛出（由上层重连重试），不会被缓存成“跳过”。
        """
        key = (schema, name)
        if key in self._chunk_cache:
            return self._chunk_cache[key]
        cur = self._cursor()
        try:
            cur.execute("""
                SELECT h.schema_name, h.table_name
                FROM _timescaledb_catalog.chunk c
                JOIN _timescaledb_catalog.hypertable h ON h.id = c.hypertable_id
                WHERE c.schema_name = %s AND c.table_name = %s
            """, (schema, name))
            row = cur.fetchone()
            if row is None:
                # chunk 可能已被 drop_chunks 删除，按命名规则 _hyper_<超表id>_<n>_chunk 兜底
                m = _CHUNK_NAME_RE.match(name)
                if m:
                    cur.execute('SELECT schema_name, table_name FROM _timescaledb_catalog.hypertable '
                                'WHERE id = %s', (int(m.group(1)),))
                    row = cur.fetchone()
        finally:
            cur.close()
        result = (row[0], row[1]) if row else None
        if result is None and name.startswith('_hyper_'):
            log.warning('无法找到 chunk %s.%s 所属的超表，其变更将被忽略', schema, name)
        self._chunk_cache[key] = result
        return result

    # ---------- Relation 消息 ----------
    def register(self, rel):
        col_names = [c.name for c in rel.columns]
        key_cols = [c.name for c in rel.columns if c.flags & pgoutput.KEY_FLAG]
        info = RelationInfo(rel.relid, rel.namespace, rel.name, rel.replident, col_names, key_cols)

        target_key = None
        if rel.namespace in self.cfg.chunk_schemas:
            hyper = self.hypertable_of(rel.namespace, rel.name)
            if hyper is not None:
                info.is_chunk = True
                target_key = '%s.%s' % hyper
        else:
            target_key = '%s.%s' % (rel.namespace, rel.name)

        table = self.cfg.tables.get(target_key) if target_key else None
        if table is not None:
            pk = table.pk or key_cols
            if not pk:
                raise ConfigError('表 %s 没有主键（复制标识），请在配置中指定 pk 或为源表添加主键'
                                  % table.qualified)
            missing = [c for c in pk if c not in col_names]
            if missing:
                raise ConfigError('表 %s 配置的主键列 %s 在源表中不存在' % (table.qualified, missing))
            not_in_identity = [c for c in pk if c not in key_cols]
            if not_in_identity:
                raise ConfigError(
                    '表 %s（源 relation %s）的复制标识不包含主键列 %s，DELETE/UPDATE 将无法定位目标行。'
                    '请为源表设置主键，或执行 ALTER TABLE ... REPLICA IDENTITY FULL'
                    % (table.qualified, info.source_name, not_in_identity))
            info.target = table
            info.pk = list(pk)
            log.debug('relation %s (relid=%d) -> %s pk=%s', info.source_name, rel.relid,
                      table.qualified, info.pk)
        self._rels[rel.relid] = info
        return info

    def get(self, relid):
        try:
            return self._rels[relid]
        except KeyError:
            raise pgoutput.DecodeError('收到未声明的 relid=%d（缺少 Relation 消息）' % relid)


# ---------------------------------------------------------------------------
# 供 init / reconcile 使用的元数据查询
# ---------------------------------------------------------------------------

def table_columns(cur, schema, name):
    """普通列（排除生成列，与 pgoutput 发送的列一致），按定义顺序。"""
    cur.execute("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = %s AND table_name = %s AND is_generated = 'NEVER'
        ORDER BY ordinal_position
    """, (schema, name))
    return [r[0] for r in cur.fetchall()]


def table_exists(cur, schema, name):
    cur.execute("""
        SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = %s AND c.relname = %s AND c.relkind IN ('r', 'p')
    """, (schema, name))
    return cur.fetchone() is not None


def primary_key(cur, schema, name):
    cur.execute("""
        SELECT a.attname
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.attnum
        WHERE n.nspname = %s AND c.relname = %s AND i.indisprimary
        ORDER BY k.ord
    """, (schema, name))
    return [r[0] for r in cur.fetchall()]


def unique_key_sets(cur, schema, name):
    """表上所有唯一索引/约束的列集合（frozenset 列表），用于校验 ON CONFLICT 目标。"""
    cur.execute("""
        SELECT array_agg(a.attname::text)
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY (i.indkey)
        WHERE n.nspname = %s AND c.relname = %s AND i.indisunique AND i.indpred IS NULL
        GROUP BY i.indexrelid
    """, (schema, name))
    return [frozenset(r[0]) for r in cur.fetchall()]


def resolve_pk(cur, table):
    """配置的 pk 优先，否则取源库主键。"""
    if table.pk:
        return list(table.pk)
    pk = primary_key(cur, table.schema, table.name)
    if not pk:
        raise ConfigError('表 %s 没有主键，请在配置 tables.%s.pk 中指定' % (table.qualified, table.qualified))
    return pk


def has_timescaledb(cur):
    cur.execute("SELECT 1 FROM pg_extension WHERE extname = 'timescaledb'")
    return cur.fetchone() is not None
