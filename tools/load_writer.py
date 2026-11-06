#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
测试工具：定时向源库的指定表写入模拟数据，用来验证 pglsync 的同步效果。

每一轮对每张表执行：
  1. 按表结构自动生成约 N 行数据（默认 10000 行，上下浮动 10%），分成多个事务批量 INSERT；
  2. 先开启一个“长事务”写入一小部分数据，等其他批次全部提交后它才提交。
     旧的触发器方案正是在这种情况下丢数据，pglsync 不会；
  3. 对刚写入的部分行执行 UPDATE 和 DELETE，覆盖三种变更类型。

列值根据 information_schema 中的列类型自动生成，不需要事先知道表结构。
有默认值的列（如 bigserial 的 id、DEFAULT now() 的时间列）交给数据库生成；
没有默认值的整数主键列按“当前最大值 + 1”递增生成。

用法:
  python3 tools/load_writer.py -c config.test.json              # 每 5 分钟一轮，常驻运行
  python3 tools/load_writer.py -c config.test.json --once       # 只写一轮（可配合 crontab）
  python3 tools/load_writer.py -c config.test.json --rows 2000 --interval 60
"""
import argparse
import datetime
import json
import logging
import os
import random
import signal
import string
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pglsync.catalog import primary_key  # noqa: E402
from pglsync.config import ConfigError, load_config  # noqa: E402
from pglsync.utils import qi, qname  # noqa: E402

log = logging.getLogger('load_writer')

INT_MAX = {'smallint': 32767, 'integer': 2147483647, 'bigint': 9223372036854775807}
NUMERIC_TYPES = ('smallint', 'integer', 'bigint', 'numeric', 'real', 'double precision')
ALNUM = string.ascii_uppercase + string.digits
UNSUPPORTED = object()

ARRAY_ELEMENT = {
    '_int2': lambda: random.randint(0, 1000),
    '_int4': lambda: random.randint(0, 100000),
    '_int8': lambda: random.randint(0, 100000),
    '_float4': lambda: round(random.uniform(-1000, 1000), 3),
    '_float8': lambda: round(random.uniform(-1000, 1000), 3),
    '_numeric': lambda: round(random.uniform(0, 1000), 2),
    '_text': lambda: _rand_str(8),
    '_varchar': lambda: _rand_str(8),
}


def _rand_str(n):
    return ''.join(random.choice(ALNUM) for _ in range(n))


class Column(object):
    def __init__(self, row):
        (self.name, self.data_type, self.udt_name, self.nullable, self.has_default,
         self.char_len, self.num_prec, self.num_scale) = row
        self.enum_values = None


def gen_value(c):
    """按列类型生成一个随机值；无法生成时返回 UNSUPPORTED。"""
    t = c.data_type
    if t in INT_MAX:
        return random.randint(0, min(INT_MAX[t], 100000))
    if t == 'numeric':
        if c.num_prec is None:            # 不限精度的 numeric
            scale, int_digits = 3, 6
        else:
            scale = c.num_scale or 0
            int_digits = c.num_prec - scale
        upper = min(10 ** int_digits - 1, 100000) if int_digits > 0 else 0.9
        return round(random.uniform(0, upper), max(scale, 0))
    if t in ('real', 'double precision'):
        return round(random.uniform(-1000, 1000), 4)
    if t == 'character':
        return _rand_str(min(c.char_len or 1, 32))
    if t in ('character varying', 'text'):
        return _rand_str(min(c.char_len or 12, 12))
    if t == 'boolean':
        return random.random() < 0.5
    if t == 'timestamp with time zone':
        return datetime.datetime.now(datetime.timezone.utc)
    if t == 'timestamp without time zone':
        return datetime.datetime.now()
    if t == 'date':
        return datetime.date.today()
    if t == 'time without time zone':
        return datetime.datetime.now().time()
    if t in ('json', 'jsonb'):
        return json.dumps({'test': True, 'v': random.randint(0, 1000)})
    if t == 'uuid':
        return str(uuid.uuid4())
    if t == 'interval':
        return '%d minutes' % random.randint(1, 600)
    if t == 'inet':
        return '10.%d.%d.%d' % (random.randint(0, 255), random.randint(0, 255), random.randint(1, 254))
    if t == 'bytea':
        return os.urandom(8)
    if t == 'ARRAY' and c.udt_name in ARRAY_ELEMENT:
        return [ARRAY_ELEMENT[c.udt_name]() for _ in range(3)]
    if t == 'USER-DEFINED' and c.enum_values:
        return random.choice(c.enum_values)
    return UNSUPPORTED


class TablePlan(object):
    """一张表的写入计划：哪些列由脚本生成、主键、用于 UPDATE 的列。"""

    def __init__(self, cur, schema, name, time_column):
        self.schema, self.name = schema, name
        self.qualified = '%s.%s' % (schema, name)
        cur.execute("""
            SELECT column_name, data_type, udt_name, is_nullable = 'YES',
                   column_default IS NOT NULL OR is_identity = 'YES',
                   character_maximum_length, numeric_precision, numeric_scale
            FROM information_schema.columns
            WHERE table_schema = %s AND table_name = %s AND is_generated = 'NEVER'
            ORDER BY ordinal_position
        """, (schema, name))
        cols = [Column(r) for r in cur.fetchall()]
        if not cols:
            raise ConfigError('源库中不存在表 %s' % self.qualified)
        for c in cols:
            if c.data_type == 'USER-DEFINED':
                cur.execute('SELECT e.enumlabel FROM pg_enum e JOIN pg_type t ON t.oid = e.enumtypid '
                            'WHERE t.typname = %s ORDER BY e.enumsortorder', (c.udt_name,))
                c.enum_values = [r[0] for r in cur.fetchall()] or None

        self.pk = primary_key(cur, schema, name)
        if not self.pk:
            raise ConfigError('表 %s 没有主键，无法用于同步测试' % self.qualified)
        self.time_column = time_column if any(c.name == time_column for c in cols) else None

        # 有默认值的列交给数据库生成，其余由脚本生成
        self.gen_cols = [c for c in cols if not c.has_default]
        if not self.gen_cols:
            raise ConfigError('表 %s 所有列都有默认值，脚本无需生成任何列，暂不支持' % self.qualified)
        self.counter_cols = [c.name for c in self.gen_cols if c.name in self.pk and c.data_type in INT_MAX]
        for c in self.gen_cols:
            if c.name in self.counter_cols or c.name == self.time_column:
                continue
            if not c.nullable and gen_value(c) is UNSUPPORTED:
                raise ConfigError('表 %s 的列 %s（类型 %s）非空且无默认值，脚本无法生成该类型的数据'
                                  % (self.qualified, c.name, c.udt_name))

        candidates = [c for c in self.gen_cols
                      if c.name not in self.pk and c.name != self.time_column and gen_value(c) is not UNSUPPORTED]
        candidates.sort(key=lambda c: 0 if c.data_type in NUMERIC_TYPES else 1)
        self.update_col = candidates[0] if candidates else None

        log.info('%s: 主键 %s，脚本生成列 %d 个，时间列 %s，UPDATE 使用列 %s',
                 self.qualified, self.pk, len(self.gen_cols), self.time_column or '无',
                 self.update_col.name if self.update_col else '无（跳过 UPDATE）')

    def next_counters(self, conn):
        counters = {}
        with conn.cursor() as cur:
            for col in self.counter_cols:
                cur.execute('SELECT COALESCE(max(%s), 0) FROM %s' % (qi(col), qname(self.schema, self.name)))
                counters[col] = cur.fetchone()[0] + 1
        conn.commit()
        return counters

    def make_row(self, counters, spread_seconds):
        delta = datetime.timedelta(seconds=random.uniform(0, spread_seconds))
        row = []
        for c in self.gen_cols:
            if c.name in counters:
                row.append(counters[c.name])
                counters[c.name] += 1
            elif c.name == self.time_column and c.data_type == 'timestamp with time zone':
                row.append(datetime.datetime.now(datetime.timezone.utc) - delta)
            elif c.name == self.time_column and c.data_type == 'timestamp without time zone':
                row.append(datetime.datetime.now() - delta)
            else:
                v = gen_value(c)
                row.append(None if v is UNSUPPORTED else v)
        return row

    def insert(self, cur, rows):
        from psycopg2.extras import execute_values
        sql = 'INSERT INTO %s (%s) VALUES %%s ON CONFLICT DO NOTHING RETURNING %s' % (
            qname(self.schema, self.name),
            ', '.join(qi(c.name) for c in self.gen_cols),
            ', '.join(qi(c) for c in self.pk))
        return execute_values(cur, sql, rows, page_size=1000, fetch=True)

    def where_pk(self):
        return ' AND '.join('%s = %%s' % qi(c) for c in self.pk)


def write_round(conn, long_conn, plan, args):
    from psycopg2.extras import execute_batch
    started = time.time()
    total = max(1, int(args.rows * random.uniform(0.9, 1.1)))
    counters = plan.next_counters(conn)
    rows = [plan.make_row(counters, args.time_spread_hours * 3600) for _ in range(total)]

    n_long = min(args.long_txn_rows, total) if long_conn is not None else 0
    long_rows, rows = rows[:n_long], rows[n_long:]
    pks = []

    # 长事务：先写入但不提交
    if long_rows:
        with long_conn.cursor() as lcur:
            pks += plan.insert(lcur, long_rows)

    # 普通批次：每 txn_size 行一个事务
    with conn.cursor() as cur:
        for i in range(0, len(rows), args.txn_size):
            pks += plan.insert(cur, rows[i:i + args.txn_size])
            conn.commit()

    # 其他批次都提交后，长事务才提交
    if long_rows:
        long_conn.commit()

    n_upd = int(len(pks) * args.update_ratio) if plan.update_col else 0
    n_del = int(len(pks) * args.delete_ratio)
    sample = random.sample(pks, min(len(pks), n_upd + n_del))
    upd, dels = sample[:n_upd], sample[n_upd:]
    table = qname(plan.schema, plan.name)
    with conn.cursor() as cur:
        if upd:
            execute_batch(cur, 'UPDATE %s SET %s = %%s WHERE %s' % (table, qi(plan.update_col.name), plan.where_pk()),
                          [[gen_value(plan.update_col)] + list(pk) for pk in upd], page_size=200)
        if dels:
            execute_batch(cur, 'DELETE FROM %s WHERE %s' % (table, plan.where_pk()),
                          [list(pk) for pk in dels], page_size=200)
    conn.commit()

    log.info('%s: 插入 %d 行（其中长事务 %d 行），更新 %d 行，删除 %d 行，耗时 %.1fs',
             plan.qualified, len(pks), len(long_rows), len(upd), len(dels), time.time() - started)


def build_parser():
    p = argparse.ArgumentParser(description='定时向源库写入测试数据（验证 pglsync 同步效果）')
    p.add_argument('-c', '--config', default='config.test.json', help='pglsync 配置文件，使用其中的 source 连接')
    p.add_argument('-t', '--tables', default='public.tcrealtime,public.tcensemblerealtime',
                   help='要写入的表，逗号分隔（默认 public.tcrealtime,public.tcensemblerealtime）')
    p.add_argument('--rows', type=int, default=10000, help='每张表每轮写入行数，实际上下浮动 10%%（默认 10000）')
    p.add_argument('--interval', type=float, default=300, help='每轮间隔秒数（默认 300，即 5 分钟）')
    p.add_argument('--once', action='store_true', help='只写一轮就退出')
    p.add_argument('--txn-size', type=int, default=1000, help='每个事务写入多少行（默认 1000）')
    p.add_argument('--long-txn-rows', type=int, default=100,
                   help='每轮放进“晚提交长事务”的行数，0 表示不模拟（默认 100）')
    p.add_argument('--update-ratio', type=float, default=0.05, help='每轮更新的行占比（默认 0.05）')
    p.add_argument('--delete-ratio', type=float, default=0.01, help='每轮删除的行占比（默认 0.01）')
    p.add_argument('--time-column', default='datatime', help='时间列名（配置文件中未指定时使用，默认 datatime）')
    p.add_argument('--time-spread-hours', type=float, default=24,
                   help='时间列取值落在“当前时间往前多少小时”内（默认 24）')
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)-7s %(message)s')
    try:
        cfg = load_config(args.config)
    except ConfigError as e:
        log.error('%s', e)
        return 3
    import psycopg2

    tables = []
    for item in args.tables.split(','):
        item = item.strip()
        if not item:
            continue
        schema, name = item.split('.', 1) if '.' in item else ('public', item)
        tc = cfg.tables.get('%s.%s' % (schema, name))
        tables.append((schema, name, (tc.time_column if tc and tc.time_column else args.time_column)))

    stop = {'flag': False}

    def on_signal(*_):
        log.info('收到停止信号，本轮结束后退出')
        stop['flag'] = True
    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    conn = long_conn = None
    plans = None
    round_no = 0
    while True:
        round_no += 1
        started = time.time()
        try:
            if conn is None or conn.closed:
                conn = psycopg2.connect(**cfg.source)
                long_conn = psycopg2.connect(**cfg.source) if args.long_txn_rows > 0 else None
                with conn.cursor() as cur:
                    plans = [TablePlan(cur, s, n, tcol) for s, n, tcol in tables]
                conn.commit()
            log.info('===== 第 %d 轮写入开始 =====', round_no)
            for plan in plans:
                write_round(conn, long_conn, plan, args)
        except ConfigError as e:
            log.error('%s', e)
            return 3
        except psycopg2.Error:
            log.exception('本轮写入失败，下一轮将重新连接')
            for c in (conn, long_conn):
                if c is not None and not c.closed:
                    c.close()
            conn = long_conn = None

        if args.once or stop['flag']:
            break
        next_at = started + args.interval
        log.info('下一轮开始时间: %s', datetime.datetime.fromtimestamp(next_at).strftime('%H:%M:%S'))
        while not stop['flag'] and time.time() < next_at:
            time.sleep(min(1.0, next_at - time.time()))
        if stop['flag']:
            break

    for c in (conn, long_conn):
        if c is not None and not c.closed:
            c.close()
    return 0


if __name__ == '__main__':
    sys.exit(main())
