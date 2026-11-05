# -*- coding: utf-8 -*-
"""单元测试：pgoutput 解码、写入逻辑、位点/跳过逻辑（不需要真实数据库）。

运行: python -m unittest discover -s tests -v
"""
import os
import struct
import sys
import types
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pglsync import pgoutput                               # noqa: E402
from pglsync.applier import Applier, ApplyError, MissingRowError  # noqa: E402
from pglsync.catalog import RelationInfo, RelationRegistry  # noqa: E402
from pglsync.config import ConfigError, TableConfig        # noqa: E402
from pglsync.stream import _Session                        # noqa: E402
from pglsync.utils import int_to_lsn, lsn_to_int, qi       # noqa: E402


# ----------------------------------------------------------------- 构造消息
def cstr(s):
    return s.encode('utf-8') + b'\x00'


def tup(*values):
    out = struct.pack('!h', len(values))
    for v in values:
        if v is None:
            out += b'n'
        elif v is Ellipsis:
            out += b'u'
        else:
            data = v.encode('utf-8')
            out += b't' + struct.pack('!i', len(data)) + data
    return out


def msg_begin(final_lsn, xid=1):
    return b'B' + struct.pack('!QqI', final_lsn, 0, xid)


def msg_commit(commit_lsn, end_lsn):
    return b'C' + struct.pack('!BQQq', 0, commit_lsn, end_lsn, 0)


def msg_relation(relid, ns, name, cols, replident='d'):
    out = b'R' + struct.pack('!I', relid) + cstr(ns) + cstr(name) + replident.encode() + struct.pack('!h', len(cols))
    for col, is_key in cols:
        out += struct.pack('!B', 1 if is_key else 0) + cstr(col) + struct.pack('!Ii', 25, -1)
    return out


def msg_insert(relid, *values):
    return b'I' + struct.pack('!I', relid) + b'N' + tup(*values)


def msg_update(relid, new, old=None, old_kind=None):
    out = b'U' + struct.pack('!I', relid)
    if old is not None:
        out += old_kind.encode() + tup(*old)
    return out + b'N' + tup(*new)


def msg_delete(relid, old, old_kind='K'):
    return b'D' + struct.pack('!I', relid) + old_kind.encode() + tup(*old)


def msg_truncate(relids, options=0):
    return b'T' + struct.pack('!IB', len(relids), options) + b''.join(struct.pack('!I', r) for r in relids)


# ----------------------------------------------------------------- 假对象
class FakeCursor(object):
    def __init__(self, rowcount=1):
        self.executed = []
        self.rowcount = rowcount
        self.default_rowcount = rowcount

    def execute(self, sql, params=None):
        self.executed.append((sql, list(params) if params is not None else None))
        self.rowcount = self.default_rowcount


class FakeConn(object):
    def __init__(self, rowcount=1):
        self.cursor_obj = FakeCursor(rowcount)
        self.commits = 0
        self.rollbacks = 0

    def cursor(self):
        return self.cursor_obj

    def commit(self):
        self.commits += 1

    def rollback(self):
        self.rollbacks += 1


def make_cfg(**kw):
    cfg = types.SimpleNamespace(
        slot_name='s', state_schema='pglsync', upsert_group_size=500, on_missing_row='warn',
        chunk_truncate='warn', batch_max_changes=5000, batch_max_seconds=1000.0,
        status_interval=10.0, stats_interval=1e9, chunk_schemas=['_timescaledb_internal'],
        tables={'public.t': TableConfig('public', 't'),
                'public.h': TableConfig('public', 'h', pk=['id', 'ts'])})
    for k, v in kw.items():
        setattr(cfg, k, v)
    return cfg


def make_info(cols=('id', 'name', 'big'), pk=('id',), is_chunk=False, target=None):
    target = target or TableConfig('public', 't')
    return RelationInfo(1, 'public', 't', 'd', list(cols), list(pk), target=target, pk=list(pk), is_chunk=is_chunk)


class ApplierHarness(object):
    def __init__(self, cfg=None, rowcount=1):
        self.conn = FakeConn(rowcount)
        self.batches = []
        self.applier = Applier(cfg or make_cfg(), self.conn, execute_values=self._ev)

    def _ev(self, cur, sql, rows, page_size=None):
        self.batches.append((sql, list(rows)))

    @property
    def executed(self):
        return self.conn.cursor_obj.executed


# =================================================================== tests
class UtilsTest(unittest.TestCase):
    def test_lsn_roundtrip(self):
        for text in ('0/0', '16/B374D848', 'FFFFFFFF/FFFFFFFF'):
            self.assertEqual(int_to_lsn(lsn_to_int(text)), text)
        self.assertEqual(lsn_to_int('1/0'), 1 << 32)

    def test_qi(self):
        self.assertEqual(qi('a"b'), '"a""b"')
        with self.assertRaises(ValueError):
            qi('a%b')


class DecodeTest(unittest.TestCase):
    def test_begin_commit(self):
        b = pgoutput.decode(msg_begin(100, 7))
        self.assertEqual((b.final_lsn, b.xid), (100, 7))
        c = pgoutput.decode(msg_commit(100, 108))
        self.assertEqual((c.commit_lsn, c.end_lsn), (100, 108))

    def test_relation(self):
        r = pgoutput.decode(msg_relation(5, '_timescaledb_internal', '_hyper_1_2_chunk',
                                         [('id', True), ('ts', True), ('v', False)]))
        self.assertEqual(r.relid, 5)
        self.assertEqual(r.name, '_hyper_1_2_chunk')
        self.assertEqual([c.name for c in r.columns], ['id', 'ts', 'v'])
        self.assertEqual([c.flags & pgoutput.KEY_FLAG for c in r.columns], [1, 1, 0])

    def test_insert_with_null_and_unicode(self):
        m = pgoutput.decode(msg_insert(5, '1', None, '台风'))
        self.assertEqual(m.new, [('t', '1'), ('n', None), ('t', '台风')])

    def test_update_variants(self):
        m = pgoutput.decode(msg_update(5, ['1', 'x']))
        self.assertIsNone(m.old_kind)
        m = pgoutput.decode(msg_update(5, ['2', 'x'], old=['1', None], old_kind='K'))
        self.assertEqual(m.old_kind, 'K')
        self.assertEqual(m.old[0], ('t', '1'))
        m = pgoutput.decode(msg_update(5, ['1', Ellipsis]))
        self.assertEqual(m.new[1], ('u', None))

    def test_delete_truncate(self):
        d = pgoutput.decode(msg_delete(5, ['1', None]))
        self.assertEqual((d.old_kind, d.old[0]), ('K', ('t', '1')))
        t = pgoutput.decode(msg_truncate([5, 6], 1))
        self.assertEqual((t.options, t.relids), (1, [5, 6]))

    def test_truncated_message(self):
        with self.assertRaises(pgoutput.DecodeError):
            pgoutput.decode(msg_insert(5, 'abc')[:-1])
        with self.assertRaises(pgoutput.DecodeError):
            pgoutput.decode(b'Z')


class ApplierTest(unittest.TestCase):
    def test_insert_is_idempotent_upsert(self):
        h = ApplierHarness()
        info = make_info()
        h.applier.insert(info, [('t', '1'), ('t', 'a'), ('n', None)])
        h.applier.commit(lsn_to_int('0/10'))
        sql, rows = h.batches[0]
        self.assertIn('ON CONFLICT ("id") DO UPDATE SET "name" = EXCLUDED."name", "big" = EXCLUDED."big"', sql)
        self.assertEqual(rows, [('1', 'a', None)])
        # 位点与数据同一事务提交
        self.assertIn('sync_state', h.executed[-1][0])
        self.assertEqual(h.executed[-1][1], ['s', '0/10'])
        self.assertEqual(h.conn.commits, 1)

    def test_group_dedup_keeps_last(self):
        h = ApplierHarness()
        info = make_info()
        h.applier.insert(info, [('t', '1'), ('t', 'a'), ('t', 'x')])
        h.applier.update(info, None, None, [('t', '1'), ('t', 'b'), ('t', 'x')])
        h.applier.insert(info, [('t', '2'), ('t', 'c'), ('t', 'x')])
        h.applier.flush_pending()
        self.assertEqual(len(h.batches), 1)
        self.assertEqual(h.batches[0][1], [('1', 'b', 'x'), ('2', 'c', 'x')])

    def test_order_preserved_around_delete(self):
        h = ApplierHarness()
        info = make_info()
        h.applier.insert(info, [('t', '1'), ('t', 'a'), ('t', 'x')])
        h.applier.delete(info, 'K', [('t', '1'), ('n', None), ('n', None)])
        # INSERT 必须在 DELETE 之前落地
        self.assertEqual(len(h.batches), 1)
        self.assertTrue(h.executed[0][0].startswith('DELETE FROM "public"."t" WHERE "id" = %s'))
        self.assertEqual(h.executed[0][1], ['1'])

    def test_update_primary_key_change(self):
        h = ApplierHarness()
        info = make_info()
        h.applier.update(info, 'K', [('t', '1'), ('n', None), ('n', None)],
                         [('t', '9'), ('t', 'a'), ('t', 'x')])
        h.applier.flush_pending()
        self.assertTrue(h.executed[0][0].startswith('DELETE'))
        self.assertEqual(h.executed[0][1], ['1'])
        self.assertEqual(h.batches[0][1], [('9', 'a', 'x')])

    def test_update_with_unchanged_toast_uses_partial_update(self):
        h = ApplierHarness()
        info = make_info()
        h.applier.update(info, None, None, [('t', '1'), ('t', 'b'), ('u', None)])
        self.assertEqual(h.batches, [])
        self.assertEqual(h.executed[0][0], 'UPDATE "public"."t" SET "name" = %s WHERE "id" = %s')
        self.assertEqual(h.executed[0][1], ['b', '1'])

    def test_unchanged_toast_filled_from_full_old_row(self):
        h = ApplierHarness()
        info = make_info()
        h.applier.update(info, 'O', [('t', '1'), ('t', 'a'), ('t', 'BIG')],
                         [('t', '1'), ('t', 'b'), ('u', None)])
        h.applier.flush_pending()
        self.assertEqual(h.batches[0][1], [('1', 'b', 'BIG')])

    def test_missing_row_policy(self):
        h = ApplierHarness(cfg=make_cfg(on_missing_row='error'), rowcount=0)
        info = make_info()
        with self.assertRaises(MissingRowError):
            h.applier.update(info, None, None, [('t', '1'), ('t', 'b'), ('u', None)])
        # DELETE 未命中不算错误
        h.applier.delete(info, 'K', [('t', '1'), ('n', None), ('n', None)])

    def test_delete_without_pk_value_fails(self):
        h = ApplierHarness()
        with self.assertRaises(ApplyError):
            h.applier.delete(make_info(), 'K', [('n', None), ('n', None), ('n', None)])

    def test_composite_pk(self):
        h = ApplierHarness()
        info = make_info(cols=('id', 'ts', 'v'), pk=('id', 'ts'), target=TableConfig('public', 'h', pk=['id', 'ts']))
        h.applier.delete(info, 'K', [('t', '1'), ('t', '2026-01-01 00:00:00'), ('n', None)])
        self.assertEqual(h.executed[0][0], 'DELETE FROM "public"."h" WHERE "id" = %s AND "ts" = %s')

    def test_truncate(self):
        h = ApplierHarness()
        normal = make_info()
        chunk = make_info(is_chunk=True, target=TableConfig('public', 'h', pk=['id']))
        h.applier.truncate([normal, chunk], 0)
        self.assertEqual(h.executed, [('TRUNCATE TABLE "public"."t"', None)])
        h2 = ApplierHarness(cfg=make_cfg(chunk_truncate='error'))
        with self.assertRaises(ApplyError):
            h2.applier.truncate([chunk], 0)
        # 超表本身被 TRUNCATE 时，同一消息中的 chunk 不再报错
        root = make_info(target=chunk.target)
        h2.applier.truncate([root, chunk], 0)
        self.assertEqual(h2.executed, [('TRUNCATE TABLE "public"."h"', None)])


class RegistryTest(unittest.TestCase):
    def _registry(self, mapping):
        reg = RelationRegistry(make_cfg())
        reg.hypertable_of = lambda schema, name: mapping.get(name)
        return reg

    def test_chunk_maps_to_hypertable(self):
        reg = self._registry({'_hyper_1_5_chunk': ('public', 'h')})
        info = reg.register(pgoutput.decode(msg_relation(
            10, '_timescaledb_internal', '_hyper_1_5_chunk', [('id', True), ('ts', True), ('v', False)])))
        self.assertTrue(info.is_chunk)
        self.assertEqual(info.target.qualified, 'public.h')
        self.assertEqual(info.pk, ['id', 'ts'])

    def test_unconfigured_and_compressed_ignored(self):
        reg = self._registry({'compress_hyper_3_7_chunk': ('_timescaledb_internal', '_compressed_hypertable_3')})
        a = reg.register(pgoutput.decode(msg_relation(11, 'public', 'other', [('id', True)])))
        b = reg.register(pgoutput.decode(msg_relation(
            12, '_timescaledb_internal', 'compress_hyper_3_7_chunk', [('x', False)])))
        self.assertIsNone(a.target)
        self.assertIsNone(b.target)

    def test_pk_must_be_in_replica_identity(self):
        reg = self._registry({})
        with self.assertRaises(ConfigError):
            reg.register(pgoutput.decode(msg_relation(13, 'public', 'h', [('id', True), ('ts', False)])))


class FakeReplCursor(object):
    def __init__(self):
        self.feedback = []
        self.wal_end = 0

    def send_feedback(self, write_lsn=0, flush_lsn=0, apply_lsn=0, reply=False, force=False):
        self.feedback.append(flush_lsn)


class SessionTest(unittest.TestCase):
    def _session(self, start_lsn, **cfg_kw):
        cfg = make_cfg(**cfg_kw)
        reg = RelationRegistry(cfg)
        reg.hypertable_of = lambda s, n: None
        h = ApplierHarness(cfg)
        cur = FakeReplCursor()
        return _Session(cfg, cur, h.applier, reg, start_lsn), h, cur

    def test_replayed_transactions_are_skipped(self):
        s, h, cur = self._session(start_lsn=200)
        s.handle(msg_relation(1, 'public', 't', [('id', True), ('name', False), ('big', False)]))
        # 已应用过的事务（commit_lsn < 200）被重放：跳过
        s.handle(msg_begin(150))
        s.handle(msg_insert(1, '1', 'old', None))
        s.handle(msg_commit(150, 160))
        self.assertEqual(s.stats['skipped_txns'], 1)
        self.assertIsNone(s.pending_end)
        # 新事务：应用
        s.handle(msg_begin(300))
        s.handle(msg_insert(1, '2', 'new', None))
        s.handle(msg_commit(300, 310))
        s.flush()
        self.assertEqual(h.batches[0][1], [('2', 'new', None)])
        self.assertEqual(h.executed[-1][1], ['s', int_to_lsn(310)])
        self.assertEqual(cur.feedback, [310])

    def test_batch_flushes_on_change_count(self):
        s, h, cur = self._session(start_lsn=0, batch_max_changes=2)
        s.handle(msg_relation(1, 'public', 't', [('id', True), ('name', False), ('big', False)]))
        s.handle(msg_begin(10))
        s.handle(msg_insert(1, '1', 'a', None))
        s.handle(msg_commit(10, 20))
        self.assertEqual(h.conn.commits, 0)
        s.handle(msg_begin(30))
        s.handle(msg_insert(1, '2', 'b', None))
        s.handle(msg_commit(30, 40))
        self.assertEqual(h.conn.commits, 1)
        self.assertEqual(cur.feedback, [40])

    def test_irrelevant_transactions_do_not_write_target(self):
        s, h, cur = self._session(start_lsn=0)
        s.handle(msg_relation(2, 'public', 'other', [('id', True)]))
        s.handle(msg_begin(10))
        s.handle(msg_insert(2, '1'))
        s.handle(msg_commit(10, 20))
        s.flush()
        self.assertEqual(h.conn.commits, 0)
        self.assertEqual(cur.feedback, [20])

    def test_idle_feedback_advances_to_wal_end(self):
        s, h, cur = self._session(start_lsn=100)
        cur.wal_end = 500
        s.last_idle_feedback = 0
        s._idle_feedback(now=1e9)
        self.assertEqual(cur.feedback, [500])


if __name__ == '__main__':
    unittest.main()
