# -*- coding: utf-8 -*-
"""
reconcile：源库与目标库数据核对（行数 + 整行哈希）。

  * 配置了 time_column 的表按时间分桶（hour/day/week/month）比较，能定位到具体时间段；
  * 其他表整表比较；
  * 同步是实时进行的，核对时刻两边可能存在短暂差异，因此不一致的桶会在延迟后复查，
    只报告持续不一致的部分；
  * --detail 会列出不一致桶中具体缺失/多余/不同的主键。
"""
import time

from .catalog import resolve_pk, table_columns
from .utils import qi, qname

BUCKET_INTERVAL = {'hour': '1 hour', 'day': '1 day', 'week': '1 week', 'month': '1 month'}

# 两边使用相同的会话设置，保证 ROW(...)::text 的文本表示一致
SESSION_SETTINGS = (
    "SET TimeZone = 'UTC'",
    "SET DateStyle = 'ISO, YMD'",
    "SET IntervalStyle = 'postgres'",
    "SET extra_float_digits = 3",
    "SET bytea_output = 'hex'",
)


def _connect(params):
    import psycopg2
    conn = psycopg2.connect(**params)
    conn.autocommit = True
    with conn.cursor() as cur:
        for stmt in SESSION_SETTINGS:
            cur.execute(stmt)
    return conn


def _where(table, since, bucket):
    clauses, params = [], []
    if table.time_column:
        col = qi(table.time_column)
        if since:
            clauses.append('%s >= %%s' % col)
            params.append(since)
        if bucket is not None:
            clauses.append('%s >= %%s::timestamptz AND %s < %%s::timestamptz + %%s::interval' % (col, col))
            params.extend([bucket, bucket, BUCKET_INTERVAL[table.bucket]])
    return (' WHERE ' + ' AND '.join(clauses)) if clauses else '', params


def bucket_hashes(conn, table, cols, pk, since=None, bucket=None):
    """返回 {桶: (行数, 哈希)}"""
    row_hash = 'md5(ROW(%s)::text)' % ', '.join(qi(c) for c in cols)
    order = ', '.join(qi(c) for c in pk)
    if table.time_column:
        bucket_expr = "date_trunc('%s', %s)::text" % (table.bucket, qi(table.time_column))
    else:
        bucket_expr = "'*'"
    where, params = _where(table, since, bucket)
    sql = ("SELECT %s, count(*), md5(string_agg(%s, '' ORDER BY %s)) FROM %s%s GROUP BY 1"
           % (bucket_expr, row_hash, order, qname(table.schema, table.name), where))
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return dict((r[0], (r[1], r[2])) for r in cur.fetchall())


def row_hashes(conn, table, cols, pk, bucket):
    sql = 'SELECT ROW(%s)::text, md5(ROW(%s)::text) FROM %s' % (
        ', '.join(qi(c) for c in pk), ', '.join(qi(c) for c in cols), qname(table.schema, table.name))
    where, params = _where(table, None, None if bucket == '*' else bucket)
    with conn.cursor() as cur:
        cur.execute(sql + where, params)
        return dict(cur.fetchall())


def _diff(src, tgt):
    return sorted(b for b in set(src) | set(tgt) if src.get(b) != tgt.get(b))


def run_reconcile(cfg, only=None, since=None, recheck_delay=10, detail=False, limit=20):
    tables = [t for t in cfg.tables.values() if not only or t.qualified in only or t.name in only]
    if not tables:
        print('没有匹配的表')
        return 1
    src = _connect(cfg.source)
    tgt = _connect(cfg.target)
    bad_tables = 0
    try:
        print('%-45s %14s %14s  %s' % ('表', '源库行数', '目标库行数', '结果'))
        print('-' * 95)
        for table in tables:
            with src.cursor() as cur:
                cols = table_columns(cur, table.schema, table.name)
                pk = resolve_pk(cur, table)
            s = bucket_hashes(src, table, cols, pk, since)
            t = bucket_hashes(tgt, table, cols, pk, since)
            bad = _diff(s, t)
            if bad and recheck_delay > 0:
                time.sleep(recheck_delay)
                still = []
                for b in bad:
                    bucket = None if b == '*' else b
                    s2 = bucket_hashes(src, table, cols, pk, since, bucket)
                    t2 = bucket_hashes(tgt, table, cols, pk, since, bucket)
                    s.pop(b, None)
                    t.pop(b, None)
                    s.update(s2)
                    t.update(t2)
                    if s2.get(b) != t2.get(b):
                        still.append(b)
                bad = still
            src_rows = sum(v[0] for v in s.values())
            tgt_rows = sum(v[0] for v in t.values())
            status = '一致' if not bad else '不一致: %d 个%s' % (len(bad), '时间桶' if table.time_column else '表')
            print('%-45s %14s %14s  %s' % (table.qualified, '{:,}'.format(src_rows), '{:,}'.format(tgt_rows), status))
            if not bad:
                continue
            bad_tables += 1
            for b in bad[:limit]:
                if table.time_column:
                    print('    %-28s 源 %10s  目标 %10s' % (
                        b, '{:,}'.format(s.get(b, (0,))[0]), '{:,}'.format(t.get(b, (0,))[0])))
            if len(bad) > limit:
                print('    ... 另有 %d 个桶不一致' % (len(bad) - limit))
            if detail:
                for b in bad[:limit]:
                    rs = row_hashes(src, table, cols, pk, b)
                    rt = row_hashes(tgt, table, cols, pk, b)
                    missing = sorted(k for k in rs if k not in rt)
                    extra = sorted(k for k in rt if k not in rs)
                    changed = sorted(k for k in rs if k in rt and rs[k] != rt[k])
                    print('    [%s] 目标缺少 %d 行, 目标多出 %d 行, 内容不同 %d 行' % (b, len(missing), len(extra), len(changed)))
                    for label, keys in (('缺少', missing), ('多出', extra), ('不同', changed)):
                        for k in keys[:limit]:
                            print('      %s pk=%s' % (label, k))
        print('-' * 95)
        print('不一致的表: %d / %d' % (bad_tables, len(tables)))
        return 0 if bad_tables == 0 else 2
    finally:
        src.close()
        tgt.close()
