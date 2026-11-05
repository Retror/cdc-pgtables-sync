# -*- coding: utf-8 -*-
"""
把解码后的行变更应用到目标库。

设计要点：
  * 所有写入都是幂等的：INSERT / 整行 UPDATE 统一用 INSERT ... ON CONFLICT (pk) DO UPDATE，
    重放同一段变更不会出错也不会产生差异；
  * UPDATE 修改了主键时，先按旧主键删除再写入新行（旧脚本会漏掉主键变更）；
  * 连续的同表 upsert 合并成一条多行 INSERT（execute_values），减少跨网络往返；
  * 列值保持 PostgreSQL 文本格式，作为未知类型字面量由目标库按列类型解析。
"""
import logging
from collections import OrderedDict

from . import pgoutput
from .state import write_state
from .utils import qi, qname

log = logging.getLogger('pglsync.apply')


class ApplyError(Exception):
    pass


class MissingRowError(ApplyError):
    pass


def tuple_to_row(info, tup):
    """pgoutput 元组 -> (OrderedDict 列名->文本值/None, 未变更 TOAST 列集合)"""
    if len(tup) != len(info.col_names):
        raise ApplyError('表 %s 列数不匹配: relation 声明 %d 列, 收到 %d 列'
                         % (info.source_name, len(info.col_names), len(tup)))
    values = OrderedDict()
    unchanged = set()
    for name, (kind, value) in zip(info.col_names, tup):
        if kind == pgoutput.TEXT:
            values[name] = value
        elif kind == pgoutput.NULL:
            values[name] = None
        elif kind == pgoutput.UNCHANGED:
            unchanged.add(name)
        else:
            raise ApplyError('不支持的列格式 %r（仅支持文本格式）' % kind)
    return values, unchanged


class Applier(object):
    def __init__(self, cfg, conn, execute_values=None):
        self.cfg = cfg
        self.conn = conn
        self.cur = conn.cursor()
        if execute_values is None:
            from psycopg2.extras import execute_values
        self._execute_values = execute_values
        self._sql_cache = {}
        self._group = None          # 待合并写入的 upsert 组
        self.missing_rows = 0

    # ------------------------------------------------------------------ SQL
    def _sql(self, kind, info, cols=()):
        key = (kind, info.target.qualified, tuple(info.pk), tuple(cols))
        stmt = self._sql_cache.get(key)
        if stmt is not None:
            return stmt
        table = qname(info.target.schema, info.target.name)
        where = ' AND '.join('%s = %%s' % qi(c) for c in info.pk)
        if kind == 'upsert':
            non_pk = [c for c in cols if c not in info.pk]
            if non_pk:
                action = 'DO UPDATE SET ' + ', '.join('%s = EXCLUDED.%s' % (qi(c), qi(c)) for c in non_pk)
            else:
                action = 'DO NOTHING'
            stmt = 'INSERT INTO %s (%s) VALUES %%s ON CONFLICT (%s) %s' % (
                table, ', '.join(qi(c) for c in cols), ', '.join(qi(c) for c in info.pk), action)
        elif kind == 'update':
            stmt = 'UPDATE %s SET %s WHERE %s' % (
                table, ', '.join('%s = %%s' % qi(c) for c in cols), where)
        elif kind == 'delete':
            stmt = 'DELETE FROM %s WHERE %s' % (table, where)
        else:
            raise ValueError(kind)
        self._sql_cache[key] = stmt
        return stmt

    # --------------------------------------------------------- upsert 合并
    def _upsert(self, info, values):
        cols = tuple(values.keys())
        key = (info.target.qualified, cols)
        if self._group is not None and self._group['key'] != key:
            self.flush_pending()
        if self._group is None:
            self._group = {'key': key, 'info': info, 'cols': cols, 'rows': OrderedDict()}
        rows = self._group['rows']
        pk_value = tuple(values[c] for c in info.pk)
        # 同一批内同主键只保留最后一次（全是整行 upsert，最终状态等价）
        rows.pop(pk_value, None)
        rows[pk_value] = tuple(values[c] for c in cols)
        if len(rows) >= self.cfg.upsert_group_size:
            self.flush_pending()

    def flush_pending(self):
        group = self._group
        if group is None:
            return
        self._group = None
        rows = list(group['rows'].values())
        stmt = self._sql('upsert', group['info'], group['cols'])
        self._execute_values(self.cur, stmt, rows, page_size=len(rows))

    # ------------------------------------------------------------ 变更类型
    def insert(self, info, new):
        values, unchanged = tuple_to_row(info, new)
        if unchanged:
            raise ApplyError('INSERT %s 中出现未变更 TOAST 列 %s' % (info.target.qualified, sorted(unchanged)))
        self._upsert(info, values)

    def update(self, info, old_kind, old, new):
        new_values, unchanged = tuple_to_row(info, new)
        old_values = None
        if old is not None:
            old_values, old_unchanged = tuple_to_row(info, old)
            if unchanged and old_kind == 'O':
                # REPLICA IDENTITY FULL 时可从旧整行补齐未变更的 TOAST 列
                for col in list(unchanged):
                    if col in old_values and col not in old_unchanged:
                        new_values[col] = old_values[col]
                        unchanged.discard(col)
                new_values = OrderedDict((c, new_values[c]) for c in info.col_names if c in new_values)

        pk = info.pk
        if any(c in unchanged for c in pk):
            raise ApplyError('UPDATE %s 的主键列为未变更 TOAST 值，无法定位目标行' % info.target.qualified)
        new_pk = [new_values[c] for c in pk]
        if old_values is not None and all(old_values.get(c) is not None for c in pk):
            old_pk = [old_values[c] for c in pk]
        else:
            old_pk = new_pk

        if old_pk != new_pk:
            # 主键发生变化：删除旧行 + 写入新行
            self.flush_pending()
            self.cur.execute(self._sql('delete', info), old_pk)
            if self.cur.rowcount == 0:
                self._missing('UPDATE(主键变更)', info, old_pk)
            if unchanged:
                raise ApplyError('UPDATE %s 修改了主键且存在未变更 TOAST 列 %s，无法重建整行；'
                                 '请对源表设置 REPLICA IDENTITY FULL' % (info.target.qualified, sorted(unchanged)))
            self._upsert(info, new_values)
            return

        if not unchanged:
            # 整行可用：幂等 upsert（目标缺行时会自动补上）
            self._upsert(info, new_values)
            return

        # 存在未变更 TOAST 列：只更新收到的列
        self.flush_pending()
        set_cols = [c for c in new_values if c not in pk]
        if not set_cols:
            return
        self.cur.execute(self._sql('update', info, set_cols), [new_values[c] for c in set_cols] + new_pk)
        if self.cur.rowcount == 0:
            self._missing('UPDATE', info, new_pk)

    def delete(self, info, old_kind, old):
        self.flush_pending()
        old_values, _ = tuple_to_row(info, old)
        pk_values = [old_values.get(c) for c in info.pk]
        if any(v is None for v in pk_values):
            raise ApplyError('DELETE %s 缺少主键值（复制标识不包含主键列 %s）' % (info.target.qualified, info.pk))
        self.cur.execute(self._sql('delete', info), pk_values)
        if self.cur.rowcount == 0:
            # 删除一条本就不存在的行不影响最终一致性，仅记录
            self.missing_rows += 1
            log.debug('DELETE %s 未命中目标行 pk=%s', info.target.qualified, pk_values)

    def truncate(self, infos, options):
        self.flush_pending()
        tables = OrderedDict()
        for info in infos:
            if not info.is_chunk:
                tables[info.target.qualified] = info.target
        for info in infos:
            if info.is_chunk and info.target.qualified not in tables:
                msg = ('源库 TRUNCATE 了 chunk %s（属于超表 %s），无法安全映射为目标库操作，已忽略；'
                       '请用 reconcile 核对该时间段数据' % (info.source_name, info.target.qualified))
                if self.cfg.chunk_truncate == 'error':
                    raise ApplyError(msg)
                log.warning(msg)
        if tables:
            stmt = 'TRUNCATE TABLE ' + ', '.join(qname(t.schema, t.name) for t in tables.values())
            log.warning('源库执行了 TRUNCATE，同步到目标库: %s', ', '.join(tables))
            self.cur.execute(stmt)

    # ---------------------------------------------------------------- 事务
    def _missing(self, op, info, pk_values):
        self.missing_rows += 1
        msg = '%s %s 未命中目标行 pk=%s（目标库与源库已不一致）' % (op, info.target.qualified, pk_values)
        if self.cfg.on_missing_row == 'error':
            raise MissingRowError(msg)
        log.warning(msg)

    def commit(self, end_lsn):
        """写入位点并提交：数据与位点原子落库。"""
        self.flush_pending()
        write_state(self.cur, self.cfg.state_schema, self.cfg.slot_name, end_lsn)
        self.conn.commit()

    def rollback(self):
        self._group = None
        self.conn.rollback()
