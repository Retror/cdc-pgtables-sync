# -*- coding: utf-8 -*-
"""目标库中的同步位点表：位点与数据在同一事务中提交，实现精确续传。"""
from .utils import int_to_lsn, lsn_to_int, qi


def ensure_state_table(conn, schema):
    with conn.cursor() as cur:
        cur.execute('CREATE SCHEMA IF NOT EXISTS %s' % qi(schema))
        cur.execute(
            'CREATE TABLE IF NOT EXISTS %s.sync_state ('
            ' slot_name text PRIMARY KEY,'
            ' lsn pg_lsn NOT NULL,'
            ' updated_at timestamptz NOT NULL DEFAULT now())' % qi(schema))
    conn.commit()


def state_upsert_sql(schema):
    return ('INSERT INTO %s.sync_state (slot_name, lsn, updated_at) VALUES (%%s, %%s::pg_lsn, now()) '
            'ON CONFLICT (slot_name) DO UPDATE SET lsn = EXCLUDED.lsn, updated_at = now()' % qi(schema))


def write_state(cur, schema, slot_name, lsn):
    """在调用方的事务内写入位点（不提交）。"""
    cur.execute(state_upsert_sql(schema), (slot_name, int_to_lsn(lsn)))


def read_state(conn, schema, slot_name):
    """返回 (lsn:int, updated_at) 或 None。"""
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass(%s)", ('%s.sync_state' % qi(schema),))
        if cur.fetchone()[0] is None:
            conn.commit()
            return None
        cur.execute('SELECT lsn::text, updated_at FROM %s.sync_state WHERE slot_name = %%s' % qi(schema),
                    (slot_name,))
        row = cur.fetchone()
    conn.commit()
    if row is None:
        return None
    return lsn_to_int(row[0]), row[1]


def delete_state(conn, schema, slot_name):
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass(%s)", ('%s.sync_state' % qi(schema),))
        if cur.fetchone()[0] is not None:
            cur.execute('DELETE FROM %s.sync_state WHERE slot_name = %%s' % qi(schema), (slot_name,))
    conn.commit()
